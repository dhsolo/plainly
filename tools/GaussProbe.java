import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;

import java.util.List;

/**
 * 连一下 openGauss，看这条路通不通。
 *
 * <p>走的是产品自己的 {@code Connections.open}，不是直接 DriverManager——
 * 要验的正是 URL 前缀、驱动类、方言这三样接对了没有，
 * 而那三样只有走真实路径才试得出来。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/GaussProbe.java 主机 端口 用户 口令 [库]
 * </pre>
 */
public class GaussProbe {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "192.168.237.153";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 15432;
        String user = args.length > 2 ? args[2] : "gaussdb";
        String password = args.length > 3 ? args[3] : "";
        String database = args.length > 4 ? args[4] : "postgres";

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("gauss-probe").setType(DbType.GAUSSDB)
                .setHost(host).setPort(port)
                .setUser(user).setPassword(password).setDatabase(database);

        System.out.println("连接 " + host + ":" + port + "  库=" + database + "  用户=" + user);
        try (DbConnection db = Connections.open(cfg)) {
            System.out.println("服务端：" + db.serverVersion());
            System.out.println("方言：  " + db.dialect().getClass().getSimpleName());

            List<SchemaInfo> schemas = db.listSchemas();
            System.out.println("模式：  " + schemas.size() + " 个"
                    + (schemas.isEmpty() ? "" : "，例如 " + schemas.get(0).name()));

            String schema = schemas.stream().map(SchemaInfo::name)
                    .filter(n -> n.equals("public")).findFirst()
                    .orElse(schemas.isEmpty() ? "public" : schemas.get(0).name());
            List<TableInfo> tables = db.listTables(schema);
            System.out.println("表：    " + schema + " 里 " + tables.size() + " 张");

            QueryResult r = db.execute("SELECT version()", 1);
            System.out.println("version()：" + r.rows().get(0).get(0));
        } catch (RuntimeException e) {
            System.out.println("× 连不上：" + e.getMessage());
            Throwable t = e.getCause();
            int depth = 0;
            while (t != null && depth++ < 4) {
                System.out.println("   起因：" + t.getClass().getSimpleName()
                        + " — " + t.getMessage());
                t = t.getCause();
            }
        }
    }
}
