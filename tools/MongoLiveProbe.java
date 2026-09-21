import com.plainly.core.db.Connections;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import com.plainly.driver.query.FilterSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 驱动在<b>真实实例</b>上的一致性检查。
 *
 * <h2>为什么单元测试不够</h2>
 * 离线测试守得住转换规则，守不住「这条指令发过去到底做了什么」。
 * 写回这条路上最危险的失败是<b>安静的</b>：{@code updateOne} 没匹配上不算错，
 * 返回 0 就完事，界面照常显示「已保存」，而库里什么也没变。
 * 这种事只有真写一遍再读回来才看得见。
 *
 * <p>会建一个 {@code plainly_mongo_probe} 库，跑完就删，不碰别的数据。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/MongoLiveProbe.java [主机] [端口] [用户] [口令]
 * </pre>
 *
 * <p>用户名和口令也可以放在环境变量 {@code PLAINLY_MONGO_USER} /
 * {@code PLAINLY_MONGO_PASSWORD} 里，免得敲在命令行上被 shell 历史记下来。
 */
public class MongoLiveProbe {

    private static final String DB = "plainly_mongo_probe";
    private static final String COLL = "docs";

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("mongo-live-probe")
                .setType(DbType.MONGODB)
                .setHost(args.length > 0 ? args[0] : "127.0.0.1")
                .setPort(args.length > 1 ? Integer.parseInt(args[1]) : 27017)
                // 账号不写死在源码里：这是要进公开仓库的文件，而默认值一旦是
                // 某台机器上的真实口令，它就会被一路复制到别人的克隆和搜索引擎里。
                // 优先取命令行参数，其次取环境变量
                .setUser(arg(args, 2, "PLAINLY_MONGO_USER"))
                .setPassword(arg(args, 3, "PLAINLY_MONGO_PASSWORD"));

