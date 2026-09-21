import com.plainly.app.AppContext;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.nio.charset.Charset;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import javax.net.ssl.SSLContext;

/**
 * 打包出来的那个运行时是不是完整的。
 *
 * <h2>为什么必须拿打包后的 java 跑，而不是开发机的 JDK</h2>
 * jpackage 靠 jdeps 静态分析决定运行时里带哪些模块，而下面这几样全都是
 * <b>反射才用到的</b>，静态分析看不见：
 * <ul>
 *   <li>{@code jdk.crypto.ec} —— 少了它，连 MySQL / PostgreSQL 的 TLS 握手
 *       会因为挑不出算法套件而失败；</li>
 *   <li>{@code jdk.charsets} —— 少了它，{@code Charset.forName("GBK")} 抛异常，
 *       导入导出里选 GBK 就炸；</li>
 *   <li>{@code jdk.localedata} —— 少了它，中文环境下的日期数字格式退回英文。</li>
 * </ul>
 * 三样漏掉都<b>不会</b>在打包时报错，只会在用户机器上以看不出原因的方式失败。
 * 所以这个检查只有一种做法：用装出来的那个运行时，真去做这几件事。
 *
 * <p>数据库那一项是<b>只读</b>的：只发 {@code SELECT 1}。
 *
 * <pre>dist\Plainly\runtime\bin\java.exe -cp "dist\Plainly\app\*" tools\RuntimeCheck.java</pre>
 */
public class RuntimeCheck {

    private static int failures;

    public static void main(String[] args) {
        System.out.println("java.version   = " + System.getProperty("java.version"));
        System.out.println("java.home      = " + System.getProperty("java.home"));
        System.out.println("file.encoding  = " + System.getProperty("file.encoding"));
        System.out.println("默认字符集     = " + Charset.defaultCharset());
        System.out.println();

        charsets();
        tls();
        locale();
        drivers();
        database();

        System.out.println();
        System.out.println(failures == 0
                ? "全部通过：这个运行时是完整的"
                : failures + " 项失败——安装出去会在用户机器上出问题");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** jdk.charsets。导入导出的编码下拉里就有 GBK。 */
    private static void charsets() {
        System.out.println("--- 字符集（jdk.charsets） ---");
        // 每个字符集配一段它本来就表示得了的文字。
        // 拿中文去考 ISO-8859-1 是在考它做不到的事：回来必然是 ???，
        // 那不是「运行时缺东西」，是用例本身出的题不对
        String[][] cases = {
                {"GBK", "数据库"},
                {"GB18030", "数据库"},
                {"UTF-16", "数据库"},
                {"ISO-8859-1", "database"},
        };
        for (String[] one : cases) {
            String name = one[0];
            String sample = one[1];
            try {
                Charset cs = Charset.forName(name);
                String round = new String(sample.getBytes(cs), cs);
                check(name + " «" + sample + "»",
                        sample.equals(round) ? null : "往返之后变成了「" + round + "」");
            } catch (Exception e) {
                check(name, e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }

    /** jdk.crypto.ec。少了它，TLS 握手挑不出算法套件。 */
    private static void tls() {
        System.out.println("--- TLS（jdk.crypto.ec） ---");
        try {
            SSLContext ctx = SSLContext.getDefault();
            String[] suites = ctx.getSocketFactory().getSupportedCipherSuites();
            long ecdhe = 0;
            for (String s : suites) {
                if (s.contains("ECDHE")) {
                    ecdhe++;
                }
            }
            System.out.println("    共 " + suites.length + " 个套件，其中 ECDHE " + ecdhe + " 个");
            check("有可用的 ECDHE 套件", ecdhe > 0 ? null : "一个都没有，连不上要求前向保密的服务端");

            java.security.KeyPairGenerator.getInstance("EC");
            check("EC 密钥算法可用", null);
        } catch (Exception e) {
            check("TLS", e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** jdk.localedata。少了它，中文下的日期与数字格式退回英文。 */
    private static void locale() {
        System.out.println("--- 中文区域（jdk.localedata） ---");
        try {
            String date = LocalDate.of(2026, 9, 7).format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                            .withLocale(Locale.SIMPLIFIED_CHINESE));
            String number = NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE)
                    .format(1234567.89);
            System.out.println("    长日期 = " + date);
            System.out.println("    数字   = " + number);
            // 没带 localedata 的话这里会退回英文的 "September 7, 2026"
            check("日期按中文格式", date.contains("年") ? null : "退回英文了：" + date);
        } catch (Exception e) {
            check("区域", e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 每个 JDBC 驱动都得能加载——jar 漏进 app 目录的话这里就露馅。 */
    private static void drivers() {
        System.out.println("--- JDBC 驱动 ---");
        for (DbType type : DbType.values()) {
            String cls = type.driverClass();
            if (cls == null || cls.isBlank()) {
                continue; // Redis 不走 JDBC
            }
            try {
                Class.forName(cls);
                check(type.displayName(), null);
            } catch (Throwable t) {
                check(type.displayName(), t.getClass().getSimpleName() + " " + cls);
            }
        }
    }

    /** 真连一次。只读，只发 SELECT 1。 */
    private static void database() {
        System.out.println("--- 真连一次（只读） ---");
        try (AppContext ctx = new AppContext()) {
            ConnectionConfig saved = ctx.registry().listAll().stream()
                    .filter(c -> !c.type().isFileBased() && c.type() != DbType.REDIS)
                    .findFirst().orElse(null);
            if (saved == null) {
                System.out.println("    (没有已保存的网络连接，跳过)");
                return;
            }
            ConnectionConfig cfg = ctx.registry().resolvePassword(saved);
            try (DbConnection conn = Connections.open(cfg)) {
                String one = conn.scalar("SELECT 1");
                System.out.println("    " + cfg.name() + " · " + conn.serverVersion());
                check("SELECT 1 -> " + one, "1".equals(one) ? null : "返回的不是 1");
            }
        } catch (Throwable t) {
            check("连接数据库", t.getClass().getSimpleName() + " " + brief(t.getMessage()));
        }
    }

    private static void check(String what, String problem) {
        if (problem == null) {
            System.out.println("    [对] " + what);
        } else {
            System.out.println("    [错] " + what + "：" + problem);
            failures++;
        }
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 97) + "…";
    }
}
