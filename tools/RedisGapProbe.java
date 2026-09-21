import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.QueryResult;
import com.plainly.driver.kv.KeyValueStore;

import java.util.List;

/**
 * Redis 这一侧到底还缺什么——拿真库一条条试出来，不靠读代码猜。
 *
 * <h2>为什么先探再改</h2>
 * 「补齐 Redis」是个很大的说法，而里面每一项的现状都不一样：有的其实已经能用
 * （原始命令），有的能用但显示不对（Stream），有的从协议上就做不到
 * （SUBSCRIBE 会把连接切进订阅模式，之后它不再响应普通请求）。
 * 不先分清楚，就会去重写已经好的东西，而真正缺的那块反倒没动。
 *
 * <p>会建若干 {@code plainly:probe:*} 的键，跑完就删。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RedisGapProbe.java "连接名"
 * </pre>
 */
public class RedisGapProbe {

    private static final String P = "plainly:probe:";

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "测试";
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry =
                    new ConnectionRegistry(store, CredentialStore.forCurrentPlatform());
            ConnectionConfig cfg = registry.listAll().stream()
                    .filter(c -> c.name().equals(name)
                            && c.type() == com.plainly.driver.DbType.REDIS)
                    .findFirst().orElse(null);
            if (cfg == null) {
                System.out.println("找不到 Redis 连接：" + name);
                return;
            }
            try (DbConnection db = Connections.open(registry.resolvePassword(cfg))) {
                System.out.println("服务端：" + db.serverVersion());
                cleanUp(db);
                try {
                    rawCommands(db);
                    dataTypes(db);
                    pubsub(db);
                    queueAndLock(db);
                } finally {
                    cleanUp(db);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 原始命令

    private static void rawCommands(DbConnection db) {
        section("原始命令（SQL 标签页那条路）");
        try {
            QueryResult r = db.execute("SET " + P + "raw \"带 空格 的值\"", 10);
            System.out.println("  SET 回复：" + first(r));
            r = db.execute("GET " + P + "raw", 10);
            System.out.println("  GET 回复：" + first(r) + "   <- 引号切分是否正确看这里");
            r = db.execute("INFO server", 10);
            System.out.println("  INFO 回复行数：" + r.rows().size());
            r = db.execute("COMMAND COUNT", 10);
            System.out.println("  服务端命令总数：" + first(r));
        } catch (RuntimeException e) {
            System.out.println("× 原始命令这条路有问题：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 数据类型

    private static void dataTypes(DbConnection db) {
        section("数据类型");
        KeyValueStore kv = (KeyValueStore) db;
        System.out.println("  界面上声明支持的类型：" + kv.valueTypes());

        exec(db, "SET " + P + "string hello");
        exec(db, "HSET " + P + "hash f1 v1 f2 v2");
        exec(db, "RPUSH " + P + "list a b c");
        exec(db, "SADD " + P + "set x y z");
        exec(db, "ZADD " + P + "zset 1 one 2 two");
        exec(db, "XADD " + P + "stream * field1 value1");
        exec(db, "SETBIT " + P + "bitmap 7 1");
        exec(db, "PFADD " + P + "hll a b c");
        exec(db, "GEOADD " + P + "geo 13.361389 38.115556 Palermo");

        for (String t : List.of("string", "hash", "list", "set", "zset",
                "stream", "bitmap", "hll", "geo")) {
            String key = P + t;
            String serverType = scalar(db, "TYPE " + key);
            String read;
            try {
                KeyValueStore.Entry e = kv.read("db0", key);
                read = e == null ? "(读不到)" : "类型=" + e.type()
                        + "  值=" + clip(e.value());
            } catch (RuntimeException ex) {
                read = "× 读失败：" + ex.getMessage();
            }
            System.out.printf("  %-8s 服务端说 %-8s  工具读到 %s%n", t, serverType, read);
        }
    }

    // ------------------------------------------------------------------ 发布订阅

    private static void pubsub(DbConnection db) {
        section("发布订阅");
        try {
            String n = scalar(db, "PUBLISH " + P + "chan hello");
            System.out.println("  PUBLISH 能发，收到的订阅者数：" + n);
        } catch (RuntimeException e) {
            System.out.println("× PUBLISH 失败：" + e.getMessage());
        }
        System.out.println("  SUBSCRIBE 不在这里试——它会把这条连接切进订阅模式，");
        System.out.println("  之后连接不再响应普通命令，试完这条连接就废了。");
        System.out.println("  结论：订阅必须用一条独立连接，且要能异步收推送。");
    }

    // ------------------------------------------------------------------ 队列与锁

    private static void queueAndLock(DbConnection db) {
        section("队列与分布式锁：现在能看到什么");
        exec(db, "RPUSH " + P + "queue job1 job2 job3");
        System.out.println("  队列长度 LLEN：" + scalar(db, "LLEN " + P + "queue"));
        System.out.println("  不消费地看队头 LRANGE 0 0：" + scalar(db, "LRANGE " + P + "queue 0 0"));

        exec(db, "SET " + P + "lock owner-abc EX 60 NX");
        System.out.println("  锁的值（持有者）：" + scalar(db, "GET " + P + "lock"));
        System.out.println("  锁的剩余秒数 TTL：" + scalar(db, "TTL " + P + "lock"));
        System.out.println("  —— 这些命令都能发，缺的是把它们做成常驻的、会自己刷新的视图。");
    }

    // ------------------------------------------------------------------ 辅助

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title);
    }

    private static void exec(DbConnection db, String cmd) {
        try {
            db.execute(cmd, 1);
        } catch (RuntimeException e) {
            System.out.println("  （" + cmd.split(" ")[0] + " 失败：" + e.getMessage() + "）");
        }
    }

    private static String scalar(DbConnection db, String cmd) {
        try {
            return first(db.execute(cmd, 10));
        } catch (RuntimeException e) {
            return "× " + e.getMessage();
        }
    }

    private static String first(QueryResult r) {
        if (r.rows().isEmpty() || r.columns().isEmpty()) {
            return "(空)";
        }
        return clip(r.rows().get(0).get(0));
    }

    private static String clip(String s) {
        if (s == null) {
            return "(null)";
        }
        String one = s.replace("\n", " ⏎ ");
        return one.length() <= 60 ? one : one.substring(0, 60) + "…";
    }

    private static void cleanUp(DbConnection db) {
        for (String t : List.of("raw", "string", "hash", "list", "set", "zset",
                "stream", "bitmap", "hll", "geo", "queue", "lock")) {
            try {
                db.execute("DEL " + P + t, 1);
            } catch (RuntimeException ignored) {
                // 本来就没有
            }
        }
    }
}