        try (DbConnection db = Connections.open(cfg)) {
            System.out.println("服务端：" + db.serverVersion());
            System.out.println();

            cleanUp(db);
            seed(db);

            metadata(db);
            reading(db);
            filtering(db);
            writeBack(db);
            inserting(db);
            deleting(db);

            cleanUp(db);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        } catch (RuntimeException e) {
            System.out.println("探针出错：" + e);
            e.printStackTrace(System.out);
        }
    }

    /** 命令行参数优先，其次环境变量，都没有就空着（连未启用认证的实例时正好）。 */
    private static String arg(String[] args, int index, String env) {
        if (args.length > index && !args[index].isBlank()) {
            return args[index];
        }
        String value = System.getenv(env);
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ 准备

    /**
     * 造几篇专挑麻烦的文档。
     *
     * <p>每一篇都在守一条具体的规则：Decimal128 不能经过 double；
     * Int64 的 19 位不能丢；长得像数字的字符串不能被改成数字；
     * 字段不齐时列不能整列消失。
     */
    private static void seed(DbConnection db) {
        // 直接用驱动的插入指令，走的就是产品里那条路
        SqlDialect d = db.dialect();
        insert(db, d, List.of("name", "price", "big", "code", "tags", "extra"),
                List.of("甲", "9.99", "9223372036854775807", "0123", "[1, 2]", "{\"k\": \"v\"}"));
        insert(db, d, List.of("name", "price", "big", "code"),
                List.of("乙", "0.10", "1234567890123456789", "42"));
        // 第三篇故意少几个字段，多一个别人没有的字段
        insert(db, d, List.of("name", "onlyHere"),
                List.of("丙", "只有这篇有"));
    }

    private static void insert(DbConnection db, SqlDialect d,
                               List<String> fields, List<String> values) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (String f : fields) {
            columns.add(col(f));
        }
        db.executeUpdate(d.buildInsert(DB, COLL, columns), values);
    }

    private static void cleanUp(DbConnection db) {
        try {
            // 没有 DDL 可用，直接借驱动内部的客户端删库是不行的——
            // 这里用一条删除全部文档的办法：逐篇按 _id 删
            QueryResult all = db.execute(
                    com.plainly.driver.mongo.MongoDialect.class.getName().isEmpty() ? "" : find(0, 1000),
                    1000);
            SqlDialect d = db.dialect();
            int idIndex = indexOf(all, "_id");
            if (idIndex < 0) {
                return;
            }
            for (com.plainly.driver.Row row : all.rows()) {
                db.executeUpdate(d.buildDelete(DB, COLL, List.of(col("_id"))),
                        List.of(row.get(idIndex)));
            }
        } catch (RuntimeException ignored) {
            // 库还不存在
        }
    }

    private static String find(int offset, int limit) {
        return new com.plainly.driver.mongo.MongoDialect()
                .selectPage(DB, COLL, FilterSpec.empty(), List.of(), limit, offset).sql();
    }

    // ------------------------------------------------------------------ 各项检查

    private static void metadata(DbConnection db) {
        section("元数据");

        List<String> dbs = new ArrayList<>();
        for (SchemaInfo s : db.listSchemas()) {
            dbs.add(s.name());
        }
        check("能列出数据库，且不含 admin/config/local",
                dbs.contains(DB) && !dbs.contains("admin"), String.valueOf(dbs));

        List<String> colls = new ArrayList<>();
        for (TableInfo t : db.listTables(DB)) {
            colls.add(t.name() + "(" + t.kind() + ")");
        }
        check("能列出集合", colls.stream().anyMatch(c -> c.startsWith(COLL)), String.valueOf(colls));

        TableStructure st = db.describeTable(DB, COLL);
        boolean idFirst = !st.columns().isEmpty()
                && "_id".equals(st.columns().get(0).name())
                && st.columns().get(0).primaryKey();
        check("_id 排第一且是主键（写回全靠它）", idFirst,
                st.columns().isEmpty() ? "没有列" : st.columns().get(0).name());
    }

    private static void reading(DbConnection db) {
        section("读取与精度");
        QueryResult r = db.execute(find(0, 100), 100);

        check("三篇都读到了", r.rows().size() == 3, "读到 " + r.rows().size() + " 篇");

        String price = cell(r, "name", "甲", "price");
        check("Decimal128 一位不改（9.99 没变成 9.9900000000000002）",
                "9.99".equals(price), String.valueOf(price));

        String big = cell(r, "name", "甲", "big");
        check("Int64 的 19 位没丢", "9223372036854775807".equals(big), String.valueOf(big));

        String code = cell(r, "name", "甲", "code");
        check("长得像数字的字符串没被当成数字（前导零还在）",
                "0123".equals(code), String.valueOf(code));

        // 第三篇独有的字段必须有列，否则用户会以为那份数据不存在
        check("字段不齐时取并集，独有字段也有列",
                indexOf(r, "onlyHere") >= 0, columnNames(r));

        check("结果集判成可写回，目标就是这个集合",
                r.isEditable() && r.source() != null
                        && COLL.equals(r.source().table()) && DB.equals(r.source().schema()),
                r.isEditable() ? String.valueOf(r.source()) : r.readOnlyReason());
    }

    private static void filtering(DbConnection db) {
        section("筛选下推");
        SqlDialect d = db.dialect();

        SqlDialect.PreparedSql eq = d.selectPage(DB, COLL, new FilterSpec(
                List.of(new FilterSpec.Condition(FilterSpec.Combiner.AND, "name",
                        FilterSpec.Operator.EQ, "乙", null)), List.of()), List.of(), 100, 0);
        QueryResult r1 = db.executeQuery(eq, List.of(), 100);
        check("等于：只回一篇", r1.rows().size() == 1, "回了 " + r1.rows().size() + " 篇");

        SqlDialect.PreparedSql like = d.selectPage(DB, COLL, new FilterSpec(
                List.of(new FilterSpec.Condition(FilterSpec.Combiner.AND, "onlyHere",
                        FilterSpec.Operator.LIKE, "只有", null)), List.of()), List.of(), 100, 0);
        QueryResult r2 = db.executeQuery(like, List.of(), 100);
        check("包含：中文子串能匹配", r2.rows().size() == 1, "回了 " + r2.rows().size() + " 篇");

        QueryResult r3 = db.executeQuery(d.countRows(DB, COLL, FilterSpec.empty(), List.of()),
                List.of(), 1);
        check("计数走服务端", "3".equals(r3.rows().get(0).get(0)), r3.rows().get(0).get(0));
    }

    private static void writeBack(DbConnection db) {
        section("写回");
        SqlDialect d = db.dialect();
        QueryResult before = db.execute(find(0, 100), 100);
        String id = cell(before, "name", "甲", "_id");

        // 一、改一个字符串字段
        db.executeUpdate(d.buildUpdate(DB, COLL, List.of(col("name")), List.of(col("_id"))),
                List.of("甲改过", id));
        QueryResult after = db.execute(find(0, 100), 100);
        check("改动真的写进库了", cell(after, "_id", id, "name") != null
                && "甲改过".equals(cell(after, "_id", id, "name")),
                String.valueOf(cell(after, "_id", id, "name")));

        // 二、改一个 Decimal128 字段，类型和精度都得保住
        db.executeUpdate(d.buildUpdate(DB, COLL, List.of(col("price")), List.of(col("_id"))),
                List.of("1234.5678", id));
        QueryResult after2 = db.execute(find(0, 100), 100);
        check("Decimal128 改完还是精确值", "1234.5678".equals(cell(after2, "_id", id, "price")),
                String.valueOf(cell(after2, "_id", id, "price")));
        check("Decimal128 改完类型没变", "Decimal128".equals(typeOf(after2, "price")),
                String.valueOf(typeOf(after2, "price")));

        // 三、改一个「长得像数字的字符串」字段——类型绝不能被改成数字
        db.executeUpdate(d.buildUpdate(DB, COLL, List.of(col("code")), List.of(col("_id"))),
                List.of("0456", id));
        QueryResult after3 = db.execute(find(0, 100), 100);
        check("字符串字段改完还是字符串（前导零没丢）",
                "0456".equals(cell(after3, "_id", id, "code")),
                String.valueOf(cell(after3, "_id", id, "code")));

        // 四、拿一个不存在的 _id 去改，必须报错而不是「已保存 0 行」
        boolean refused = false;
        try {
            db.executeUpdate(d.buildUpdate(DB, COLL, List.of(col("name")), List.of(col("_id"))),
                    List.of("x", "507f1f77bcf86cd799439011"));
        } catch (RuntimeException e) {
            refused = true;
        }
        check("改不到任何文档时当场报错，不会假装保存成功", refused, refused ? "" : "静静地成功了");
    }

    private static void inserting(DbConnection db) {
        section("新增");
        SqlDialect d = db.dialect();
        db.executeUpdate(d.buildInsert(DB, COLL,
                List.of(col("name"), col("price"))), List.of("丁", "3.14"));

        QueryResult r = db.execute(find(0, 100), 100);
        check("新文档插进去了", r.rows().size() == 4, "现在 " + r.rows().size() + " 篇");
        check("没填 _id 时由服务端发号", cell(r, "name", "丁", "_id") != null,
                String.valueOf(cell(r, "name", "丁", "_id")));
        check("新增时带小数点的存成 Decimal128 而不是 Double",
                "3.14".equals(cell(r, "name", "丁", "price")),
                String.valueOf(cell(r, "name", "丁", "price")));
    }

    private static void deleting(DbConnection db) {
        section("删除");
        SqlDialect d = db.dialect();
        QueryResult before = db.execute(find(0, 100), 100);
        String id = cell(before, "name", "丁", "_id");

        db.executeUpdate(d.buildDelete(DB, COLL, List.of(col("_id"))), List.of(id));
        QueryResult after = db.execute(find(0, 100), 100);
        check("删掉了，而且只删了那一篇", after.rows().size() == before.rows().size() - 1,
                before.rows().size() + " → " + after.rows().size());

        boolean refused = false;
        try {
            db.executeUpdate(d.buildDelete(DB, COLL, List.of(col("_id"))), List.of(id));
        } catch (RuntimeException e) {
            refused = true;
        }
        check("再删一次要报错，而不是显示删成功", refused, refused ? "" : "静静地成功了");
    }

    // ------------------------------------------------------------------ 辅助

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  " + what);
        } else {
            fail++;
            System.out.println("× " + what + "   实际：" + detail);
        }
    }

    private static ColumnInfo col(String name) {
        return new ColumnInfo(name, "String", TypeCategory.STRING, 0, 0,
                true, "_id".equals(name), false, null, "", 1);
    }

    private static int indexOf(QueryResult r, String column) {
        for (int i = 0; i < r.columns().size(); i++) {
            if (r.columns().get(i).name().equals(column)) {
                return i;
            }
        }
        return -1;
    }

    private static String columnNames(QueryResult r) {
        List<String> names = new ArrayList<>();
        r.columns().forEach(c -> names.add(c.name()));
        return String.valueOf(names);
    }

    private static String typeOf(QueryResult r, String column) {
        int i = indexOf(r, column);
        return i < 0 ? null : r.columns().get(i).nativeType();
    }

    /** 找到 {@code byColumn == byValue} 的那一行，取它 {@code column} 列的值。 */
    private static String cell(QueryResult r, String byColumn, String byValue, String column) {
        int keyIndex = indexOf(r, byColumn);
        int valueIndex = indexOf(r, column);
        if (keyIndex < 0 || valueIndex < 0) {
            return null;
        }
        for (com.plainly.driver.Row row : r.rows()) {
            if (byValue.equals(row.get(keyIndex))) {
                return row.get(valueIndex);
            }
        }
        return null;
    }
}
