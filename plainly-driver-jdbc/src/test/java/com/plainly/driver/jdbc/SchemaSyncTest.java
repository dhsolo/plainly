package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.DdlBatchException;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.ddl.SchemaDiff;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构同步：库级差异计算与真库执行。
 *
 * <h2>核心断言</h2>
 * 这套测试真正守的是一条不变量：<b>把计划应用到目标库之后，再比一次必须为空</b>。
 * 它一次性覆盖了「差异算得全不全」「DDL 生成对不对」「数据库认不认」三件事——
 * 任何一环出问题，第二次比对都会剩下东西。
 */
@DisplayName("结构同步")
class SchemaSyncTest {

    private static DbConnection conn;
    private static final String SRC = "SRC";
    private static final String TGT = "TGT";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("sync-probe")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_sync;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @BeforeEach
    void resetSchemas() {
        conn.execute("DROP SCHEMA IF EXISTS " + SRC + " CASCADE", 0);
        conn.execute("DROP SCHEMA IF EXISTS " + TGT + " CASCADE", 0);
        conn.execute("CREATE SCHEMA " + SRC, 0);
        conn.execute("CREATE SCHEMA " + TGT, 0);
    }

    // ------------------------------------------------------------------ 工具

    private List<TableStructure> readAll(String schema) {
        List<TableStructure> out = new ArrayList<>();
        for (TableInfo t : conn.listTables(schema)) {
            if (t.kind() == ObjectKind.TABLE) {
                out.add(conn.describeTable(schema, t.name()));
            }
        }
        return out;
    }

    private List<SchemaChange> diff() {
        return SchemaDiff.compute(readAll(SRC), readAll(TGT));
    }

    /** 生成 DDL 并在一个事务里应用到目标库。 */
    private List<String> applyToTarget(List<SchemaChange> changes) {
        List<SqlDialect.SchemaChangeSql> ddl = conn.dialect().ddlForSchema(TGT, changes);
        List<String> sql = ddl.stream().map(SqlDialect.SchemaChangeSql::sql).toList();
        conn.executeDdlBatch(sql);
        return sql;
    }

    /** 应用后再比一次，必须为空。 */
    private void assertConverged() {
        List<SchemaChange> remaining = diff();
        assertTrue(remaining.isEmpty(),
                "同步之后仍有差异，说明有变更没被算出来或没被正确执行：\n  "
                        + remaining.stream().map(SchemaChange::describe)
                        .reduce((a, b) -> a + "\n  " + b).orElse(""));
    }

    private TableStructure targetTable(String name) {
        return conn.describeTable(TGT, name);
    }

    // ------------------------------------------------------------------ 差异

    @Test
    @DisplayName("两库一致时没有任何差异")
    void identicalSchemasProduceNoChanges() {
        String ddl = "(ID BIGINT NOT NULL PRIMARY KEY, AMOUNT DECIMAL(38,10), NOTE VARCHAR(255))";
        conn.execute("CREATE TABLE " + SRC + ".ORDERS " + ddl, 0);
        conn.execute("CREATE TABLE " + TGT + ".ORDERS " + ddl, 0);

        assertTrue(diff().isEmpty());
    }

