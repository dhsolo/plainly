import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;

import java.util.List;
import java.util.Locale;

/**
 * SQL 查询结果到底判成可编辑没有。
 *
 * <h2>要弄清的事</h2>
 * 「查询结果能不能改」被拆成两半：结果集自己判不判得出写回目标（这一半在驱动里，
 * 已经有 {@code QueryResult.readOnlyReason()}），以及界面上有没有地方把改动写回去。
 * 动手加功能之前得先知道现在卡在哪一半——两半的做法完全不同。
 *
 * <p>只跑 SELECT，不改任何数据。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SqlEditableProbe.java ["连接名" 模式名 表名]
 * </pre>
 */
public class SqlEditableProbe {

    public static void main(String[] args) throws Exception {
        if (args.length >= 3) {
            try (LocalStore store = new LocalStore()) {
                ConnectionRegistry registry = new ConnectionRegistry(store,
                        CredentialStore.forCurrentPlatform());
                ConnectionConfig config = registry.listAll().stream()
                        .filter(c -> c.name().equals(args[0]))
                        .findFirst().orElse(null);
                if (config == null) {
                    System.out.println("找不到连接 " + args[0]);
                    return;
                }
                try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                    run(db, args[1], args[2]);
                }
            }
            return;
        }
        // 默认打仓库里的 H2 演示库
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("sql-editable-probe")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");
        try (DbConnection db = Connections.open(cfg)) {
            run(db, "PUBLIC", "ORDERS");
        }
    }

    private static void run(DbConnection db, String schema, String table) {
        System.out.println("数据库：" + db.serverVersion());
        String qualified = db.dialect().qualify(schema, table);

        check(db, "单表 SELECT *", "SELECT * FROM " + qualified);
        check(db, "带 WHERE 的单表", "SELECT * FROM " + qualified + " WHERE 1 = 1");
        check(db, "只选几列（不含主键）", "SELECT " + columnsWithoutKey(db, schema, table)
                + " FROM " + qualified);
        check(db, "带表达式", "SELECT *, 1 + 1 AS calc FROM " + qualified);
        check(db, "聚合", "SELECT COUNT(*) AS n FROM " + qualified);
    }

    /** 挑几个非主键列，用来看「结果里没有主键」这一路的判定。 */
    private static String columnsWithoutKey(DbConnection db, String schema, String table) {
        QueryResult probe = db.execute("SELECT * FROM " + db.dialect().qualify(schema, table), 1);
        StringBuilder sb = new StringBuilder();
        for (ColumnMeta c : probe.columns()) {
            if (c.partOfKey()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(db.dialect().quote(c.name()));
            if (sb.length() > 40) {
                break;
            }
        }
        return sb.length() == 0 ? "*" : sb.toString();
    }

    private static void check(DbConnection db, String what, String sql) {
        System.out.println();
        System.out.println("== " + what);
        System.out.println("   " + sql);
        try {
            QueryResult r = db.execute(sql, 5);
            System.out.println("   可编辑：" + (r.isEditable() ? "  是" : "否"));
            if (!r.isEditable()) {
                System.out.println("   理由：  " + r.readOnlyReason());
            }
            System.out.println("   列的归属：" + describe(r.columns()));
        } catch (RuntimeException e) {
            System.out.println("   查询失败：" + e.getMessage());
        }
    }

    private static String describe(List<ColumnMeta> columns) {
        StringBuilder sb = new StringBuilder();
        for (ColumnMeta c : columns) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(c.name().toLowerCase(Locale.ROOT))
                    .append('[').append(c.tableName().isBlank() ? "无表" : c.tableName())
                    .append(c.partOfKey() ? " 主键" : "").append(']');
        }
        return sb.toString();
    }
}
