import com.plainly.core.sync.StructureSyncService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.jdbc.JdbcConnections;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 穿过服务层跑一遍结构同步，验证真实文件库上的完整回路。
 *
 * <p>与单元测试的分工：{@code SchemaSyncTest} 在 H2 的两个 schema 之间做严格验证，
 * 这里走的是「两条真实连接 + StructureSyncService」，覆盖单元测试绕开的服务层装配。
 *
 * <p>只应用安全子集（与界面默认勾选一致），再比一次应当只剩下那两条删表。
 *
 * <p>用法：
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SyncCheck.java</pre>
 */
public class SyncCheck {

    public static void main(String[] args) {
        StructureSyncService service = new StructureSyncService();

        try (DbConnection source = open(System.getProperty("plainly.home", ".") + "/demo/plainly-demo-test");
             DbConnection target = open(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")) {

            System.out.println("目标库支持事务性 DDL：" + StructureSyncService.isAtomic(target));
            System.out.println();

            StructureSyncService.SyncPlan plan =
                    service.plan(source, "PUBLIC", target, "PUBLIC", null);

            System.out.println("差异 " + plan.changes().size() + " 项：");
            plan.changes().forEach(c -> System.out.printf("  [%-14s] %s%n",
                    c.risk(), c.describe()));

            // 与界面一致的默认勾选：不可逆的操作不替用户决定
            Set<SchemaChange> safe = new LinkedHashSet<>();
            for (SchemaChange c : plan.changes()) {
                if (!(c instanceof SchemaChange.DropTable) && !(c instanceof SchemaChange.DropIndex)) {
                    safe.add(c);
                }
            }
            StructureSyncService.SyncPlan restricted =
                    service.restrictTo(plan, target, "PUBLIC", safe);

            System.out.println();
            System.out.println("将执行 " + restricted.sql().size() + " 条语句：");
            restricted.sql().forEach(s -> System.out.println("  " + s.replace("\n", " ")));

            int executed = service.apply(target, restricted);
            System.out.println();
            System.out.println("已执行 " + executed + " 条");

            // 再比一次
            StructureSyncService.SyncPlan after =
                    service.plan(source, "PUBLIC", target, "PUBLIC", null);
            System.out.println();
            System.out.println("同步后剩余差异 " + after.changes().size() + " 项：");
            after.changes().forEach(c -> System.out.println("  " + c.describe()));

            List<SchemaChange> remaining = after.changes();
            boolean onlyDrops = !remaining.isEmpty()
                    && remaining.stream().allMatch(c -> c instanceof SchemaChange.DropTable);
            System.out.println();
            if (onlyDrops && remaining.size() == 2) {
                System.out.println("通过：安全子集已同步完毕，只剩下两条未勾选的删表。");
                System.exit(0);
            }
            System.out.println("不符预期：应当只剩两条删表。");
            System.exit(1);
        }
    }

    private static DbConnection open(String path) {
        return JdbcConnections.open(new ConnectionConfig()
                .setName(path)
                .setType(DbType.H2)
                .setFilePath(path)
                .setUser("sa")
                .setPassword(""));
    }
}
