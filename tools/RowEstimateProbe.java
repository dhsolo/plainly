import com.plainly.app.AppContext;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;

/**
 * 行数估算到底取回来了没有。
 *
 * <p>这条链路有四段，任何一段断了，界面上的表现都是同一个「什么也不显示」：
 * 方言给的语句、驱动执行它、按表名并回 TableInfo、树上渲染。所以这里把
 * 中间结果全打出来——语句本身、返回的条数、每张表的 rowEstimate 与它的短写法。
 *
 * <p><b>只读。</b>只发 SELECT，不碰任何用户数据。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RowEstimateProbe.java [连接名]</pre>
 */
public class RowEstimateProbe {

    public static void main(String[] args) throws Exception {
        try (AppContext ctx = new AppContext()) {
            for (ConnectionConfig saved : ctx.registry().listAll()) {
                if (args.length > 0 && !saved.name().contains(args[0])) {
                    continue;
                }
                probe(ctx, saved);
            }
        }
        System.out.println();
        System.out.println("-- 短写法 --");
        long[] samples = {0, 7, 999, 9999, 10000, 12834, 999999, 12834591, 100000000L, 350000000L};
        for (long v : samples) {
            System.out.println(String.format("%,15d -> %s", v, UiUtils.approxCount(v)));
        }
        System.out.println("        (未知 -1) -> [" + UiUtils.approxCount(-1) + "]");
    }

    private static void probe(AppContext ctx, ConnectionConfig saved) {
        System.out.println();
        System.out.println("=== " + saved.name() + " (" + saved.type() + ") ===");
        ConnectionConfig cfg = ctx.registry().resolvePassword(saved);
        try (DbConnection conn = Connections.open(cfg)) {
            SchemaInfo target = conn.listSchemas().stream()
                    .filter(SchemaInfo::isDefault).findFirst()
                    .orElse(conn.listSchemas().isEmpty() ? null : conn.listSchemas().get(0));
            if (target == null) {
                System.out.println("  没有可用的库");
                return;
            }
            System.out.println("  库 = " + target.name());
            System.out.println("  语句 = " + conn.dialect().tableRowCountQuery(target.name()));

            long start = System.nanoTime();
            var tables = conn.listTables(target.name());
            long ms = (System.nanoTime() - start) / 1_000_000;

            long known = tables.stream().filter(t -> t.rowEstimate() >= 0).count();
            System.out.println("  表 " + tables.size() + " 张，其中 " + known
                    + " 张有行数估算；listTables 耗时 " + ms + " ms");
            int shown = 0;
            for (TableInfo t : tables) {
                if (shown++ >= 12) {
                    break;
                }
                System.out.println(String.format("    %-30s %-6s %12d  ->  %s",
                        t.name(), t.kind(), t.rowEstimate(),
                        t.rowEstimate() < 0 ? "(不显示)" : UiUtils.approxCount(t.rowEstimate())));
            }
        } catch (Exception e) {
            System.out.println("  连不上或查不到：" + e);
        }
    }
}
