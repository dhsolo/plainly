package com.plainly.driver.redis;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.kv.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新建键、改值、改名、删除。
 *
 * <p>写入这条路上最容易出的两类事故，各有专门的用例守着：
 * <b>改完之后旧数据还留着</b>（集合类不先删就写，是并进去而不是替换），
 * 以及<b>值的格式没解析对却照样动了手</b>（旧值已经删了、新值写不进去）。
 */
@DisplayName("Redis · 写入")
class RedisWriteTest {

    private FakeRedisServer server;
    private DbConnection conn;

    @BeforeEach
    void start() throws IOException {
        server = new FakeRedisServer();
        server.putString(0, "user:1", "张三");
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("name", "张三");
        profile.put("city", "杭州");
        server.put(0, "profile:1", profile);
        server.put(0, "queue:jobs", new ArrayList<>(List.of("job-1", "job-2", "job-3")));
    }

    @AfterEach
    void stop() {
        if (conn != null) {
            conn.close();
        }
        server.close();
    }

    private KeyValueStore open() {
        conn = RedisConnections.open(new ConnectionConfig()
                .setType(DbType.REDIS).setHost("127.0.0.1").setPort(server.port())
                .setDatabase("db0"));
        return (KeyValueStore) conn;
    }

    // ------------------------------------------------------------------ 读

    @Nested
    @DisplayName("读回来编辑")
    class Read {

        @Test
        @DisplayName("读的是完整值，不是预览")
        void readsFullValue() {
            server.putString(0, "long:1", "x".repeat(400));
            KeyValueStore kv = open();

            KeyValueStore.Entry entry = kv.read("db0", "long:1");
            assertNotNull(entry);
            assertEquals("string", entry.type());
            assertEquals(400, entry.value().length(),
                    "拿预览去编辑再保存，等于把用户剩下的数据抹掉");
            assertTrue(entry.persistent());
        }

        @Test
        @DisplayName("集合读成 JSON，和导出、和保存时认的是同一种写法")
        void collectionsReadAsJson() {
            KeyValueStore kv = open();
            assertEquals("{\"name\":\"张三\",\"city\":\"杭州\"}", kv.read("db0", "profile:1").value());
            assertEquals("[\"job-1\",\"job-2\",\"job-3\"]", kv.read("db0", "queue:jobs").value());
        }

        @Test
        @DisplayName("键不存在返回 null，不是抛异常")
        void missingKeyReadsAsNull() {
            assertNull(open().read("db0", "nope:1"));
        }
    }

    // ------------------------------------------------------------------ 写

    @Nested
    @DisplayName("新建与改值")
    class Write {

        @Test
        @DisplayName("新建一个字符串键")
        void createString() {
            KeyValueStore kv = open();
            kv.write("db0", new KeyValueStore.Entry("greeting", "string", "你好", -1));

            assertTrue(kv.exists("db0", "greeting"));
            assertEquals("你好", kv.read("db0", "greeting").value());
        }

        @Test
        @DisplayName("改哈希：删掉的字段真的没了，不是并进去")
        void hashIsReplacedNotMerged() {
            KeyValueStore kv = open();
            // 原来是 name + city，现在只留 name，并改掉它
            kv.write("db0", new KeyValueStore.Entry("profile:1", "hash",
                    "{\"name\":\"李四\"}", -1));

            assertEquals("{\"name\":\"李四\"}", kv.read("db0", "profile:1").value(),
                    "不先删就写，city 会留下来——而界面上显示的是新值，两边对不上");
        }

        @Test
        @DisplayName("改列表：顺序和内容整体替换")
        void listIsReplaced() {
            KeyValueStore kv = open();
            kv.write("db0", new KeyValueStore.Entry("queue:jobs", "list",
                    "[\"only-one\"]", -1));
            assertEquals("[\"only-one\"]", kv.read("db0", "queue:jobs").value());
        }

        @Test
        @DisplayName("换类型：本来是列表，改成哈希")
        void typeCanChange() {
            KeyValueStore kv = open();
            kv.write("db0", new KeyValueStore.Entry("queue:jobs", "hash",
                    "{\"a\":\"1\"}", -1));

            KeyValueStore.Entry after = kv.read("db0", "queue:jobs");
            assertEquals("hash", after.type(),
                    "不先删就直接换类型，服务端回的是 WRONGTYPE");
            assertEquals("{\"a\":\"1\"}", after.value());
        }

        @Test
        @DisplayName("有序集合：导出写的是 [成员, 分数]，发给服务端要对调成「分数 成员」")
        void zsetOrderIsSwappedForZadd() {
            KeyValueStore kv = open();
            kv.write("db0", new KeyValueStore.Entry("rank:1", "zset",
                    "[[\"张三\",\"100\"],[\"李四\",\"90\"]]", -1));

            // 读回来仍然是 [成员, 分数]——两头对调抵消，用户看到的前后一致
            assertEquals("[[\"张三\",\"100\"],[\"李四\",\"90\"]]", kv.read("db0", "rank:1").value());
        }

