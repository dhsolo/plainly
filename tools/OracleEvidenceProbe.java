import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;

import java.util.Locale;

/**
 * Oracle 上那两项不通过，到底是谁的问题。
 *
 * <h2>要分清的两件事</h2>
 * <ul>
 *   <li><b>末尾那个 0 丢了</b>（{@code ...1234567890} 读回来是 {@code ...123456789}）：
 *       是 Oracle 本来就不存末尾零，还是我们的读取路径削掉的？
 *       前者是厂商语义（数值一位没差，只是 scale 不同），后者是缺陷。
 *       用 {@code TO_CHAR} 直接问数据库自己怎么看这个值，就分得开；</li>
 *   <li><b>结果集认不出主键</b>：是 Oracle 的 JDBC 驱动不报表名，
 *       还是我们没去问？{@code ResultSetMetaData} 直接打出来看。</li>
 * </ul>
 *
 * <p>只读，用的是上一步 PrecisionProbe 留下的那张探针表——所以得先跑那个。
 * 这里会自己建表、查完删掉。
 */
public class OracleEvidenceProbe {

    private static final String TABLE = "PLAINLY_ORA_EVIDENCE";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：OracleEvidenceProbe \"连接名\" 模式名 [类型]");
            return;
        }
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            String wantedType = args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : null;
            ConnectionConfig config = registry.listAll().stream()
                    .filter(c -> c.name().equals(args[0]))
                    .filter(c -> wantedType == null || c.type().name().equals(wantedType))
                    .findFirst().orElse(null);
            if (config == null) {
                System.out.println("找不到连接");
                return;
            }
            String schema = args[1];

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                String t = db.dialect().qualify(schema, TABLE);
                drop(db, t);
                db.execute("CREATE TABLE " + t
                        + " (ID NUMBER(19) NOT NULL PRIMARY KEY, DEC_VAL NUMBER(38,10))", 0);
                db.execute("INSERT INTO " + t
                        + " VALUES (1, 12345678901234567890.1234567890)", 0);

                System.out.println("== 一、数据库自己怎么看这个值");
                System.out.println("  TO_CHAR 原样输出：");
                show(db, "SELECT TO_CHAR(DEC_VAL) FROM " + t + " WHERE ID = 1");
                System.out.println("  按 38.10 格式化输出：");
                show(db, "SELECT TO_CHAR(DEC_VAL, 'FM99999999999999999999.0000000000')"
                        + " FROM " + t + " WHERE ID = 1");
                System.out.println("  数据库报的 scale：");
                show(db, "SELECT DATA_SCALE, DATA_PRECISION FROM ALL_TAB_COLUMNS"
                        + " WHERE OWNER = '" + schema + "' AND TABLE_NAME = '" + TABLE + "'"
                        + " AND COLUMN_NAME = 'DEC_VAL'");
                System.out.println("  本工具读到的：");
                show(db, "SELECT DEC_VAL FROM " + t + " WHERE ID = 1");

                System.out.println();
                System.out.println("== 二、驱动报不报表名（结果集能不能定位主键就看它）");
                var r = db.execute("SELECT ID, DEC_VAL FROM " + t, 5);
                r.columns().forEach(c -> System.out.println(
                        "  列 " + c.name() + "：tableName=[" + c.tableName() + "]"
                                + " partOfKey=" + c.partOfKey()
                                + " 原生类型=" + c.nativeType()));
                System.out.println("  可编辑：" + r.isEditable()
                        + (r.isEditable() ? "" : "   只读原因：" + r.readOnlyReason()));

                drop(db, t);
            }
        }
    }

    private static void drop(DbConnection db, String t) {
        try {
            db.execute("DROP TABLE " + t, 0);
        } catch (RuntimeException ignored) {
            // 本来就没有
        }
    }

    private static void show(DbConnection db, String sql) {
        try {
            var r = db.execute(sql, 5);
            if (r.rows().isEmpty()) {
                System.out.println("    （0 行）");
                return;
            }
            r.rows().forEach(row -> {
                StringBuilder sb = new StringBuilder("    ");
                for (int i = 0; i < r.columns().size(); i++) {
                    sb.append('[').append(row.get(i)).append("] ");
                }
                System.out.println(sb);
            });
        } catch (RuntimeException e) {
            System.out.println("    失败：" + e.getMessage());
        }
    }
}
