import com.plainly.app.AppContext;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.redis.RedisClient;
import java.util.List;

/**
 * 用户那条 Redis 连接到底卡在哪一步。
 *
 * <p>逐步走：建 TCP → PING → AUTH → SELECT → INFO → CONFIG → SCAN → 列键空间。
 * 一步步来是因为「连接失败」这四个字盖住了太多种可能——端口不通、口令不对、
 * 命令被禁、协议对不上，每一种的下一步动作完全不同。
 *
 * <p><b>只发读命令</b>，不改用户的任何数据。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RedisDiag.java</pre>
 */
public class RedisDiag {

    public static void main(String[] args) {
        AppContext context = new AppContext();
        try {
            for (ConnectionConfig raw : context.registry().listAll()) {
                if (raw.type() != DbType.REDIS) {
                    continue;
                }
                ConnectionConfig c = context.registry().resolvePassword(raw);
                System.out.println("=== 连接「" + c.name() + "」 " + c.host() + ":" + c.port()
                        + "  库=" + c.database()
                        + "  用户=" + (c.user() == null || c.user().isBlank() ? "(空)" : c.user())
                        + "  口令=" + (c.password() == null || c.password().isEmpty()
                                ? "(空)" : "(有，" + c.password().length() + " 位)"));
                System.out.println("    额外属性：" + c.extraProperties());
                probe(c);
            }
        } finally {
            context.close();
        }
    }

    private static void probe(ConnectionConfig c) {
        System.out.println("\n-- 逐条命令 --");
        RedisClient client = null;
        try {
            client = RedisClient.connect(c.host(), c.port(), 8000, 15000);
            System.out.println("  TCP 已建立");

            say(client, "PING");
            if (c.password() != null && !c.password().isEmpty()) {
                if (c.user() != null && !c.user().isBlank()) {
                    say(client, "AUTH", c.user().trim(), c.password());
                } else {
                    say(client, "AUTH", c.password());
                }
            } else {
                System.out.println("  （没配口令，跳过 AUTH）");
            }
            say(client, "SELECT", "0");
            say(client, "INFO", "server");
            say(client, "INFO", "keyspace");
            say(client, "CONFIG", "GET", "databases");
            say(client, "DBSIZE");
            say(client, "SCAN", "0", "MATCH", "*", "COUNT", "20");
        } catch (RuntimeException e) {
            System.out.println("  ！底层失败：" + e);
        } finally {
            if (client != null) {
                client.close();
            }
        }

        System.out.println("\n-- 走完整驱动 --");
        try (DbConnection conn = Connections.open(c)) {
            System.out.println("  serverVersion = " + conn.serverVersion());
            List<SchemaInfo> schemas = conn.listSchemas();
            System.out.println("  库 " + schemas.size() + " 个");
            String first = schemas.isEmpty() ? "db0" : schemas.get(0).name();
            List<TableInfo> tables = conn.listTables(first);
            System.out.println("  " + first + " 里键空间 " + tables.size() + " 个");
            tables.stream().limit(8).forEach(t ->
                    System.out.println("      " + t.name() + "  " + t.comment()));
        } catch (RuntimeException e) {
            System.out.println("  ！驱动失败：" + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("      ← " + cause.getClass().getName() + ": "
                        + cause.getMessage());
                cause = cause.getCause();
            }
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.getClassName().startsWith("com.plainly")) {
                    System.out.println("      @ " + el);
                }
            }
        }
    }

    /** 发一条命令，把回复原样打出来；错误也打，不抛。 */
    private static void say(RedisClient client, String... args) {
        String label = String.join(" ", args);
        if (args[0].equals("AUTH")) {
            label = "AUTH ****";
        }
        try {
            RedisClient.Reply reply = client.raw(args);
            String shown = String.valueOf(reply.display());
            if (shown.length() > 300) {
                shown = shown.substring(0, 300) + "…";
            }
            System.out.println("  " + label + "  →  [" + reply.kind() + "] "
                    + shown.replace("\r\n", " / "));
        } catch (RuntimeException e) {
            System.out.println("  " + label + "  →  ！" + e.getMessage());
        }
    }
}
