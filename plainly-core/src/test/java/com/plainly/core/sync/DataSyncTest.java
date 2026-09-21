package com.plainly.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据同步。
 *
 * <p>两处容易出错的地方：{@code 1.10} 和 {@code 1.1} 算不算相同（scale 差异），
 * 以及 DELETE 会不会被顺手带上——「源里没有」不等于「该删」，
 * 很可能只是源库还没同步过来，所以它必须由用户显式勾选。
 */
@DisplayName("数据同步 · 逐行比对")
class DataSyncTest {

    private static final String HUGE = "12345678901234567890.1234567890";

    private static DbConnection source;
    private static DbConnection target;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "COUPONS";

    @BeforeEach
    void reset() {
        if (source == null) {
            source = JdbcConnections.open(new ConnectionConfig()
                    .setName("sync-src").setType(DbType.H2)
                    .setFilePath("mem:plainly_sync_src;DB_CLOSE_DELAY=-1")
                    .setUser("sa").setPassword(""));
            target = JdbcConnections.open(new ConnectionConfig()
                    .setName("sync-dst").setType(DbType.H2)
                    .setFilePath("mem:plainly_sync_dst;DB_CLOSE_DELAY=-1")
                    .setUser("sa").setPassword(""));
            for (DbConnection c : List.of(source, target)) {
                c.executeDdlBatch(List.of(
                        "CREATE TABLE IF NOT EXISTS " + TABLE + " (ID INT PRIMARY KEY,"
                                + " CODE VARCHAR(32), DISCOUNT DECIMAL(38,10))"));
            }
        }
        source.executeDdlBatch(List.of("DELETE FROM " + TABLE));
        target.executeDdlBatch(List.of("DELETE FROM " + TABLE));

        source.executeDdlBatch(List.of(
                "INSERT INTO " + TABLE + " VALUES (1, 'SAME', 0.15)",
                "INSERT INTO " + TABLE + " VALUES (2, 'DIFFER', 0.15)",
                "INSERT INTO " + TABLE + " VALUES (3, 'SCALE', 0.1)",
                "INSERT INTO " + TABLE + " VALUES (4, 'ONLY-SOURCE', " + HUGE + ")"));
        target.executeDdlBatch(List.of(
                "INSERT INTO " + TABLE + " VALUES (1, 'SAME', 0.15)",
                "INSERT INTO " + TABLE + " VALUES (2, 'DIFFER', 0.12)",
                "INSERT INTO " + TABLE + " VALUES (3, 'SCALE', 0.1000)",
                "INSERT INTO " + TABLE + " VALUES (9, 'ONLY-TARGET', 0.05)"));
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

    private DataSyncService.DiffResult compare(DataSyncService.NumericMode mode) {
        return DataSyncService.compare(source, SCHEMA, TABLE, target, SCHEMA, TABLE,
                List.of("ID"), mode);
    }

    @Test
    @DisplayName("三类差异各自归位")
    void classifiesDiffs() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.NUMERIC);
        assertEquals(1, r.count(DataSyncService.Action.INSERT), "只有源有 id=4");
        assertEquals(1, r.count(DataSyncService.Action.UPDATE), "id=2 的折扣不同");
        assertEquals(1, r.count(DataSyncService.Action.DELETE), "只有目标有 id=9");
        assertEquals(2, r.same(), "id=1 相同；id=3 在数值模式下也算相同");
    }

    /**
     * scale 差异只在两侧的列定义不同时才出现。
     *
     * <p>同为 {@code DECIMAL(38,10)} 时，写进去的 {@code 0.1} 和 {@code 0.1000}
     * 读回来都是 {@code 0.1000000000}——两侧文本相同，根本没有差异可比。
     * 真实场景是目标库的这一列少几位小数，所以这里专门建一对不同精度的表。
     */
    @Test
    @DisplayName("严格模式下 scale 不同就算差异——存储形式也要对齐")
    void textModeSeesScaleDifference() {
        source.executeDdlBatch(List.of(
                "DROP TABLE IF EXISTS SCALED",
                "CREATE TABLE SCALED (ID INT PRIMARY KEY, RATE DECIMAL(18,6))",
                "INSERT INTO SCALED VALUES (1, 0.15)"));
        target.executeDdlBatch(List.of(
                "DROP TABLE IF EXISTS SCALED",
                "CREATE TABLE SCALED (ID INT PRIMARY KEY, RATE DECIMAL(18,2))",
                "INSERT INTO SCALED VALUES (1, 0.15)"));

        DataSyncService.DiffResult strict = DataSyncService.compare(
                source, SCHEMA, "SCALED", target, SCHEMA, "SCALED",
                List.of("ID"), DataSyncService.NumericMode.TEXT);
        assertEquals(1, strict.count(DataSyncService.Action.UPDATE),
                "0.150000 与 0.15 文本不同，严格模式该算差异");

        DataSyncService.DiffResult loose = DataSyncService.compare(
                source, SCHEMA, "SCALED", target, SCHEMA, "SCALED",
                List.of("ID"), DataSyncService.NumericMode.NUMERIC);
        assertEquals(0, loose.count(DataSyncService.Action.UPDATE),
                "数值相同，宽松模式不该生成 UPDATE");
        assertEquals(1, loose.same());
    }

    @Test
    @DisplayName("数值模式下 0.1 与 0.1000 算相同")
    void numericModeIgnoresTrailingZeros() {
        assertTrue(DataSyncService.equalValues("0.1", "0.1000", true,
                DataSyncService.NumericMode.NUMERIC));
        assertFalse(DataSyncService.equalValues("0.1", "0.1000", true,
                DataSyncService.NumericMode.TEXT));
    }

    @Test
    @DisplayName("UPDATE 只写真正变了的列")
    void updateTouchesOnlyChangedColumns() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.NUMERIC);
        DataSyncService.RowDiff update = r.diffs().stream()
                .filter(d -> d.action() == DataSyncService.Action.UPDATE)
                .findFirst().orElseThrow();
        assertEquals(List.of("DISCOUNT"), List.copyOf(update.changedColumns()));
    }

    @Test
    @DisplayName("生成的脚本全参数化，高精度值不进 SQL 文本")
    void scriptIsParameterized() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.NUMERIC);
        List<ColumnInfo> targetColumns = target.describeTable(SCHEMA, TABLE).columns();
        List<DataSyncService.Statement> script = DataSyncService.script(
                target.dialect(), SCHEMA, TABLE, r, targetColumns, r.diffs());

        for (DataSyncService.Statement s : script) {
            assertFalse(s.sql().sql().contains(HUGE), "值不该出现在语句里：" + s.sql().sql());
        }
        assertTrue(script.stream().anyMatch(s -> s.sql().sql().startsWith("INSERT")));
        assertTrue(script.stream().anyMatch(s -> s.sql().sql().startsWith("UPDATE")));
        assertTrue(script.stream().anyMatch(s -> s.sql().sql().startsWith("DELETE")));
    }

    @Test
    @DisplayName("只应用勾选的那些：不勾 DELETE 就不会删")
    void appliesOnlySelected() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.NUMERIC);
        List<DataSyncService.RowDiff> withoutDelete = r.diffs().stream()
                .filter(d -> d.action() != DataSyncService.Action.DELETE)
                .toList();
        List<ColumnInfo> targetColumns = target.describeTable(SCHEMA, TABLE).columns();

        DataSyncService.apply(target, DataSyncService.script(
                target.dialect(), SCHEMA, TABLE, r, targetColumns, withoutDelete));

        QueryResult after = target.execute("SELECT COUNT(*) FROM " + TABLE, 1);
        assertEquals("5", after.rows().get(0).get(0), "插了一行，没删 id=9");

        QueryResult only = target.execute(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE ID = 9", 1);
        assertEquals("1", only.rows().get(0).get(0), "没勾就绝不能删");
    }

    @Test
    @DisplayName("应用之后再比一次，应当没有剩余差异")
    void applyingEverythingConverges() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.TEXT);
        List<ColumnInfo> targetColumns = target.describeTable(SCHEMA, TABLE).columns();
        DataSyncService.apply(target, DataSyncService.script(
                target.dialect(), SCHEMA, TABLE, r, targetColumns, r.diffs()));

        DataSyncService.DiffResult again = compare(DataSyncService.NumericMode.TEXT);
        assertTrue(again.diffs().isEmpty(), "还剩 " + again.diffs().size() + " 处差异");
    }

    @Test
    @DisplayName("高精度值同步过去一位不差")
    void hugeDecimalSurvives() {
        DataSyncService.DiffResult r = compare(DataSyncService.NumericMode.TEXT);
        List<ColumnInfo> targetColumns = target.describeTable(SCHEMA, TABLE).columns();
        DataSyncService.apply(target, DataSyncService.script(
                target.dialect(), SCHEMA, TABLE, r, targetColumns, r.diffs()));

        QueryResult after = target.execute(
                "SELECT DISCOUNT FROM " + TABLE + " WHERE ID = 4", 1);
        assertEquals(HUGE, after.rows().get(0).get(0));
    }
}
