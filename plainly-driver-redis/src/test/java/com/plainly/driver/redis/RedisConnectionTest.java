package com.plainly.driver.redis;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 驱动：协议、浏览、以及做不到的事怎么拒绝。
 *
 * <p>测试跑在 {@link FakeRedisServer} 上——一台在本进程里、按字节说 RESP 的假服务端。
 * 所以走的是真 socket、真编解码，不是 mock。它<b>证明不了</b>和真 Redis 行为一致，
 * 但客户端这一侧写错任何一位都会在这里露出来。
 */
@DisplayName("Redis · 驱动")
class RedisConnectionTest {

    private FakeRedisServer server;
    private DbConnection conn;

    @BeforeEach
    void start() throws IOException {
        server = new FakeRedisServer();
        seed();
    }

    @AfterEach
    void stop() {
        if (conn != null) {
            conn.close();
        }
        server.close();
    }

    private void seed() {
        server.putString(0, "user:1", "张三");
        server.putString(0, "user:2", "李四");
        server.putString(0, "user:3", "王五");
        server.putString(0, "session:abc", "token-abc");
        server.putString(0, "counter", "9223372036854775807");

        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("name", "张三");
        profile.put("city", "杭州");
        server.put(0, "profile:1", profile);

        server.put(0, "queue:jobs", new ArrayList<>(List.of("job-1", "job-2", "job-3")));
        server.put(0, "tags:hot", new LinkedHashSet<>(List.of("红", "黄")));

        server.putString(1, "other:1", "另一个库里的值");
    }

    private DbConnection open() {
        conn = RedisConnections.open(new ConnectionConfig()
                .setName("fake-redis")
                .setType(DbType.REDIS)
                .setHost("127.0.0.1")
                .setPort(server.port())
                .setDatabase("db0"));
        return conn;
    }

    // ------------------------------------------------------------------ 连接

    @Nested
    @DisplayName("连接")
    class Connect {

        @Test
        @DisplayName("连上之后能报出服务端版本")
        void reportsServerVersion() {
            assertEquals("Redis 7.2.4", open().serverVersion());
        }

        @Test
        @DisplayName("没设口令时不发 AUTH——发了服务端会报错")
        void noAuthWhenNoPassword() {
            open();
            assertFalse(server.received().contains("AUTH"),
                    "没有口令还发 AUTH，服务端回的是 ERR，连接会白白失败");
            assertTrue(server.received().contains("PING"),
                    "至少要 PING 一下确认对面确实是 Redis");
        }

        @Test
        @DisplayName("设了口令就先认证")
        void authenticatesWhenPasswordGiven() {
            server.requirePassword("s3cret");
            conn = RedisConnections.open(new ConnectionConfig()
                    .setType(DbType.REDIS).setHost("127.0.0.1").setPort(server.port())
                    .setPassword("s3cret"));
            assertTrue(server.received().contains("AUTH"));
            assertEquals("Redis 7.2.4", conn.serverVersion());
        }

        @Test
        @DisplayName("口令不对时报错说得清楚")
        void wrongPasswordFailsLoudly() {
            server.requirePassword("s3cret");
            DbException e = assertThrows(DbException.class, () ->
                    RedisConnections.open(new ConnectionConfig()
                            .setType(DbType.REDIS).setHost("127.0.0.1").setPort(server.port())
                            .setPassword("nope")));
            assertTrue(e.getMessage().contains("WRONGPASS"), e.getMessage());
        }

        @Test
        @DisplayName("库编号「0」和「db0」都认，别的报错")
        void databaseIndexIsForgivingButNotSilent() {
            assertEquals("db0", RedisConnections.normalizeDb("0"));
            assertEquals("db3", RedisConnections.normalizeDb("db3"));
            assertEquals("db0", RedisConnections.normalizeDb(null));
            assertThrows(DbException.class, () -> RedisConnections.normalizeDb("默认"));
        }
    }

    // ------------------------------------------------------------------ 浏览

    @Nested
    @DisplayName("浏览")
    class Browse {

        @Test
        @DisplayName("库列表按服务端的 databases 配置来")
        void listsDatabases() {
            List<SchemaInfo> schemas = open().listSchemas();
            assertEquals(16, schemas.size());
            assertEquals("db0", schemas.get(0).name());
            assertTrue(schemas.get(0).isDefault());
        }

