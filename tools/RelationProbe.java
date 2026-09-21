import com.plainly.app.AppContext;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.List;

/**
 * 外键与触发器读取的落地检查：真库里读出来的东西对不对得上。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RelationProbe.java [库] [表]</pre>
 */
public class RelationProbe {

    public static void main(String[] args) throws Exception {
        String schema = args.length > 0 ? args[0] : "nj";
        String table = args.length > 1 ? args[1] : "ele_dev";

        try (AppContext ctx = new AppContext()) {
            ConnectionConfig cfg = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL).findFirst().orElseThrow();
            DbConnection conn = JdbcConnections.open(ctx.registry().resolvePassword(cfg));
            conn.useSchema(schema);

            List<TriggerInfo> triggers = conn.listTriggers(schema, table);
            System.out.println("触发器 " + triggers.size() + " 个");
            for (TriggerInfo t : triggers) {
                String body = t.action() == null ? "" : t.action().replaceAll("\\s+", " ");
                System.out.println("  " + t.name() + "  " + t.timing() + " " + t.event()
                        + "  " + (body.length() > 70 ? body.substring(0, 70) + "…" : body));
            }

            List<ForeignKeyInfo> out = conn.listForeignKeys(schema, table);
            System.out.println("本表的外键 " + out.size() + " 条");
            for (ForeignKeyInfo k : out) {
                System.out.println("  " + k.name() + "  " + k.columns() + " → "
                        + k.refSchema() + "." + k.refTable() + " " + k.refColumns()
                        + "  更新=" + k.onUpdate() + " 删除=" + k.onDelete());
            }

            List<ForeignKeyInfo> in = conn.listReferencingKeys(schema, table);
            System.out.println("指向本表的外键 " + in.size() + " 条");
            for (ForeignKeyInfo k : in) {
                System.out.println("  " + k.name() + "  " + k.schema() + "." + k.table()
                        + " " + k.columns() + " → 本表 " + k.refColumns());
            }
            conn.close();
        }
    }
}
