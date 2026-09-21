import com.plainly.app.AppContext;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 加了行数估算之后，展开一个库贵了多少。
 *
 * <p>树上展开一个库会调一次 {@code listTables}。现在它比原来多发一条
 * information_schema 查询——多出来的这段时间是用户盯着「读取中…」的时间，
 * 所以必须知道它有多长，而不是猜「一条查询而已，很快」。
 *
 * <p>分三段计时：只取元数据、只取统计信息、两段合起来（即真实的 listTables）。
 * 每段跑三次取中位，第一次连接与预热不计入。<b>只读。</b>
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ListTablesCostProbe.java &lt;库&gt;</pre>
 */
public class ListTablesCostProbe {

    public static void main(String[] args) throws Exception {
        String schema = args.length > 0 ? args[0] : "app";

        try (AppContext ctx = new AppContext()) {
            ConnectionConfig saved = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL)
                    .findFirst().orElseThrow();
            ConnectionConfig cfg = ctx.registry().resolvePassword(saved);
            System.out.println("连接 = " + cfg.name() + "   库 = " + schema);

            String url = "jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + schema
                    + "?useInformationSchema=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8";
            try (Connection raw = DriverManager.getConnection(url, cfg.user(), cfg.password())) {
                // 预热：第一次调用要建元数据缓存，把它算进去就得到一个谁也复现不了的数
                meta(raw, schema);
                stats(raw, schema);

                System.out.println("  仅表元数据 getTables   " + median(() -> meta(raw, schema)) + " ms");
                System.out.println("  仅统计信息 TABLE_ROWS  " + median(() -> stats(raw, schema)) + " ms");
            }

            try (DbConnection conn = Connections.open(cfg)) {
                conn.listTables(schema);
                System.out.println("  合计 listTables        "
                        + median(() -> conn.listTables(schema).size()) + " ms  （用户实际等待的那一段）");
            }
        }
    }

    private static long median(java.util.function.Supplier<Integer> action) {
        long[] runs = new long[3];
        for (int i = 0; i < runs.length; i++) {
            long start = System.nanoTime();
            action.get();
            runs[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(runs);
        return runs[1];
    }

    private static int meta(Connection conn, String schema) {
        int n = 0;
        try (ResultSet rs = conn.getMetaData().getTables(schema, null, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                n++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return n;
    }

    private static int stats(Connection conn, String schema) {
        int n = 0;
        String sql = "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = '" + schema + "'";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                n++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return n;
    }
}
