import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;

/**
 * demo 库里的中文是不是真的坏了。
 *
 * <p>控制台自己的编码也会把好字显示成乱码，所以不能只看输出——把每个字的码点打出来。
 * U+4E00 往上是汉字；出现 U+9225 这种孤零零的生僻字，就是 UTF-8 字节被按 GBK 读了一遍。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/EncCheck.java</pre>
 */
public class EncCheck {

    public static void main(String[] args) {
        ConnectionConfig cfg = new ConnectionConfig().setName("p").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo").setUser("sa").setPassword("");
        cfg.setId("enc");
        try (DbConnection conn = JdbcConnections.open(cfg)) {
            dump(conn, "SELECT NAME FROM CUSTOMERS LIMIT 3");
            dump(conn, "SELECT NOTE FROM ORDERS WHERE NOTE IS NOT NULL LIMIT 3");
        }
    }

    private static void dump(DbConnection conn, String sql) {
        System.out.println("── " + sql);
        QueryResult r = conn.execute(sql, 3);
        for (var row : r.rows()) {
            String v = row.get(0);
            StringBuilder cp = new StringBuilder();
            v.codePoints().limit(6).forEach(x -> cp.append(String.format("U+%04X ", x)));
            System.out.println("   [" + v + "]   " + cp);
        }
        System.out.println();
    }
}
