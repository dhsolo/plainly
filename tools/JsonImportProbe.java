import com.plainly.core.db.Connections;
import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.core.imports.ImportOptions;
import com.plainly.core.imports.Importer;
import com.plainly.core.imports.JsonRows;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.ColumnInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 导入：导出一份再导回来，逐格对。
 *
 * <p>这条链路上最要紧的是<b>精度</b>：一个 30 位的十进制数，只要中途经过一次
 * double，回来就变了——而且不会报错，两张表看起来都好好的。所以这里不看
 * 「导入成功了几行」，而是把两张表逐格比对，任何一个字符不同就打出来。
 *
 * <p>顺带验三件光看代码看不出的事：字段多寡不齐的对象、JSON null 落进可空列、
 * NDJSON（每行一个对象）。
 *
 * <p>用临时 H2 库，<b>不碰</b> demo 目录和任何用户的库。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/JsonImportProbe.java</pre>
 */
public class JsonImportProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("plainly-json-probe");
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe")
                .setType(DbType.H2)
                .setFilePath(dir.resolve("probe").toString());

        try (DbConnection conn = Connections.open(cfg)) {
            roundTrip(conn, dir);
            unevenFields(conn, dir);
            ndjson(conn, dir);
        } finally {
            clean(dir);
        }
    }

    // ---------------------------------------------------------- 导出再导回

    private static void roundTrip(DbConnection conn, Path dir) {
        System.out.println("=== 导出 JSON 再导回来 ===");
        conn.executeDdlBatch(List.of(
                "CREATE TABLE SRC (ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(200), FLAG BOOLEAN)",
                "CREATE TABLE DST (ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(200), FLAG BOOLEAN)"));
        // 第一行那个数字正是会被 double 毁掉的：28 位有效数字
        conn.execute("INSERT INTO SRC VALUES (1, 1234567890123456789012345678.9012345678,"
                + " '带\"引号\"和,逗号', TRUE)", 0);
        conn.execute("INSERT INTO SRC VALUES (2, -0.0000000001, '换行\n在这里', FALSE)", 0);
        conn.execute("INSERT INTO SRC VALUES (3, NULL, NULL, NULL)", 0);

        Path file = dir.resolve("out.json");
        long written = Exporters.export(
                RowSource.ofTable(conn, "PUBLIC", "SRC", "ID", 500, 3L),
                new ExportOptions().setFormat(ExportOptions.Format.JSON)
                        .setTarget(file).setCharset(StandardCharsets.UTF_8),
                n -> { });
        System.out.println("  导出 " + written + " 行 -> " + file.getFileName());
        System.out.println("  文件字段名：" + JsonRows.fieldNames(file, StandardCharsets.UTF_8));

        Importer.Result result = importInto(conn, file, "DST");
        System.out.println("  导入：读 " + result.read() + "，写 " + result.written()
                + "，失败 " + result.failed());
        result.problems().forEach(p -> System.out.println("    ! " + p));

        compare(conn, "SRC", "DST", List.of("ID", "AMOUNT", "NOTE", "FLAG"));
    }

    /** 逐格比对两张表，任何一格不同都打出来。 */
    private static void compare(DbConnection conn, String a, String b, List<String> columns) {
        int mismatches = 0;
        for (String column : columns) {
            String diff = conn.scalar(
                    "SELECT COUNT(*) FROM " + a + " s JOIN " + b + " d ON s.ID = d.ID"
                            + " WHERE s." + column + " IS DISTINCT FROM d." + column);
            if (!"0".equals(diff)) {
                System.out.println("  !! " + column + " 有 " + diff + " 行对不上");
                mismatches++;
            }
        }
        String counts = conn.scalar("SELECT COUNT(*) FROM " + a) + "/"
                + conn.scalar("SELECT COUNT(*) FROM " + b);
        System.out.println("  行数 " + counts + "，逐格比对"
                + (mismatches == 0 ? "全部一致" : "有 " + mismatches + " 列对不上"));
        // 把那个长数字原样打出来，眼睛也看一遍
        System.out.println("  源  AMOUNT = " + conn.scalar("SELECT AMOUNT FROM " + a + " WHERE ID=1"));
        System.out.println("  回  AMOUNT = " + conn.scalar("SELECT AMOUNT FROM " + b + " WHERE ID=1"));
    }

    // ---------------------------------------------------------- 字段不齐

    private static void unevenFields(DbConnection conn, Path dir) throws Exception {
        System.out.println();
        System.out.println("=== 对象字段多寡不齐 ===");
        conn.executeDdlBatch(List.of(
                "CREATE TABLE UNEVEN (A VARCHAR(20), B VARCHAR(20), C VARCHAR(20))"));
        Path file = dir.resolve("uneven.json");
        Files.writeString(file,
                "[{\"A\":\"1\"},{\"A\":\"2\",\"B\":\"x\"},{\"A\":\"3\",\"C\":\"y\",\"B\":null}]",
                StandardCharsets.UTF_8);

        System.out.println("  字段名并集：" + JsonRows.fieldNames(file, StandardCharsets.UTF_8)
                + "（只看第一个对象的话就只有 [A]，B 和 C 会被整列丢掉）");
        Importer.Result result = importInto(conn, file, "UNEVEN");
        System.out.println("  导入：读 " + result.read() + "，写 " + result.written());
        System.out.println("  库里：" + conn.scalar(
                "SELECT LISTAGG(COALESCE(A,'-')||'/'||COALESCE(B,'-')||'/'||COALESCE(C,'-'), ' | ')"
                        + " WITHIN GROUP (ORDER BY A) FROM UNEVEN"));
    }

    // ---------------------------------------------------------- NDJSON

    private static void ndjson(DbConnection conn, Path dir) throws Exception {
        System.out.println();
        System.out.println("=== 每行一个对象（NDJSON） ===");
        conn.executeDdlBatch(List.of("CREATE TABLE LOGS (LEVEL VARCHAR(10), MSG VARCHAR(100))"));
        Path file = dir.resolve("logs.ndjson");
        Files.writeString(file,
                "{\"LEVEL\":\"INFO\",\"MSG\":\"起来了\"}\n"
                        + "{\"LEVEL\":\"WARN\",\"MSG\":\"慢查询\"}\n"
                        + "{\"LEVEL\":\"ERROR\",\"MSG\":\"连不上\"}\n",
                StandardCharsets.UTF_8);
        Importer.Result result = importInto(conn, file, "LOGS");
        System.out.println("  导入：读 " + result.read() + "，写 " + result.written());
        System.out.println("  库里：" + conn.scalar(
                "SELECT LISTAGG(LEVEL||':'||MSG, ' | ') WITHIN GROUP (ORDER BY LEVEL) FROM LOGS"));
    }

    // ---------------------------------------------------------- 工具

    /** 按字段名同名映射导进去，走的就是界面上那条路。 */
    private static Importer.Result importInto(DbConnection conn, Path file, String table) {
        List<ColumnInfo> target = conn.columnsOf("PUBLIC", table);
        List<String> names = JsonRows.fieldNames(file, StandardCharsets.UTF_8);
        List<ImportOptions.ColumnMapping> mappings = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String source = names.get(i);
            String matched = target.stream().map(ColumnInfo::name)
                    .filter(n -> n.equalsIgnoreCase(source)).findFirst().orElse(null);
            mappings.add(new ImportOptions.ColumnMapping(i, source, matched));
        }
        ImportOptions options = new ImportOptions()
                .setSource(file)
                .setFormat(ImportOptions.Format.JSON)
                .setCharset(StandardCharsets.UTF_8)
                .setNumberMode(ImportOptions.NumberMode.TEXT)
                .setWriteErrorFile(false)
                // 无主键的表上「跳过冲突行」无从判定，直接插
                .setConflictPolicy(com.plainly.driver.query.ConflictPolicy.ABORT)
                .setMappings(mappings);
        return Importer.run(conn, "PUBLIC", table, target, options, n -> { });
    }

    private static void clean(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清不掉不影响结论
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