        @Test
        @DisplayName("CONFIG 被禁掉时退回按 INFO keyspace 推断，而不是报错")
        void survivesDisabledConfig() {
            server.disableConfig();
            List<SchemaInfo> schemas = open().listSchemas();
            assertFalse(schemas.isEmpty(), "托管 Redis 常禁 CONFIG，这时候不该整棵树打不开");
            assertTrue(schemas.stream().anyMatch(s -> s.name().equals("db0")));
        }

        @Test
        @DisplayName("键按第一个分隔符分组，外加一个「全部键」")
        void groupsKeysByPrefix() {
            List<String> names = open().listTables("db0").stream().map(TableInfo::name).toList();
            assertEquals(RedisConnection.ALL_KEYS, names.get(0),
                    "「全部键」排最前：搜索多半不知道前缀，得有个整库的入口");
            assertEquals(List.of("counter", "profile", "queue", "session", "tags", "user"),
                    names.stream().skip(1).sorted().toList());
        }

        @Test
        @DisplayName("分组里带上键数，空库和有货的库在树上一眼能分开")
        void groupsCarryKeyCounts() {
            TableInfo users = open().listTables("db0").stream()
                    .filter(t -> t.name().equals("user")).findFirst().orElseThrow();
            assertEquals(3, users.rowEstimate());
            assertTrue(users.comment().contains("3 个键"), users.comment());
        }

        @Test
        @DisplayName("前缀切分：冒号优先，其次下划线，都没有就自成一组")
        void prefixRules() {
            assertEquals("user", RedisConnection.prefixOf("user:1"));
            assertEquals("job", RedisConnection.prefixOf("job_42"));
            assertEquals("counter", RedisConnection.prefixOf("counter"));
            // 冒号在前就按冒号切，哪怕下划线更靠前也不行？——不，取更靠前的那个是错的：
            // 习惯上冒号才是命名空间分隔符，user_name:1 的组是 user_name
            assertEquals("user_name", RedisConnection.prefixOf("user_name:1"));
        }

        @Test
        @DisplayName("一页键带出类型、TTL、大小和值预览")
        void pageCarriesTypeTtlSizeAndPreview() {
            DbConnection c = open();
            QueryResult r = page(c, "db0", "user", 10, 0);

            assertEquals(List.of("键", "类型", "TTL", "大小", "值预览"),
                    r.columns().stream().map(m -> m.label()).toList());
            assertEquals(3, r.rows().size());

            int row = indexOfKey(r, "user:1");
            assertEquals("string", r.rows().get(row).get(1));
            assertEquals("永不过期", r.rows().get(row).get(2));
            assertEquals("6 字节", r.rows().get(row).get(3), "「张三」是 6 个 UTF-8 字节");
            assertEquals("张三", r.rows().get(row).get(4));
        }

        @Test
        @DisplayName("TTL 的 -1 / -2 翻译成人话")
        void ttlIsTranslated() {
            assertEquals("永不过期", RedisConnection.describeTtl("-1"));
            assertEquals("已过期", RedisConnection.describeTtl("-2"));
            assertEquals("600 秒", RedisConnection.describeTtl("600"));
        }

        @Test
        @DisplayName("哈希、列表、集合各按自己的方式预览")
        void collectionsHaveTypedPreviews() {
            DbConnection c = open();

            QueryResult hash = page(c, "db0", "profile", 10, 0);
            assertEquals("hash", hash.rows().get(0).get(1));
            assertEquals("2 个字段", hash.rows().get(0).get(3));
            assertEquals("name = 张三, city = 杭州", hash.rows().get(0).get(4));

            QueryResult list = page(c, "db0", "queue", 10, 0);
            assertEquals("list", list.rows().get(0).get(1));
            assertEquals("3 个元素", list.rows().get(0).get(3));
            assertEquals("job-1, job-2, job-3", list.rows().get(0).get(4));

            QueryResult set = page(c, "db0", "tags", 10, 0);
            assertEquals("set", set.rows().get(0).get(1));
            assertEquals("2 个成员", set.rows().get(0).get(3));
            assertEquals("红, 黄", set.rows().get(0).get(4));
        }

