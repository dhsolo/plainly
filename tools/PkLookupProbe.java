import com.plainly.app.AppContext;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 主键为什么认不出来——把三路来源摆在一起对。
 *
 * <p>网格可编辑的前提是「这一列属于主键」，判据来自
 * {@code DatabaseMetaData.getPrimaryKeys(catalog, schema, table)}。
 * 这个调用对 catalog / schema 的期待各家不同，传错了不会报错，只会返回空——
 * 于是整张表悄悄变成只读。所以同时打印：结果集元数据说自己属于哪个库、
 * getPrimaryKeys 的几种传参各返回什么、information_schema 里的真实主键是什么。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/PkLookupProbe.java &lt;库&gt; &lt;表&gt;</pre>
 */
public class PkLookupProbe {

    public static void main(String[] args) throws Exception {
        String schema = args.length > 0 ? args[0] : "nj";
        String table = args.length > 1 ? args[1] : "ele_account";

        try (AppContext ctx = new AppContext()) {
            ConnectionConfig cfg = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL)
                    .findFirst().orElseThrow();
            cfg = ctx.registry().resolvePassword(cfg);

            String url = "jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + schema
                    + "?useInformationSchema=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8";
            try (Connection conn = DriverManager.getConnection(url, cfg.user(), cfg.password())) {
                System.out.println("库=" + schema + "  表=" + table);
                System.out.println("connection.getCatalog() = " + conn.getCatalog());
                System.out.println("connection.getSchema()  = " + conn.getSchema());

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM `" + table + "` LIMIT 1")) {
                    ResultSetMetaData md = rs.getMetaData();
                    System.out.println("列1 的 catalogName = [" + md.getCatalogName(1) + "]");
                    System.out.println("列1 的 schemaName  = [" + md.getSchemaName(1) + "]");
                    System.out.println("列1 的 tableName   = [" + md.getTableName(1) + "]");
                    System.out.println("列1 的 columnName  = [" + md.getColumnName(1) + "]");
                }

                DatabaseMetaData md = conn.getMetaData();
                System.out.println();
                System.out.println("getPrimaryKeys 各种传参：");
                dump(md, schema, null, table, "catalog=库, schema=null");
                dump(md, schema, "", table, "catalog=库, schema=空串");
                dump(md, null, null, table, "catalog=null, schema=null");
                dump(md, null, schema, table, "catalog=null, schema=库");

                System.out.println();
                System.out.println("information_schema 里的真实主键：");
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COLUMN_NAME, CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE"
                                     + " WHERE TABLE_SCHEMA='" + schema + "' AND TABLE_NAME='" + table
                                     + "' AND CONSTRAINT_NAME='PRIMARY'")) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        System.out.println("  " + rs.getString(1));
                    }
                    if (!any) {
                        System.out.println("  （这张表没有主键）");
                    }
                }
            }
        }
    }

    private static void dump(DatabaseMetaData md, String catalog, String schema,
                             String table, String tag) {
        List<String> keys = new ArrayList<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        } catch (Exception e) {
            System.out.println("  " + tag + " → 异常 " + e.getMessage());
            return;
        }
        System.out.println("  " + tag + " → " + (keys.isEmpty() ? "空" : keys));
    }
}
