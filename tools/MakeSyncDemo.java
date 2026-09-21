import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 造一对结构有差异的演示库，用来试结构同步。
 *
 * <p>差异是按真实场景设计的：测试库跑在前面（多了字段、多了表、放宽了长度、加了索引），
 * 生产库留着一张早该清掉的历史表。这正是「把测试库的结构对齐到生产库」时会遇到的形状。
 *
 * <p>用法：
 * <pre>java -cp path/to/h2.jar tools/MakeSyncDemo.java demo</pre>
 */
public class MakeSyncDemo {

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "./demo";

        // 源：测试库，结构比生产库新
        try (Connection c = open(dir + "/plainly-demo-test")) {
            exec(c, "DROP TABLE IF EXISTS ORDERS");
            exec(c, "DROP TABLE IF EXISTS COUPONS");
            exec(c, """
                    CREATE TABLE ORDERS (
                      ID          BIGINT         NOT NULL PRIMARY KEY,
                      USER_ID     INT            NOT NULL,
                      AMOUNT      DECIMAL(38,10) NOT NULL,
                      CURRENCY    CHAR(3)        NOT NULL,
                      STATUS      VARCHAR(16)    NOT NULL,
                      CREATED_AT  TIMESTAMP(6)   NOT NULL,
                      SETTLED_AT  TIMESTAMP(6),
                      NOTE        VARCHAR(512)
                    )""");
            exec(c, "CREATE INDEX IDX_ORDERS_USER ON ORDERS (USER_ID)");
            exec(c, "CREATE INDEX IDX_ORDERS_CREATED ON ORDERS (CREATED_AT)");
            exec(c, "CREATE INDEX IDX_ORDERS_STATUS ON ORDERS (STATUS)");
            exec(c, """
                    CREATE TABLE COUPONS (
                      ID         BIGINT        NOT NULL PRIMARY KEY,
                      CODE       VARCHAR(32)   NOT NULL,
                      DISCOUNT   DECIMAL(8,6)  NOT NULL,
                      EXPIRES_AT TIMESTAMP(6)
                    )""");
            exec(c, "CREATE UNIQUE INDEX IDX_COUPON_CODE ON COUPONS (CODE)");
            System.out.println("源库（测试）已生成：" + dir + "/plainly-demo-test.mv.db");
        }

        // 目标：生产库。先复位成「落后于源」的状态，脚本才可重复执行——
        // 跑过一次同步之后目标库已经被改了，不复位的话第二次就比不出差异了。
        try (Connection c = open(dir + "/plainly-demo")) {
            exec(c, "DROP TABLE IF EXISTS COUPONS");
            quiet(c, "DROP INDEX IF EXISTS IDX_ORDERS_STATUS");
            quiet(c, "ALTER TABLE ORDERS DROP COLUMN SETTLED_AT");
            quiet(c, "ALTER TABLE ORDERS ALTER COLUMN NOTE SET DATA TYPE VARCHAR(255)");

            exec(c, "DROP TABLE IF EXISTS LEGACY_AUDIT");
            exec(c, """
                    CREATE TABLE LEGACY_AUDIT (
                      ID      BIGINT NOT NULL PRIMARY KEY,
                      PAYLOAD VARCHAR(255)
                    )""");
            System.out.println("目标库（生产）已复位为落后状态，并补上 LEGACY_AUDIT");
        }

        System.out.println();
        System.out.println("预期差异：");
        System.out.println("  · ORDERS 新增 SETTLED_AT，NOTE 由 VARCHAR(255) 放宽到 VARCHAR(512)");
        System.out.println("  · 新建表 COUPONS（含唯一索引）");
        System.out.println("  · 新建索引 IDX_ORDERS_STATUS");
        System.out.println("  · 删除表 LEGACY_AUDIT 与 CUSTOMERS（默认不勾选）");
    }

    private static Connection open(String path) throws Exception {
        return DriverManager.getConnection("jdbc:h2:" + path, "sa", "");
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** 复位用：目标本来就没有这些东西时报错是正常的，忽略即可。 */
    private static void quiet(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception ignored) {
            // 首次运行时这些对象不存在
        }
    }
}
