import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.core.sync.StructureSyncService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import java.util.List;

/**
 * 结构同步「比对」这一步到底在哪儿炸的。
 *
 * <p><b>只跑比对，不跑应用。</b>比对只读元数据；应用会往目标库发 DDL，
 * 那是用户的真实数据库，不能在排查时替他执行。
 *
 * <p>界面上的报错经过了一层 rootMessage 提炼，堆栈丢了。这里原样打出来，
 * 才知道是哪一行、哪一类问题。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SyncErrorProbe.java [源连接名] [源库] [目标连接名] [目标库]</pre>
 */
public class SyncErrorProbe {

    public static void main(String[] args) {
        AppContext context = new AppContext();
        try {
            List<ConnectionConfig> all = context.registry().listAll();
            System.out.println("已保存的连接：");
            for (ConnectionConfig c : all) {
                System.out.println("   " + c.name() + "  (" + c.type().displayName() + ")");
            }
            System.out.println();

            if (args.length >= 4) {
                run(context, find(all, args[0]), args[1], find(all, args[2]), args[3]);
                return;
            }

            // 没给参数就把能连上的库两两之间试一遍第一组
            for (ConnectionConfig c : all) {
                try {
                    DbSession s = context.openSession(c);
                    List<SchemaInfo> schemas = s.schemas();
                    System.out.println(c.name() + " 的库：");
                    for (SchemaInfo si : schemas) {
                        System.out.println("   " + si.name() + (si.isDefault() ? "  (默认)" : ""));
                    }
                } catch (RuntimeException e) {
                    System.out.println(c.name() + " 连不上：" + e.getMessage());
                }
            }
            System.out.println();
            System.out.println("给四个参数再跑一次比对：源连接名 源库 目标连接名 目标库");
        } finally {
            context.close();
        }
    }

    private static ConnectionConfig find(List<ConnectionConfig> all, String name) {
        return all.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有叫「" + name + "」的连接"));
    }

    private static void run(AppContext context, ConnectionConfig src, String srcSchema,
                            ConnectionConfig tgt, String tgtSchema) {
        System.out.println("比对：" + src.name() + "." + srcSchema
                + "  →  " + tgt.name() + "." + tgtSchema);
        StructureSyncService service = new StructureSyncService();
        try {
            DbSession s = context.openSession(src);
            DbSession t = context.openSession(tgt);
            StructureSyncService.SyncPlan plan = service.plan(
                    s.connection(), srcSchema, t.connection(), tgtSchema,
                    name -> System.out.println("   读 " + name));
            System.out.println("比对完成，差异 " + plan.changes().size() + " 项");
            plan.changes().stream().limit(20).forEach(c ->
                    System.out.println("   [" + c.risk() + "] " + c.objectName()
                            + " · " + c.describe()));
        } catch (Throwable e) {
            System.out.println();
            System.out.println("=== 比对炸了，完整堆栈 ===");
            e.printStackTrace(System.out);
        }
    }
}
