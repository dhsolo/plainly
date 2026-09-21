import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.jdbc.diag.PrecisionCheck;

import java.util.Locale;

/**
 * 把精度一致性检查跑到一台<b>真实实例</b>上。
 *
 * <h2>为什么需要它</h2>
 * 单元测试跑在 H2 上，证明不了 Oracle、达梦、SQL Server 的行为——那几家的驱动、
 * 类型映射、绑定方式都不一样。而精度丢失是<b>静默</b>的：数据看着还在，
 * 只是尾巴没了，事后从结果里看不出来。README 里那条「未在真实实例上验证」，
 * 缺的就是这么一次真跑。
 *
 * <h2>它会动数据库</h2>
 * 在指定模式下建一张 {@code PLAINLY_PRECISION_PROBE}、插四行、做一次参数化写回，
 * <b>跑完删掉</b>。除此之外不碰任何东西。所以别指着生产库上没有写权限的账号跑，
 * 也别指着一个已经有同名表的模式跑。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/PrecisionProbe.java "连接名" 模式名 [类型]
 * </pre>
 */
public class PrecisionProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：PrecisionProbe \"连接名\" 模式名 [类型]");
            System.out.println("  或：PrecisionProbe --direct 类型 主机 端口 用户 口令 库 模式名");
            System.out.println("会在该模式下建一张 " + PrecisionCheck.TABLE + " 并在结束时删掉。");
            return;
        }
        /*
         * 直连：不经过本机保存的连接清单。
         *
         * 为了验一台库而往用户的配置里塞一条连接，会把口令落盘——
         * 验证是一次性的事，不该留下这种痕迹。
         */
        if ("--direct".equals(args[0])) {
            if (args.length < 8) {
                System.out.println("用法：--direct 类型 主机 端口 用户 口令 库 模式名");
                return;
            }
            ConnectionConfig direct = new ConnectionConfig()
                    .setName("precision-probe")
                    .setType(com.plainly.driver.DbType.valueOf(args[1].toUpperCase(Locale.ROOT)))
                    .setHost(args[2]).setPort(Integer.parseInt(args[3]))
                    .setUser(args[4]).setPassword(args[5]).setDatabase(args[6]);
            try (DbConnection db = Connections.open(direct)) {
                report(db, args[7]);
            }
            return;
        }
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            String wantedType = args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : null;
            ConnectionConfig config = registry.listAll().stream()
                    .filter(c -> c.name().equals(args[0]))
                    .filter(c -> wantedType == null || c.type().name().equals(wantedType))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                System.out.println("找不到连接 " + args[0]
                        + (wantedType == null ? "" : "（类型 " + wantedType + "）"));
                return;
            }

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                if ("?".equals(args[1])) {
                    // 不知道该往哪个模式里建探针表时，先看看有哪些。
                    // 猜一个名字跑过去，失败信息会是「库不存在」——
                    // 那跟精度一点关系都没有，只会浪费一轮
                    // 连上来的这个账号自己的模式，往往才是该建探针表的地方——
                    // Oracle 里一个用户就是一个模式，而它可能因为「像系统模式」
                    // 被树上的过滤挡掉，不出现在下面那份清单里
                    try {
                        System.out.println("当前账号：" + db.scalar("SELECT USER FROM DUAL"));
                    } catch (RuntimeException ignored) {
                        // 不是所有家都有 DUAL，问不出就算了
                    }
                    System.out.println("这条连接上的模式：");
                    db.listSchemas().forEach(x -> System.out.println(
                            "  " + x.name() + (x.isDefault() ? "   （默认）" : "")));
                    return;
                }
                report(db, args[1]);
            }
        }
    }

    /** 跑一遍并打印。抽出来是为了直连和按连接名两条路共用同一份输出。 */
    private static void report(DbConnection db, String schema) {
            PrecisionCheck.Report report = PrecisionCheck.run(db, schema);

            System.out.println("数据库：" + report.type().displayName());
            System.out.println("版本：" + report.serverVersion());
            System.out.println("模式：" + schema);
            System.out.println();

            for (PrecisionCheck.Finding f : report.findings()) {
                String mark = switch (f.verdict()) {
                    case PASS -> "  通过  ";
                    case FAIL -> "× 不通过 ";
                    case VENDOR -> "! 厂商行为";
                };
                System.out.println(mark + "  " + f.name()
                        + (f.detail().isBlank() ? "" : "\n              " + f.detail()));
            }

            System.out.println();
            System.out.println("小结：通过 " + report.count(PrecisionCheck.Verdict.PASS)
                    + " · 不通过 " + report.count(PrecisionCheck.Verdict.FAIL)
                    + " · 厂商行为 " + report.count(PrecisionCheck.Verdict.VENDOR));
            System.out.println(report.allPassed()
                    ? "结论：精度一致性通过。"
                    : "结论：有不通过项，上面标着 × 的就是。");
    }

}
