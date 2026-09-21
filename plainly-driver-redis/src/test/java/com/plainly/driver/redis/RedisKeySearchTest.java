package com.plainly.driver.redis;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.kv.KeyValueStore;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.query.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键名搜索。
 *
 * <p>三件事各有用例守着：<b>通配符的语义</b>（{@code .} 是普通字符，不是正则的任意字符）、
 * <b>输入怎么变成模式</b>（没写星号时要不要自动加）、
 * <b>两个条件怎么同时生效</b>（键空间的前缀 + 用户搜的那个，而 SCAN 只收一个模式）。
 */
@DisplayName("Redis · 键名搜索")
class RedisKeySearchTest {

    private FakeRedisServer server;
    private DbConnection conn;

    @BeforeEach
    void start() throws IOException {
        server = new FakeRedisServer();
        server.putString(0, "user:1001", "张三");
        server.putString(0, "user:1002", "李四");
        server.putString(0, "user:2001", "王五");
        server.putString(0, "order:1001", "订单");
        server.putString(0, "session:abc", "token");
        server.putString(0, "report[2024]:q1", "季度报表");
        server.putString(0, "a.b", "点号不是通配符");
        server.putString(0, "axb", "如果按正则理解，a.b 会匹配到它");
    }

    @AfterEach
    void stop() {
        if (conn != null) {
            conn.close();
        }
        server.close();
    }

    private DbConnection open() {
        conn = RedisConnections.open(new ConnectionConfig()
                .setType(DbType.REDIS).setHost("127.0.0.1").setPort(server.port())
                .setDatabase("db0"));
        return conn;
    }

    // ------------------------------------------------------------------ 通配符

    @Nested
    @DisplayName("通配符的语义跟着 Redis 走")
    class Glob {

        @Test
        @DisplayName("星号、问号、字符集")
        void basics() {
            assertTrue(RedisGlob.matches("user:*", "user:1001"));
            assertFalse(RedisGlob.matches("user:*", "order:1001"));
            assertTrue(RedisGlob.matches("user:100?", "user:1001"));
            assertFalse(RedisGlob.matches("user:100?", "user:10012"));
            assertTrue(RedisGlob.matches("user:[12]001", "user:2001"));
            assertFalse(RedisGlob.matches("user:[12]001", "user:3001"));
            assertTrue(RedisGlob.matches("user:[^3]001", "user:2001"));
        }

        @Test
        @DisplayName("点号、加号这些正则元字符在这里就是普通字符")
        void regexMetaCharactersAreLiteral() {
            // 这条是本类存在的主要理由：按正则去理解，a.b 会把 axb 也匹配上，
            // 而用户明明搜的是那个带点的键
            assertTrue(RedisGlob.matches("a.b", "a.b"));
            assertFalse(RedisGlob.matches("a.b", "axb"));
            assertTrue(RedisGlob.matches("a+b", "a+b"));
        }

        @Test
        @DisplayName("反斜杠转义：想搜真正的星号")
        void backslashEscapes() {
            assertTrue(RedisGlob.matches("a\\*b", "a*b"));
            assertFalse(RedisGlob.matches("a\\*b", "axxb"));
        }

        @Test
        @DisplayName("方括号是字符集，不是字面量——要搜真带方括号的键得转义")
        void bracketsAreCharClasses() {
            // [2024] 的意思是「2、0、4 里的任意一个字符」，所以它匹配不上
            // 一个真的叫 report[2024]:q1 的键。这跟 Redis 一致
            assertFalse(RedisGlob.matches("report[2024]:*", "report[2024]:q1"));
            // 转义之后才是字面的方括号。escapeGlob 就是干这个的，
            // 键空间的前缀匹配一直走的这条路
            assertTrue(RedisGlob.matches(
                    RedisConnection.escapeGlob("report[2024]") + "*", "report[2024]:q1"));
        }

        @Test
        @DisplayName("没有收尾方括号时，那个方括号当普通字符")
        void unclosedBracketIsLiteral() {
            assertTrue(RedisGlob.matches("a[b", "a[b"));
            assertFalse(RedisGlob.matches("a[b", "ab"));
        }

        @Test
        @DisplayName("空模式和 * 都表示全都要")
        void emptyMeansEverything() {
            assertTrue(RedisGlob.matchesEverything(""));
            assertTrue(RedisGlob.matchesEverything("*"));
            assertTrue(RedisGlob.matchesEverything(null));
            assertFalse(RedisGlob.matchesEverything("a*"));
        }
    }

    // ------------------------------------------------------------------ 输入变模式

    @Nested
    @DisplayName("输入怎么变成模式")
    class SearchText {

        @Test
        @DisplayName("没写通配符就两边加星号——不然搜 1001 一条也搜不到")
        void plainTextBecomesContains() {
            assertEquals("*1001*", RedisGlob.fromSearchText("1001"));
            assertEquals("*user*", RedisGlob.fromSearchText("  user  "));
        }

        @Test
        @DisplayName("自己写了通配符就照原样用")
        void wildcardsArePassedThrough() {
            assertEquals("user:*", RedisGlob.fromSearchText("user:*"));
            assertEquals("user:100?", RedisGlob.fromSearchText("user:100?"));
            assertEquals("[ab]*", RedisGlob.fromSearchText("[ab]*"));
        }

