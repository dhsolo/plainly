package com.plainly.driver.jdbc;

import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.ddl.TableDiff;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构变更的差异计算与 DDL 生成。
 *
 * <p>重点不在「能拼出一条 SQL」，而在三件事：
 * 差异算得对、各家方言的语法差异落实了、<b>做不到的事如实拒绝</b>。
 * 最后一条最容易被忽略——生成一条注定失败的 SQL 比直接说不支持更糟。
 */
@DisplayName("结构变更 · DDL 生成")
class DdlGenerationTest {

    private static final String SCHEMA = "shop";
    private static final String TABLE = "orders";

    // ------------------------------------------------------------------ 夹具

    private static ColumnInfo col(String name, String type, int precision, int scale,
                                  boolean nullable, boolean pk) {
        return new ColumnInfo(name, type, com.plainly.driver.TypeNames.categoryOf(type),
                precision, scale, nullable, pk, false, null, "", 0);
    }

    private static TableStructure structure(ColumnInfo... columns) {
        return new TableStructure(
                new TableInfo(SCHEMA, TABLE, ObjectKind.TABLE, "", -1),
                List.of(columns), List.of());
    }

    /** 典型表：主键 id、金额 amount、备注 note。 */
    private static TableStructure ordersTable() {
        return structure(
                col("id", "BIGINT", 0, 0, false, true),
                col("amount", "DECIMAL", 38, 10, false, false),
                col("note", "VARCHAR", 255, 0, true, false));
    }

    private static List<ColumnDraft> draftsOf(TableStructure s) {
        List<ColumnDraft> drafts = new ArrayList<>();
        s.columns().forEach(c -> drafts.add(ColumnDraft.of(c)));
        return drafts;
    }

    private static String allSql(SqlDialect dialect, List<TableChange> changes) {
        StringBuilder sb = new StringBuilder();
        dialect.ddlFor(SCHEMA, TABLE, changes)
                .forEach(s -> sb.append(s.sql()).append(";\n"));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 差异

    @Nested
    @DisplayName("差异计算")
    class Diff {

        @Test
        @DisplayName("没改动就没有变更")
        void noChangesWhenUntouched() {
            TableStructure s = ordersTable();
            assertTrue(TableDiff.compute(s, draftsOf(s), TABLE).isEmpty());
        }

        @Test
        @DisplayName("新增字段带上它前面那一列，用于 MySQL 的 AFTER")
        void addColumnCarriesPosition() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            ColumnDraft added = ColumnDraft.added("settled_at");
            added.setNativeType("DATETIME");
            drafts.add(added);

            List<TableChange> changes = TableDiff.compute(s, drafts, TABLE);
            assertEquals(1, changes.size());
            TableChange.AddColumn add = (TableChange.AddColumn) changes.get(0);
            assertEquals("settled_at", add.column().name());
            assertEquals("note", add.afterColumn());
        }

        @Test
        @DisplayName("删掉的字段被识别为删除，且标为破坏性")
        void dropColumnIsDestructive() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.removeIf(d -> d.name().equals("note"));

            List<TableChange> changes = TableDiff.compute(s, drafts, TABLE);
            assertEquals(1, changes.size());
            assertEquals(TableChange.Risk.DESTRUCTIVE, changes.get(0).risk());
        }

        @Test
        @DisplayName("改名与改定义合成一个变更，不会既 rename 又 modify")
        void renameAndModifyCollapseIntoOne() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            ColumnDraft note = drafts.get(2);
            note.setName("remark");
            note.setPrecision(512);

