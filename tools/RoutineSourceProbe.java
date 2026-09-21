import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.util.List;

/**
 * 各家怎么把「存储过程 / 函数的源码」交出来。
 *
 * <h2>为什么要先探再写</h2>
 * 备份要包含存储过程，就得能读到它们的定义。但这件事各家差得很远，而且
 * <b>差在会不会给出完整的 CREATE 语句</b>——这一点决定了备份文件能不能直接还原：
 * <ul>
 *   <li>有的给整条 {@code CREATE PROCEDURE ...}（PostgreSQL 的 pg_get_functiondef）；</li>
 *   <li>有的只给<b>函数体</b>（MySQL 的 information_schema.ROUTINES.ROUTINE_DEFINITION），
 *       拿去还原会缺参数列表和返回类型；</li>
 *   <li>有的按<b>行</b>存，要自己拼回去（Oracle / 达梦的 ALL_SOURCE）。</li>
 * </ul>
 *
 * <p>按这个项目的规矩，没在真实实例上验过的方言 SQL 不写进代码——
 * 备份少一个对象是遗漏，备份出一段还原不回去的文本是骗人。
 *
 * <h2>只读</h2>
 * 全程只发 SELECT / SHOW，不建不改不删。口令从本机凭据库取，<b>不打印</b>。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RoutineSourceProbe.java "连接名" 模式名
 * </pre>
 * 不给参数时列出本机所有连接。
 */
public class RoutineSourceProbe {