        @Test
        @DisplayName("设了过期时间就真的设上")
        void ttlIsApplied() {
            KeyValueStore kv = open();
            kv.write("db0", new KeyValueStore.Entry("tmp:1", "string", "x", 600));
            assertEquals(600, kv.read("db0", "tmp:1").ttlSeconds());
            assertFalse(kv.read("db0", "tmp:1").persistent());
        }

        @Test
        @DisplayName("值里带引号、逗号、换行，原样存进去")
        void awkwardValuesSurvive() {
            KeyValueStore kv = open();
            String nasty = "带\"引号\"，带,逗号\n还有换行";
            kv.write("db0", new KeyValueStore.Entry("odd:1", "string", nasty, -1));
            assertEquals(nasty, kv.read("db0", "odd:1").value());

            kv.write("db0", new KeyValueStore.Entry("odd:2", "hash",
                    "{\"k\":\"带\\\"引号\\\"，带,逗号\"}", -1));
            assertEquals("带\"引号\"，带,逗号",
                    RedisJsonReader.flatObject(kv.read("db0", "odd:2").value()).get(1));
        }
    }

    // ------------------------------------------------------------------ 拒绝

    @Nested
    @DisplayName("拦在动手之前")
    class Refusals {

        @Test
        @DisplayName("格式不对时旧值一点没动")
        void badJsonLeavesTheOldValueAlone() {
            KeyValueStore kv = open();
            String before = kv.read("db0", "profile:1").value();

            DbException e = assertThrows(DbException.class, () -> kv.write("db0",
                    new KeyValueStore.Entry("profile:1", "hash", "{\"name\":}", -1)));
            assertTrue(e.getMessage().contains("第"), "报错要说清错在第几个字符：" + e.getMessage());

            assertEquals(before, kv.read("db0", "profile:1").value(),
                    "边解析边发命令的话，这里会停在「旧值已删、新值没写」的半截状态");
        }

        @Test
        @DisplayName("空集合明确拒绝——Redis 里那等于这个键不存在")
        void emptyCollectionIsRefusedWithAnExplanation() {
            KeyValueStore kv = open();
            DbException e = assertThrows(DbException.class, () -> kv.write("db0",
                    new KeyValueStore.Entry("profile:1", "hash", "{}", -1)));
            assertTrue(e.getMessage().contains("删除键"),
                    "得告诉用户真想删的话该点哪儿：" + e.getMessage());
            assertTrue(kv.exists("db0", "profile:1"), "拒绝之后原来的键还得在");
        }

        @Test
        @DisplayName("嵌套的对象说清楚该怎么写，而不是一句「格式错误」")
        void nestedStructuresAreExplained() {
            KeyValueStore kv = open();
            DbException e = assertThrows(DbException.class, () -> kv.write("db0",
                    new KeyValueStore.Entry("x", "hash", "{\"a\":{\"b\":\"c\"}}", -1)));
            assertTrue(e.getMessage().contains("字符串"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 改名与删除

    @Nested
    @DisplayName("改名与删除")
    class RenameAndDelete {

        @Test
        @DisplayName("改名之后旧名字没了、新名字在")
        void renameMovesTheKey() {
            KeyValueStore kv = open();
            kv.rename("db0", "user:1", "member:1");

            assertFalse(kv.exists("db0", "user:1"));
            assertEquals("张三", kv.read("db0", "member:1").value());
        }

        @Test
        @DisplayName("改一个不存在的键，服务端的错原样抛出来")
        void renamingAMissingKeyFails() {
            KeyValueStore kv = open();
            DbException e = assertThrows(DbException.class,
                    () -> kv.rename("db0", "nope:1", "x"));
            assertTrue(e.getMessage().contains("no such key"), e.getMessage());
        }

        @Test
        @DisplayName("删除多个键，返回真正删掉的个数")
        void deleteReportsHowManyWentAway() {
            KeyValueStore kv = open();
            assertEquals(2, kv.delete("db0", List.of("user:1", "profile:1", "nope:1")),
                    "不存在的那个不该算进去");
            assertFalse(kv.exists("db0", "user:1"));
        }

        @Test
        @DisplayName("空列表什么也不做")
        void deletingNothingIsNoop() {
            assertEquals(0, open().delete("db0", List.of()));
        }
    }

    // ------------------------------------------------------------------ 与浏览的衔接

    @Test
    @DisplayName("写完之后翻页立刻能看到——键名快照被作废了")
    void writesInvalidateTheSnapshot() {
        KeyValueStore kv = open();
        DbConnection c = (DbConnection) kv;

        assertEquals(1, keyCount(c, "user"));
        kv.write("db0", new KeyValueStore.Entry("user:2", "string", "李四", -1));
        assertEquals(2, keyCount(c, "user"),
                "快照有 30 秒有效期，写完不作废的话，用户会以为没保存上");

        kv.delete("db0", List.of("user:2"));
        assertEquals(1, keyCount(c, "user"));
    }

    private static int keyCount(DbConnection c, String prefix) {
        QueryResult r = c.executeQuery(new SqlDialect.PreparedSql(
                RedisDialect.scanCommand("db0", prefix, 100, 0), List.of()), List.of(), 100);
        return r.rows().size();
    }
}