        @Test
        @DisplayName("翻页在同一份快照上切片，第二页不重复第一页的键")
        void pagingSlicesOneSnapshot() {
            DbConnection c = open();
            QueryResult first = page(c, "db0", "user", 2, 0);
            QueryResult second = page(c, "db0", "user", 2, 2);

            assertEquals(2, first.rows().size());
            assertEquals(1, second.rows().size());
            assertTrue(first.truncated(), "还有下一页时应当标成截断");

            List<String> keys = new ArrayList<>();
            first.rows().forEach(r -> keys.add(r.get(0)));
            second.rows().forEach(r -> keys.add(r.get(0)));
            assertEquals(3, keys.size());
            assertEquals(3, keys.stream().distinct().count(), "翻页翻出了重复的键：" + keys);
        }

        @Test
        @DisplayName("计数给的是这一组键的总数")
        void countsKeysInGroup() {
            DbConnection c = open();
            QueryResult r = c.executeQuery(
                    new SqlDialect.PreparedSql(RedisDialect.countCommand("db0", "user"), List.of()),
                    List.of(), 1);
            assertEquals("3", r.rows().get(0).get(0));
        }

        @Test
        @DisplayName("切库之后看到的是另一个库的键")
        void selectSwitchesDatabase() {
            DbConnection c = open();
            List<String> names = c.listTables("db1").stream().map(TableInfo::name).toList();
            assertEquals(List.of(RedisConnection.ALL_KEYS, "other"), names);
        }

        @Test
        @DisplayName("前缀里的通配符要转义，否则那一组键一个也扫不出来")
        void globCharactersInPrefixAreEscaped() {
            server.putString(0, "report[2024]:a", "x");
            server.putString(0, "report[2024]:b", "y");
            DbConnection c = open();

            QueryResult r = page(c, "db0", "report[2024]", 10, 0);
            assertEquals(2, r.rows().size(),
                    "没转义的话 [2024] 会被当成字符集匹配，结果是一个都扫不到");
            assertEquals("report[2024]*", "report[2024]*",
                    RedisConnection.escapeGlob("report[2024]") + "*");
        }
    }

    // ------------------------------------------------------------------ 精度

    @Nested
    @DisplayName("值的保真")
    class Fidelity {

        @Test
        @DisplayName("64 位整数按文本原样带回，没有经过 double")
        void bigIntegerSurvives() {
            DbConnection c = open();
            QueryResult r = page(c, "db0", "counter", 10, 0);
            assertEquals("9223372036854775807", r.rows().get(0).get(4),
                    "Redis 的值就是字节串，任何数值转换都是多余且有损的");
        }

        @Test
        @DisplayName("整数回复也按文本保留")
        void integerRepliesStayText() {
            DbConnection c = open();
            QueryResult r = c.execute("DBSIZE", 10);
            assertEquals(String.valueOf(server.db(0).size()), r.rows().get(0).get(0));
        }

        @Test
        @DisplayName("中文长值的预览不会被当成二进制——刀切在了字中间而已")
        void chinesePreviewIsNotMistakenForBinary() {
            // 一个汉字三字节，200 字节这一刀必然落在某个字中间
            server.putString(0, "cn:1", "很长的一段中文".repeat(40));
            DbConnection c = open();

            String preview = page(c, "db0", "cn", 10, 0).rows().get(0).get(4);
            assertFalse(preview.startsWith("[二进制"),
                    "一段好好的中文在网格里显示成二进制摘要，看着像数据坏了：" + preview);
            assertTrue(preview.startsWith("很长的一段中文"), preview);
            assertTrue(preview.endsWith("…"),
                    "后面还有内容就得有省略号，否则和正好 200 字节的值看着一样");
        }

        @Test
        @DisplayName("不是合法 UTF-8 的值显示成二进制摘要，而不是一串 U+FFFD")
        void binaryValuesAreNotMangled() {
            byte[] garbage = {(byte) 0xFF, (byte) 0xFE, 0x01, 0x02};
            server.put(0, "blob:1", garbage);

            DbConnection c = open();
            QueryResult r = page(c, "db0", "blob", 10, 0);
            String preview = r.rows().get(0).get(4);
            assertTrue(preview.startsWith("[二进制"), preview);
            assertTrue(preview.contains("FFFE0102"), preview);
            assertFalse(preview.contains("�"),
                    "替换字符是不可逆的，显示出来就再也看不出原值：" + preview);
        }

