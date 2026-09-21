package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.dialect.Dialects;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/** 按 {@link ConnectionConfig} 建立 JDBC 连接。 */
public final class JdbcConnections {

    /** 建连超时（秒）。默认的「无限等」在界面上表现为整个窗口卡死。 */
    private static final String NL = System.lineSeparator();

    private static final int LOGIN_TIMEOUT_SECONDS = 15;

    private JdbcConnections() {
    }

    public static DbConnection open(ConnectionConfig config) {
        loadDriver(config.type());

        // 配了跳板机就先把隧道打通，再让 JDBC 连本地那一头
        SshTunnel tunnel = null;
        ConnectionConfig effective = config;
        SshTunnel.Config tunnelConfig =
                SshTunnel.configFrom(config.extraProperties(), config.host(), config.port());
        if (tunnelConfig != null) {
            tunnel = SshTunnel.open(tunnelConfig);
            effective = config.copy().setHost("127.0.0.1").setPort(tunnel.localPort());
        }

        String url = buildUrl(effective);
        Properties props = buildProperties(effective);
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            Connection conn = DriverManager.getConnection(url, props);
            applySessionSettings(conn, effective);
            boolean legacy = needsLegacyKingbase(conn, effective);
            if (legacy) {
                // 换成简单查询协议重连一次，理由见 needsLegacyKingbase
                conn.close();
                conn = DriverManager.getConnection(url, simpleQueryMode(props));
                applySessionSettings(conn, effective);
            }
            return new JdbcConnection(config, conn, Dialects.forType(config.type()),
                    tunnel, legacy);
        } catch (SQLException e) {
            // 连不上就别把隧道晾在那儿——它是个活着的 SSH 会话
            if (tunnel != null) {
                tunnel.close();
            }
            throw new DbException("连接失败：" + e.getMessage() + "\nURL：" + url, e.getSQLState(), e);
        } catch (RuntimeException e) {
            if (tunnel != null) {
                tunnel.close();
            }
            throw e;
        }
    }

    /**
     * 金仓 V8R3：驱动和服务端不是同一代，元数据得我们自己接管。
     *
     * <h2>症状</h2>
     * V8R3 的系统目录叫 {@code sys_catalog}，V8R6 起才改成和 PostgreSQL 一致的
     * {@code pg_catalog}。驱动按自己那一代的名字拼元数据查询，于是
     * {@link java.sql.DatabaseMetaData} 上每个方法都报
     * {@code relation "PG_CATALOG.PG_namespace" does not exist}。
     *
     * <h2>为什么不是「换个驱动」就完了</h2>
     * 官方能拿到的最老驱动就是 V8R6，而它<b>自己带版本校验</b>，
     * 连 V8R3 时直接拒绝（{@code database version does not match driver version}）；
     * V9 的驱动连得上，但同样只认 {@code pg_catalog}。12 个可获得的版本全试过，
     * 没有一个能读 V8R3 的元数据。所以只能连得上之后自己发 SQL，
     * 见 {@link KingbaseLegacy}。
     *
     * <p>判断用<b>症状</b>而不是比版本号：版本号格式各版本都在变，
     * 比错了会把好连接拦下来；而「{@code pg_catalog} 查不到、
     * {@code sys_catalog} 查得到」是确凿的。
     */
    private static boolean needsLegacyKingbase(Connection conn, ConnectionConfig config) {
        return config.type() == DbType.KINGBASE && KingbaseLegacy.detect(conn);
    }

    /**
     * 换成简单查询协议。
     *
     * <p>V8R3 上<b>任何</b>带参数的语句都会失败——包括纯文本列、包括显式 CAST：
     * {@code ERROR: cache lookup failed for type 536871955}。
     * 不是类型推断的问题，是扩展协议本身：驱动在 Parse 消息里发的参数类型 OID
     * 这个服务端不认识。
     *
     * <p>{@code preferQueryMode=simple} 让驱动改走简单查询协议，
     * 参数在客户端拼进语句，服务端不必解析类型 OID。实测通过，
     * 而且<b>精度不受影响</b>：{@code 123456789012.345678} 原样往返。
     *
     * <p>这不是无代价的——简单协议下每条语句都要重新解析，批量写入会慢一些。
     * 但另一条路是「一条参数化语句都发不出去」，没得选。
     */
    private static Properties simpleQueryMode(Properties base) {
        Properties p = new Properties();
        p.putAll(base);
        p.setProperty("preferQueryMode", "simple");
        return p;
    }

    /**
     * 重开一条裸连接，给断线重连用。
     *
     * <p>和 {@link #open} 走同一套 URL 与属性拼装——分成两套的话，
     * 重连出来的连接和原来那条在时区、字符集、只读标志上会悄悄不一致，
     * 而这种不一致要等到某个具体的值读出来不对才会被发现。
     *
     * @param tunnel 原来那条隧道。SSH 会话还活着就接着用它，
     *               连接只是重新拨一次本地端口；隧道也死了的话，
     *               这里会连不上并如实报错，而不是悄悄换成直连
     *               ——直连意味着绕过跳板机，那多半根本连不通，
     *               真连通了则是把本该走加密通道的流量裸奔发了出去
     */
    static Connection reopenRaw(ConnectionConfig config, SshTunnel tunnel) {
        loadDriver(config.type());
        ConnectionConfig effective = tunnel == null
                ? config
                : config.copy().setHost("127.0.0.1").setPort(tunnel.localPort());
        String url = buildUrl(effective);
        Properties props = buildProperties(effective);
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            Connection conn = DriverManager.getConnection(url, props);
            applySessionSettings(conn, effective);
            return conn;
        } catch (SQLException e) {
            throw new DbException("重连失败：" + e.getMessage() + "\nURL：" + url,
                    e.getSQLState(), e);
        }
    }

    /** 只验证能否连通，随即关闭。 */
    public static String test(ConnectionConfig config) {
        long start = System.nanoTime();
        try (DbConnection c = open(config)) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return c.serverVersion() + " · 握手 " + ms + " ms";
        }
    }

    private static void loadDriver(DbType type) {
        try {
            Class.forName(type.driverClass());
        } catch (ClassNotFoundException e) {
            throw new DbException("缺少 " + type.displayName() + " 的 JDBC 驱动："
                    + type.driverClass(), e);
        }
    }

    static String buildUrl(ConnectionConfig c) {
        switch (c.type()) {
            case MYSQL:
                return "jdbc:mysql://" + c.host() + ":" + c.port() + "/"
                        + nullToEmpty(c.database());
            case POSTGRESQL:
                return "jdbc:postgresql://" + c.host() + ":" + c.port() + "/"
                        + (c.database().isBlank() ? "postgres" : c.database());
            case SQLITE:
                return "jdbc:sqlite:" + c.filePath();
            case H2:
                return c.filePath().startsWith("jdbc:") ? c.filePath() : "jdbc:h2:" + c.filePath();
            case ORACLE:
                return oracleUrl(c);
            case DM:
                // 达梦的模式不进 URL，走 schema 属性（见 buildProperties）
                return "jdbc:dm://" + c.host() + ":" + c.port();
            case KINGBASE:
                return "jdbc:kingbase8://" + c.host() + ":" + c.port() + "/"
                        + (c.database().isBlank() ? "test" : c.database());
            case OCEANBASE:
                return "jdbc:oceanbase://" + c.host() + ":" + c.port() + "/"
                        + nullToEmpty(c.database());
            case GAUSSDB:
                // 注意是 opengauss 不是 postgresql——驱动虽然是 pgjdbc 的分支，
                // 但用的是 org.opengauss 那个重命名过的变体，URL 前缀也跟着变
                return "jdbc:opengauss://" + c.host() + ":" + c.port() + "/"
                        + (c.database().isBlank() ? "postgres" : c.database());
            default:
                throw new DbException("不支持的数据库类型：" + c.type());
        }
    }

    /**
     * Oracle 的连接串有三种写法，而<b>「库」那一格填什么，决定了用哪一种</b>。
     *
     * <ul>
     *   <li>默认按<b>服务名</b>连：{@code jdbc:oracle:thin:@//host:port/服务名}。
     *       12c 起的正统写法，PDB 也只能这么连；</li>
     *   <li>写成 {@code SID:ORCL} 就按 <b>SID</b> 连：{@code ...@host:port:ORCL}。
     *       老库（11g 及更早）常常只有 SID 没有服务名，而这两种写法<b>不能互换</b>——
     *       拿 SID 当服务名去连，报的是「监听程序无法解析服务名」，
     *       看着像网络不通，其实是写法不对；</li>
     *   <li>整段 {@code (DESCRIPTION=...)} 描述符原样粘进来也认，
     *       RAC 或者要指定 failover 策略时用得上。</li>
     * </ul>
     */
    private static String oracleUrl(ConnectionConfig c) {
        String db = nullToEmpty(c.database()).trim();
        if (db.startsWith("jdbc:")) {
            return db;
        }
        if (db.startsWith("(")) {
            return "jdbc:oracle:thin:@" + db;
        }
        if (db.regionMatches(true, 0, "SID:", 0, 4)) {
            return "jdbc:oracle:thin:@" + c.host() + ":" + c.port() + ":" + db.substring(4).trim();
        }
        return "jdbc:oracle:thin:@//" + c.host() + ":" + c.port() + "/" + db;
    }

    private static Properties buildProperties(ConnectionConfig c) {
        Properties p = new Properties();
        // SQLite 没有账号体系；H2 有，默认用 sa
        if (c.type() != DbType.SQLITE) {
            String user = nullToEmpty(c.user());
            if (user.isBlank() && c.type() == DbType.H2) {
                user = "sa";
            }
            p.setProperty("user", user);
            p.setProperty("password", nullToEmpty(c.password()));
        }

        if (c.type() == DbType.MYSQL) {
            p.setProperty("useUnicode", "true");
            p.setProperty("characterEncoding", "UTF-8");
            // 让 0000-00-00 这类非法日期变成 NULL 而不是抛异常
            p.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");
            // 时区必须显式指定，否则驱动会拿 JVM 默认时区去猜
            if (c.timezone() != null && !c.timezone().isBlank()) {
                p.setProperty("connectionTimeZone", c.timezone());
            }
            // 取元数据走 information_schema，getTableName() 才能返回真实表名，
            // 可编辑网格依赖它定位回写目标
            p.setProperty("useInformationSchema", "true");
            p.setProperty("allowPublicKeyRetrieval", "true");
            p.setProperty("useSSL", "false");
        }

        if (c.type() == DbType.DM && !nullToEmpty(c.database()).isBlank()) {
            // 驱动自己承认的属性名（DmDriver.getPropertyInfo 里查到的）：
            // 「指定用户登录后的当前模式，默认为用户的默认模式」
            p.setProperty("schema", c.database().trim());
        }

        for (Map.Entry<String, String> e : c.extraProperties().entrySet()) {
            // ssh.* 是本工具自己的跳板机配置，不是 JDBC 属性。
            // 混进去之后，宽容的驱动会忽略它，严格的驱动会直接拒连——
            // 而报错信息只会说「未知属性 ssh.enabled」，看不出跟跳板机有关
            if (e.getKey().startsWith("ssh.")) {
                continue;
            }
            p.setProperty(e.getKey(), e.getValue());
        }
        return p;
    }

    private static void applySessionSettings(Connection conn, ConnectionConfig c) throws SQLException {
        if (c.readOnly()) {
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignored) {
                // 驱动不支持就算了，写操作在 JdbcConnection 里还有一道拦截
            }
        }
        if (!c.database().isBlank() && c.type() == DbType.MYSQL) {
            conn.setCatalog(c.database());
        }
        if (c.type().isOracleFamily()) {
            pinOracleSessionFormats(conn);
        }
    }

    /**
     * 把 Oracle 会话的日期与数字格式钉死。
     *
     * <p>这不是为了好看，是两条真会出事的路径：
     *
     * <ul>
     *   <li><b>{@code NLS_NUMERIC_CHARACTERS}</b> 决定小数点是「.」还是「,」。
     *       服务端按某些地区设置跑的时候，{@code 3.14} 会以 {@code 3,14} 的形式
     *       经文本路径出来——{@code CellReader} 的注释里点名了这个坑。
     *       精确数值走的是 {@code getBigDecimal}，不受影响；但字符串路径、
     *       以及把文本绑回去的那一侧会受影响；</li>
     *   <li><b>{@code NLS_DATE_FORMAT} / {@code NLS_TIMESTAMP_FORMAT}</b> 决定
     *       「{@code 2024-01-01 12:00:00}」这段文本能不能被服务端解析成日期。
     *       本工具的值一律以文本传递，日期列写回时就是靠这个格式去认的。
     *       不钉死的话，同一条 UPDATE 在两台服务器上一台成功一台报 ORA-01861。</li>
     * </ul>
     *
     * <p>失败不算致命：达梦不一定认这几条 {@code ALTER SESSION}，
     * 认不了就维持服务端默认，连接照常建立。
     */
    private static void pinOracleSessionFormats(Connection conn) {
        String[] statements = {
            "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'",
            "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS'",
            "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF'",
            "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF TZR'",
        };
        for (String sql : statements) {
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (SQLException ignored) {
                // 这一家不认就算了
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
