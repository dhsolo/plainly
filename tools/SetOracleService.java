import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;

/**
 * 改一条 Oracle 连接的服务名（PDB）。
 *
 * <p>Oracle 的多租户里，连到 CDB 根上是建不了普通表的——那儿只放系统对象。
 * 要做任何实际的事都得连到某个 PDB（{@code FREEPDB1} 是 Free 版的默认可插拔库）。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SetOracleService.java "连接名" FREEPDB1
 * </pre>
 */
public class SetOracleService {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：SetOracleService \"连接名\" 服务名");
            return;
        }
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            ConnectionConfig config = registry.listAll().stream()
                    .filter(c -> c.name().equals(args[0]) && c.type() == DbType.ORACLE)
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                System.out.println("找不到名为 " + args[0] + " 的 Oracle 连接");
                return;
            }
            System.out.println("原服务名：" + config.database());
            // password 保持 null：注册表把 null 理解成「这次不碰口令」，
            // 库里那份密文原样保留（见 ConnectionRegistry.save 的说明）
            registry.save(config.setDatabase(args[1]));
            System.out.println("新服务名：" + args[1] + "  已保存");
        }
    }
}
