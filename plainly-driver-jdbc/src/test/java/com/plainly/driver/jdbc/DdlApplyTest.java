package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.DdlBatchException;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.ddl.TableDiff;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成的 DDL 拿到真库上跑一遍。
 *
 * <p>与 {@code DdlGenerationTest} 的区别：那边验证「拼出来的字符串对不对」，
 * 这边验证「这串字符串数据库真的认」。语法拼对了但方言细节错了的情况很常见，
 * 只有真跑一次才知道。
 */
@DisplayName("结构变更 · 在 H2 上实际执行")
class DdlApplyTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "DDL_PROBE";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("ddl-probe")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_ddl;DB_CLOSE_DELAY=-1")
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
    void resetTable() {
        conn.execute("DROP TABLE IF EXISTS " + TABLE, 0);
        conn.execute("CREATE TABLE " + TABLE + " ("
                + "  ID     BIGINT         NOT NULL PRIMARY KEY,"
                + "  AMOUNT DECIMAL(38,10) NOT NULL,"
                + "  NOTE   VARCHAR(255)"
                + ")", 0);
        conn.execute("INSERT INTO " + TABLE + " VALUES "
                + "(1, 12345678901234567890.1234567890, '首行'),"
                + "(2, 0.0000000001, NULL)", 0);
    }

    // ------------------------------------------------------------------ 工具

    private TableStructure current() {
        return conn.describeTable(SCHEMA, TABLE);
    }

    private List<ColumnDraft> drafts() {
        List<ColumnDraft> list = new ArrayList<>();
        current().columns().forEach(c -> list.add(ColumnDraft.of(c)));
        return list;
    }

    /** 走完整链路：算差异 → 生成 DDL → 一个事务里执行。 */
    private List<String> apply(List<ColumnDraft> drafts) {
        List<TableChange> changes = TableDiff.compute(current(), drafts, TABLE);
        List<SqlDialect.TableChangeSql> ddl = conn.dialect().ddlFor(SCHEMA, TABLE, changes);
        List<String> statements = ddl.stream().map(SqlDialect.TableChangeSql::sql).toList();
        conn.executeDdlBatch(statements);
        return statements;
    }

    private ColumnInfo column(String name) {
        return current().columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("不变量：原样打开设计器，不得凭空冒出任何变更")
    void draftingUntouchedTableYieldsNoChanges() {
        // 这条是最强的一条守门测试：只要「库里的元数据」和「草稿模型」对某个类型的
        // 理解有一丝偏差，这里就会失败。
        // 它抓到过一次真实缺陷：H2 对 TIMESTAMP 报 COLUMN_SIZE=26、DECIMAL_DIGITS=6，
        // 两边各取一个，未经改动的列被判成 TIMESTAMP(26,6) → TIMESTAMP(26)。
        conn.execute("DROP TABLE IF EXISTS TYPE_ZOO", 0);
        conn.execute("CREATE TABLE TYPE_ZOO ("
                + "  A BIGINT NOT NULL PRIMARY KEY,"
                + "  B INTEGER,"
                + "  C SMALLINT,"
                + "  D DECIMAL(38,10),"
                + "  E DECIMAL(10),"
                + "  F DOUBLE,"
                + "  G VARCHAR(255),"
                + "  H CHAR(3),"
                + "  I CLOB,"
                + "  J TIMESTAMP,"
                + "  K TIMESTAMP(6),"
                + "  L TIMESTAMP(9),"
                + "  M DATE,"
                + "  N TIME,"
                + "  O BOOLEAN,"
                + "  P VARBINARY(64),"
                + "  Q UUID"
                + ")", 0);

        TableStructure zoo = conn.describeTable(SCHEMA, "TYPE_ZOO");
        List<ColumnDraft> untouched = new ArrayList<>();
        zoo.columns().forEach(c -> untouched.add(ColumnDraft.of(c)));

        List<TableChange> changes = TableDiff.compute(zoo, untouched, "TYPE_ZOO");

        assertTrue(changes.isEmpty(),
                "未改动却产生了变更，说明元数据与草稿对类型的理解不一致：\n  "
                        + changes.stream().map(TableChange::describe)
                        .reduce((a, b) -> a + "\n  " + b).orElse(""));

        conn.execute("DROP TABLE TYPE_ZOO", 0);
    }

    @Test
    @DisplayName("时间类型的括号参数取秒小数位，而不是字符串宽度")
    void temporalPrecisionIsFractionalSeconds() {
        conn.execute("DROP TABLE IF EXISTS TS_PROBE", 0);
        conn.execute("CREATE TABLE TS_PROBE (A TIMESTAMP(6), B TIMESTAMP)", 0);

        TableStructure s = conn.describeTable(SCHEMA, "TS_PROBE");
        ColumnInfo a = s.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase("A")).findFirst().orElseThrow();

        assertEquals("TIMESTAMP(6)", a.displayType(),
                "取到的应当是秒小数位 6，而不是格式化宽度 26");
        assertEquals("TIMESTAMP(6)", ColumnDraft.of(a).fullType(),
                "草稿必须和元数据显示成同一个类型，否则会比出幻影变更");

        conn.execute("DROP TABLE TS_PROBE", 0);
    }

    @Test
    @DisplayName("新增字段：库里真的多了一列，且默认值生效")
    void addColumn() {
        List<ColumnDraft> drafts = drafts();
        ColumnDraft settled = ColumnDraft.added("SETTLED_AT");
        settled.setNativeType("TIMESTAMP");
        drafts.add(settled);

        ColumnDraft flag = ColumnDraft.added("PAID_FLAG");
        flag.setNativeType("INTEGER");
        flag.setNullable(false);
        flag.setDefaultValue("0");
        drafts.add(flag);

        apply(drafts);

        assertNotNull(column("SETTLED_AT"));
        ColumnInfo added = column("PAID_FLAG");
        assertNotNull(added);
        assertFalse(added.nullable());
        // 既有两行都拿到了默认值
        assertEquals("0", conn.scalar("SELECT PAID_FLAG FROM " + TABLE + " WHERE ID = 1"));
    }

    @Test
    @DisplayName("删除字段：列没了，其余数据不受影响")
    void dropColumn() {
        List<ColumnDraft> drafts = drafts();
        drafts.removeIf(d -> d.name().equalsIgnoreCase("NOTE"));

        apply(drafts);

        assertNull(column("NOTE"));
        assertEquals("12345678901234567890.1234567890",
                conn.scalar("SELECT AMOUNT FROM " + TABLE + " WHERE ID = 1"),
                "结构变更不该动到数据");
    }

    @Test
    @DisplayName("重命名字段：新名字可查，数据跟着走")
    void renameColumn() {
        List<ColumnDraft> drafts = drafts();
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("NOTE"))
                .forEach(d -> d.setName("REMARK"));

        apply(drafts);

        assertNull(column("NOTE"));
        assertNotNull(column("REMARK"));
        assertEquals("首行", conn.scalar("SELECT REMARK FROM " + TABLE + " WHERE ID = 1"));
    }

    @Test
    @DisplayName("扩大 DECIMAL 的小数位：既有值按新 scale 补零，一位不丢")
    void widenDecimalKeepsValue() {
        List<ColumnDraft> drafts = drafts();
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("AMOUNT"))
                .forEach(d -> {
                    d.setPrecision(38);
                    d.setScale(12);
                });

        List<String> sql = apply(drafts);
        assertTrue(sql.stream().anyMatch(s -> s.contains("SET DATA TYPE DECIMAL(38,12)")),
                sql.toString());

        ColumnInfo amount = column("AMOUNT");
        assertEquals(12, amount.scale());
        assertEquals(TypeCategory.EXACT_NUMERIC, amount.category());
        // 值没变，只是多了两位零
        assertEquals("12345678901234567890.123456789000",
                conn.scalar("SELECT AMOUNT FROM " + TABLE + " WHERE ID = 1"));
    }

    @Test
    @DisplayName("改可空性：NOT NULL 加得上，也去得掉")
    void toggleNullability() {
        List<ColumnDraft> drafts = drafts();
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("AMOUNT"))
                .forEach(d -> d.setNullable(true));
        apply(drafts);
        assertTrue(column("AMOUNT").nullable());

        List<ColumnDraft> back = drafts();
        back.stream().filter(d -> d.name().equalsIgnoreCase("AMOUNT"))
                .forEach(d -> d.setNullable(false));
        apply(back);
        assertFalse(column("AMOUNT").nullable());
    }

    @Test
    @DisplayName("改名与改定义同时发生：一趟走完，两者都生效")
    void renameAndModifyTogether() {
        List<ColumnDraft> drafts = drafts();
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("NOTE"))
                .forEach(d -> {
                    d.setName("REMARK");
                    d.setPrecision(512);
                });

        apply(drafts);

        ColumnInfo renamed = column("REMARK");
        assertNotNull(renamed);
        assertEquals(512, renamed.precision());
    }

    @Test
    @DisplayName("换主键：旧主键去掉，新主键建上")
    void changePrimaryKey() {
        // 先让 AMOUNT 具备做主键的条件
        conn.execute("DELETE FROM " + TABLE + " WHERE ID = 2", 0);

        List<ColumnDraft> drafts = drafts();
        drafts.forEach(d -> d.setPrimaryKey(d.name().equalsIgnoreCase("AMOUNT")));

        apply(drafts);

        assertFalse(column("ID").primaryKey());
        assertTrue(column("AMOUNT").primaryKey());
    }

    @Test
    @DisplayName("批次失败时如实报告库的状态")
    void failedBatchReportsHonestState() {
        // 这条测试原本断言「整批回滚」并且通过了——但它是空过的：
        // 变更的执行顺序让失败发生在 ADD COLUMN 之前，回滚从未被真正验证。
        // H2 与 MySQL 的 DDL 会隐式提交，回滚本就做不到；契约改成如实报告状态。
        List<ColumnDraft> drafts = drafts();
        // 先放一条一定成功的，再放一条一定失败的，顺序由 TableDiff 决定：
        // DropColumn → Rename/Modify → AddColumn，所以让失败出现在 Modify 上、
        // 成功出现在 AddColumn 上是不行的。改成让 NOTE 改名成功、AMOUNT 收窄失败。
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("NOTE"))
                .forEach(d -> d.setName("REMARK"));
        drafts.stream().filter(d -> d.name().equalsIgnoreCase("AMOUNT"))
                .forEach(d -> d.setNativeType("INTEGER"));

        DdlBatchException failure = assertThrows(DdlBatchException.class, () -> apply(drafts),
                "把含超长小数的 DECIMAL 收窄成 INTEGER 应当失败");

        assertEquals(conn.dialect().supportsTransactionalDdl(), failure.rolledBack());
        assertTrue(failure.totalCount() >= 1);

        if (!failure.rolledBack() && failure.executedCount() > 0) {
            assertTrue(failure.getMessage().contains("无法回滚"), failure.getMessage());
        }
    }
}
