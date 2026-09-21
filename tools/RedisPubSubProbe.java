import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.kv.KeyValueStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订阅真的收得到消息吗，而且订阅之后<b>原来那条连接还能用吗</b>。
 *
 * <h2>第二问才是重点</h2>
 * {@code SUBSCRIBE} 会把连接切进订阅模式。如果实现偷懒借用了正在用的那条连接，
 * 订阅本身照样成功、消息也照样收得到——<b>坏的是别的地方</b>：
 * 树展不开、表打不开，而且看不出跟「刚才点了订阅」有任何关系。
 *
 * <p>所以这里订阅之后立刻拿原连接再发一条普通命令。这一条过不了，
 * 就说明那个坑还在。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RedisPubSubProbe.java "连接名"
 * </pre>
 */
public class RedisPubSubProbe {

    private static final String CHAN = "plainly:probe:chan";
    private static final String PATTERN_CHAN = "plainly:probe:news.sports";
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
                run(db);
            }
        }
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
    }

    private static void run(DbConnection db) throws Exception {
        System.out.println("服务端：" + db.serverVersion());
        KeyValueStore kv = (KeyValueStore) db;
        check("声明支持发布订阅", kv.supportsPubSub(), "supportsPubSub() 是 false");

        List<KeyValueStore.Message> got = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();

        try (KeyValueStore.Subscription sub = kv.subscribe(
                List.of(CHAN), List.of("plainly:probe:news.*"), got::add, errors::add)) {

            check("订阅建立后是活的", sub.active(), "active() 是 false");

            // ——— 重点：订阅之后，原来那条连接还得能用
            String pong = null;
            try {
                pong = db.execute("PING", 1).rows().get(0).get(0);
            } catch (RuntimeException e) {
                pong = "× " + e.getMessage();
            }
            check("订阅之后原连接照常可用（说明用的是独立连接）",
                    pong != null && pong.toUpperCase(java.util.Locale.ROOT).contains("PONG"),
                    "PING 回的是：" + pong);

            // 给订阅一点建立时间，再发消息
            Thread.sleep(300);
            long n1 = kv.publish(CHAN, "hello-exact");
            long n2 = kv.publish(PATTERN_CHAN, "hello-pattern");
            System.out.println("  精确频道订阅者数：" + n1 + "，通配频道订阅者数：" + n2);

            waitFor(got, 2, 3000);

            check("收到了精确订阅的消息", got.stream().anyMatch(
                    m -> m.channel().equals(CHAN) && m.payload().equals("hello-exact")),
                    "收到的是：" + got);
            check("收到了通配订阅的消息，且频道名没和模式串位",
                    got.stream().anyMatch(m -> m.channel().equals(PATTERN_CHAN)
                            && m.payload().equals("hello-pattern")
                            && m.pattern().equals("plainly:probe:news.*")),
                    "收到的是：" + got);
            check("过程中没有报错", errors.isEmpty(), String.valueOf(errors));
        }

        Thread.sleep(300);
        check("关掉之后不再报「连接出错」（自己关的不算故障）",
                errors.isEmpty(), String.valueOf(errors));

        // 关掉订阅之后，原连接仍然要好使
        String pong = db.execute("PING", 1).rows().get(0).get(0);
        check("关掉订阅后原连接仍然可用",
                pong.toUpperCase(java.util.Locale.ROOT).contains("PONG"), pong);
    }

    private static void waitFor(List<?> list, int want, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (list.size() < want && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  " + what);
        } else {
            fail++;
            System.out.println("× " + what + "   " + detail);
        }
    }
}