        @Test
        @DisplayName("键不存在读成 null，和空字符串分得开")
        void nilIsNotEmptyString() {
            server.putString(0, "empty:1", "");
            DbConnection c = open();

            assertEquals("", c.execute("GET empty:1", 10).rows().get(0).get(0));
            assertNull(c.execute("GET missing:1", 10).rows().get(0).get(0));
        }
    }

    // ------------------------------------------------------------------ 命令台

    @Nested
    @DisplayName("命令台")
    class Console {

        @Test
        @DisplayName("SQL 编辑器在 Redis 上就是发命令")
        void editorSendsCommands() {
            DbConnection c = open();
            assertEquals("PONG", c.execute("PING", 10).rows().get(0).get(0));
            assertEquals("张三", c.execute("GET user:1", 10).rows().get(0).get(0));
        }

        @Test
        @DisplayName("带空格的参数要用引号括起来")
        void quotedArgumentsKeepTheirSpaces() {
            assertArrayEquals2(new String[] {"SET", "msg", "hello world"},
                    RedisConnection.tokenize("SET msg \"hello world\""));
            assertArrayEquals2(new String[] {"GET", "user:1"},
                    RedisConnection.tokenize("  GET   user:1  "));
            assertArrayEquals2(new String[] {"SET", "k", ""},
                    RedisConnection.tokenize("SET k \"\""));
        }

        @Test
        @DisplayName("数组回复摆成两列：序号和值")
        void arrayRepliesBecomeRows() {
            DbConnection c = open();
            QueryResult r = c.execute("LRANGE queue:jobs 0 -1", 10);
            assertEquals(List.of("序号", "值"), r.columns().stream().map(m -> m.label()).toList());
            assertEquals("job-1", r.rows().get(0).get(1));
        }

