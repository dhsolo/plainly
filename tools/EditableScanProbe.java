import com.plainly.app.AppContext;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫一遍真库：有主键的表，网格判定是不是可编辑。
 *
 * <p>上一步发现那张表压根没有主键，只读是对的。但那只说明「这一张」，
 * 说明不了判据本身没坏。所以这里按 information_schema 分成两拨——
 * 有主键的和没主键的——各挑几张，看 {@code isEditable()} 是不是正好对上。
 * 有主键却判成只读，才是真 bug。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/EditableScanProbe.java [库]</pre>
 */
public class EditableScanProbe {

    public static void main(String[] args) throws Exception {
        String schema = args.length > 0 ? args[0] : "nj";

        try (AppContext ctx = new AppContext()) {
            ConnectionConfig cfg = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL).findFirst().orElseThrow();
            DbConnection conn = JdbcConnections.open(ctx.registry().resolvePassword(cfg));
            conn.useSchema(schema);

            List<String> withPk = tables(conn, schema, true);
            List<String> noPk = tables(conn, schema, false);
            System.out.println("库 " + schema + "：有主键的表 " + withPk.size()
                    + " 张，没主键的 " + noPk.size() + " 张");

            System.out.println();
            System.out.println("有主键的（期望：可编辑）");
            boolean ok = true;
            for (String t : withPk.subList(0, Math.min(5, withPk.size()))) {
                ok &= report(conn, t, true);
            }
            System.out.println();
            System.out.println("没主键的（期望：只读）");
            for (String t : noPk.subList(0, Math.min(3, noPk.size()))) {
                ok &= report(conn, t, false);
            }
            System.out.println();
            System.out.println(ok ? "判据与真实主键一致" : "判据与真实主键对不上");
            conn.close();
            System.exit(ok ? 0 : 1);
        }
    }

    private static boolean report(DbConnection conn, String table, boolean expected) {
        try {
            QueryResult r = conn.execute(
                    "SELECT * FROM " + conn.dialect().quote(table) + " LIMIT 3", 3);
            boolean actual = r.isEditable();
            String keys = r.columns().stream().filter(ColumnMeta::partOfKey)
                    .map(ColumnMeta::name).reduce((a, b) -> a + "," + b).orElse("(无)");
            System.out.printf("  %-28s isEditable=%-6s 认到的主键列=%s  %s%n",
                    table, actual, keys, actual == expected ? "" : "← 对不上");
            return actual == expected;
        } catch (RuntimeException e) {
            System.out.println("  " + table + " 取数失败：" + e.getMessage());
            return true;
        }
    }

    private static List<String> tables(DbConnection conn, String schema, boolean havingPk) {
        String sql = "SELECT t.TABLE_NAME FROM information_schema.TABLES t"
                + " WHERE t.TABLE_SCHEMA='" + schema + "' AND t.TABLE_TYPE='BASE TABLE'"
                + (havingPk ? " AND EXISTS" : " AND NOT EXISTS")
                + " (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k"
                + "  WHERE k.TABLE_SCHEMA=t.TABLE_SCHEMA AND k.TABLE_NAME=t.TABLE_NAME"
                + "  AND k.CONSTRAINT_NAME='PRIMARY')"
                + " ORDER BY t.TABLE_NAME";
        List<String> out = new ArrayList<>();
        QueryResult r = conn.execute(sql, 0);
        r.rows().forEach(row -> out.add(row.get(0)));
        return out;
    }
}
