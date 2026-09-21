package com.plainly.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 查询构建器生成的 SQL。
 *
 * <p>光看字符串对不对没用——拼错了照样像 SQL。所以每条都拿到 H2 上真跑一遍，
 * 并且对高精度列做聚合，确认结果依旧按原文取回、不经 double。
 */
@DisplayName("查询构建器 · 生成的 SQL")
class QueryPlanTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("qb-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_qb;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.executeDdlBatch(List.of(
                "CREATE TABLE CUSTOMERS (ID BIGINT PRIMARY KEY, REGION VARCHAR(8))",
                "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, USER_ID BIGINT,"
                        + " AMOUNT DECIMAL(38,10), CURRENCY CHAR(3))",
                "INSERT INTO CUSTOMERS VALUES (1, 'CN-SH'), (2, 'CN-BJ')",
                "INSERT INTO ORDERS VALUES (10, 1, 12345678901234567890.1234567890, 'CNY')",
                "INSERT INTO ORDERS VALUES (11, 1, 0.0000000001, 'CNY')",
                "INSERT INTO ORDERS VALUES (12, 2, 5.5, 'USD')"));
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

    private static final List<QueryPlan.Source> SOURCES = List.of(
            new QueryPlan.Source("ORDERS", "o"),
            new QueryPlan.Source("CUSTOMERS", "c"));

    private static final List<QueryPlan.Join> JOINS = List.of(
            new QueryPlan.Join(QueryPlan.JoinType.INNER, "o", "USER_ID", "c", "ID"));

    @Test
    @DisplayName("最简单的一张表一列")
    void singleTable() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA,
                List.of(new QueryPlan.Source("ORDERS", "o")), List.of(),
                List.of(new QueryPlan.Field("o", "ID", null, QueryPlan.Aggregate.NONE,
                        false, true, null, null)));
        QueryResult r = conn.execute(sql, 10);
        assertEquals(3, r.rows().size(), sql);
    }

    @Test
    @DisplayName("连接跑得通，条件写进 WHERE")
    void joinAndWhere() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA, SOURCES, JOINS, List.of(
                new QueryPlan.Field("o", "ID", null, QueryPlan.Aggregate.NONE,
                        false, true, null, null),
                new QueryPlan.Field("c", "REGION", null, QueryPlan.Aggregate.NONE,
                        false, true, null, "= 'CN-SH'")));
        assertTrue(sql.contains("INNER JOIN"), sql);
        assertTrue(sql.contains("WHERE"), sql);
        QueryResult r = conn.execute(sql, 10);
        assertEquals(2, r.rows().size(), sql);
    }

    @Test
    @DisplayName("有聚合就自动补 GROUP BY——忘写它是手写聚合最常见的错")
    void addsGroupBy() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA, SOURCES, JOINS, List.of(
                new QueryPlan.Field("o", "CURRENCY", null, QueryPlan.Aggregate.NONE,
                        true, true, "ASC", null),
                new QueryPlan.Field("o", "AMOUNT", "GMV", QueryPlan.Aggregate.SUM,
                        false, true, "DESC", null)));
        assertTrue(sql.contains("GROUP BY o.CURRENCY"), sql);
        QueryResult r = conn.execute(sql, 10);
        assertEquals(2, r.rows().size(), "CNY 和 USD 两组");
    }

    @Test
    @DisplayName("对高精度列求和，结果依旧一位不差")
    void aggregateKeepsPrecision() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA,
                List.of(new QueryPlan.Source("ORDERS", "o")), List.of(), List.of(
                        new QueryPlan.Field("o", "CURRENCY", null, QueryPlan.Aggregate.NONE,
                                true, true, null, null),
                        new QueryPlan.Field("o", "AMOUNT", "GMV", QueryPlan.Aggregate.SUM,
                                false, true, null, "= 12345678901234567890.1234567890")));
        QueryResult r = conn.execute(sql, 10);
        assertEquals("12345678901234567890.1234567890",
                r.rows().get(0).get(r.indexOf("GMV")),
                "SUM 之后仍按原文取回，不该经过 double");
    }

    @Test
    @DisplayName("排序用别名，别名没有时用表达式")
    void ordersByAlias() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA,
                List.of(new QueryPlan.Source("ORDERS", "o")), List.of(), List.of(
                        new QueryPlan.Field("o", "AMOUNT", "GMV", QueryPlan.Aggregate.NONE,
                                false, true, "DESC", null)));
        assertTrue(sql.contains("ORDER BY \"GMV\" DESC"), sql);
        conn.execute(sql, 10);
    }

    @Test
    @DisplayName("不输出的字段只参与条件，不进 SELECT")
    void conditionOnlyField() {
        String sql = QueryPlan.toSql(dialect(), SCHEMA,
                List.of(new QueryPlan.Source("ORDERS", "o")), List.of(), List.of(
                        new QueryPlan.Field("o", "ID", null, QueryPlan.Aggregate.NONE,
                                false, true, null, null),
                        new QueryPlan.Field("o", "CURRENCY", null, QueryPlan.Aggregate.NONE,
                                false, false, null, "= 'USD'")));
        assertFalse(sql.contains("o.CURRENCY,"), "不输出就不该出现在 SELECT 里：" + sql);
        assertTrue(sql.contains("WHERE o.CURRENCY = 'USD'"), sql);
        assertEquals(1, conn.execute(sql, 10).rows().size());
    }

    @Test
    @DisplayName("没选表、没选输出时给的是提示，不是半截 SQL")
    void emptyPlansAreExplained() {
        assertTrue(QueryPlan.toSql(dialect(), SCHEMA, List.of(), List.of(), List.of())
                .startsWith("--"));
        assertTrue(QueryPlan.toSql(dialect(), SCHEMA,
                List.of(new QueryPlan.Source("ORDERS", "o")), List.of(), List.of())
                .startsWith("--"));
    }

    @Test
    @DisplayName("别名冲突时自动错开")
    void aliasesDoNotCollide() {
        Set<String> taken = new java.util.LinkedHashSet<>();
        String a = QueryPlan.aliasFor("orders", taken);
        taken.add(a);
        String b = QueryPlan.aliasFor("order_items", taken);
        assertEquals("o", a);
        assertEquals("o2", b);
    }
}
