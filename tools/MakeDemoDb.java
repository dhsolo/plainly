import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Random;

/**
 * 生成演示库，供不想现搭 MySQL 的人直接打开验证。
 *
 * <p>数据刻意包含 double 装不下的极端值：BIGINT 上界、DECIMAL(38,10) 满位、1e-10，
 * 打开表就能看到网格、单元格面板和导出对话框在这些值上的表现。
 *
 * <p>用法（Java 17 单文件源码模式）：
 * <pre>java -cp path/to/h2.jar tools/MakeDemoDb.java demo/plainly-demo</pre>
 */
public class MakeDemoDb {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "./demo/plainly-demo";
        String url = "jdbc:h2:" + path;

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS ORDERS");
                st.execute("DROP TABLE IF EXISTS CUSTOMERS");

                st.execute("""
                        CREATE TABLE CUSTOMERS (
                          ID            BIGINT       NOT NULL PRIMARY KEY,
                          NAME          VARCHAR(64)  NOT NULL,
                          REGION_CODE   VARCHAR(8),
                          REGISTERED_AT TIMESTAMP,
                          REFERRAL_ID   BIGINT,
                          RETENTION_TIER TINYINT
                        )""");

                st.execute("""
                        CREATE TABLE ORDERS (
                          ID          BIGINT         NOT NULL PRIMARY KEY,
                          USER_ID     INT            NOT NULL,
                          AMOUNT      DECIMAL(38,10) NOT NULL,
                          CURRENCY    CHAR(3)        NOT NULL,
                          STATUS      VARCHAR(16)    NOT NULL,
                          CREATED_AT  TIMESTAMP      NOT NULL,
                          NOTE        VARCHAR(255)
                        )""");
                st.execute("CREATE INDEX IDX_ORDERS_USER ON ORDERS (USER_ID)");
                st.execute("CREATE INDEX IDX_ORDERS_CREATED ON ORDERS (CREATED_AT)");
            }

            seedCustomers(conn);
            seedOrders(conn);

            try (Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM ORDERS")) {
                rs.next();
                System.out.println("演示库已生成：" + path + ".mv.db");
                System.out.println("ORDERS 共 " + rs.getInt(1) + " 行");
            }
        }
    }

    private static void seedCustomers(Connection conn) throws Exception {
        String sql = "INSERT INTO CUSTOMERS (ID, NAME, REGION_CODE, REGISTERED_AT,"
                + " REFERRAL_ID, RETENTION_TIER) VALUES (?,?,?,?,?,?)";
        String[] names = {"周雨桐", "陈立诚", "黄思远", "林佳", "赵启明",
                "吴静怡", "孙铭轩", "郑晓萱", "何俊杰", "马雪松"};
        String[] regions = {"CN-SH", "CN-BJ", "CN-GD", "HK", "SG"};
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < names.length; i++) {
                ps.setLong(1, 10000L + i);
                ps.setString(2, names[i]);
                ps.setString(3, regions[i % regions.length]);
                ps.setTimestamp(4, java.sql.Timestamp.valueOf("2025-0" + (i % 9 + 1)
                        + "-1" + (i % 9) + " 09:00:00"));
                ps.setLong(5, 10000L + (i + 3) % names.length);
                ps.setInt(6, i % 4);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedOrders(Connection conn) throws Exception {
        String sql = "INSERT INTO ORDERS (ID, USER_ID, AMOUNT, CURRENCY, STATUS,"
                + " CREATED_AT, NOTE) VALUES (?,?,?,?,?,?,?)";

        // 前几行是精度用例，刻意放在最前面，打开表第一屏就能看到
        Object[][] probes = {
                {9007199254740993L, 88102, "1899.0000000000", "CNY", "PAID",
                        "2026-08-31 09:14:02", "2^53+1，double 会变成 ...992"},
                {9223372036854775807L, 88102, "12345678901234567890.1234567890", "CNY", "PENDING",
                        "2026-08-31 09:16:44", null},
                {1844674407370955161L, 90277, "68.5000000000", "CNY", "PAID",
                        "2026-08-31 09:22:15", "普通订单"},
                {1844674407370955162L, 90277, "99999999999999999999.9999999999", "USD", "HOLD",
                        "2026-08-31 10:44:30", "DECIMAL(38,10) 满位"},
                {1844674407370955163L, 11408, "0.0000000001", "BTC", "PENDING",
                        "2026-08-31 12:07:55", "最小精度用例"},
                {1844674407370955164L, 11408, "-5600.0000000000", "CNY", "VOID",
                        "2026-08-31 13:27:33", "重复下单撤销"},
        };

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] p : probes) {
                ps.setLong(1, (Long) p[0]);
                ps.setInt(2, (Integer) p[1]);
                ps.setBigDecimal(3, new BigDecimal((String) p[2]));
                ps.setString(4, (String) p[3]);
                ps.setString(5, (String) p[4]);
                ps.setTimestamp(6, java.sql.Timestamp.valueOf((String) p[5]));
                ps.setString(7, (String) p[6]);
                ps.addBatch();
            }

            // 再灌一批普通数据，让分页、计数、导出都有真实体量
            Random rnd = new Random(20260901L);
            String[] currencies = {"CNY", "CNY", "CNY", "USD", "HKD", "EUR"};
            String[] statuses = {"PAID", "PAID", "PAID", "PENDING", "REFUND", "VOID"};
            String[] notes = {null, "首单立减", "优惠券抵扣", "运费", "跨境", "部分退款", null};

            for (int i = 0; i < 5000; i++) {
                ps.setLong(1, 1844674407370960000L + i);
                ps.setInt(2, 10000 + rnd.nextInt(10));
                BigDecimal amount = BigDecimal.valueOf(rnd.nextInt(900000), 2)
                        .setScale(10, java.math.RoundingMode.UNNECESSARY);
                ps.setBigDecimal(3, amount);
                ps.setString(4, currencies[rnd.nextInt(currencies.length)]);
                ps.setString(5, statuses[rnd.nextInt(statuses.length)]);
                ps.setTimestamp(6, java.sql.Timestamp.valueOf(
                        String.format("2026-0%d-%02d %02d:%02d:%02d",
                                rnd.nextInt(8) + 1, rnd.nextInt(28) + 1,
                                rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60))));
                ps.setString(7, notes[rnd.nextInt(notes.length)]);
                ps.addBatch();
                if (i % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }
}