    public static void main(String[] args) throws Exception {
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            List<ConnectionConfig> all = registry.listAll();

            if (args.length < 1) {
                System.out.println("用法：RoutineSourceProbe \"连接名\" [模式名]");
                System.out.println("本机的连接：");
                all.forEach(c -> System.out.println(
                        "  " + c.name() + "   [" + c.type().name() + "]"));
                return;
            }
            String wanted = args[0];
            // 同名连接是常态：一台机器上 MySQL、达梦、Oracle 往往都叫同一个 IP。
            // 只按名字取会静静地拿到第一条，然后用错误的方言去探——
            // 探出来的「0 行」看着像「这个库里没有存储过程」，其实是问错了对象
            String wantedType = args.length > 2 ? args[2].toUpperCase(java.util.Locale.ROOT) : null;
            ConnectionConfig config = all.stream()
                    .filter(c -> c.name().equals(wanted))
                    .filter(c -> wantedType == null || c.type().name().equals(wantedType))
                    .findFirst()
                    .orElse(null);
            if (config == null && wantedType == null) {
                long sameName = all.stream().filter(c -> c.name().equals(wanted)).count();
                if (sameName > 1) {
                    System.out.println("有多条连接都叫 " + wanted + "，请在第三个参数里指明类型。");
                }
            }
            if (config == null) {
                System.out.println("找不到名为 " + wanted + " 的连接。");
                return;
            }

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                String schema = args.length > 1 ? args[1] : db.config().database();
                System.out.println("已连接：" + db.serverVersion());
                System.out.println("类型：" + config.type() + "   模式：" + schema);
                System.out.println();

                switch (config.type()) {
                    case MYSQL -> mysql(db, schema);
                    case POSTGRESQL -> postgres(db, schema);
                    case ORACLE, DM -> oracle(db, schema);
                    case SQLSERVER -> sqlServer(db, schema);
                    default -> System.out.println("这一家这次不探。");
                }
            }
        }
    }

    private static void mysql(DbConnection db, String schema) {
        if (schema == null || schema.isBlank()) {
            // 连接没填默认库时，先看看整台实例上哪些库有存储过程——
            // 探不到东西和「这一家不支持」是两回事，别把前者当成后者
            System.out.println("== 没给模式名，先看整台实例上哪些库有例程");
            run(db, "SELECT ROUTINE_SCHEMA, COUNT(*) FROM information_schema.ROUTINES"
                    + " GROUP BY ROUTINE_SCHEMA");
            return;
        }
        System.out.println("== 例程清单");
        run(db, "SELECT ROUTINE_NAME, ROUTINE_TYPE, IS_DETERMINISTIC"
                + " FROM information_schema.ROUTINES"
                + " WHERE ROUTINE_SCHEMA = '" + schema + "'");

        System.out.println();
        System.out.println("== ROUTINE_DEFINITION 给的是整条语句还是只有函数体？");
        run(db, "SELECT ROUTINE_NAME, LEFT(ROUTINE_DEFINITION, 120)"
                + " FROM information_schema.ROUTINES"
                + " WHERE ROUTINE_SCHEMA = '" + schema + "'");

        System.out.println();
        System.out.println("== SHOW CREATE 能不能拿到完整语句（逐个对象，要权限）");
        try {
            var list = db.execute("SELECT ROUTINE_NAME, ROUTINE_TYPE"
                    + " FROM information_schema.ROUTINES"
                    + " WHERE ROUTINE_SCHEMA = '" + schema + "'", 5);
            if (list.rows().isEmpty()) {
                System.out.println("  （这个库里没有存储过程或函数）");
                return;
            }
            for (var row : list.rows()) {
                String kind = "FUNCTION".equalsIgnoreCase(row.get(1)) ? "FUNCTION" : "PROCEDURE";
                run(db, "SHOW CREATE " + kind + " `" + schema + "`.`" + row.get(0) + "`");
            }
        } catch (RuntimeException e) {
            System.out.println("  失败：" + e.getMessage());
        }
    }

    private static void postgres(DbConnection db, String schema) {
        System.out.println("== pg_get_functiondef 给的是不是整条 CREATE");
        run(db, "SELECT p.proname, left(pg_get_functiondef(p.oid), 160)"
                + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + schema + "'");
    }

    private static void oracle(DbConnection db, String schema) {
        System.out.println("== ALL_SOURCE 里有哪些例程");
        run(db, "SELECT DISTINCT NAME, TYPE FROM ALL_SOURCE"
                + " WHERE OWNER = '" + schema + "'"
                + " AND TYPE IN ('PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY')");

        System.out.println();
        System.out.println("== 源码是按行存的，看看头几行长什么样");
        run(db, "SELECT NAME, LINE, TEXT FROM ALL_SOURCE"
                + " WHERE OWNER = '" + schema + "'"
                + " AND TYPE IN ('PROCEDURE','FUNCTION') ORDER BY NAME, LINE");
    }

    private static void sqlServer(DbConnection db, String schema) {
        System.out.println("== sys.sql_modules 给的是不是整条 CREATE");
        run(db, "SELECT o.name, o.type_desc, LEFT(m.definition, 160)"
                + " FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id"
                + " JOIN sys.schemas s ON s.schema_id = o.schema_id"
                + " WHERE s.name = '" + schema + "'"
                + " AND o.type IN ('P','FN','IF','TF')");
    }

    private static void run(DbConnection db, String sql) {
        System.out.println("  " + sql);
        try {
            var result = db.execute(sql, 30);
            StringBuilder head = new StringBuilder("    列：");
            result.columns().forEach(c -> head.append(c.name()).append("  "));
            System.out.println(head);
            if (result.rows().isEmpty()) {
                System.out.println("    （0 行）");
                return;
            }
            result.rows().forEach(row -> {
                StringBuilder line = new StringBuilder("    ");
                for (int i = 0; i < result.columns().size(); i++) {
                    String v = row.get(i);
                    if (v != null) {
                        v = v.replace('\n', '¶').replace('\r', ' ');
                        if (v.length() > 150) {
                            v = v.substring(0, 150) + "…";
                        }
                    }
                    line.append('[').append(v).append("] ");
                }
                System.out.println(line);
            });
        } catch (RuntimeException e) {
            System.out.println("    失败：" + e.getMessage());
        }
    }
}
