import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;

/**
 * 「双击能不能改」的判据探针。
 *
 * <p>网格是否可编辑，全看 {@link QueryResult#isEditable()}：所有列同源一张表，
 * 且能定位到主键。这两件事都来自 JDBC 的结果集元数据，各家驱动给不给、给成什么样，
 * 差别很大——所以不猜，直接把每一列拿到的 tableName / partOfKey 打出来。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/EditableProbe.java [SQL]</pre>
 */
public class EditableProbe {

    public static void main(String[] args) {
        String sql = args.length > 0 ? args[0] : "SELECT * FROM ORDERS";

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-editable");
        DbConnection conn = JdbcConnections.open(cfg);

        QueryResult r = conn.execute(sql, 5);
        System.out.println("SQL: " + sql);
        System.out.println("isEditable = " + r.isEditable());
        System.out.printf("%-16s %-14s %-10s %s%n", "列", "表名", "主键", "类型");
        for (ColumnMeta c : r.columns()) {
            System.out.printf("%-16s %-14s %-10s %s%n",
                    c.name(),
                    c.tableName().isBlank() ? "(空)" : c.tableName(),
                    c.partOfKey() ? "是" : "否",
                    c.displayType());
        }
        conn.close();
    }
}
