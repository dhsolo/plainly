import com.plainly.app.AppContext;
import com.plainly.core.db.Connections;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;

import java.util.List;

/**
 * MySQL 上流式到底生效没有。
 *
 * <h2>为什么这一家非单独验不可</h2>
 * MySQL 的 JDBC 驱动只认 {@code setFetchSize(Integer.MIN_VALUE)} 这一个魔法值。
 * 给它 1000、给它 10000，它都<b>照单全收然后把整个结果集拉进客户端内存</b>——
 * 不报错、不警告，代码看上去完全正确，直到某天有人导一张大表时 OOM。
 * 所以这条路只能用内存去验，看代码是验不出来的。
 *
 * <h2>只读，且不碰任何业务数据</h2>
 * 行是拿一张小表自连接<b>现造</b>出来的（{@code a JOIN b JOIN c}），
 * 不读业务内容、不写、不建表。默认挑行数最少但够自乘出几十万行的那张表。
 *
 * <pre>java -Xmx192m -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/MysqlStreamProbe.java [连接名片段]</pre>
 */
public class MysqlStreamProbe {

    /** 要造出来的行数。够看出内存趋势，又不至于让服务端跑太久。 */
    private static final int TARGET_ROWS = 400_000;

    public static void main(String[] args) throws Exception {
        System.out.println("堆上限 " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");

        try (AppContext ctx = new AppContext()) {
            ConnectionConfig saved = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL)
                    .filter(c -> args.length == 0 || c.name().contains(args[0]))
                    .findFirst().orElse(null);
            if (saved == null) {
                System.out.println("没有可用的 MySQL 连接");
                return;
            }
            ConnectionConfig cfg = ctx.registry().resolvePassword(saved);
            System.out.println("连接 " + cfg.name());

            try (DbConnection conn = Connections.open(cfg)) {
                String schema = conn.listSchemas().stream()
                        .filter(s -> s.isDefault()).findFirst()
                        .orElse(conn.listSchemas().get(0)).name();
                conn.useSchema(schema);
                String table = pickTable(conn, schema);
                if (table == null) {
                    System.out.println("这个库里没有能用来自乘的表");
                    return;
                }
                String sql = "SELECT a.n AS ID, RPAD(CONCAT(a.n,'-',b.n,'-',c.n), 200, 'x') AS PAD"
                        + " FROM (SELECT 1 AS n FROM " + table + ") a"
                        + " JOIN (SELECT 1 AS n FROM " + table + ") b"
                        + " JOIN (SELECT 1 AS n FROM " + table + ") c"
                        + " LIMIT " + TARGET_ROWS;
                System.out.println("造行用的表 = " + schema + "." + table);
                System.out.println("语句 = " + sql);

                control(conn, sql);
                streamed(conn, sql);
                stillUsable(conn);
            }
        }
    }

    /** 挑一张自乘之后够到目标行数、但本身不大的表。只读 information_schema。 */
    private static String pickTable(DbConnection conn, String schema) {
        QueryResult r = conn.execute(
                "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = '" + schema + "' AND TABLE_TYPE = 'BASE TABLE'"
                        + " AND TABLE_ROWS >= 60 ORDER BY TABLE_ROWS ASC LIMIT 1", 5);
        return r.rows().isEmpty() ? null : r.rows().get(0).get(0);
    }

    /**
     * 对照组：原来的全量取法。
     *
     * <p>它必须撑不住。撑住了就说明这次造的行不够多，下面那一步证明不了任何事。
     */
    private static void control(DbConnection conn, String sql) {
        System.out.println();
        System.out.println("=== 对照组：全量取回（应当 OOM） ===");
        long start = System.nanoTime();
        try {
            QueryResult r = conn.execute(sql, 0);
            System.out.println("  [错] 取回来了 " + r.rows().size()
                    + " 行——这次造的行不够多，下面的结论不作数");
        } catch (OutOfMemoryError e) {
            System.out.println("  [对] 如期 OOM（" + ms(start) + " ms）：" + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("  [对] 如期失败（" + ms(start) + " ms）：" + root(e));
        }
    }

    private static void streamed(DbConnection conn, String sql) {
        System.out.println();
        System.out.println("=== 同一条语句，改走流式 ===");
        Runtime rt = Runtime.getRuntime();
        long[] count = {0};
        long[] peak = {0};
        long start = System.nanoTime();

        conn.stream(sql, new DbConnection.RowStream() {
            @Override
            public void columns(List<ColumnMeta> columns) {
                System.out.println("  列 " + columns.size() + " 个，第一行之前就拿到了");
            }

            @Override
            public void row(Row row) {
                count[0]++;
                if (count[0] % 50_000 == 0) {
                    long used = rt.totalMemory() - rt.freeMemory();
                    peak[0] = Math.max(peak[0], used);
                    System.out.println("    " + count[0] / 10000 + " 万行，占用 "
                            + (used >> 20) + " MB");
                }
            }
        });
        System.out.println("  [" + (count[0] > 0 ? "对" : "错") + "] 流出 " + count[0]
                + " 行，耗时 " + ms(start) + " ms，采样峰值 " + (peak[0] >> 20) + " MB");
    }

    /** 流完之后这条连接还得能正常查——只发一条 SELECT 1，不碰任何数据。 */
    private static void stillUsable(DbConnection conn) {
        System.out.println();
        String back = conn.scalar("SELECT 1");
        System.out.println("=== 流式之后连接还正常吗 ===");
        System.out.println("  [" + ("1".equals(back) ? "对" : "错") + "] SELECT 1 -> " + back);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String root(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String m = cur.getMessage() == null ? "" : cur.getMessage().replaceAll("\\s+", " ").trim();
        return cur.getClass().getSimpleName() + " " + (m.length() > 90 ? m.substring(0, 87) + "…" : m);
    }
}
