import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.core.sync.StructureSyncService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.ddl.SchemaChange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把结构同步<b>将要执行</b>的语句原样打出来，但一条都不执行。
 *
 * <p>「应用时报错」这种问题，光看界面上那句提炼过的错误没法定位——
 * 得看它到底生成了什么语句。生成是纯计算，不碰目标库；执行才碰。
 * 所以这里只做前一半。
 *
 * <pre>java -cp "..." tools/SyncSqlDump.java 源连接名 源库 目标连接名 目标库</pre>
 */
public class SyncSqlDump {

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("用法：源连接名 源库 目标连接名 目标库");
            return;
        }
        AppContext context = new AppContext();
        try {
            List<ConnectionConfig> all = context.registry().listAll();
            ConnectionConfig src = find(all, args[0]);
            ConnectionConfig tgt = find(all, args[2]);

            DbSession s = context.openSession(src);
            DbSession t = context.openSession(tgt);

            StructureSyncService service = new StructureSyncService();
            StructureSyncService.SyncPlan plan =
                    service.plan(s.connection(), args[1], t.connection(), args[3], name -> { });

            // 按界面的默认勾选规则来：不可逆的默认不选
            Set<SchemaChange> picked = new LinkedHashSet<>();
            for (SchemaChange c : plan.changes()) {
                boolean irreversible = c instanceof SchemaChange.DropTable
                        || c instanceof SchemaChange.DropIndex;
                if (!irreversible) {
                    picked.add(c);
                }
            }
            System.out.println("差异 " + plan.changes().size() + " 项，按默认规则勾选 "
                    + picked.size() + " 项");

            StructureSyncService.SyncPlan restricted =
                    service.restrictTo(plan, t.connection(), args[3], picked);

            System.out.println("能否应用 canApply=" + restricted.canApply());
            System.out.println("将执行 " + restricted.sql().size() + " 条语句：");
            System.out.println("──────────────────────────────────────────");
            int i = 0;
            for (String sql : restricted.sql()) {
                System.out.println("[" + (++i) + "] " + sql + ";");
            }
            System.out.println("──────────────────────────────────────────");
            System.out.println("（一条都没执行）");
        } catch (Throwable e) {
            System.out.println("=== 生成阶段就炸了 ===");
            e.printStackTrace(System.out);
        } finally {
            context.close();
        }
    }

    private static ConnectionConfig find(List<ConnectionConfig> all, String name) {
        return all.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有叫「" + name + "」的连接"));
    }
}