            List<TableChange> changes = TableDiff.compute(s, drafts, TABLE);
            assertEquals(1, changes.size());
            TableChange.RenameColumn rename = (TableChange.RenameColumn) changes.get(0);
            assertTrue(rename.definitionAlsoChanged());
        }

        @Test
        @DisplayName("加非空且无默认值，风险等级是「取决于既有数据」")
        void addingNotNullIsDataDependent() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setNullable(false);

            List<TableChange> changes = TableDiff.compute(s, drafts, TABLE);
            assertEquals(TableChange.Risk.DATA_DEPENDENT, changes.get(0).risk());
        }

        @Test
        @DisplayName("主键变化被单独识别出来")
        void primaryKeyChangeDetected() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(1).setPrimaryKey(true);

            List<TableChange> changes = TableDiff.compute(s, drafts, TABLE);
            TableChange.ChangePrimaryKey pk = (TableChange.ChangePrimaryKey) changes.stream()
                    .filter(c -> c instanceof TableChange.ChangePrimaryKey).findFirst().orElseThrow();
            assertEquals(List.of("id"), pk.from());
            assertEquals(List.of("id", "amount"), pk.to());
        }
    }

    // ------------------------------------------------------------------ 校验

    @Nested
    @DisplayName("草稿校验")
    class Validation {

        @Test
        @DisplayName("重名字段被拦下")
        void duplicateNameRejected() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setName("amount");

            List<String> problems = TableDiff.validate(drafts);
            assertTrue(problems.stream().anyMatch(p -> p.contains("重复")), problems.toString());
        }

        @Test
        @DisplayName("小数位大于总位数被拦下")
        void scaleLargerThanPrecisionRejected() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(1).setPrecision(5);
            drafts.get(1).setScale(10);

            assertTrue(TableDiff.validate(drafts).stream()
                    .anyMatch(p -> p.contains("小数位")));
        }

        @Test
        @DisplayName("非整型字段设自增被拦下")
        void autoIncrementOnNonIntegerRejected() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setAutoIncrement(true);

            assertTrue(TableDiff.validate(drafts).stream()
                    .anyMatch(p -> p.contains("自增")));
        }
    }

    // ------------------------------------------------------------------ 方言

    @Nested
    @DisplayName("MySQL")
    class MySql {

        private final SqlDialect dialect = Dialects.forType(DbType.MYSQL);

        @Test
        @DisplayName("新增字段用反引号、带 AFTER 与 COMMENT")
        void addColumn() {
            ColumnDraft d = ColumnDraft.added("settled_at");
            d.setNativeType("DATETIME");
            d.setPrecision(6);
            d.setComment("结算时间");

            String sql = allSql(dialect, List.of(new TableChange.AddColumn(d, "note")));
            assertTrue(sql.contains("ALTER TABLE `shop`.`orders`"), sql);
            assertTrue(sql.contains("ADD COLUMN `settled_at` DATETIME(6)"), sql);
            assertTrue(sql.contains("AFTER `note`"), sql);
            assertTrue(sql.contains("COMMENT '结算时间'"), sql);
        }

        @Test
        @DisplayName("改名用一条 CHANGE COLUMN，同时带上新定义")
        void renameUsesSingleChangeStatement() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setName("remark");
            drafts.get(2).setPrecision(512);

            String sql = allSql(dialect, TableDiff.compute(s, drafts, TABLE));
            assertTrue(sql.contains("CHANGE COLUMN `note` `remark` VARCHAR(512)"), sql);
            // 不该再多出一条 MODIFY
            assertFalse(sql.contains("MODIFY COLUMN"), sql);
        }

        @Test
        @DisplayName("改定义用 MODIFY COLUMN，一条语句写完")
        void modifyColumn() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(1).setScale(4);

            String sql = allSql(dialect, TableDiff.compute(s, drafts, TABLE));
            assertTrue(sql.contains("MODIFY COLUMN `amount` DECIMAL(38,4) NOT NULL"), sql);
        }
    }

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final SqlDialect dialect = Dialects.forType(DbType.POSTGRESQL);

        @Test
        @DisplayName("改定义拆成多条 ALTER COLUMN，改类型带 USING")
        void modifySplitsIntoSeparateStatements() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setNativeType("TEXT");
            drafts.get(2).setNullable(false);

            String sql = allSql(dialect, TableDiff.compute(s, drafts, TABLE));
            assertTrue(sql.contains("ALTER COLUMN \"note\" TYPE TEXT USING \"note\"::TEXT"), sql);
            assertTrue(sql.contains("ALTER COLUMN \"note\" SET NOT NULL"), sql);
        }

        @Test
        @DisplayName("改名是独立的 RENAME COLUMN，不是 MySQL 的 CHANGE")
        void renameIsSeparate() {
            TableStructure s = ordersTable();
            List<ColumnDraft> drafts = draftsOf(s);
            drafts.get(2).setName("remark");

            String sql = allSql(dialect, TableDiff.compute(s, drafts, TABLE));
            assertTrue(sql.contains("RENAME COLUMN \"note\" TO \"remark\""), sql);
            assertFalse(sql.contains("CHANGE COLUMN"), sql);
        }

        @Test
        @DisplayName("自增写成 GENERATED AS IDENTITY，而不是 AUTO_INCREMENT")
        void identityInsteadOfAutoIncrement() {
            ColumnDraft d = ColumnDraft.added("seq");
            d.setNativeType("BIGINT");
            d.setAutoIncrement(true);

            String sql = allSql(dialect, List.of(new TableChange.AddColumn(d, null)));
            assertTrue(sql.contains("GENERATED BY DEFAULT AS IDENTITY"), sql);
            assertFalse(sql.contains("AUTO_INCREMENT"), sql);
        }
    }

    @Nested
    @DisplayName("SQLite 的能力边界")
    class Sqlite {

        private final SqlDialect dialect = Dialects.forType(DbType.SQLITE);

        @Test
        @DisplayName("加列、删列、纯改名都支持")
        void supportsWhatItCan() {
            ColumnDraft added = ColumnDraft.added("extra");
            assertTrue(dialect.supports(new TableChange.AddColumn(added, null)));
            assertTrue(dialect.supports(
                    new TableChange.DropColumn(col("note", "TEXT", 0, 0, true, false))));

            ColumnInfo from = col("note", "TEXT", 0, 0, true, false);
            ColumnDraft renamed = ColumnDraft.of(from);
            renamed.setName("remark");
            assertTrue(dialect.supports(new TableChange.RenameColumn(from, renamed)));
        }

        @Test
        @DisplayName("改列定义如实拒绝，并说明原因")
        void refusesColumnModification() {
            ColumnInfo from = col("note", "TEXT", 0, 0, true, false);
            ColumnDraft to = ColumnDraft.of(from);
            to.setNullable(false);
            TableChange change = new TableChange.ModifyColumn(from, to);

            assertFalse(dialect.supports(change));
            assertTrue(dialect.unsupportedReason(change).contains("重建表"));

            DbException e = assertThrows(DbException.class,
                    () -> dialect.ddlFor(SCHEMA, TABLE, List.of(change)));
            assertTrue(e.getMessage().contains("无法修改"), e.getMessage());
        }

        @Test
        @DisplayName("改名的同时改定义也要拒绝——只做一半比不做更糟")
        void refusesRenameThatAlsoChangesDefinition() {
            ColumnInfo from = col("note", "TEXT", 0, 0, true, false);
            ColumnDraft to = ColumnDraft.of(from);
            to.setName("remark");
            to.setNullable(false);

            assertFalse(dialect.supports(new TableChange.RenameColumn(from, to)));
        }

        @Test
        @DisplayName("改主键如实拒绝")
        void refusesPrimaryKeyChange() {
            TableChange change = new TableChange.ChangePrimaryKey(List.of("id"), List.of("amount"));
            assertFalse(dialect.supports(change));
            assertTrue(dialect.unsupportedReason(change).contains("主键"));
        }
    }

    // ------------------------------------------------------------------ 类型

    @Test
    @DisplayName("换成不带参数的类型时，长度与小数位被清掉，不会生成 TEXT(255)")
    void switchingTypeClearsStaleLength() {
        ColumnDraft d = ColumnDraft.added("note");
        d.setNativeType("DECIMAL");
        d.setPrecision(38);
        d.setScale(10);
        assertEquals("DECIMAL(38,10)", d.fullType());

        d.setNativeType("TEXT");
        assertEquals("TEXT", d.fullType());
    }

    @Test
    @DisplayName("回归：括号参数不跨语义继承，VARCHAR(255) 改 TIMESTAMP 不会变成 TIMESTAMP(255)")
    void lengthDoesNotCarryAcrossDifferentMeanings() {
        // 这条来自一次真库执行失败：H2 报
        // “Scale or fractional seconds precision ("255") must be between "0" and "9"”。
        // 三种括号参数（字符长度 / 十进制位数 / 秒小数位）含义与取值范围都不同，不能互相沿用。
        ColumnDraft d = ColumnDraft.added("settled_at");
        assertEquals("VARCHAR(255)", d.fullType(), "新建字段的默认值");

        d.setNativeType("TIMESTAMP");
        assertEquals("TIMESTAMP", d.fullType(), "字符长度不能当秒小数位用");

        d.setNativeType("DECIMAL");
        d.setPrecision(38);
        d.setScale(10);
        d.setNativeType("TIMESTAMP");
        assertEquals("TIMESTAMP", d.fullType(), "十进制位数同样不能带到时间类型上");
    }

    @Test
    @DisplayName("秒小数位被夹在 0–9，写多少都不会生成非法 DDL")
    void fractionalSecondsAreClamped() {
        ColumnDraft d = ColumnDraft.added("created_at");
        d.setNativeType("TIMESTAMP");
        d.setPrecision(255);
        assertEquals("TIMESTAMP(9)", d.fullType());

        d.setPrecision(6);
        assertEquals("TIMESTAMP(6)", d.fullType());
    }

    @Test
    @DisplayName("同类语义之间保留长度：VARCHAR 改 CHAR 不用重填")
    void lengthSurvivesWithinSameMeaning() {
        ColumnDraft d = ColumnDraft.added("code");
        d.setNativeType("VARCHAR");
        d.setPrecision(32);
        d.setNativeType("CHAR");
        assertEquals("CHAR(32)", d.fullType());
    }

    @Test
    @DisplayName("类型名判定：精确数值一个都不能漏")
    void exactNumericClassification() {
        for (String t : new String[]{"DECIMAL", "NUMERIC", "NUMBER", "BIGINT", "MONEY"}) {
            assertEquals(TypeCategory.EXACT_NUMERIC, com.plainly.driver.TypeNames.categoryOf(t),
                    t + " 必须归入 EXACT_NUMERIC，否则该列会走上有损读取路径");
        }
        assertEquals(TypeCategory.APPROX_NUMERIC, com.plainly.driver.TypeNames.categoryOf("DOUBLE"));
        assertEquals(TypeCategory.INTEGER, com.plainly.driver.TypeNames.categoryOf("INT"));
    }

    @Test
    @DisplayName("设为主键时自动置为非空，模型不会自相矛盾")
    void primaryKeyImpliesNotNull() {
        ColumnDraft d = ColumnDraft.added("id");
        d.setNullable(true);
        d.setPrimaryKey(true);
        assertFalse(d.nullable());
    }
}
