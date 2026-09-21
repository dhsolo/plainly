import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.TriggerEvent;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import com.plainly.driver.meta.DbObjects.TriggerTiming;

import java.util.List;

/**
 * 建触发器这条路，在 PostgreSQL 家族上真的走得通吗。
 *
 * <h2>验三件事，第二件最容易被忽略</h2>
 * <ol>
 *   <li><b>建得出来。</b>openGauss 的内核是 PG 9.2，挂载子句只认
 *       {@code EXECUTE PROCEDURE}；PG 11 之后的 {@code EXECUTE FUNCTION}
 *       在那边是语法错。</li>
 *   <li><b>用户写的触发器体真的进去了。</b>这一条原来是坏的：方言接了
 *       {@code body} 参数却<b>一个字都没用</b>，生成的函数永远只是
 *       {@code RETURN NEW}。表现是触发器建得好好的、什么也不做——
 *       比报错难发现得多。</li>
 *   <li><b>真被触发时确实执行了。</b>光看 DDL 看不出第 2 条，
 *       所以这里插一行真数据，去看触发器有没有往日志表里写。</li>
 * </ol>
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/TriggerProbe.java
 * </pre>
 */
public class TriggerProbe {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        run("PostgreSQL 16", DbType.POSTGRESQL, "192.168.237.153", 5432,
                "postgres", "postgres", "postgres");
        run("openGauss 5.0", DbType.GAUSSDB, "192.168.237.153", 15432,
                "gaussdb", "Gaussdb@123", "postgres");

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void run(String label, DbType type, String host, int port,
                            String user, String password, String database) {
        System.out.println("=========== " + label);
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("trigger-probe").setType(type)
                .setHost(host).setPort(port)
                .setUser(user).setPassword(password).setDatabase(database);

        String schema = "public";
        String t = "pl_tg_src";
        String log = "pl_tg_log";
        String name = "pl_tg_probe";

        try (DbConnection db = Connections.open(cfg)) {
            SqlDialect d = db.dialect();
            System.out.println("服务端：" + db.serverVersion() + "   方言："
                    + d.getClass().getSimpleName());

            clean(db, d, schema, t, log, name);
            exec(db, "CREATE TABLE " + d.qualify(schema, t)
                    + " (\"id\" INTEGER, \"n\" VARCHAR(20))");
            exec(db, "CREATE TABLE " + d.qualify(schema, log)
                    + " (\"note\" VARCHAR(50))");

            // 用户在编辑器里写的那一段：往日志表插一条。
            // 这里故意不用模板默认值——要验的正是「用户写的东西有没有进去」
            String body = "BEGIN" + nl()
                    + "    INSERT INTO " + d.qualify(schema, log)
                    + " (\"note\") VALUES ('触发器跑过了 ' || NEW.\"n\");" + nl()
                    + "    RETURN NEW;" + nl()
                    + "END;";

            String ddl = d.createTriggerDdl(schema, t, name,
                    TriggerTiming.AFTER, TriggerEvent.INSERT, body);
            System.out.println();
            System.out.println(ddl);
            System.out.println();

            check("生成的语句里带着用户写的那段", ddl.contains("触发器跑过了"),
                    "用户的 body 被丢掉了");

            List<String> statements = d.triggerStatements(ddl);
            System.out.println("拆成 " + statements.size() + " 条发出去");
            try {
                db.executeDdlBatch(statements);
                check("建触发器成功", true, "");
            } catch (RuntimeException e) {
                check("建触发器成功", false, first(e));
                clean(db, d, schema, t, log, name);
                return;
            }

            // 元数据里读得到
            List<TriggerInfo> found = db.listTriggers(schema, t);
            check("在触发器列表里看得到", found.stream().anyMatch(x -> x.name().equals(name)),
                    String.valueOf(found.size()) + " 个");

            // 真插一行，看触发器有没有干活
            exec(db, "INSERT INTO " + d.qualify(schema, t)
                    + " (\"id\", \"n\") VALUES (1, '甲')");
            String note = db.execute("SELECT \"note\" FROM " + d.qualify(schema, log), 5)
                    .rows().stream().findFirst().map(r -> r.get(0)).orElse(null);
            // 这一条是全篇的要害：DDL 长得对不代表触发器真的在做用户写的事
            check("触发器真的执行了用户写的逻辑", "触发器跑过了 甲".equals(note),
                    note == null ? "日志表里什么都没有" : "读到 [" + note + "]");

            // 「修改触发器」打开时，编辑框里填的是 TriggerInfo.action()。
            //
            // 原来那一栏取的是 information_schema 的 action_statement，
            // 而 PG 上那只有一句 EXECUTE FUNCTION f()——逻辑在函数里。
            // 用户看到的不是自己写的东西；更糟的是存回去时，那一句会被当成
            // 函数体塞进 $$ ... $$，把原来的逻辑顶掉，而且不报错
            TriggerInfo back = db.listTriggers(schema, t).stream()
                    .filter(x -> x.name().equals(name)).findFirst().orElse(null);
            String shown = back == null || back.action() == null ? "" : back.action().strip();
            check("修改触发器时填的是用户写的那段，不是 EXECUTE FUNCTION",
                    shown.contains("触发器跑过了") && !shown.toUpperCase().startsWith("EXECUTE"),
                    "编辑框里会出现 [" + shown + "]");
            // 读出来的要能原样写回去：拿它当 body 再生成一次，内容不能走样
            check("读出来的内容能原样写回去",
                    d.createTriggerDdl(schema, t, name, TriggerTiming.AFTER,
                            TriggerEvent.INSERT, shown).contains("触发器跑过了"),
                    "写回去之后逻辑没了");

            // 重建：改一段逻辑再存一次。这是「改已有触发器」那条路
            //
            // 原来这一步是坏的：对话框按「整段是不是以 CREATE OR REPLACE 开头」
            // 判断要不要先 DROP，而 PG 那段开头正是 CREATE OR REPLACE FUNCTION，
            // 于是判成「不必删」，接着 CREATE TRIGGER 报 already exists
            String body2 = body.replace("触发器跑过了", "改过之后跑的");
            String ddl2 = d.createTriggerDdl(schema, t, name,
                    TriggerTiming.AFTER, TriggerEvent.INSERT, body2);
            boolean replaces = com.plainly.core.sql.TriggerNames.replacesExisting(ddl2);
            check("认得出 PG 家族不会静默覆盖（所以要先 DROP）", !replaces,
                    "判成了「会覆盖」，于是不发 DROP，接着必然报 already exists");

            java.util.List<String> rebuild = new java.util.ArrayList<>();
            if (!replaces) {
                rebuild.add(d.dropTriggerDdl(schema, t, name));
            }
            rebuild.addAll(d.triggerStatements(ddl2));
            try {
                db.executeDdlBatch(rebuild);
                check("重建同名触发器成功", true, "");
            } catch (RuntimeException e) {
                check("重建同名触发器成功", false, first(e));
            }
            exec(db, "DELETE FROM " + d.qualify(schema, log));
            exec(db, "INSERT INTO " + d.qualify(schema, t)
                    + " (\"id\", \"n\") VALUES (2, '乙')");
            String note2 = db.execute("SELECT \"note\" FROM " + d.qualify(schema, log), 5)
                    .rows().stream().findFirst().map(r -> r.get(0)).orElse(null);
            check("重建之后跑的是新逻辑", "改过之后跑的 乙".equals(note2),
                    note2 == null ? "日志表里什么都没有" : "读到 [" + note2 + "]");

            // 删得掉
            try {
                db.executeDdlBatch(List.of(d.dropTriggerDdl(schema, t, name)));
                check("删触发器成功", true, "");
            } catch (RuntimeException e) {
                check("删触发器成功", false, first(e));
            }

            clean(db, d, schema, t, log, name);
        } catch (RuntimeException e) {
            check("连接 " + label, false, first(e));
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ 辅助

    private static String nl() {
        return String.valueOf((char) 10);
    }

    private static void clean(DbConnection db, SqlDialect d, String schema,
                              String t, String log, String name) {
        exec(db, "DROP TABLE IF EXISTS " + d.qualify(schema, t));
        exec(db, "DROP TABLE IF EXISTS " + d.qualify(schema, log));
        exec(db, "DROP FUNCTION IF EXISTS " + d.qualify(schema, name + "_fn") + "()");
    }

    private static void exec(DbConnection db, String sql) {
        try {
            db.executeDdlBatch(List.of(sql));
        } catch (RuntimeException ignored) {
            // 铺垫和清理，失败不计分
        }
    }

    private static String first(RuntimeException e) {
        Throwable t = e;
        String last = String.valueOf(t.getMessage());
        int depth = 0;
        while (t.getCause() != null && depth++ < 6) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                last = t.getMessage();
            }
        }
        return last.split(nl())[0];
    }

    private static void check(String what, boolean okay, String detail) {
        if (okay) {
            pass++;
            System.out.println("  " + what);
        } else {
            fail++;
            System.out.println("X " + what + "   实际：" + detail);
        }
    }
}
