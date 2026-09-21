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
 * 锁视图：看得对，而且<b>不会误删别人的锁</b>。
 *
 * <h2>最要紧的一条</h2>
 * 释放锁必须原子地「比对持有者 + 删除」。拆成客户端的 GET + DEL，
 * 两步之间锁可能过期并被别人拿到——那一删就是删了别人刚拿到的锁，
 * 两个进程同时进临界区，而且事后极难查。
 *
 * <p>所以这里专门试一次<b>持有者不匹配</b>的释放：它必须拒绝，而且事后那把锁还在。
 *
 * <p>会建 {@code plainly:probe:lock:*}，跑完就删。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RedisLockProbe.java ["连接名"]
 * </pre>
 */
public class RedisLockProbe {

    private static final String PREFIX = "plainly:probe:lock:";
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
                    run(db);
                } finally {
                    cleanUp(db);
                }
            }
        }
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
    }

    private static void run(DbConnection db) {
        KeyValueStore kv = (KeyValueStore) db;
        check("声明支持锁视图", kv.supportsLocks(), "supportsLocks() 是 false");

        db.execute("SET " + PREFIX + "order owner-A EX 120", 1);
        db.execute("SET " + PREFIX + "stock owner-B EX 300", 1);
        // 一把没有过期时间的锁：持有方崩了就永远不会自己释放，值得单独提醒
        db.execute("SET " + PREFIX + "stuck owner-C", 1);
        // 一个不该被扫进来的普通缓存键
        db.execute("SET plainly:probe:cache:x hello EX 120", 1);

        section("列出");
        List<KeyValueStore.LockInfo> locks = kv.scanLocks("db0", PREFIX + "*", 100);
        check("扫到了三把锁", locks.size() == 3, "扫到 " + locks.size() + " 把：" + locks);
        check("模式之外的缓存键没被扫进来",
                locks.stream().noneMatch(l -> l.key().contains("cache")), String.valueOf(locks));

        KeyValueStore.LockInfo order = find(locks, PREFIX + "order");
        check("持有者读对了", order != null && "owner-A".equals(order.holder()),
                String.valueOf(order));
        check("剩余时间读到了", order != null && order.ttlSeconds() > 0 && order.ttlSeconds() <= 120,
                order == null ? "没找到" : "ttl=" + order.ttlSeconds());

        KeyValueStore.LockInfo stuck = find(locks, PREFIX + "stuck");
        check("认出了没有过期时间的锁", stuck != null && stuck.neverExpires(),
                stuck == null ? "没找到" : "ttl=" + stuck.ttlSeconds());

        section("释放：持有者不对");
        boolean wrong = kv.releaseLock("db0", PREFIX + "order", "owner-别人");
        check("持有者不匹配时拒绝释放", !wrong, "居然返回了 true");
        String stillThere = scalar(db, "GET " + PREFIX + "order");
        check("而且那把锁还在，一点没动", "owner-A".equals(stillThere),
                "现在是：" + stillThere);

        section("释放：持有者对");
        boolean right = kv.releaseLock("db0", PREFIX + "order", "owner-A");
        check("持有者匹配时释放成功", right, "返回了 false");
        check("锁真的没了", "(空)".equals(scalar(db, "GET " + PREFIX + "order"))
                        || scalar(db, "EXISTS " + PREFIX + "order").equals("0"),
                "EXISTS=" + scalar(db, "EXISTS " + PREFIX + "order"));

        section("释放：锁已经不在了");
        boolean gone = kv.releaseLock("db0", PREFIX + "order", "owner-A");
        check("再释放一次返回 false 而不是报错", !gone, "返回了 true");
    }

    // ------------------------------------------------------------------ 辅助

    private static KeyValueStore.LockInfo find(List<KeyValueStore.LockInfo> locks, String key) {
        return locks.stream().filter(l -> l.key().equals(key)).findFirst().orElse(null);
    }

    private static String scalar(DbConnection db, String cmd) {
        try {
            var r = db.execute(cmd, 1);
            if (r.rows().isEmpty()) {
                return "(空)";
            }
            String v = r.rows().get(0).get(0);
            return v == null ? "(空)" : v;
        } catch (RuntimeException e) {
            return "× " + e.getMessage();
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
        for (String k : List.of(PREFIX + "order", PREFIX + "stock", PREFIX + "stuck",
                "plainly:probe:cache:x")) {
            try {
                db.execute("DEL " + k, 1);
            } catch (RuntimeException ignored) {
                // 本来就没有
            }
        }
    }
}
