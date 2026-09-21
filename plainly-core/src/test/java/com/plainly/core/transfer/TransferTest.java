package com.plainly.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.TypeMapper;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨库传输。
 *
 * <p>类型映射错了不会报错——{@code BIGINT UNSIGNED} 映射成 {@code BIGINT} 完全合法，
 * 只在某一行的值超过 {@code 9223372036854775807} 时才溢出，而那时数据已经搬了一半。
 * 所以这里逐条断言映射规则，再用两个 H2 库把「结构 + 数据」真搬一遍，
 * 最后逐位比对高精度列。
 */
@DisplayName("数据传输 · 类型映射与实搬")
class TransferTest {

    private static final String HUGE = "12345678901234567890.1234567890";

    private static DbConnection source;
    private static DbConnection target;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "TRANSFER_PROBE";

    @BeforeAll
    static void open() {
        source = JdbcConnections.open(new ConnectionConfig()
                .setName("src").setType(DbType.H2)
                .setFilePath("mem:plainly_tx_src;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        target = JdbcConnections.open(new ConnectionConfig()
                .setName("dst").setType(DbType.H2)
                .setFilePath("mem:plainly_tx_dst;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));

        source.executeDdlBatch(List.of(
                "CREATE TABLE " + TABLE + " (ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(64))",
                "INSERT INTO " + TABLE + " VALUES (1, " + HUGE + ", '高精度')",
                "INSERT INTO " + TABLE + " VALUES (2, 0.0000000001, '很小')",
                "INSERT INTO " + TABLE + " VALUES (3, -5600, NULL)"));
    }

    @AfterAll
    static void close() {
        if (source != null) {
            source.close();
        }
        if (target != null) {
            target.close();
        }
    }

    private static ColumnInfo column(String type, int precision, int scale) {
        return new ColumnInfo("C", type, TypeCategory.OTHER, precision, scale,
                true, false, false, null, "", 1);
    }

    // ------------------------------------------------------------------ 映射

    @Test
    @DisplayName("MySQL 的无符号 BIGINT 要放宽，否则会溢出")
    void unsignedBigintWidens() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("BIGINT UNSIGNED", 20, 0), DbType.MYSQL, DbType.POSTGRESQL);
        assertEquals("NUMERIC(20)", m.target());
        assertFalse(m.exact(), "换了类型就不该说成精确对应");
        assertTrue(m.reason().contains("18446744073709551615"), m.reason());
    }

    @Test
    @DisplayName("常见类型逐个精确对应")
    void exactMappings() {
        assertEquals("NUMERIC", TypeMapper.mapType(
                column("DECIMAL", 38, 10), DbType.MYSQL, DbType.POSTGRESQL).target());
        assertEquals("BIGINT", TypeMapper.mapType(
                column("BIGINT", 19, 0), DbType.MYSQL, DbType.POSTGRESQL).target());
        assertEquals("TIMESTAMP", TypeMapper.mapType(
                column("DATETIME", 26, 6), DbType.MYSQL, DbType.POSTGRESQL).target());
        assertEquals("JSONB", TypeMapper.mapType(
                column("JSON", 0, 0), DbType.MYSQL, DbType.POSTGRESQL).target());
        assertEquals("BYTEA", TypeMapper.mapType(
                column("LONGBLOB", 0, 0), DbType.MYSQL, DbType.POSTGRESQL).target());
        assertEquals("BOOLEAN", TypeMapper.mapType(
                column("BIT", 1, 0), DbType.MYSQL, DbType.POSTGRESQL).target());
    }

    @Test
    @DisplayName("搬到 SQLite 时 DECIMAL 必须走 TEXT")
    void decimalToSqliteBecomesText() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("DECIMAL", 38, 10), DbType.MYSQL, DbType.SQLITE);
        assertEquals("TEXT", m.target());
        assertTrue(m.reason().contains("REAL"), m.reason());
    }

    @Test
    @DisplayName("同库之间原样不动")
    void sameTypeStaysPut() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("DECIMAL(38,10)", 38, 10), DbType.MYSQL, DbType.MYSQL);
        assertEquals("DECIMAL(38,10)", m.target());
        assertTrue(m.exact());
    }

    @Test
    @DisplayName("认不出的类型原样带过去并标明不确定，不擅自换成 VARCHAR")
    void unknownTypeIsNotGuessed() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("GEOMETRY", 0, 0), DbType.MYSQL, DbType.POSTGRESQL);
        assertEquals("GEOMETRY", m.target());
        assertFalse(m.exact());
        assertTrue(m.reason().contains("自行确认"), m.reason());
    }

    @Test
    @DisplayName("PostgreSQL 的 TIMESTAMP 搬到 MySQL 要换成 DATETIME")
    void timestampToMysqlAvoids2038() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("TIMESTAMP", 26, 6), DbType.POSTGRESQL, DbType.MYSQL);
        assertEquals("DATETIME", m.target());
        assertTrue(m.reason().contains("2038"), m.reason());
    }

    // ------------------------------------------------------------------ 实搬

    @Test
    @DisplayName("结构与数据都搬过去，高精度列一位不差")
    void transfersStructureAndData() {
        TransferService.TablePlan plan = TransferService.plan(
                source, SCHEMA, TABLE, target, SCHEMA);
        assertEquals(3, plan.rows());
        assertTrue(plan.createDdl().startsWith("CREATE TABLE"), plan.createDdl());

        TransferService.Outcome outcome = TransferService.transfer(
                source, SCHEMA, plan, target, SCHEMA,
                TransferService.TargetMode.CREATE, 100, n -> { });
        assertTrue(outcome.ok(), "传输失败：" + outcome.problem());
        assertEquals(3, outcome.read());

        assertEquals("一致 · 3 行",
                TransferService.verify(source, SCHEMA, target, SCHEMA, TABLE));

        QueryResult r = target.execute(
                "SELECT AMOUNT FROM " + TABLE + " WHERE ID = 1", 1);
        assertEquals(HUGE, r.rows().get(0).get(0), "搬过去的高精度值必须一位不差");
    }

    @Test
    @DisplayName("再搬一次：清空模式不会把行数搬成两倍")
    void truncateModeDoesNotDouble() {
        TransferService.TablePlan plan = TransferService.plan(
                source, SCHEMA, TABLE, target, SCHEMA);
        TransferService.transfer(source, SCHEMA, plan, target, SCHEMA,
                TransferService.TargetMode.CREATE, 100, n -> { });
        TransferService.Outcome again = TransferService.transfer(
                source, SCHEMA, plan, target, SCHEMA,
                TransferService.TargetMode.TRUNCATE, 100, n -> { });

        assertTrue(again.ok(), again.problem());
        assertEquals("一致 · 3 行",
                TransferService.verify(source, SCHEMA, target, SCHEMA, TABLE));
    }

    @Test
    @DisplayName("NULL 搬过去还是 NULL，不会变成空字符串")
    void nullStaysNull() {
        TransferService.TablePlan plan = TransferService.plan(
                source, SCHEMA, TABLE, target, SCHEMA);
        TransferService.transfer(source, SCHEMA, plan, target, SCHEMA,
                TransferService.TargetMode.TRUNCATE, 100, n -> { });

        QueryResult r = target.execute("SELECT NOTE FROM " + TABLE + " WHERE ID = 3", 1);
        assertEquals(null, r.rows().get(0).get(0));
    }
}
