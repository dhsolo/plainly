import com.plainly.core.db.Connections;
import com.plainly.core.ddl.TableCopy;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 复制表：从生成语句一路跑到库里，再回头查结果。
 *
 * <p>单元测试只证明「拼出来的字符串长这样」。真正会出事的是那一步之后：
 * 语句被数据库接受了吗？索引真的建上了吗？数据条数对得上吗？
 * 所以这里在一个临时 H2 库上真建、真灌、真查。
 *
 * <p>用临时库，<b>不碰</b> demo 目录里那两个，也不碰任何用户的库。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/CopyTableProbe.java</pre>
 */
public class CopyTableProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("plainly-copy-probe");
        Path file = dir.resolve("probe");

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe")
                .setType(DbType.H2)
                .setFilePath(file.toString());

        try (DbConnection conn = Connections.open(cfg)) {
            seed(conn);

            TableStructure source = conn.describeTable("PUBLIC", "ORDERS");
            System.out.println("源表 ORDERS：" + source.columns().size() + " 列，"
                    + source.indexes().size() + " 个索引，"
                    + conn.scalar("SELECT COUNT(*) FROM ORDERS") + " 行");
            for (IndexInfo i : source.indexes()) {
                System.out.println("    索引 " + i.name() + " " + i.columns()
                        + (i.unique() ? " UNIQUE" : "") + (i.primary() ? " PRIMARY" : ""));
            }

            run(conn, source, new TableCopy.Options("PUBLIC", "ORDERS_STRUCT", true, false),
                    "仅结构");
            run(conn, source, new TableCopy.Options("PUBLIC", "ORDERS_FULL", true, true),
                    "结构和数据");
            run(conn, source, new TableCopy.Options("PUBLIC", "ORDERS_BARE", false, true),
                    "不带索引");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清不掉不影响结论
                    }
                });
            }
        }
    }

    private static void seed(DbConnection conn) {
        conn.executeDdlBatch(List.of(
                "CREATE TABLE ORDERS (ID BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " UID BIGINT NOT NULL, CODE VARCHAR(32), AMOUNT DECIMAL(12,2))",
                "CREATE INDEX IDX_ORDERS_UID ON ORDERS (UID)",
                "CREATE UNIQUE INDEX UK_CODE ON ORDERS (CODE)"));
        for (int i = 1; i <= 25; i++) {
            conn.execute("INSERT INTO ORDERS (UID, CODE, AMOUNT) VALUES ("
                    + (100 + i) + ", 'C" + i + "', " + (i * 3) + ".50)", 0);
        }
    }

    private static void run(DbConnection conn, TableStructure source,
                            TableCopy.Options options, String label) {
        System.out.println();
        System.out.println("=== " + label + " -> " + options.newName() + " ===");
        TableCopy.Plan plan = TableCopy.build(conn.dialect(), "PUBLIC", source, options);
        plan.all().forEach(sql -> System.out.println("  " + sql.replace("\n", "\n  ") + ";"));

        try {
            conn.executeDdlBatch(plan.ddl());
            if (plan.insert() != null) {
                conn.execute(plan.insert(), 0);
            }
        } catch (RuntimeException e) {
            System.out.println("  !! 执行失败：" + e.getMessage());
            return;
        }

        TableStructure copy = conn.describeTable("PUBLIC", options.newName());
        String rows = conn.scalar("SELECT COUNT(*) FROM " + options.newName());
        String sourceRows = conn.scalar("SELECT COUNT(*) FROM ORDERS");

        System.out.println("  -> 列 " + copy.columns().size() + "/" + source.columns().size()
                + "   索引 " + copy.indexes().size() + "/" + source.indexes().size()
                + "   行 " + rows + "/" + sourceRows);
        System.out.println("  -> 主键 " + copy.primaryKeyColumns()
                + "   源 " + source.primaryKeyColumns());
        for (IndexInfo i : copy.indexes()) {
            System.out.println("     " + i.name() + " " + i.columns()
                    + (i.unique() ? " UNIQUE" : "") + (i.primary() ? " PRIMARY" : ""));
        }

        // 逐列比对类型，串了列这里就会露出来
        for (int i = 0; i < Math.min(copy.columns().size(), source.columns().size()); i++) {
            var a = source.columns().get(i);
            var b = copy.columns().get(i);
            String flag = a.name().equals(b.name()) && a.displayType().equals(b.displayType())
                    ? "  " : "!!";
            System.out.println("     " + flag + " " + a.name() + " " + a.displayType()
                    + "   ->   " + b.name() + " " + b.displayType());
        }
        // 灌完数据要把计数器推到已有数据之后，否则下一条插入必撞主键。
        // 这一段和 CopyTableDialog.advanceCounter 做的是同一件事
        String column = TableCopy.autoIncrementColumn(source);
        if (plan.insert() != null && column != null) {
            String max = conn.scalar("SELECT MAX(" + column + ") FROM " + options.newName());
            String restart = TableCopy.restartAutoIncrement(conn.dialect(), source, options,
                    Long.parseLong(max));
            System.out.println("  推计数器：" + restart);
            conn.executeDdlBatch(List.of(restart));
        }

        // 自增到底跟过来了没有：不给主键值插一条，插得进去才算数。
        // 光看生成的语句里有没有 IDENTITY 是不够的——那只证明字符串对
        try {
            conn.execute("INSERT INTO " + options.newName()
                    + " (UID, CODE, AMOUNT) VALUES (999, 'AUTO-" + options.newName()
                    + "', 1.00)", 0);
            System.out.println("  -> 不给主键值也插得进去：自增跟过来了");
            conn.execute("DELETE FROM " + options.newName() + " WHERE UID = 999", 0);
        } catch (RuntimeException e) {
            System.out.println("  -> !! 不给主键值插不进去，自增没跟过来：" + e.getMessage());
        }
        if (plan.insert() != null) {
            String mismatch = conn.scalar(
                    "SELECT COUNT(*) FROM ORDERS o JOIN " + options.newName() + " c ON o.ID = c.ID"
                            + " WHERE o.UID <> c.UID OR o.CODE <> c.CODE OR o.AMOUNT <> c.AMOUNT");
            System.out.println("  -> 逐行值不一致的行数：" + mismatch + "（应为 0）");
        }
    }
}
