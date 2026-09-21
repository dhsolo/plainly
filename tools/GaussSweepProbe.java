import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TriggerEvent;
import com.plainly.driver.meta.DbObjects.TriggerTiming;
import com.plainly.driver.TypeCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 GaussDB 方言生成的每一条 SQL，都拿到真的 openGauss 上执行一遍。
 *
 * <h2>为什么要扫，而不是一条条等着撞</h2>
 * openGauss 自报服务端版本 {@code 9.2.4}——它是从 PG 10 之前分出去的。
 * 而我们的方言继承自 PostgreSQL，那一份是照着 PG 16 写的。
 * 于是「PG 10/11 之后才有的写法」会散落在各处：自增列是 PG 10 的
 * {@code AS IDENTITY}，触发器是 PG 11 的 {@code EXECUTE FUNCTION}……
 * 这些单看代码全都正常，只有发到这台 9.2 上才知道认不认。
 *
 * <p>一条条等用户撞出来代价太大：每一条都是「建表失败」「建触发器失败」这种
 * 拦在半路上的错。所以这里<b>一次把能生成的都发一遍</b>，让服务端自己说哪些不认。
 *
 * <p>只读查询直接执行；会改结构的都建在自己的临时对象上，跑完删掉。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/GaussSweepProbe.java [主机] [端口] [用户] [口令] [库] [模式]
 * </pre>
 */
public class GaussSweepProbe {

    private static final String NL = String.valueOf((char) 10);
    private static DbConnection db;
    private static SqlDialect d;
    private static String schema;
    private static int ok;
    private static final List<String> broken = new ArrayList<>();

    private static final String T = "pl_sweep";
    private static final String V = "pl_sweep_v";

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "192.168.237.153";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 15432;
        String user = args.length > 2 ? args[2] : "gaussdb";
        String password = args.length > 3 ? args[3] : "Gaussdb@123";
        String database = args.length > 4 ? args[4] : "postgres";
        schema = args.length > 5 ? args[5] : "public";

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("gauss-sweep").setType(DbType.GAUSSDB)
                .setHost(host).setPort(port)
                .setUser(user).setPassword(password).setDatabase(database);

        try (DbConnection c = Connections.open(cfg)) {
            db = c;
            d = c.dialect();
            System.out.println("服务端：" + c.serverVersion()
                    + "   方言：" + d.getClass().getSimpleName());
            System.out.println();

            setUp();
            sweepQueries();
            sweepDdl();
            tearDown();
        } catch (RuntimeException e) {
            System.out.println("连不上：" + e.getMessage());
            return;
        }

