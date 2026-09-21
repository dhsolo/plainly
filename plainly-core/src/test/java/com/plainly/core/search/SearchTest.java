package com.plainly.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnRef;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 搜索。
 *
 * <p>排名部分是纯函数，直接断言顺序；跨表找值的部分要真跑一次 SQL，
 * 因为最容易出错的恰恰是 {@code LIKE} 的转义——那是在数据库里发生的，
 * 在 Java 这边怎么想都想不出来。
 */
@DisplayName("搜索 · 找得到，且找的是对的东西")
class SearchTest {

    private static final String SCHEMA = "PUBLIC";
    private static DbConnection conn;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("search").setType(DbType.H2)
                .setFilePath("mem:plainly_search;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.execute("CREATE TABLE orders ("
                + "id BIGINT PRIMARY KEY, code VARCHAR(64), note VARCHAR(400),"
                + "amount DECIMAL(38,10))", 0);
        conn.execute("INSERT INTO orders VALUES "
                + "(1, 'SO-2024-001', '正常订单', 12345678901234567890.1234567890),"
                + "(2, 'SO-2024-002', '备注里也有 SO-2024-001 这串', 1.50),"
                + "(3, 'DISCOUNT-50%', '打五折', 0.5),"
                + "(4, 'user_id', '下划线要按字面处理', 2),"
                + "(5, 'userXid', '这条不该被 user_id 搜出来', 3)", 0);

        conn.execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, msg VARCHAR(200))", 0);
        conn.execute("INSERT INTO audit_log VALUES (1, '开单 SO-2024-001')", 0);

