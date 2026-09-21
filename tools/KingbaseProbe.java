import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;

/**
 * 人大金仓 V8R3：驱动比服务端新一代，元数据由我们自己接管之后，还能不能用。
 *
 * <h2>这台机器的处境</h2>
 * V8R3 的系统目录叫 {@code sys_catalog}，V8R6 起才改成 {@code pg_catalog}。
 * 而官方能拿到的最老驱动就是 V8R6，它按 {@code pg_catalog} 查——
 * 于是 {@code DatabaseMetaData} 上每个方法都报
 * {@code relation "PG_CATALOG.PG_namespace" does not exist}。
 * 12 个可获得的驱动全试过，没有一个能读它的元数据。
 *
 * <p>所以走的是另一条路：连接照旧，元数据改由 {@code KingbaseLegacy} 自己发 SQL。
 * 这个探针验的就是那条路真的走得通——<b>而且是走产品自己的 API</b>，
 * 不手写 SQL，否则验的只是我写的查询对不对，不是产品能不能用。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/KingbaseProbe.java [连接名]
 * </pre>
 */
public class KingbaseProbe {

    private static int pass;
    private static int fail;

    private static final String T = "PL_PROBE";
    private static final String CHILD = "PL_PROBE_CHILD";

    public static void main(String[] args) {
        ConnectionConfig cfg = find(args.length > 0 ? args[0] : null);
        if (cfg == null) {
            System.out.println("本地连接库里没有金仓连接。先在界面上建一个再跑这个。");
            return;
        }
        System.out.println("连接：" + cfg.host() + ":" + cfg.port()
                + "  库=" + cfg.database() + "  用户=" + cfg.user());

        try (DbConnection db = Connections.open(cfg)) {
            SqlDialect d = db.dialect();
            System.out.println("服务端：" + db.serverVersion());
            System.out.println("方言：  " + d.getClass().getSimpleName());
            System.out.println();

            String schema = defaultSchema(db);
            check("连上并认出模式", schema != null, "一个模式都没读到");
            if (schema == null) {
                return;
            }
            System.out.println("用模式：" + schema);

            clean(db, schema);
            setUp(db, schema);

            tables(db, schema);
            structure(db, schema);
            data(db, d, schema);

            clean(db, schema);
        } catch (RuntimeException e) {
            check("打开连接", false, first(e));
        }

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 各段

    private static String defaultSchema(DbConnection db) {
        List<SchemaInfo> schemas = db.listSchemas();
        check("模式列表读得出来", !schemas.isEmpty(), "0 个");
        // 系统模式不该混进来——金仓的 SYS_ 开头有七八个，不滤掉用户自己那个会淹没
        check("系统模式已经滤掉",
                schemas.stream().noneMatch(x -> x.name().toUpperCase().startsWith("SYS")),
                String.valueOf(schemas.stream().map(SchemaInfo::name).toList()));
        return schemas.stream().map(SchemaInfo::name)
                .filter(n -> n.equalsIgnoreCase("PUBLIC")).findFirst()
                .orElse(schemas.isEmpty() ? null : schemas.get(0).name());
    }

    private static void tables(DbConnection db, String schema) {
        List<TableInfo> list = db.listTables(schema);
        check("表列表里有刚建的表",
                list.stream().anyMatch(x -> x.name().equalsIgnoreCase(T)),
                "共 " + list.size() + " 个对象");
        check("视图也认得出来",
                list.stream().anyMatch(x -> x.name().equalsIgnoreCase("PL_PROBE_V")
                        && x.kind().name().contains("VIEW")),
                "没认出视图");
        check("序列也认得出来",
                list.stream().anyMatch(x -> x.name().equalsIgnoreCase("PL_PROBE_SEQ")
                        && x.kind().name().equals("SEQUENCE")),
                "没认出序列");
        check("表注释读得到",
                list.stream().filter(x -> x.name().equalsIgnoreCase(T))
                        .anyMatch(x -> "主表注释".equals(x.comment())),
                "注释是空的");
    }

    private static void structure(DbConnection db, String schema) {
        TableStructure st = db.describeTable(schema, T);
        check("列数对得上", st.columns().size() == 4,
                "读到 " + st.columns().size() + " 列");

        ColumnInfo id = column(st, "ID");
        ColumnInfo amt = column(st, "AMT");
        ColumnInfo n = column(st, "N");

        check("主键认得出来", id != null && id.primaryKey(), "ID 不是主键");
        check("自增认得出来（nextval 默认值）", id != null && id.autoIncrement(),
                id == null ? "没这列" : "autoIncrement=" + id.autoIncrement());
        // 精度丢了不会当场报错，要等某个 20 位的数被截断才发现
        check("NUMERIC 的精度和小数位都在",
                amt != null && amt.precision() == 20 && amt.scale() == 6,
                amt == null ? "没这列" : amt.precision() + "," + amt.scale());
        check("VARCHAR 的长度在", n != null && n.precision() == 20,
                n == null ? "没这列" : String.valueOf(n.precision()));
        check("非空认得出来", n != null && !n.nullable(), "N 被当成可空");
        check("列注释读得到", n != null && "名字".equals(n.comment()),
                n == null ? "没这列" : "[" + n.comment() + "]");
        check("类型没掉进 OTHER",
                id != null && id.category().name().equals("INTEGER")
                        && amt != null && amt.category().name().equals("EXACT_NUMERIC"),
                id == null ? "?" : id.category() + " / "
                        + (amt == null ? "?" : amt.category().toString()));

        List<IndexInfo> ix = st.indexes();
        check("索引读得到", ix.stream().anyMatch(x -> x.name().equalsIgnoreCase("PL_PROBE_IX")),
                String.valueOf(ix.stream().map(IndexInfo::name).toList()));
        // 复合索引的列序决定它能不能被用上，顺序错了会让人得出相反的判断
        IndexInfo multi = ix.stream()
                .filter(x -> x.name().equalsIgnoreCase("PL_PROBE_IX2")).findFirst().orElse(null);
        check("复合索引的列序是索引里的顺序，不是表里的列序",
                multi != null && multi.columns().size() == 2
                        && multi.columns().get(0).equalsIgnoreCase("N")
                        && multi.columns().get(1).equalsIgnoreCase("ID"),
                multi == null ? "没读到" : String.valueOf(multi.columns()));
    }

    private static void data(DbConnection db, SqlDialect d, String schema) {
        String q = d.qualify(schema, T);
        // 精度：这个数放进 double 就会走样
        QueryResult r = db.execute("SELECT ID, AMT, N FROM " + q + " ORDER BY ID", 10);
        String amt = r.rows().isEmpty() ? null : r.rows().get(0).get(1);
        check("读回来精度不丢", "123456789012.345678".equals(amt), "读到 " + amt);

        // 参数化写回——V8R3 上这条要靠简单查询协议才成
        List<ColumnInfo> cols = db.describeTable(schema, T).columns();
        ColumnInfo n = cols.stream().filter(c -> c.name().equalsIgnoreCase("N"))
                .findFirst().orElse(null);
        ColumnInfo id = cols.stream().filter(c -> c.name().equalsIgnoreCase("ID"))
                .findFirst().orElse(null);
        try {
            int changed = db.executeUpdate(
                    d.buildUpdate(schema, T, List.of(n), List.of(id)),
                    List.of("改过了", "1"));
            check("参数化写回成功", changed == 1, "改了 " + changed + " 行");
        } catch (RuntimeException e) {
            check("参数化写回成功", false, first(e));
        }
        String after = db.execute("SELECT N FROM " + q + " WHERE ID = 1", 1)
                .rows().stream().findFirst().map(x -> x.get(0)).orElse(null);
        check("写回去的值读得回来", "改过了".equals(after), "读到 " + after);

        // 写回一个高精度值，确认简单协议没把它变成浮点
        try {
            ColumnInfo amtCol = cols.stream().filter(c -> c.name().equalsIgnoreCase("AMT"))
                    .findFirst().orElse(null);
            db.executeUpdate(d.buildUpdate(schema, T, List.of(amtCol), List.of(id)),
                    List.of("999999999999.111111", "1"));
            String back = db.execute("SELECT AMT FROM " + q + " WHERE ID = 1", 1)
                    .rows().get(0).get(0);
            check("高精度值写回去也不走样", "999999999999.111111".equals(back), "读到 " + back);
        } catch (RuntimeException e) {
            check("高精度值写回去也不走样", false, first(e));
        }

        // 列类型冷门的表也得打得开。
        //
        // 这一条是补的：第一版只拿 INT / NUMERIC / VARCHAR 试过，而那几种驱动
        // 静态就认得，不会去查系统目录。碰上 regrole 这类它不认识的，
        // 它要查 pg_type 才能回答 getColumnType——于是整条 SELECT 报
        // 「relation "PG_CATALOG.PG_type" does not exist」，
        // 而报错里贴的是用户自己那条 SELECT，看上去像 SQL 写错了
        exoticTypes(db, d, schema);
        generateInto(db, schema);

        // 查询结果这一侧：驱动问不出来源表，应当如实说，而不是套「你的 SQL 里没有表」
        check("查询结果只读时给的是对得上的理由",
                r.isEditable() || String.valueOf(r.readOnlyReason()).contains("老一代"),
                "理由：" + r.readOnlyReason());
    }

    /**
     * 往列类型冷门的表里生成测试数据。
     *
     * <p>真实故障：{@code regclass} 列拿到了本工具编的 {@code partrel-1-bb}，
     * 服务端一 CAST 就报 {@code relation "partrel-1-bb" does not exist}。
     */
    private static void generateInto(DbConnection db, String schema) {
        String t = "PL_PROBE_GEN";
        exec(db, "DROP TABLE " + schema + "." + t);
        try {
            db.executeDdlBatch(List.of("CREATE TABLE " + schema + "." + t
                    + " (N VARCHAR(20), R REGCLASS)"));
        } catch (RuntimeException e) {
            System.out.println("  - 生成测试数据那一条跳过（建不出这种表）");
            return;
        }
        try {
            List<ColumnInfo> cols = db.describeTable(schema, t).columns();
            long rows = com.plainly.core.generate.DataGenerator.generate(
                    db, schema, t, cols, List.of(), 5, 100, 1L, n -> { });
            check("冷门类型的列不挡住生成测试数据", rows == 5, "写了 " + rows + " 行");
        } catch (RuntimeException e) {
            check("冷门类型的列不挡住生成测试数据", false, first(e));
        }
        exec(db, "DROP TABLE " + schema + "." + t);

        // 非空又编不出来的，要当场说清楚是哪一列、怎么办，
        // 而不是把数据库那句 not-null 违反甩给用户
        exec(db, "DROP TABLE " + schema + "." + t);
        try {
            db.executeDdlBatch(List.of("CREATE TABLE " + schema + "." + t
                    + " (N VARCHAR(20), R REGCLASS NOT NULL)"));
            List<ColumnInfo> cols = db.describeTable(schema, t).columns();
            com.plainly.core.generate.DataGenerator.generate(
                    db, schema, t, cols, List.of(), 5, 100, 1L, n -> { });
            check("非空的冷门列要拦下来并说清楚", false, "居然没报错");
        } catch (IllegalArgumentException e) {
            check("非空的冷门列要拦下来并说清楚",
                    e.getMessage().contains("生成方式") && e.getMessage().contains("固定值")
                            && e.getMessage().contains("R"),
                    e.getMessage());
        } catch (RuntimeException e) {
            check("非空的冷门列要拦下来并说清楚", false, "报的是别的错：" + first(e));
        }
        exec(db, "DROP TABLE " + schema + "." + t);
    }

    /** 建一张列类型冷门的表，确认打得开。建不出来就跳过，不硬凑。 */
    private static void exoticTypes(DbConnection db, SqlDialect d, String schema) {
        String t = "PL_PROBE_ODD";
        exec(db, "DROP TABLE " + schema + "." + t);
        try {
            db.executeDdlBatch(List.of("CREATE TABLE " + schema + "." + t
                    + " (A REGCLASS, B OID, C REGTYPE, N VARCHAR(10))"));
        } catch (RuntimeException e) {
            System.out.println("  - 冷门类型那一条跳过（这一家建不出这种表）");
            return;
        }
        try {
            db.executeDdlBatch(List.of("INSERT INTO " + schema + "." + t
                    + " (N) VALUES ('x')"));
            QueryResult r = db.execute("SELECT * FROM " + d.qualify(schema, t) + " LIMIT 200", 200);
            check("列类型冷门的表也打得开", r.columns().size() == 4,
                    "读到 " + r.columns().size() + " 列");
        } catch (RuntimeException e) {
            check("列类型冷门的表也打得开", false, first(e));
        }
        exec(db, "DROP TABLE " + schema + "." + t);
    }

    // ------------------------------------------------------------------ 铺垫

    private static void setUp(DbConnection db, String schema) {
        exec(db, "CREATE TABLE " + schema + "." + T + " (ID SERIAL PRIMARY KEY,"
                + " AMT NUMERIC(20,6), N VARCHAR(20) NOT NULL, TS TIMESTAMP)");
        exec(db, "COMMENT ON TABLE " + schema + "." + T + " IS '主表注释'");
        exec(db, "COMMENT ON COLUMN " + schema + "." + T + ".N IS '名字'");
        exec(db, "CREATE INDEX PL_PROBE_IX ON " + schema + "." + T + " (N)");
        // 故意让索引的列序和表里的列序相反
        exec(db, "CREATE INDEX PL_PROBE_IX2 ON " + schema + "." + T + " (N, ID)");
        exec(db, "CREATE TABLE " + schema + "." + CHILD + " (CID INT PRIMARY KEY,"
                + " PID INT REFERENCES " + schema + "." + T + "(ID))");
        exec(db, "CREATE VIEW " + schema + ".PL_PROBE_V AS SELECT ID, N FROM " + schema + "." + T);
        exec(db, "CREATE SEQUENCE " + schema + ".PL_PROBE_SEQ INCREMENT BY 3 START WITH 10");
        exec(db, "INSERT INTO " + schema + "." + T + " (AMT, N)"
                + " VALUES (123456789012.345678, '甲')");
    }

    private static void clean(DbConnection db, String schema) {
        exec(db, "DROP VIEW " + schema + ".PL_PROBE_V");
        exec(db, "DROP TABLE " + schema + "." + CHILD);
        exec(db, "DROP TABLE " + schema + "." + T);
        exec(db, "DROP SEQUENCE " + schema + ".PL_PROBE_SEQ");
    }

    // ------------------------------------------------------------------ 辅助

    private static ColumnInfo column(TableStructure st, String name) {
        return st.columns().stream().filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static void exec(DbConnection db, String sql) {
        try {
            db.executeDdlBatch(List.of(sql));
        } catch (RuntimeException ignored) {
            // 铺垫与清理，失败不计分
        }
    }

    private static ConnectionConfig find(String name) {
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry =
                    new ConnectionRegistry(store, CredentialStore.forCurrentPlatform());
            for (ConnectionConfig c : registry.listAll()) {
                if (c.type() == DbType.KINGBASE && (name == null || name.equals(c.name()))) {
                    return registry.resolvePassword(c);
                }
            }
        } catch (RuntimeException e) {
            System.out.println("读本地连接库失败：" + e.getMessage());
        }
        return null;
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
        return last.split(String.valueOf((char) 10))[0];
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

    private static List<String> unused() {
        return new ArrayList<>();
    }
}