        System.out.println();
        System.out.println("通过 " + ok + " 条 · 服务端不认 " + broken.size() + " 条");
        if (!broken.isEmpty()) {
            System.out.println();
            System.out.println("下面这些要在 GaussDialect 里覆写：");
            broken.forEach(b -> System.out.println("  - " + b));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 铺垫

    private static void setUp() {
        tearDown();
        ddl("CREATE TABLE " + q(T) + " (\"id\" bigserial, \"name\" VARCHAR(50), "
                + "\"amt\" NUMERIC(20,6), \"at\" TIMESTAMP, PRIMARY KEY (\"id\"))");
        ddl("INSERT INTO " + q(T) + " (\"name\", \"amt\") VALUES ('jia', 1.5)");
        ddl("CREATE VIEW " + q(V) + " AS SELECT \"id\", \"name\" FROM " + q(T));
        ddl("CREATE INDEX \"pl_sweep_ix\" ON " + q(T) + " (\"name\")");
        ddl("CREATE SEQUENCE " + q("pl_sweep_seq") + " INCREMENT BY 3 START WITH 10");
        ddl("SELECT nextval('" + schema + ".pl_sweep_seq')");
    }

    private static void tearDown() {
        ddl("DROP VIEW IF EXISTS " + q(V));
        ddl("DROP TABLE IF EXISTS " + q(T));
        ddl("DROP TABLE IF EXISTS " + q(T + "_2"));
        ddl("DROP SEQUENCE IF EXISTS " + q("pl_sweep_seq"));
    }

    // -------------------------------------------------------------- 只读查询

    private static void sweepQueries() {
        System.out.println("---- 元数据查询 ----");
        query("tableRowCountQuery", d.tableRowCountQuery(schema));
        query("sequencesQuery", d.sequencesQuery(schema));
        query("sequenceAttributesQuery", d.sequenceAttributesQuery(schema));
        query("eventsQuery", d.eventsQuery(schema));
        query("routineListQuery", d.routineListQuery(schema));
        query("triggersQuery", d.triggersQuery(schema, T));
        query("triggerNameConflictQuery", d.triggerNameConflictQuery(schema, "pl_sweep_tg"));
        query("viewDefinitionQuery", d.viewDefinitionQuery(schema, V));
        for (ObjectKind kind : ObjectKind.values()) {
            // 这个方法内部按 kind 分支，只试一种会漏掉别的支——
            // 上一轮就是只试了 VIEW，而用到 pg_sequences 的恰恰是 SEQUENCE 那一支
            query("objectDefinitionQuery(" + kind + ")",
                    d.objectDefinitionQuery(kind, schema, kind == ObjectKind.SEQUENCE
                            ? "pl_sweep_id_seq" : V));
        }
        query("explainQuery", d.explainQuery("SELECT 1"));
        query("selectPage", d.selectPage(schema, T, "\"id\"", 10, 0));
        query("countRows", d.countRows(schema, T));
        query("limitOffset", "SELECT \"id\" FROM " + q(T) + " " + d.limitOffset(5, 2));
    }

    // ------------------------------------------------------------------ DDL

    private static void sweepDdl() {
        System.out.println();
        System.out.println("---- 结构变更 ----");

        // 触发器：PG 11 起才是 EXECUTE FUNCTION，之前是 EXECUTE PROCEDURE
        String trigger = d.createTriggerDdl(schema, T, "pl_sweep_tg",
                TriggerTiming.AFTER, TriggerEvent.INSERT, null);
        batch("createTriggerDdl", d.triggerStatements(trigger));
        batch("dropTriggerDdl", List.of(d.dropTriggerDdl(schema, T, "pl_sweep_tg")));

        batch("createOrReplaceViewDdl",
                List.of(d.createOrReplaceViewDdl(schema, V,
                        "SELECT \"id\", \"name\" FROM " + q(T))));
        batch("truncateTableDdl", List.of(d.truncateTableDdl(schema, T + "_never")), true);

        change("ddlFor(AddColumn)", new TableChange.AddColumn(col("memo", "VARCHAR", 30), null));
        change("ddlFor(ModifyColumn)", new TableChange.ModifyColumn(
                info("memo", "VARCHAR", 30), col("memo", "VARCHAR", 60)));
        change("ddlFor(RenameColumn)", new TableChange.RenameColumn(
                info("memo", "VARCHAR", 60), col("memo2", "VARCHAR", 60)));
        change("ddlFor(DropColumn)", new TableChange.DropColumn(info("memo2", "VARCHAR", 60)));
        change("ddlFor(ChangePrimaryKey)",
                new TableChange.ChangePrimaryKey(List.of("id"), List.of("id")));
        change("ddlFor(RenameTable)", new TableChange.RenameTable(T, T + "_2"));
        ddl("ALTER TABLE " + q(T + "_2") + " RENAME TO \"" + T + "\"");

        batch("dropIndexDdl", List.of(d.dropIndexDdl(schema, T, "pl_sweep_ix")));
        batch("addForeignKeyDdl / dropForeignKeyDdl", List.of(
                d.addForeignKeyDdl(schema, T, "pl_sweep_fk",
                        List.of("id"), schema, T, List.of("id"), "NO ACTION", "NO ACTION"),
                d.dropForeignKeyDdl(schema, T, "pl_sweep_fk")));
        batch("restartAutoIncrementDdl",
                List.of(d.restartAutoIncrementDdl(schema, T, "id", 50)));
        batch("createSchemaDdl", List.of(
                d.createSchemaDdl("pl_sweep_s", null, null), "DROP SCHEMA \"pl_sweep_s\""));

        // 参数化写回：占位符与 CAST 的写法也得服务端认
        prepared("buildInsert", d.buildInsert(schema, T, List.of(
                        info("name", "VARCHAR", 50), info("at", "TIMESTAMP", 0))),
                List.of("yi", "2026-01-01 00:00:00"));
        prepared("buildUpdate", d.buildUpdate(schema, T,
                        List.of(info("name", "VARCHAR", 50)), List.of(info("id", "INT8", 0))),
                List.of("bing", "1"));
        prepared("buildDelete", d.buildDelete(schema, T, List.of(info("id", "INT8", 0))),
                List.of("999999"));
    }

    // ------------------------------------------------------------------ 执行

    private static void query(String what, String sql) {
        if (sql == null || sql.isBlank()) {
            System.out.println("  - " + what + "（这一家没有，跳过）");
            return;
        }
        try {
            db.execute(sql, 5);
            pass(what);
        } catch (RuntimeException e) {
            miss(what, sql, e);
        }
    }

    private static void change(String what, TableChange c) {
        List<String> sqls = new ArrayList<>();
        d.ddlFor(schema, T, c).forEach(x -> sqls.add(x.sql()));
        batch(what, sqls);
    }

    private static void batch(String what, List<String> sqls) {
        batch(what, sqls, false);
    }

    private static void batch(String what, List<String> sqls, boolean allowMissingObject) {
        if (sqls.isEmpty()) {
            System.out.println("  - " + what + "（没生成语句，跳过）");
            return;
        }
        try {
            db.executeDdlBatch(sqls);
            pass(what);
        } catch (RuntimeException e) {
            // 「对象不存在」说明语法本身是通的，那不是这里要找的问题
            String m = String.valueOf(serverMessage(e));
            if (allowMissingObject && (m.contains("does not exist") || m.contains("不存在"))) {
                pass(what + "（语法通过）");
                return;
            }
            miss(what, String.join(NL, sqls), e);
        }
    }

    private static void prepared(String what, SqlDialect.PreparedSql p, List<String> values) {
        try {
            db.executeUpdate(p, values);
            pass(what);
        } catch (RuntimeException e) {
            miss(what, p.sql(), e);
        }
    }

    private static void ddl(String sql) {
        try {
            db.executeDdlBatch(List.of(sql));
        } catch (RuntimeException ignored) {
            // 铺垫用的，失败不计分
        }
    }

    // ------------------------------------------------------------------ 记分

    private static void pass(String what) {
        ok++;
        System.out.println("  " + what);
    }

    private static void miss(String what, String sql, RuntimeException e) {
        broken.add(what);
        System.out.println("X " + what);
        System.out.println("      " + firstLine(serverMessage(e)));
        for (String line : sql.split(NL)) {
            System.out.println("      | " + line);
        }
    }

    /** 剥到服务端那句话——外面包了好几层，最外层往往只说「第几条语句失败」。 */
    private static String serverMessage(Throwable t) {
        String last = String.valueOf(t.getMessage());
        int depth = 0;
        while (t.getCause() != null && depth++ < 6) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                last = t.getMessage();
            }
        }
        return last;
    }

    private static String firstLine(String s) {
        for (String p : s.split(NL)) {
            if (!p.isBlank()) {
                return p.trim();
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ 辅助

    private static String q(String table) {
        return d.qualify(schema, table);
    }

    private static ColumnDraft col(String name, String type, int precision) {
        ColumnDraft c = ColumnDraft.added(name);
        c.setNativeType(type);
        c.setPrecision(precision);
        c.setNullable(true);
        return c;
    }

    private static ColumnInfo info(String name, String type, int precision) {
        return new ColumnInfo(name, type,
                type.startsWith("TIMESTAMP") ? TypeCategory.TEMPORAL
                        : type.startsWith("INT") ? TypeCategory.INTEGER : TypeCategory.STRING,
                precision, 0, true, false, false, null, "", 1);
    }
}
