import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.jdbc.JdbcConnections;

/**
 * 冒烟测试：用应用自己的读取路径打开演示库，把精度用例原样打印出来。
 *
 * <p>与单元测试的区别是它走的是完整装配（JdbcConnections → JdbcConnection → CellReader），
 * 而不是测试里手搭的连接，能顺带验证驱动加载、方言选择、元数据识别是否都对。
 *
 * <p>用法：
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SmokeTest.java</pre>
 */
public class SmokeTest {

    public static void main(String[] args) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("demo")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");

        try (DbConnection conn = JdbcConnections.open(cfg)) {
            System.out.println("服务端      : " + conn.serverVersion());
            System.out.println("库          : " + conn.listSchemas());
            System.out.println("表          : " + conn.listTables("PUBLIC"));
            System.out.println();

            QueryResult r = conn.execute(
                    "SELECT ID, USER_ID, AMOUNT, CURRENCY, STATUS, NOTE"
                            + " FROM ORDERS ORDER BY ID LIMIT 6", 10);

            System.out.println("结果集可编辑: " + r.isEditable());
            System.out.println("列类别      :");
            for (ColumnMeta c : r.columns()) {
                System.out.printf("  %-10s %-16s %-14s %s%n",
                        c.name(), c.displayType(), c.category(),
                        c.partOfKey() ? "[主键]" : "");
            }

            System.out.println();
            System.out.println("精度用例（读出的原始文本）:");
            int amountIdx = r.indexOf("AMOUNT");
            int idIdx = r.indexOf("ID");
            boolean allOk = true;
            for (Row row : r.rows()) {
                String id = row.get(idIdx);
                String amount = row.get(amountIdx);
                boolean lossless = amount != null && !amount.toUpperCase().contains("E");
                allOk &= lossless;
                System.out.printf("  id=%-20s amount=%-32s %s%n",
                        id, amount, lossless ? "" : "<== 出现科学计数法！");
            }

            System.out.println();
            System.out.println(allOk
                    ? "通过：所有值以 toPlainString 形式原样返回，无科学计数法、无截断。"
                    : "失败：存在被转换过的值。");
            System.exit(allOk ? 0 : 1);
        }
    }
}
