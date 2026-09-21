import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.TypeNames;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.List;
import java.util.Locale;

/**
 * 「新建表」生成的建表语句，到底跑不跑得通。
 *
 * <h2>为什么要真跑</h2>
 * 建表语句是纯拼字符串，拼错了在生成那一刻毫无迹象——要等用户点下「应用」，
 * 数据库才把它退回来。Oracle 上就撞过一次：身份列子句被放在 NOT NULL 后面，
 * 报 {@code ORA-03076}，而代码注释里还写着「Oracle 要求 identity 子句在列约束之后」。
 * 一句写在注释里的错误假设，比没有注释更能骗人。
 *
 * <p>所以这里不看拼出来的字符串像不像，而是<b>发给数据库</b>，让它说了算。
 *
 * <h2>它会动数据库</h2>
 * 建一张 {@code PLAINLY_CREATE_PROBE}，看完就删。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/CreateTableProbe.java "连接名" 模式名 [类型]
 * </pre>
 */
public class CreateTableProbe {

    private static final String TABLE = "PLAINLY_CREATE_PROBE";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：CreateTableProbe \"连接名\" 模式名 [类型]");
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
                System.out.println("找不到连接 " + args[0]);
                return;
            }
            String schema = args[1];

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                System.out.println("数据库：" + db.serverVersion());

                // 和「新建表」对话框给的默认草稿一模一样：一个自增主键 + 几个普通列
                ColumnDraft id = ColumnDraft.added("id");
                id.setNativeType(TypeNames.defaultKeyType(config.type()));
                id.setNullable(false);
                id.setPrimaryKey(true);
                id.setAutoIncrement(true);

                ColumnDraft name = ColumnDraft.added("name");
                name.setNativeType(TypeNames.defaultColumnType(config.type()));

                ColumnDraft note = ColumnDraft.added("note");
                note.setNativeType(TypeNames.defaultColumnType(config.type()));
                note.setNullable(false);
                note.setDefaultValue("'x'");

                TableStructure draft = new TableStructure(
                        new TableInfo(schema, TABLE, ObjectKind.TABLE, "", -1),
                        List.of(id.toColumnInfo(1), name.toColumnInfo(2), note.toColumnInfo(3)),
                        List.of());

                String ddl = db.dialect().createTableDdl(schema, draft);
                System.out.println();
                System.out.println("生成的语句：");
                System.out.println(ddl);
                System.out.println();

                drop(db, schema);
                try {
                    db.executeDdlBatch(schema, List.of(ddl));
                    System.out.println("结果：  建表成功");
                } catch (RuntimeException e) {
                    System.out.println("结果：× 建表失败");
                    System.out.println("  " + e.getMessage());
                    return;
                }

                // 自增到底能不能用：不给主键值插一行，看它自己发不发号
                try {
                    db.execute("INSERT INTO " + db.dialect().qualify(schema, TABLE)
                            + " (" + db.dialect().quote("name") + ") VALUES ('自增测试')", 0);
                    String got = db.scalar("SELECT " + db.dialect().quote("id")
                            + " FROM " + db.dialect().qualify(schema, TABLE));
                    System.out.println("自增：  不给主键值也插进去了，id = " + got);
                } catch (RuntimeException e) {
                    System.out.println("自增：× 不给主键值插不进去 —— " + e.getMessage());
                }
                drop(db, schema);
                System.out.println("探针表已删除。");
            }
        }
    }

    private static void drop(DbConnection db, String schema) {
        try {
            db.execute("DROP TABLE " + db.dialect().qualify(schema, TABLE), 0);
        } catch (RuntimeException ignored) {
            // 本来就没有
        }
    }
}