    @Test
    @DisplayName("源有目标没有的表 → 建表，且带上它的索引")
    void tableOnlyInSourceIsCreated() {
        conn.execute("CREATE TABLE " + SRC + ".COUPONS ("
                + "ID BIGINT NOT NULL PRIMARY KEY, CODE VARCHAR(32), DISCOUNT DECIMAL(8,6))", 0);
        conn.execute("CREATE UNIQUE INDEX IDX_COUPON_CODE ON " + SRC + ".COUPONS (CODE)", 0);

        List<SchemaChange> changes = diff();
        assertEquals(1, changes.size(), "建表与它自带的索引应当合并成一项变更");
        assertTrue(changes.get(0) instanceof SchemaChange.CreateTable);
        assertEquals(TableChange.Risk.SAFE, changes.get(0).risk());

        List<String> sql = applyToTarget(changes);
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("CREATE TABLE")), sql.toString());
        assertTrue(sql.stream().anyMatch(s -> s.contains("CREATE UNIQUE INDEX")), sql.toString());

        assertConverged();
    }

    @Test
    @DisplayName("目标有源没有的表 → 删表，标为破坏性，且排在最后执行")
    void tableOnlyInTargetIsDroppedLast() {
        conn.execute("CREATE TABLE " + SRC + ".A (ID BIGINT NOT NULL PRIMARY KEY)", 0);
        conn.execute("CREATE TABLE " + TGT + ".LEGACY (ID BIGINT NOT NULL PRIMARY KEY)", 0);

        List<SchemaChange> changes = diff();
        assertEquals(2, changes.size());
        // CreateTable(20) 必须排在 DropTable(50) 之前
        assertTrue(changes.get(0) instanceof SchemaChange.CreateTable);
        assertTrue(changes.get(1) instanceof SchemaChange.DropTable);
        assertEquals(TableChange.Risk.DESTRUCTIVE, changes.get(1).risk());

        applyToTarget(changes);
        assertConverged();
    }

    @Test
    @DisplayName("字段差异复用单表引擎：新增、删除、改类型一次算全")
    void columnDifferencesReuseTableDiff() {
        conn.execute("CREATE TABLE " + SRC + ".T ("
                + "ID BIGINT NOT NULL PRIMARY KEY,"
                + "AMOUNT DECIMAL(38,10) NOT NULL,"   // 目标是 DECIMAL(10,2)
                + "ADDED VARCHAR(64)"                 // 目标没有
                + ")", 0);
        conn.execute("CREATE TABLE " + TGT + ".T ("
                + "ID BIGINT NOT NULL PRIMARY KEY,"
                + "AMOUNT DECIMAL(10,2) NOT NULL,"
                + "REMOVED VARCHAR(64)"               // 源没有
                + ")", 0);

        List<SchemaChange> changes = diff();
        assertEquals(1, changes.size());
        SchemaChange.AlterTable alter = (SchemaChange.AlterTable) changes.get(0);

        assertTrue(alter.changes().stream().anyMatch(c -> c instanceof TableChange.ModifyColumn),
                "AMOUNT 的类型差异应当被算出来");
        assertTrue(alter.changes().stream().anyMatch(c -> c instanceof TableChange.AddColumn),
                "源多出的 ADDED 应当算作新增——这是 rebase/newFrom 用错就会漏掉的那一类");
        assertTrue(alter.changes().stream().anyMatch(c -> c instanceof TableChange.DropColumn),
                "目标多出的 REMOVED 应当算作删除");

        applyToTarget(changes);

        TableStructure t = targetTable("T");
        assertEquals(10, t.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase("AMOUNT")).findFirst().orElseThrow().scale());
        assertTrue(t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase("ADDED")));
        assertFalse(t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase("REMOVED")));

        assertConverged();
    }

    @Test
    @DisplayName("主键索引不参与索引比对，否则会和主键变更生成互相冲突的 DDL")
    void primaryKeyIndexIsNotDiffedAsIndex() {
        // 两库主键相同但索引名不同（H2 会自动生成 PRIMARY_KEY_x，编号可能不一致）
        conn.execute("CREATE TABLE " + SRC + ".P (ID BIGINT NOT NULL PRIMARY KEY, X INT)", 0);
        conn.execute("CREATE TABLE " + TGT + ".Q (ID BIGINT NOT NULL PRIMARY KEY, X INT)", 0);
        conn.execute("CREATE TABLE " + TGT + ".P (ID BIGINT NOT NULL PRIMARY KEY, X INT)", 0);
        conn.execute("DROP TABLE " + TGT + ".Q", 0);

        List<SchemaChange> changes = diff();
        assertTrue(changes.stream().noneMatch(c -> c instanceof SchemaChange.CreateIndex
                        || c instanceof SchemaChange.DropIndex),
                "主键索引被当成普通索引比对了：" + changes.stream()
                        .map(SchemaChange::describe).toList());
    }

    @Test
    @DisplayName("索引差异：新增、删除、改定义（先删后建）")
    void indexDifferences() {
        conn.execute("CREATE TABLE " + SRC + ".T (ID BIGINT NOT NULL PRIMARY KEY, A INT, B INT)", 0);
        conn.execute("CREATE TABLE " + TGT + ".T (ID BIGINT NOT NULL PRIMARY KEY, A INT, B INT)", 0);

        conn.execute("CREATE INDEX IDX_NEW ON " + SRC + ".T (A)", 0);
        conn.execute("CREATE INDEX IDX_GONE ON " + TGT + ".T (B)", 0);
        // 同名不同定义
        conn.execute("CREATE INDEX IDX_CHANGED ON " + SRC + ".T (A, B)", 0);
        conn.execute("CREATE INDEX IDX_CHANGED ON " + TGT + ".T (B)", 0);

        List<SchemaChange> changes = diff();
        long creates = changes.stream().filter(c -> c instanceof SchemaChange.CreateIndex).count();
        long drops = changes.stream().filter(c -> c instanceof SchemaChange.DropIndex).count();
        assertEquals(2, creates, "IDX_NEW 与重建的 IDX_CHANGED");
        assertEquals(2, drops, "IDX_GONE 与被重建前删掉的 IDX_CHANGED");

        // 删索引(10) 必须排在建索引(40) 之前，否则重建同名索引会撞名
        int firstCreate = -1;
        int lastDrop = -1;
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i) instanceof SchemaChange.CreateIndex && firstCreate < 0) {
                firstCreate = i;
            }
            if (changes.get(i) instanceof SchemaChange.DropIndex) {
                lastDrop = i;
            }
        }
        assertTrue(lastDrop < firstCreate, "所有删索引必须排在建索引之前");

        applyToTarget(changes);
        assertConverged();
    }

    @Test
    @DisplayName("删索引排在删列之前——列被索引引用时删不掉")
    void dropIndexPrecedesDropColumn() {
        conn.execute("CREATE TABLE " + SRC + ".T (ID BIGINT NOT NULL PRIMARY KEY)", 0);
        conn.execute("CREATE TABLE " + TGT + ".T (ID BIGINT NOT NULL PRIMARY KEY, EXTRA INT)", 0);
        conn.execute("CREATE INDEX IDX_EXTRA ON " + TGT + ".T (EXTRA)", 0);

        List<SchemaChange> changes = diff();
        int dropIndexAt = -1;
        int alterAt = -1;
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i) instanceof SchemaChange.DropIndex) {
                dropIndexAt = i;
            }
            if (changes.get(i) instanceof SchemaChange.AlterTable) {
                alterAt = i;
            }
        }
        assertTrue(dropIndexAt >= 0 && alterAt >= 0);
        assertTrue(dropIndexAt < alterAt, "删索引必须排在删列之前");

        applyToTarget(changes);
        assertConverged();
    }

    // ------------------------------------------------------------ 收敛不变量

    @Test
    @DisplayName("不变量：一次同步之后再比对，必须完全一致")
    void syncConverges() {
        // 一次把各类差异全放进来
        conn.execute("CREATE TABLE " + SRC + ".ORDERS ("
                + "ID BIGINT NOT NULL PRIMARY KEY,"
                + "AMOUNT DECIMAL(38,10) NOT NULL,"
                + "CURRENCY CHAR(3),"
                + "SETTLED_AT TIMESTAMP(6),"
                + "NOTE VARCHAR(512))", 0);
        conn.execute("CREATE INDEX IDX_ORD_CUR ON " + SRC + ".ORDERS (CURRENCY)", 0);
        conn.execute("CREATE TABLE " + SRC + ".COUPONS ("
                + "ID BIGINT NOT NULL PRIMARY KEY, CODE VARCHAR(32))", 0);

        conn.execute("CREATE TABLE " + TGT + ".ORDERS ("
                + "ID BIGINT NOT NULL PRIMARY KEY,"
                + "AMOUNT DECIMAL(10,2) NOT NULL,"     // 类型不同
                + "CURRENCY CHAR(3),"
                + "NOTE VARCHAR(255),"                 // 长度不同
                + "OBSOLETE INT)", 0);                 // 源没有
        conn.execute("CREATE INDEX IDX_STALE ON " + TGT + ".ORDERS (NOTE)", 0);
        conn.execute("CREATE TABLE " + TGT + ".LEGACY (ID BIGINT NOT NULL PRIMARY KEY)", 0);

        List<SchemaChange> changes = diff();
        assertFalse(changes.isEmpty());

        applyToTarget(changes);
        assertConverged();

        // 顺带确认结果确实是「源的样子」
        TableStructure orders = targetTable("ORDERS");
        assertEquals(10, orders.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase("AMOUNT")).findFirst().orElseThrow().scale());
        assertNotNull(conn.describeTable(TGT, "COUPONS"));
        assertTrue(orders.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase("SETTLED_AT")));
    }

    @Test
    @DisplayName("同步是幂等的：连跑两次，第二次无事可做")
    void syncIsIdempotent() {
        conn.execute("CREATE TABLE " + SRC + ".T (ID BIGINT NOT NULL PRIMARY KEY, A DECIMAL(38,10))", 0);
        conn.execute("CREATE TABLE " + TGT + ".T (ID BIGINT NOT NULL PRIMARY KEY, A DECIMAL(10,2))", 0);

        applyToTarget(diff());
        assertConverged();

        List<SchemaChange> second = diff();
        assertTrue(second.isEmpty(), "第二次比对应当没有变更");
    }

    @Test
    @DisplayName("批次失败时如实报告库的状态，而不是假装回滚了")
    void failedBatchReportsHonestState() {
        // 这条测试写下来的过程本身就抓到了一个缺陷：
        // 原先界面上写着「变更在一个事务内执行，失败整体回滚」，
        // 但 H2 和 MySQL 的 DDL 会隐式提交，根本回滚不了——那是个不实承诺。
        // 现在的契约是：能回滚就回滚，不能回滚就说清楚已经执行到第几条。
        conn.execute("CREATE TABLE " + SRC + ".AAA (ID BIGINT NOT NULL PRIMARY KEY)", 0);
        conn.execute("CREATE TABLE " + SRC + ".T (ID BIGINT NOT NULL PRIMARY KEY, A INT NOT NULL)", 0);
        conn.execute("CREATE TABLE " + TGT + ".T (ID BIGINT NOT NULL PRIMARY KEY, A INT)", 0);
        // A 列有 NULL，加非空必然失败
        conn.execute("INSERT INTO " + TGT + ".T VALUES (1, NULL)", 0);

        DdlBatchException failure = assertThrows(DdlBatchException.class,
                () -> applyToTarget(diff()),
                "对含 NULL 的列加非空应当失败");

        boolean transactional = conn.dialect().supportsTransactionalDdl();
        assertEquals(transactional, failure.rolledBack(),
                "回滚与否必须与方言声明的能力一致");

        boolean aaaExists = conn.listTables(TGT).stream()
                .anyMatch(t -> t.name().equalsIgnoreCase("AAA"));

        if (transactional) {
            assertFalse(aaaExists, "声明支持事务性 DDL，就必须真的整批回滚");
            assertTrue(failure.getMessage().contains("整批已回滚"));
        } else {
            // H2 走的是这一支：建表已经生效，撤不回来
            assertTrue(aaaExists, "DDL 隐式提交的库上，先执行成功的建表会保留");
            assertTrue(failure.leftPartialState(), "应当识别出库处于改了一半的状态");
            assertTrue(failure.getMessage().contains("无法回滚"),
                    "错误信息必须明说撤不回来，否则用户会以为库还是原样：" + failure.getMessage());
            assertTrue(failure.executedCount() > 0);
        }
    }
}
