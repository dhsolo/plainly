import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.kv.KeyValueStore;

import java.util.List;

/**
 * 队列观察：看得准，而且<b>看完一条都不少</b>。
 *
 * <h2>第二条才是红线</h2>
 * 监听工具最严重的错误不是显示不准，是把别人的消息取走了：
 * 消费方收不到、消息没了、而且没有任何痕迹指向这个工具。
 * 所以每看一次都要回头数一遍长度，确认它没变。
 *
 * <p>会建 {@code plainly:probe:queue} 和 {@code plainly:probe:stream}，跑完就删。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RedisQueueProbe.java ["连接名"]
 * </pre>
 */
public class RedisQueueProbe {

    private static final String LIST_KEY = "plainly:probe:queue";
    private static final String STREAM_KEY = "plainly:probe:stream";
    private static final String GROUP = "probe-group";
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "测试";
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry =
                    new ConnectionRegistry(store, CredentialStore.forCurrentPlatform());
            ConnectionConfig cfg = registry.listAll().stream()
                    .filter(c -> c.name().equals(name) && c.type() == DbType.REDIS)
                    .findFirst().orElse(null);
            if (cfg == null) {
                System.out.println("找不到 Redis 连接：" + name);
                return;
            }
            try (DbConnection db = Connections.open(registry.resolvePassword(cfg))) {
                System.out.println("服务端：" + db.serverVersion());
                cleanUp(db);
                try {
                    listQueue(db);
                    streamQueue(db);
                    refuseNonQueue(db);
                } finally {
                    cleanUp(db);
                }
            }
        }
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
    }

    // ------------------------------------------------------------------ 列表队列

    private static void listQueue(DbConnection db) {
        section("列表队列");
        KeyValueStore kv = (KeyValueStore) db;
        for (int i = 1; i <= 5; i++) {
            db.execute("RPUSH " + LIST_KEY + " job" + i, 1);
        }

        KeyValueStore.QueueSnapshot snap = kv.inspectQueue("db0", LIST_KEY, 3);
        check("类型认出来了", "list".equals(snap.type()), snap.type());
        check("长度对得上", snap.depth() == 5, "depth=" + snap.depth());
        check("只看了 3 条队头", snap.head().size() == 3, String.valueOf(snap.head()));
        check("看的是队头（下一批会被处理的），不是队尾",
                snap.head().get(0).equals("job1"), String.valueOf(snap.head()));

        // 红线
        long after = len(db, "LLEN " + LIST_KEY);
        check("看完之后队列一条都没少", after == 5, "现在还剩 " + after + " 条");
    }

    // ------------------------------------------------------------------ Stream

    private static void streamQueue(DbConnection db) {
        section("Stream 队列与消费组");
        KeyValueStore kv = (KeyValueStore) db;
        for (int i = 1; i <= 4; i++) {
            db.execute("XADD " + STREAM_KEY + " * task task" + i, 1);
        }
        db.execute("XGROUP CREATE " + STREAM_KEY + " " + GROUP + " 0", 1);
        // 投两条给消费者但<b>不确认</b>，制造出真实的 pending
        db.execute("XREADGROUP GROUP " + GROUP + " c1 COUNT 2 STREAMS " + STREAM_KEY + " >", 5);

        KeyValueStore.QueueSnapshot snap = kv.inspectQueue("db0", STREAM_KEY, 2);
        check("类型认出来了", "stream".equals(snap.type()), snap.type());
        check("长度对得上", snap.depth() == 4, "depth=" + snap.depth());
        check("队头看到了消息内容而不是 [N 个元素]",
                !snap.head().isEmpty() && snap.head().get(0).contains("task=task1"),
                String.valueOf(snap.head()));

        check("列出了消费组", snap.groups().size() == 1, String.valueOf(snap.groups()));
        if (!snap.groups().isEmpty()) {
            KeyValueStore.ConsumerGroup g = snap.groups().get(0);
            System.out.println("    组：" + g.name() + "  消费者=" + g.consumers()
                    + "  未确认=" + g.pending() + "  未投递=" + g.lag()
                    + "  最后投递=" + g.lastDelivered());
            check("未确认条数是 2（投出去了没 XACK）", g.pending() == 2,
                    "pending=" + g.pending());
            check("未投递条数是 2", g.lag() == 2, "lag=" + g.lag());
        }

        long after = len(db, "XLEN " + STREAM_KEY);
        check("看完之后 Stream 一条都没少", after == 4, "现在还剩 " + after + " 条");
    }

    // ------------------------------------------------------------------ 不是队列的键

    private static void refuseNonQueue(DbConnection db) {
        section("不是队列的键");
        KeyValueStore kv = (KeyValueStore) db;
        db.execute("SET plainly:probe:plain hello", 1);
        try {
            kv.inspectQueue("db0", "plainly:probe:plain", 3);
            check("应当拒绝把 string 当队列看", false, "居然没报错");
        } catch (RuntimeException e) {
            check("拒绝把 string 当队列看，并说清原因",
                    e.getMessage() != null && e.getMessage().contains("不是队列"),
                    e.getMessage());
        }
        db.execute("DEL plainly:probe:plain", 1);
    }

    // ------------------------------------------------------------------ 辅助

    private static long len(DbConnection db, String cmd) {
        try {
            return Long.parseLong(db.execute(cmd, 1).rows().get(0).get(0).trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void section(String t) {
        System.out.println();
        System.out.println("=== " + t);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  " + what);
        } else {
            fail++;
            System.out.println("× " + what + "   实际：" + detail);
        }
    }

    private static void cleanUp(DbConnection db) {
        for (String k : List.of(LIST_KEY, STREAM_KEY, "plainly:probe:plain")) {
            try {
                db.execute("DEL " + k, 1);
            } catch (RuntimeException ignored) {
                // 本来就没有
            }
        }
    }
}
