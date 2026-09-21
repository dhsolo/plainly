package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import com.plainly.driver.query.FilterSpec;
import com.plainly.driver.query.FilterSpec.Combiner;
import com.plainly.driver.query.FilterSpec.Condition;
import com.plainly.driver.query.FilterSpec.Operator;
import com.plainly.driver.query.FilterSpec.Sort;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 筛选下推。
 *
 * <p>两个关注点。其一是值绝不进 SQL 文本：拼进去的长数值会被驱动按 double 解析，
 * 或被数据库按字面量推断类型，两条路都丢精度——所以断言语句里出现的是占位符，
 * 而不是那串数字。其二是筛完还能筛出对的行：语句里没有值，正确性只能靠真跑一遍验证。
 */
@DisplayName("筛选与排序 · 下推到 H2 执行")
class FilterPushdownTest {

    private static DbConnection conn;
    private static List<ColumnInfo> columns;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "FILTER_PROBE";

    /** 19 位以上，double 存不下：全程按原文传递才留得住。 */
    private static final String HUGE = "12345678901234567890.1234567890";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("filter-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_filter;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));

        conn.executeDdlBatch(List.of(
                "CREATE TABLE " + TABLE + " (ID INT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " STATUS VARCHAR(16), NOTE VARCHAR(64))",
                "INSERT INTO " + TABLE + " VALUES (1, " + HUGE + ", 'PENDING', 'first')",
                "INSERT INTO " + TABLE + " VALUES (2, 10.5, 'PENDING', NULL)",
                "INSERT INTO " + TABLE + " VALUES (3, 99.25, 'DONE', 'third')",
                "INSERT INTO " + TABLE + " VALUES (4, 1000.0000000000, 'DONE', 'fourth')"));

        TableStructure structure = conn.describeTable(SCHEMA, TABLE);
        columns = structure.columns();
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    private static SqlDialect dialect() {
        return conn.dialect();
    }

    private static List<String> valuesOf(FilterSpec spec) {
        return spec.conditions().stream().flatMap(c -> c.values().stream()).toList();
    }

    @Test
    @DisplayName("值走占位符，不进 SQL 文本")
    void valuesNeverEnterSql() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "AMOUNT", Operator.GE, HUGE, null)),
                List.of());
        SqlDialect.PreparedSql sql = dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0);

        assertFalse(sql.sql().contains(HUGE), "长数值不该出现在语句里：" + sql.sql());
        assertTrue(sql.sql().contains("?"), "应当用占位符：" + sql.sql());
        assertEquals(1, sql.boundColumns().size());
        assertEquals("AMOUNT", sql.boundColumns().get(0).name());
    }

    @Test
    @DisplayName("高精度比较筛得出对的行")
    void filtersOnHugeDecimal() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "AMOUNT", Operator.EQ, HUGE, null)),
                List.of());
        QueryResult r = conn.executeQuery(
                dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0), valuesOf(spec), 100);
        assertEquals(1, r.rows().size());
        assertEquals("1", r.rows().get(0).get(r.indexOf("ID")));
        // 取回来的值一位不差
        assertEquals(HUGE, r.rows().get(0).get(r.indexOf("AMOUNT")));
    }

    @Test
    @DisplayName("介于：绑两个值")
    void betweenBindsTwo() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "AMOUNT", Operator.BETWEEN, "10", "100")),
                List.of());
        SqlDialect.PreparedSql sql = dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0);
        assertEquals(2, sql.boundColumns().size());
        QueryResult r = conn.executeQuery(sql, valuesOf(spec), 100);
        assertEquals(2, r.rows().size(), "10.5 和 99.25");
    }

    @Test
    @DisplayName("为 NULL：一个值都不绑")
    void isNullBindsNothing() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "NOTE", Operator.IS_NULL, null, null)),
                List.of());
        SqlDialect.PreparedSql sql = dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0);
        assertTrue(sql.boundColumns().isEmpty());
        assertFalse(sql.sql().contains("?"), "IS NULL 不该有占位符：" + sql.sql());
        QueryResult r = conn.executeQuery(sql, valuesOf(spec), 100);
        assertEquals(1, r.rows().size());
        assertEquals("2", r.rows().get(0).get(r.indexOf("ID")));
    }

    @Test
    @DisplayName("包含：通配符由我们补，不用用户自己记")
    void likeWrapsWildcards() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "NOTE", Operator.LIKE, "ir", null)),
                List.of());
        QueryResult r = conn.executeQuery(
                dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0), valuesOf(spec), 100);
        assertEquals(2, r.rows().size(), "first 和 third");
    }

    @Test
    @DisplayName("多条件按 AND / OR 串起来")
    void combinesConditions() {
        FilterSpec spec = new FilterSpec(List.of(
                new Condition(Combiner.AND, "STATUS", Operator.EQ, "DONE", null),
                new Condition(Combiner.OR, "ID", Operator.EQ, "2", null)),
                List.of());
        SqlDialect.PreparedSql sql = dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0);
        assertTrue(sql.sql().contains(" WHERE "), sql.sql());
        assertTrue(sql.sql().contains(" OR "), sql.sql());
        QueryResult r = conn.executeQuery(sql, valuesOf(spec), 100);
        assertEquals(3, r.rows().size(), "两条 DONE 加上 id=2");
    }

    @Test
    @DisplayName("排序拼进 ORDER BY，方向跟着走")
    void ordersRows() {
        FilterSpec spec = new FilterSpec(List.of(),
                List.of(new Sort("AMOUNT", true)));
        QueryResult r = conn.executeQuery(
                dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0), valuesOf(spec), 100);
        assertEquals(HUGE, r.rows().get(0).get(r.indexOf("AMOUNT")), "降序时最大的在最前");
    }

    @Test
    @DisplayName("计数用同一套条件，否则翻页会翻到空页")
    void countUsesSameFilter() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "STATUS", Operator.EQ, "DONE", null)),
                List.of(new Sort("ID", false)));
        SqlDialect.PreparedSql count = dialect().countRows(SCHEMA, TABLE, spec, columns);
        assertFalse(count.sql().toUpperCase().contains("ORDER BY"), "计数不需要排序：" + count.sql());
        assertFalse(count.sql().toUpperCase().contains("LIMIT"), "计数不该带分页：" + count.sql());
        QueryResult r = conn.executeQuery(count, valuesOf(spec), 1);
        assertEquals("2", r.rows().get(0).get(0));
    }

    @Test
    @DisplayName("认不出的列直接跳过，不生成半截语句")
    void unknownColumnIsSkipped() {
        FilterSpec spec = new FilterSpec(
                List.of(new Condition(Combiner.AND, "NO_SUCH_COLUMN", Operator.EQ, "x", null)),
                List.of());
        SqlDialect.PreparedSql sql = dialect().selectPage(SCHEMA, TABLE, spec, columns, 100, 0);
        assertFalse(sql.sql().contains("WHERE"), sql.sql());
        assertTrue(sql.boundColumns().isEmpty());
    }
}