        // 一张只有数字列的表：应当被跳过，而不是报错
        conn.execute("CREATE TABLE metrics (id BIGINT PRIMARY KEY, v DECIMAL(20,4))", 0);
        conn.execute("INSERT INTO metrics VALUES (1, 1.5000)", 0);
    }

    @AfterAll
    static void close() {
        conn.close();
    }

    // ------------------------------------------------------------------ 排名

    @Test
    @DisplayName("完全同名排在开头匹配前面，表又排在字段前面")
    void ranksExactBeforePrefixAndTablesBeforeColumns() {
        List<TableInfo> tables = List.of(
                new TableInfo(SCHEMA, "order_item", ObjectKind.TABLE, "", -1),
                new TableInfo(SCHEMA, "order", ObjectKind.TABLE, "", -1),
                new TableInfo(SCHEMA, "v_order", ObjectKind.VIEW, "", -1));
        List<ColumnRef> columns = List.of(
                new ColumnRef(SCHEMA, "invoice", "order", "VARCHAR"),
                new ColumnRef(SCHEMA, "invoice", "order_no", "VARCHAR"));

        List<ObjectSearch.Hit> hits = ObjectSearch.match("order", tables, columns, 10);

        assertEquals("order", hits.get(0).name());
        assertEquals(ObjectSearch.Kind.TABLE, hits.get(0).kind(),
                "同样叫 order，表应当排在字段前面");
        assertEquals(ObjectSearch.Kind.COLUMN, hits.get(1).kind());
        // 之后才轮到开头匹配的
        assertEquals("v_order", hits.get(hits.size() - 1).name(),
                "只有中间匹配的排在最后");
    }

    @Test
    @DisplayName("大小写不影响匹配：库里写成什么样是建库那天的习惯")
    void ignoresCase() {
        List<ObjectSearch.Hit> hits = ObjectSearch.match("ORDER",
                List.of(new TableInfo(SCHEMA, "t_order", ObjectKind.TABLE, "", -1)),
                List.of(), 10);
        assertEquals(1, hits.size());
    }

    @Test
    @DisplayName("空片段不返回任何东西——几千张表全铺出来等于没搜")
    void blankReturnsNothing() {
        List<TableInfo> tables = List.of(
                new TableInfo(SCHEMA, "a", ObjectKind.TABLE, "", -1));
        assertTrue(ObjectSearch.match("", tables, List.of(), 10).isEmpty());
        assertTrue(ObjectSearch.match(null, tables, List.of(), 10).isEmpty());
    }

    // ------------------------------------------------------------------ 找值

    @Test
    @DisplayName("跨表找一个值，并说清它在哪张表的哪一列")
    void findsAcrossTables() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA,
                List.of("ORDERS", "AUDIT_LOG"), "SO-2024-001",
                DataSearch.Mode.CONTAINS, 100, true, name -> { }, () -> false);

        assertEquals(2, r.matchedTables(), "两张表里都有");
        // orders 里 code 和 note 各命中一次，audit_log 里 msg 命中一次
        assertEquals(3, r.matches().size());
        assertTrue(r.matches().stream()
                        .anyMatch(m -> m.table().equals("ORDERS") && m.column().equals("CODE")),
                "要指出是 CODE 列命中的");
        assertTrue(r.matches().stream()
                        .anyMatch(m -> m.table().equals("ORDERS") && m.column().equals("NOTE")),
                "同一行里两列都命中，两条都要报");
        assertTrue(r.matches().stream().allMatch(m -> m.locator().contains("ID = ")),
                "有主键就要能指回去");
    }

    @Test
    @DisplayName("百分号按字面搜，不当通配符")
    void percentIsLiteral() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA, List.of("ORDERS"), "50%",
                DataSearch.Mode.CONTAINS, 100, true, name -> { }, () -> false);
        assertEquals(1, r.matches().size(), "只有 DISCOUNT-50% 这一条");
        assertEquals("DISCOUNT-50%", r.matches().get(0).value());
    }

    @Test
    @DisplayName("下划线按字面搜：user_id 不该把 userXid 带出来")
    void underscoreIsLiteral() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA, List.of("ORDERS"), "user_id",
                DataSearch.Mode.EQUALS, 100, true, name -> { }, () -> false);
        assertEquals(1, r.matches().size());
        assertEquals("user_id", r.matches().get(0).value());
    }

    @Test
    @DisplayName("没有文本列的表被跳过，并说明原因，而不是报错")
    void skipsTablesWithoutTextColumns() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA, List.of("METRICS"), "1.5",
                DataSearch.Mode.CONTAINS, 100, true, name -> { }, () -> false);
        assertTrue(r.matches().isEmpty());
        assertEquals("没有文本列", r.tables().get(0).skipped());
    }

    @Test
    @DisplayName("取消之后不再往下搜")
    void stopsWhenCancelled() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA,
                List.of("ORDERS", "AUDIT_LOG"), "SO",
                DataSearch.Mode.CONTAINS, 100, true, name -> { }, () -> true);
        assertTrue(r.cancelled());
        assertTrue(r.tables().isEmpty(), "第一张表都没开始搜");
    }

    @Test
    @DisplayName("达到每表上限时如实报出来——不然用户以为「就这么多」")
    void reportsTruncation() {
        DataSearch.Result r = DataSearch.run(conn, SCHEMA, List.of("ORDERS"), "-",
                DataSearch.Mode.CONTAINS, 1, true, name -> { }, () -> false);
        assertTrue(r.tables().get(0).truncated());
    }

    @Test
    @DisplayName("忽略大小写时，小写的查询词能找到大写的数据")
    void ignoresCaseWhenAsked() {
        DataSearch.Result on = DataSearch.run(conn, SCHEMA, List.of("ORDERS"), "so-2024-002",
                DataSearch.Mode.CONTAINS, 100, true, name -> { }, () -> false);
        assertEquals(1, on.matches().size(), "H2 的 LIKE 区分大小写，得靠 LOWER() 压平");

        // 关掉之后就是数据库自己的行为：H2 上一条都找不到。
        // 这条测试的意义在于证明这个开关真的在起作用，而不是摆设
        DataSearch.Result off = DataSearch.run(conn, SCHEMA, List.of("ORDERS"), "so-2024-002",
                DataSearch.Mode.CONTAINS, 100, false, name -> { }, () -> false);
        assertTrue(off.matches().isEmpty());
    }

    @Test
    @DisplayName("按名字片段找列：敲小写也要找得到大写的列名")
    void searchesColumnsByFragment() {
        // H2 把列名存成大写。getColumns 的名字模式区分大小写，
        // 只按用户敲的原样查，这里一条都返回不了，而且不会报任何错
        List<ColumnRef> refs = conn.searchColumns(SCHEMA, "mou", 50);
        assertTrue(refs.stream().anyMatch(c -> c.column().equalsIgnoreCase("AMOUNT")),
                "AMOUNT 里含 mou，实得：" + refs);
        assertFalse(refs.isEmpty());
    }
}
