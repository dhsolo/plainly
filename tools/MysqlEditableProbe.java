import com.plainly.app.AppContext;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.List;

/**
 * 用本机已保存的连接，看真实数据库给出的结果集元数据够不够开编辑。
 *
 * <p>网格能不能改，取决于 {@link QueryResult#isEditable()}：所有列同源一张表、
 * 且能定位到主键。这两项全靠 JDBC 的 {@code ResultSetMetaData}，
 * 各家驱动给不给差别很大——H2 上成立不代表 MySQL 上成立，所以直接问真库。
 *
 * <p>只读：列一遍连接，取一张表的前几行，打印元数据。不写任何东西。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/MysqlEditableProbe.java [表名]</pre>
 */
public class MysqlEditableProbe {

    private static final List<String> SYSTEM =
            List.of("information_schema", "performance_schema", "mysql", "sys");

    public static void main(String[] args) throws Exception {
        try (AppContext ctx = new AppContext()) {
            List<ConnectionConfig> all = ctx.registry().listAll();
            System.out.println("本机已保存的连接：");
            for (ConnectionConfig c : all) {
                System.out.println("  " + c.name() + "  [" + c.type() + "]  "
                        + c.host() + ":" + c.port() + "  库=" + c.database());
            }

            // 连接上没配默认库，得先问它有哪些库
            String wantSchema = args.length > 0 ? args[0] : null;
            ConnectionConfig target = null;
            DbConnection conn = null;
            String schema = null;
            for (ConnectionConfig c : all) {
                if (c.type() != DbType.MYSQL) {
                    continue;
                }
                try {
                    DbConnection candidate = JdbcConnections.open(ctx.registry().resolvePassword(c));
                    List<String> schemas = candidate.listSchemas().stream()
                            .map(s -> s.name()).toList();
                    System.out.println();
                    System.out.println(c.name() + " 的库：" + schemas);
                    String pick = wantSchema != null && schemas.contains(wantSchema)
                            ? wantSchema
                            : schemas.stream().filter(s -> !SYSTEM.contains(s)).findFirst().orElse(null);
                    if (pick != null) {
                        target = c;
                        conn = candidate;
                        schema = pick;
                        break;
                    }
                    candidate.close();
                } catch (RuntimeException e) {
                    System.out.println(c.name() + " 连不上：" + e.getMessage());
                }
            }
            if (conn == null) {
                System.out.println("没有可用的 MySQL 连接");
                return;
            }

            conn.useSchema(schema);
            String table = args.length > 1 ? args[1] : firstTable(conn, schema);
            if (table == null) {
                System.out.println(schema + " 里没有表可试");
                return;
            }
            System.out.println("用 " + target.name() + " 的 " + schema + "." + table);
            String sql = "SELECT * FROM " + conn.dialect().quote(table) + " LIMIT 5";
            System.out.println();
            System.out.println("试： " + sql);

            QueryResult r = conn.execute(sql, 5);
            System.out.println("isEditable = " + r.isEditable());
            System.out.printf("%-20s %-16s %-6s %s%n", "列", "表名", "主键", "类型");
            for (ColumnMeta c : r.columns()) {
                System.out.printf("%-20s %-16s %-6s %s%n", c.name(),
                        c.tableName().isBlank() ? "(空)" : c.tableName(),
                        c.partOfKey() ? "是" : "否", c.displayType());
            }
            conn.close();
        }
    }

    private static String firstTable(DbConnection conn, String database) {
        var tables = conn.listTables(database);
        return tables.isEmpty() ? null : tables.get(0).name();
    }
}