        @Test
        @DisplayName("服务端回的错误照实抛出来，不当成数据显示")
        void serverErrorsAreRaised() {
            DbConnection c = open();
            DbException e = assertThrows(DbException.class, () -> c.execute("NOSUCHCMD", 10));
            assertTrue(e.getMessage().contains("unknown command"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 能力边界

    @Nested
    @DisplayName("做不到的事怎么说")
    class Boundaries {

        @Test
        @DisplayName("网格只读，而且给的理由是 Redis 自己的理由")
        void gridIsReadOnlyWithARelevantReason() {
            DbConnection c = open();
            QueryResult r = page(c, "db0", "user", 10, 0);

            assertFalse(r.isEditable());
            String reason = r.readOnlyReason();
            assertNotNull(reason);
            // 不能编辑的理由是「这一列是预览」，不是「不能写」——
            // 写是支持的，入口在工具条上，理由里得把人指过去
            assertTrue(reason.contains("预览"), "得说清为什么不让在格子里改：" + reason);
            assertTrue(reason.contains("改值"), "得告诉用户该点哪儿：" + reason);
            assertFalse(reason.contains("主键"),
                    "「没有主键」是关系库的说法，套在 Redis 上会把人引到错的方向：" + reason);
        }

        @Test
        @DisplayName("没有表结构这回事，界面据此少显示五个页签")
        void hasNoTableStructure() {
            assertFalse(open().dialect().hasTableStructure());
        }

        @Test
        @DisplayName("改结构一律拒绝，并说清为什么")
        void structureChangesAreRefused() {
            SqlDialect dialect = open().dialect();
            TableChange change = new TableChange.AddColumn(ColumnDraft.added("x"), null);

            assertFalse(dialect.supports(change));
            assertTrue(dialect.unsupportedReason(change).contains("键空间"),
                    dialect.unsupportedReason(change));
            assertThrows(DbException.class, () -> dialect.ddlFor("db0", "user", change));
        }

        @Test
        @DisplayName("「清空键空间」明确不做——它和 FLUSHDB 差着一整个库")
        void truncateIsRefusedBecauseItWouldMeanFlushdb() {
            SqlDialect dialect = open().dialect();
            DbException e = assertThrows(DbException.class,
                    () -> dialect.truncateTableDdl("db0", "user"));
            assertTrue(e.getMessage().contains("FLUSHDB"), e.getMessage());
        }

        @Test
        @DisplayName("建库、触发器、索引都有各自的解释，不是一句「不支持」")
        void refusalsAreSpecific() {
            SqlDialect dialect = open().dialect();
            assertTrue(dialect.schemaCreationUnsupportedReason().contains("databases"));
            assertTrue(dialect.triggerUnsupportedReason().contains("notify-keyspace-events"));
            assertNull(dialect.createTriggerTemplate("db0", "user", "t"));
        }

        @Test
        @DisplayName("不拿 MULTI/EXEC 冒充事务")
        void multiIsNotPresentedAsATransaction() {
            DbConnection c = open();
            DbException e = assertThrows(DbException.class,
                    () -> c.inTransaction(List.of("SET a 1")));
            assertTrue(e.getMessage().contains("ROLLBACK"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 导出

    @Nested
    @DisplayName("导出走的那条路")
    class Export {

        /**
         * 导出「全部键」用的是 {@code RowSource.ofTable}，它调的是<b>取字符串的</b>
         * 那个 selectPage，再把结果丢给 {@code conn.execute(String)}。
         * 而 execute 在 Redis 上是「把这段文本当命令发出去」——
         * 于是本工具的私有指令被原样发给了服务端。
         */
        @Test
        @DisplayName("execute 认得自己的私有指令，不会把它当命令发给服务端")
        void executeRoutesTheInternalCommand() {
            DbConnection c = open();
            String pseudo = c.dialect().selectPage("db0", "user", null, 10, 0);
            QueryResult r = c.execute(pseudo, 10);
            assertEquals(3, r.rows().size(), "全部键导出取不到数据：" + pseudo);
        }

        @Test
        @DisplayName("导出取的是完整值，不是网格里那个预览")
        void exportReadsFullValues() {
            server.putString(0, "long:1", "x".repeat(500));
            DbConnection c = open();

            QueryResult grid = page(c, "db0", "long", 10, 0);
            // 200 个字节，外加一个表示「后面还有」的省略号
            assertEquals("x".repeat(200) + "…", grid.rows().get(0).get(4),
                    "网格里应当只取前 200 字节，并标出被截断");
            assertEquals("值预览", grid.columns().get(4).label());

            QueryResult export = c.execute(c.dialect().selectPage("db0", "long", null, 10, 0), 10);
            assertEquals(500, export.rows().get(0).get(4).length(),
                    "导出把预览当成值写进文件，是一份看着完整、其实被截断的数据");
            assertEquals("值", export.columns().get(4).label(),
                    "两种模式的列名要不一样，文件头一眼能看出取的是哪种");
        }

        @Test
        @DisplayName("集合类的完整值导成 JSON，不是给人看的那行摘要")
        void collectionsExportAsJson() {
            DbConnection c = open();
            QueryResult r = c.execute(c.dialect().selectPage("db0", "profile", null, 10, 0), 10);
            assertEquals("{\"name\":\"张三\",\"city\":\"杭州\"}", r.rows().get(0).get(4),
                    "「name = 张三, city = 杭州」是给眼睛看的，机器读不回去");

            QueryResult list = c.execute(c.dialect().selectPage("db0", "queue", null, 10, 0), 10);
            assertEquals("[\"job-1\",\"job-2\",\"job-3\"]", list.rows().get(0).get(4));
        }

        @Test
        @DisplayName("元素太多的集合整个不导，并在那一格说明——不截一半")
        void hugeCollectionsAreRefusedNotTruncated() {
            server.fakeSize("profile:1", 80_000);
            DbConnection c = open();

            QueryResult r = c.execute(c.dialect().selectPage("db0", "profile", null, 10, 0), 10);
            String value = r.rows().get(0).get(4);
            assertTrue(value.startsWith("[元素过多"), value);
            assertTrue(value.contains("80000"), "得说清到底多少个：" + value);
            // 关键在这儿：不能是一份看着正常、其实只有前几万个元素的 JSON
            assertFalse(value.startsWith("{"),
                    "截一半写进文件，拿到文件的人是看不出来的：" + value);
        }
    }

    // ------------------------------------------------------------------ 并发

    @Nested
    @DisplayName("同一条连接被多个线程用")
    class Concurrency {

        /**
         * 这条测试对应一个真出过的故障。
         *
         * <p>打开一张表时，界面把「取这一页」和「数一共多少行」<b>两个任务一起</b>
         * 丢进线程池（{@code TableTabPane#loadPage} / {@code loadCount}），
         * 用的是同一条连接。JDBC 驱动内部自带同步，所以关系库上一直没事；
         * RESP 是裸 socket，两个线程交叉写命令、交叉读回复，
         * 拿到的就是别人那条命令的回复。
         *
         * <p>症状是<b>时好时坏</b>：有时报「读取失败」，有时更糟——
         * 返回一份看着正常、其实张冠李戴的数据。所以断言不能只看「没抛异常」，
         * 还要逐条核对内容对不对。
         */
        @Test
        @DisplayName("并发取数与计数，结果不会串台")
        void concurrentReadsDoNotInterleave() throws Exception {
            DbConnection c = open();
            int threads = 8;
            int rounds = 25;
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            List<Throwable> failures =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            List<String> wrong =
                    java.util.Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            switch (id % 4) {
                                case 0: {
                                    QueryResult r = page(c, "db0", "user", 10, 0);
                                    if (r.rows().size() != 3) {
                                        wrong.add("user 这一组应当 3 个键，实得 "
                                                + r.rows().size());
                                    }
                                    break;
                                }
                                case 1: {
                                    QueryResult r = c.executeQuery(new SqlDialect.PreparedSql(
                                            RedisDialect.countCommand("db0", "user"), List.of()),
                                            List.of(), 1);
                                    if (!"3".equals(r.rows().get(0).get(0))) {
                                        wrong.add("计数应当是 3，实得 " + r.rows().get(0).get(0));
                                    }
                                    break;
                                }
                                case 2: {
                                    QueryResult r = c.execute("GET user:1", 10);
                                    if (!"张三".equals(r.rows().get(0).get(0))) {
                                        wrong.add("GET user:1 应当是「张三」，实得 "
                                                + r.rows().get(0).get(0));
                                    }
                                    break;
                                }
                                default:
                                    c.listTables("db0");
                            }
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS),
                    "并发跑不完，八成是死锁了");

            assertEquals(List.of(), wrong.stream().distinct().toList(),
                    "回复串台了——某条命令拿到了别人的回复");
            assertTrue(failures.isEmpty(),
                    "并发下抛异常：" + (failures.isEmpty() ? "" : failures.get(0)));
        }

        @Test
        @DisplayName("一个线程在读，另一个线程能把连接关掉——关闭不参与那把锁")
        void closeIsNotBlockedByTheLock() throws Exception {
            DbConnection c = open();
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        c.listTables("db0");
                    }
                } catch (RuntimeException ignored) {
                    // 关掉之后读失败是预期的
                }
            });
            reader.start();
            Thread.sleep(20);
            c.close();
            reader.join(10_000);
            assertFalse(reader.isAlive(), "关掉连接之后读线程还卡着，说明 close 被锁挡住了");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static QueryResult page(DbConnection c, String db, String prefix,
                                    int limit, int offset) {
        return c.executeQuery(
                new SqlDialect.PreparedSql(
                        RedisDialect.scanCommand(db, prefix, limit, offset), List.of()),
                List.of(), limit);
    }

    private static int indexOfKey(QueryResult r, String key) {
        for (int i = 0; i < r.rows().size(); i++) {
            if (key.equals(r.rows().get(i).get(0))) {
                return i;
            }
        }
        throw new AssertionError("结果里没有键 " + key);
    }

    private static void assertArrayEquals2(String[] expected, String[] actual) {
        assertEquals(List.of(expected), List.of(actual));
    }

    /** 让 UTF-8 字节数那条断言的意图明确：中文一个字三字节。 */
    @Test
    @DisplayName("测试自证：断言里用到的字节数确实是三字节一个汉字")
    void utf8Assumption() {
        assertEquals(6, "张三".getBytes(StandardCharsets.UTF_8).length);
    }
}
