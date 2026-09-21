import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一个库里到底有多少种、多少个对象——按数据库自己的账本数一遍，
 * 再按本工具看到的数一遍，两边对不上的就是漏掉的东西。
 *
 * <h2>为什么要两边各数一遍</h2>
 * 「备份少了点什么」这件事，光看备份文件是看不出来的：文件里没有视图，
 * 可能是因为这个库本来就没有视图，也可能是因为本工具压根没看见它们。
 * 两个数字摆在一起才分得清。
 *
 * <h2>只读</h2>
 * 只发 SELECT 和元数据查询。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SchemaObjectProbe.java "连接名" 模式名 [类型]
 * </pre>
 */
public class SchemaObjectProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：SchemaObjectProbe \"连接名\" 模式名 [类型]");
            return;
        }
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            String wantedType = args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : null;
            ConnectionConfig config = registry.listAll().stream()
                    .filter(c -> c.name().equals(args[0]))
                    .filter(c -> wantedType == null || c.type().name().equals(wantedType))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                System.out.println("找不到连接 " + args[0]);
                return;
            }
            String schema = args[1];

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                System.out.println("已连接：" + db.serverVersion());
                System.out.println("模式：" + schema);

                System.out.println();
                System.out.println("== 本工具的 listTables 看到了什么");
                Map<ObjectKind, Integer> mine = new LinkedHashMap<>();
                for (TableInfo t : db.listTables(schema)) {
                    mine.merge(t.kind(), 1, Integer::sum);
                }
                mine.forEach((k, v) -> System.out.println("  " + k + " = " + v));

                System.out.println();
                System.out.println("== 数据库自己的账本");
                switch (config.type()) {
                    case MYSQL -> {
                        show(db, "表 / 视图", "SELECT TABLE_TYPE, COUNT(*) FROM information_schema.TABLES"
                                + " WHERE TABLE_SCHEMA = '" + schema + "' GROUP BY TABLE_TYPE");
                        show(db, "例程", "SELECT ROUTINE_TYPE, COUNT(*) FROM information_schema.ROUTINES"
                                + " WHERE ROUTINE_SCHEMA = '" + schema + "' GROUP BY ROUTINE_TYPE");
                        show(db, "触发器", "SELECT COUNT(*) FROM information_schema.TRIGGERS"
                                + " WHERE TRIGGER_SCHEMA = '" + schema + "'");
                        show(db, "事件", "SELECT COUNT(*) FROM information_schema.EVENTS"
                                + " WHERE EVENT_SCHEMA = '" + schema + "'");
                        show(db, "外键", "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                                + " WHERE CONSTRAINT_SCHEMA = '" + schema + "'"
                                + " AND CONSTRAINT_TYPE = 'FOREIGN KEY'");
                    }
                    case POSTGRESQL -> {
                        show(db, "表 / 视图", "SELECT table_type, count(*) FROM information_schema.tables"
                                + " WHERE table_schema = '" + schema + "' GROUP BY table_type");
                        show(db, "例程", "SELECT count(*) FROM pg_proc p JOIN pg_namespace n"
                                + " ON n.oid = p.pronamespace WHERE n.nspname = '" + schema + "'"
                                + " AND p.prokind IN ('f','p')");
                        show(db, "序列", "SELECT count(*) FROM pg_sequences WHERE schemaname = '"
                                + schema + "'");
                    }
                    default -> System.out.println("  （这一家这次不对账）");
                }
            }
        }
    }

    private static void show(DbConnection db, String label, String sql) {
        System.out.println("  -- " + label);
        try {
            var r = db.execute(sql, 50);
            if (r.rows().isEmpty()) {
                System.out.println("     （0 行）");
                return;
            }
            r.rows().forEach(row -> {
                StringBuilder sb = new StringBuilder("     ");
                for (int i = 0; i < r.columns().size(); i++) {
                    sb.append(row.get(i)).append("  ");
                }
                System.out.println(sb);
            });
        } catch (RuntimeException e) {
            System.out.println("     失败：" + e.getMessage());
        }
    }
}
