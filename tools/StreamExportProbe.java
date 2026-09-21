import com.plainly.core.db.Connections;
import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 流式导出到底流不流：拿内存说话。
 *
 * <p>「加了 setFetchSize 就是流式」是一句听起来对、验起来经常错的话——
 * MySQL 给正数 fetchSize 照样把整个结果集拉进客户端内存，PostgreSQL 不关自动提交
 * 也一样，两者都<b>不报错</b>，只是某一刻 OOM。所以不能看代码，只能看内存。
 *
 * <p>做法：造一个远大于堆的结果集（一百万行 × 200 字符 ≈ 400MB），在
 * {@code -Xmx256m} 下先用原来的全量取法跑一遍，再用流式跑一遍。
 * 前者必须 OOM，后者必须跑完——前者不 OOM 就说明这个用例没造够大，
 * 结论不作数。
 *
 * <p>用 H2 的 {@code SYSTEM_RANGE}，不建表、不落盘，也不碰任何用户的库。
 *
 * <pre>java -Xmx256m -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/StreamExportProbe.java</pre>
 */
public class StreamExportProbe {

    /**
     * 一百万行 × 200 字符，远超 -Xmx256m。
     *
     * <p>填充值必须<b>逐行不同</b>（拿 X 去拼），不能用 {@code RPAD('v',200,'x')}
     * 那样的常量：常量的话每一行拿到的是同一个 String 对象，一百万行只占十几兆，
     * 对照组根本不会 OOM——第一版就是这么写的，于是「全量取回也没事」，
     * 整个用例白做。
     */
    private static final String BIG_QUERY =
            "SELECT X AS ID, RPAD(CAST(X AS VARCHAR), 200, 'x') AS PAD"
                    + " FROM SYSTEM_RANGE(1, 1000000)";

