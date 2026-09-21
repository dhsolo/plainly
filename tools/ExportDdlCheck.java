import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.SqlScript;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 「同时导出建表语句」的落地检查。
 *
 * <p>导出的 .sql 号称能直接拿去建库回灌，那就真拿去跑一遍：
 * 导出 → 在一个全新的空库里逐条执行 → 比对行数和几个值。
 * 只看文件头几行像不像 SQL，是证明不了它能跑的。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ExportDdlCheck.java</pre>
 */
public class ExportDdlCheck {

    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "ORDERS";

    public static void main(String[] args) throws Exception {
        Path out = Paths.get("C:/Users/root/AppData/Local/Temp/claude/D--dh/"
                + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/export-ddl.sql");
        Files.deleteIfExists(out);

        DbConnection src = open(System.getProperty("plainly.home", ".") + "/demo/plainly-demo");
        SqlDialect dialect = src.dialect();
        TableStructure structure = src.describeTable(SCHEMA, TABLE);

        ExportOptions options = new ExportOptions()
                .setFormat(ExportOptions.Format.SQL_INSERT)
                .setTableName(TABLE)
                .setDialect(dialect)
                .setIncludeDdl(true)
                .setDdl(ddlOf(dialect, structure))
                .setTarget(out);

        long rows = Exporters.export(
                RowSource.ofTable(src, SCHEMA, TABLE, null, 500, -1), options, n -> { });
        String script = Files.readString(out, StandardCharsets.UTF_8);
        List<String> statements = SqlScript.split(script);
        System.out.println("导出 " + rows + " 行，脚本切出 " + statements.size() + " 条语句");
        System.out.println("--- 文件开头 ---");
        script.lines().limit(8).forEach(l -> System.out.println("  " + l));

        // 拿去空库里真跑一遍
        Path fresh = Paths.get("C:/Users/root/AppData/Local/Temp/claude/D--dh/"
                + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/reimport");
        for (String suffix : new String[] {".mv.db", ".trace.db"}) {
            Files.deleteIfExists(Paths.get(fresh + suffix));
        }
        DbConnection dst = open(fresh.toString());

        boolean ok = true;
        for (int i = 0; i < statements.size(); i++) {
            try {
                dst.execute(statements.get(i), 0);
            } catch (RuntimeException e) {
                System.out.println("第 " + (i + 1) + " 条执行失败：" + statements.get(i));
                System.out.println("  " + e.getMessage());
                ok = false;
                break;
            }
        }

        if (ok) {
            QueryResult before = src.execute("SELECT COUNT(*) FROM " + TABLE, 1);
            QueryResult after = dst.execute("SELECT COUNT(*) FROM " + TABLE, 1);
            String a = before.rows().get(0).get(0);
            String b = after.rows().get(0).get(0);
            System.out.println("原库 " + a + " 行，回灌后 " + b + " 行");
            ok = a.equals(b);

            // 逐行比对高精度列——一位没差才算数，这是整个项目的底线
            String q = "SELECT ID, AMOUNT FROM " + TABLE + " ORDER BY ID";
            List<String> left = values(src.execute(q, 0));
            List<String> right = values(dst.execute(q, 0));
            if (!left.equals(right)) {
                int i = 0;
                while (i < Math.min(left.size(), right.size()) && left.get(i).equals(right.get(i))) {
                    i++;
                }
                System.out.println("数值对不上，第 " + (i + 1) + " 行：原 "
                        + (i < left.size() ? left.get(i) : "(缺)") + " → 回灌 "
                        + (i < right.size() ? right.get(i) : "(缺)"));
                ok = false;
            } else {
                System.out.println("逐行比对 " + left.size() + " 行 AMOUNT，一位没差");
            }
        }

        System.out.println(ok ? "通过：导出的脚本能建库回灌" : "失败");
        src.close();
        dst.close();
        System.exit(ok ? 0 : 1);
    }

    private static List<String> values(QueryResult r) {
        return r.rows().stream().map(row -> row.get(0) + "|" + row.get(1)).toList();
    }

    private static String ddlOf(SqlDialect dialect, TableStructure structure) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Plainly 导出于 ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append('\n');
        sb.append("-- 表：").append(dialect.qualify(SCHEMA, TABLE)).append('\n');
        sb.append(dialect.createTableDdl(SCHEMA, structure)).append(";\n");
        for (IndexInfo index : structure.indexes()) {
            if (index.primary()) {
                continue;
            }
            sb.append(dialect.createIndexDdl(SCHEMA, TABLE, index)).append(";\n");
        }
        return sb.toString();
    }

    private static DbConnection open(String file) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("check").setType(DbType.H2).setFilePath(file)
                .setUser("sa").setPassword("");
        cfg.setId("check-" + file.hashCode());
        return JdbcConnections.open(cfg);
    }
}