        @Test
        @DisplayName("空输入表示不过滤")
        void emptyMeansNoFilter() {
            assertEquals("", RedisGlob.fromSearchText(""));
            assertEquals("", RedisGlob.fromSearchText("   "));
            assertEquals("", RedisGlob.fromSearchText(null));
        }

        @Test
        @DisplayName("驱动把这套规则暴露给界面，界面不用自己认识 glob")
        void driverExposesTheRule() {
            KeyValueStore kv = (KeyValueStore) open();
            assertEquals("*1001*", kv.searchPattern("1001"));
            assertEquals("user:*", kv.searchPattern("user:*"));
        }
    }

    // ------------------------------------------------------------------ 搜索

    @Nested
    @DisplayName("搜出来的东西对不对")
    class Search {

        @Test
        @DisplayName("树上有一个「全部键」节点——搜索常常不知道前缀")
        void allKeysNodeExists() {
            List<String> names = open().listTables("db0").stream().map(TableInfo::name).toList();
            assertEquals(RedisConnection.ALL_KEYS, names.get(0), "它得排在最前面");
            assertTrue(names.contains("user"));
        }

        @Test
        @DisplayName("在「全部键」里搜，跨命名空间都能搜到")
        void searchAcrossTheWholeDatabase() {
            DbConnection c = open();
            List<String> keys = search(c, RedisConnection.ALL_KEYS, "*1001*");
            assertEquals(List.of("order:1001", "user:1001"), sorted(keys),
                    "1001 在两个命名空间里各有一个，都该搜到");
        }

        @Test
        @DisplayName("在某个键空间里搜，结果不会溢出到别的命名空间")
        void searchInsideOneKeyspaceStaysThere() {
            DbConnection c = open();
            List<String> keys = search(c, "user", "*1001*");
            assertEquals(List.of("user:1001"), keys,
                    "SCAN 只收一个模式，前缀那个条件得在本地兜住，"
                    + "否则 order:1001 会跟着出来");
        }

        @Test
        @DisplayName("计数跟着搜索走，不是这个键空间的总数")
        void countFollowsTheSearch() {
            DbConnection c = open();
            assertEquals("3", count(c, "user", ""), "不搜的时候是全部");
            assertEquals("2", count(c, "user", "*100*"), "搜出来两个就该是两个");
            // 两个数不一致的话，分页器会让人翻到空页
            assertEquals(search(c, "user", "*100*").size(),
                    Integer.parseInt(count(c, "user", "*100*")));
        }

        @Test
        @DisplayName("搜不到就是空的，不是报错")
        void noMatchIsJustEmpty() {
            assertTrue(search(open(), RedisConnection.ALL_KEYS, "*不存在的东西*").isEmpty());
        }

        @Test
        @DisplayName("换个搜索词就重新扫，不会拿上一次的结果")
        void changingThePatternRescans() {
            DbConnection c = open();
            assertEquals(2, search(c, RedisConnection.ALL_KEYS, "*1001*").size());
            assertEquals(1, search(c, RedisConnection.ALL_KEYS, "*2001*").size(),
                    "快照缓存的键里没带上模式的话，这里会拿回上一次那两条");
        }

        @Test
        @DisplayName("搜索词里的点号不会变成正则的任意字符")
        void dotInSearchIsLiteral() {
            DbConnection c = open();
            // 输入 a.b 没有通配符，会变成 *a.b*，匹配的是「含有 a.b 的键」
            List<String> keys = search(c, RedisConnection.ALL_KEYS,
                    ((KeyValueStore) c).searchPattern("a.b"));
            assertEquals(List.of("a.b"), keys, "axb 不该出现在结果里");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static List<String> search(DbConnection c, String keyspace, String pattern) {
        QueryResult r = c.executeQuery(new SqlDialect.PreparedSql(
                RedisDialect.scanCommand("db0", keyspace, 200, 0, pattern), List.of()),
                List.of(), 200);
        List<String> keys = new ArrayList<>();
        r.rows().forEach(row -> keys.add(row.get(0)));
        return keys;
    }

    private static String count(DbConnection c, String keyspace, String pattern) {
        return c.executeQuery(new SqlDialect.PreparedSql(
                RedisDialect.countCommand("db0", keyspace, pattern), List.of()), List.of(), 1)
                .rows().get(0).get(0);
    }

    private static List<String> sorted(List<String> keys) {
        List<String> copy = new ArrayList<>(keys);
        copy.sort(String::compareTo);
        return copy;
    }

    @Test
    @DisplayName("方言从筛选条件里认出键名模式，别的列一律忽略")
    void dialectReadsThePatternFromTheFilter() {
        SqlDialect dialect = open().dialect();
        FilterSpec filter = new FilterSpec(List.of(
                new FilterSpec.Condition(FilterSpec.Combiner.AND, "值预览",
                        FilterSpec.Operator.LIKE, "忽略我", null),
                new FilterSpec.Condition(FilterSpec.Combiner.AND, KeyValueStore.KEY_COLUMN,
                        FilterSpec.Operator.EQ, "user:*", null)),
                List.of());

        String sql = dialect.selectPage("db0", "user", filter, List.of(), 100, 0).sql();
        assertTrue(sql.endsWith("user:*"), sql);
        assertFalse(sql.contains("忽略我"),
                "值上的条件 Redis 服务端不认，认一半比不认更危险：" + sql);
    }
}