    public static void main(String[] args) throws Exception {
        System.out.println("堆上限 " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");

        Path dir = Files.createTempDirectory("plainly-stream-probe");
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(dir.resolve("probe").toString());

        try (DbConnection conn = Connections.open(cfg)) {
            correctness(conn);
            fullReadMustFail(conn);
            streamMustSurvive(conn);
            exportEndToEnd(conn, dir);
            autoCommitIntact(conn);
        } finally {
            clean(dir);
        }
    }

    // ------------------------------------------------------------ 一、正确性

    /** 先在小数据上确认：流出来的行和全量取回的完全一致。 */
    private static void correctness(DbConnection conn) {
        System.out.println();
        System.out.println("=== 流出来的内容和全量取的一致吗 ===");
        String sql = "SELECT X AS ID, X * 3 AS TRIPLE FROM SYSTEM_RANGE(1, 5000)";

        QueryResult full = conn.execute(sql, 0);
        StringBuilder fullDigest = new StringBuilder();
        full.rows().forEach(r -> fullDigest.append(r.get(0)).append(':').append(r.get(1)).append(';'));

        StringBuilder streamDigest = new StringBuilder();
        long[] streamed = {0};
        RowSource.ofQuery(conn, "PUBLIC", sql, full.columns(), -1).forEach(r -> {
            streamDigest.append(r.get(0)).append(':').append(r.get(1)).append(';');
            streamed[0]++;
        });

        System.out.println("  全量 " + full.rows().size() + " 行，流式 " + streamed[0] + " 行");
        System.out.println("  " + (fullDigest.toString().contentEquals(streamDigest)
                ? "[对] 逐行逐格完全一致" : "[错] 内容不一致"));
    }

    // ------------------------------------------------------------ 二、对照组

    /**
     * 原来的全量取法必须在这个用例上 OOM。
     *
     * <p>这一步是<b>对照组</b>：它不 OOM，就说明结果集造得不够大，
     * 下一步「流式跑通了」也就证明不了任何事。
     */
    private static void fullReadMustFail(DbConnection conn) {
        System.out.println();
        System.out.println("=== 对照组：全量取回（应当 OOM） ===");
        try {
            QueryResult r = conn.execute(BIG_QUERY, 0);
            System.out.println("  [错] 居然取回来了 " + r.rows().size()
                    + " 行——用例没造够大，下面那一步的结论不作数");
        } catch (OutOfMemoryError e) {
            System.out.println("  [对] 如期 OOM：" + e.getMessage());
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("  [对] 如期失败："
                    + root.getClass().getSimpleName() + " " + brief(root.getMessage()));
        }
    }

    // ------------------------------------------------------------ 三、流式

    private static void streamMustSurvive(DbConnection conn) {
        System.out.println();
        System.out.println("=== 同一个结果集，改走流式 ===");
        Runtime rt = Runtime.getRuntime();
        long[] count = {0};
        long[] peak = {0};
        long start = System.nanoTime();

        conn.stream(BIG_QUERY, new DbConnection.RowStream() {
            @Override
            public void columns(java.util.List<com.plainly.driver.ColumnMeta> columns) {
                System.out.println("  列：" + columns.size() + " 个，第一行之前就拿到了");
            }

            @Override
            public void row(com.plainly.driver.Row row) {
                count[0]++;
                if (count[0] % 100_000 == 0) {
                    long used = rt.totalMemory() - rt.freeMemory();
                    peak[0] = Math.max(peak[0], used);
                    System.out.println("    已流出 " + count[0] / 10000 + " 万行，"
                            + "当前占用 " + (used >> 20) + " MB");
                }
            }
        });

        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("  [" + (count[0] == 1_000_000 ? "对" : "错") + "] 共流出 "
                + count[0] + " 行，耗时 " + ms + " ms，采样到的最高占用 "
                + (peak[0] >> 20) + " MB");
    }

    // ------------------------------------------------------------ 四、真导一次

    /** 走完整的导出路径写一个 CSV，看文件里的行数对不对。 */
    private static void exportEndToEnd(DbConnection conn, Path dir) throws Exception {
        System.out.println();
        System.out.println("=== 走导出管线写文件 ===");
        String sql = "SELECT X AS ID, RPAD(CAST(X AS VARCHAR), 200, 'x') AS PAD"
                + " FROM SYSTEM_RANGE(1, 300000)";
        // 界面上拿到的是被行数上限截过的那一份
        QueryResult capped = conn.execute(sql, 1000);
        System.out.println("  网格里这份：" + capped.rows().size() + " 行，truncated="
                + capped.truncated());

        Path file = dir.resolve("out.csv");
        long written = Exporters.export(
                RowSource.ofQuery(conn, "PUBLIC", sql, capped.columns(), -1),
                new ExportOptions().setFormat(ExportOptions.Format.CSV)
                        .setTarget(file).setCharset(StandardCharsets.UTF_8),
                n -> { });
        long lines;
        try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
            lines = stream.count();
        }
        System.out.println("  导出写入 " + written + " 行，文件 " + lines + " 行（含表头）"
                + "，大小 " + (Files.size(file) >> 20) + " MB");
        System.out.println("  [" + (written == 300000 && lines == 300001 ? "对" : "错")
                + "] 导出的是完整结果，不是网格里那 1000 行");
    }

    // ------------------------------------------------------------ 五、善后

    /**
     * 流式跑完，连接必须还能正常用。
     *
     * <p>PostgreSQL 那条路会把自动提交关掉，还不回去的话，这条连接后面每一次写
     * 都会「成功」却不落库——最难查的一类失效。H2 上走不到那个分支，
     * 但这一条断言留着：换到 PG 上跑同一个探针时它就是那道关。
     */
    private static void autoCommitIntact(DbConnection conn) {
        System.out.println();
        System.out.println("=== 流式之后连接还正常吗 ===");
        conn.executeDdlBatch(java.util.List.of("CREATE TABLE AFTER_STREAM (V INT)"));
        conn.execute("INSERT INTO AFTER_STREAM VALUES (42)", 0);
        String back = conn.scalar("SELECT V FROM AFTER_STREAM");
        System.out.println("  [" + ("42".equals(back) ? "对" : "错")
                + "] 流式之后写进去的值读得回来：" + back);
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 90 ? flat : flat.substring(0, 87) + "…";
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
