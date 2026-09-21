package com.plainly.driver;

/**
 * v1 支持的数据库种类。
 *
 * <p>每一种的加入门槛是通过 {@code PrecisionConformanceTest}，而不是「能连上」。
 */
public enum DbType {

    MYSQL("MySQL / MariaDB", "com.mysql.cj.jdbc.Driver", 3306),
    POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432),
    SQLITE("SQLite", "org.sqlite.JDBC", 0),
    H2("H2", "org.h2.Driver", 0),

    /**
     * SQL Server。
     *
     * <p>方言与类型映射已经写好，但{@code PrecisionConformanceTest} 还没有在真实实例上跑过——
     * 手上没有可用的 SQL Server。按本项目的准入门槛，它现在只能算「已接线」，
     * 不能算「已验证」。第一次连上真库时请先跑那套测试。
     */
    SQLSERVER("SQL Server（未验证精度）",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),

    /**
     * Oracle。
     *
     * <p>和 SQL Server 一样属于「已接线、未在真机验证」：方言、类型映射、
     * 会话设置都写好了，{@code OracleDialectTest} 逐条核对生成的 SQL，
     * {@code OracleSyntaxSmokeTest} 把这些语句真的执行了一遍（跑在 H2 的 Oracle
     * 兼容模式上）——但那不是 Oracle。按本项目的准入门槛，
     * {@code PrecisionConformanceTest} 没在真实实例上跑过之前，它只能这么标。
     * 手上有库的话，第一件事是把那套测试指过去。
     */
    ORACLE("Oracle", "oracle.jdbc.OracleDriver", 1521),

    /**
     * 达梦 DM8。
     *
     * <p>DM 默认就是 Oracle 兼容语法，所以方言继承 Oracle 的那一套，
     * 只覆盖真正不同的地方（系统模式名、建模式、清空表的说法）。
     * 同样未在真机验证。
     */
    DM("达梦 DM8", "dm.jdbc.driver.DmDriver", 5236),

    /**
     * Redis。
     *
     * <p>唯一一个不走 JDBC 的：它没有 JDBC 驱动，也不该有——键值库和结果集
     * 这套东西对不上。协议（RESP2）由 {@code plainly-driver-redis} 自己实现，
     * 所以 {@link #driverClass()} 是空的，别拿它去 {@code Class.forName}。
     *
     * <p>精度上它反而是最省心的一家：Redis 的值就是字节串，
     * 服务端不做数值解析，本工具也不做，没有可丢的地方。
     */
    REDIS("Redis", "", 6379),

    /**
     * MongoDB。
     *
     * <p>和 Redis 一样不走 JDBC，但走的是官方驱动而不是自己实现协议：
     * Mongo 的线协议带 BSON、OP_MSG、SCRAM 认证和拓扑发现，自己实现划不来。
     *
     * <p>精度上要当心的是它有两个长得像的数值类型：{@code Double} 是 IEEE 754，
     * {@code Decimal128} 是 34 位十进制——钱都存在后者里。
     * {@code MongoValues} 保证后者全程走字符串。
     */
    MONGODB("MongoDB", "", 27017),

    /**
     * 人大金仓 KingBase ES V8。
     *
     * <p>它是基于 PostgreSQL 的，方言直接继承 PG 那一套，只在真正不同的地方覆写。
     *
     * <p><b>已在真实实例上验证：</b>KingbaseES V008R003C002B0320（2026-09-11），
     * {@code PrecisionCheck} 20 项通过、0 不通过、3 项厂商行为。
     *
     * <h2>V8R3 需要一条专门的路</h2>
     * 这一代的系统目录叫 {@code sys_catalog}，V8R6 起才改成和 PostgreSQL 一致的
     * {@code pg_catalog}。而<b>官方能拿到的最老驱动就是 V8R6</b>——12 个可获得的
     * 驱动版本全试过，没有一个能读 V8R3 的元数据：V8R6 的驱动自己带版本校验、
     * 连都不让连，V9 的连得上但同样只认 {@code pg_catalog}。
     *
     * <p>所以用 V9 驱动建立连接，元数据整套由 {@code KingbaseLegacy} 自己发 SQL 取；
     * 参数化语句还要换成简单查询协议，否则任何带 {@code ?} 的语句都报
     * {@code cache lookup failed for type}。两处都只在<b>确实撞上 V8R3</b> 时才启用，
     * 判据是症状不是版本号。
     *
     * <p>V8R6 与 V9 的服务端手上没有实例，没验过——它们走的是和 PostgreSQL 一样的
     * 常规路径，理应正常，但「理应」不是验过。
     */
    KINGBASE("人大金仓 KingBase", "com.kingbase8.Driver", 54321),

    /**
     * OceanBase。
     *
     * <p>它有 MySQL 和 Oracle 两种兼容模式，<b>这里按 MySQL 模式接入</b>——
     * 那是默认模式，也是绝大多数部署用的。租户建成 Oracle 模式时，
     * 这里生成的语句会对不上，那种情况目前不支持。
     *
     * <p>同样未在真实实例上验证过。
     */
    OCEANBASE("OceanBase（MySQL 模式，未验证）", "com.oceanbase.jdbc.Driver", 2881),

    /**
     * GaussDB / openGauss。
     *
     * <p>用的是 openGauss 的 JDBC 驱动，而且必须是 <b>{@code -og} 后缀</b>那个变体：
     * 不带后缀的那个把类放在 {@code org.postgresql} 包下、注册的驱动类也叫
     * {@code org.postgresql.Driver}，和真正的 PostgreSQL 驱动<b>同名</b>。
     * 两个 jar 一起放在 classpath 上，用哪个取决于谁先被加载——
     * 连 PG 可能走成 openGauss 的驱动，反之亦然，而且不会有任何报错。
     *
     * <p><b>已在真实实例上验证：</b>openGauss 5.0.0（2026-09-11），
     * {@code PrecisionCheck} 21 项通过、0 不通过、1 项厂商行为。
     * 那一项是「空字符串被读成 NULL」——它取决于建库时的
     * {@code DBCOMPATIBILITY}，默认的 A 模式（Oracle 兼容）下空串就等于 NULL，
     * 服务端自己也这么说（{@code SELECT '' IS NULL} 返回真）。那是它的语义，
     * 不是读取路径的问题。
     */
    GAUSSDB("GaussDB / openGauss", "org.opengauss.Driver", 5432);

    private final String displayName;
    private final String driverClass;
    private final int defaultPort;

    DbType(String displayName, String driverClass, int defaultPort) {
        this.displayName = displayName;
        this.driverClass = driverClass;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public String driverClass() {
        return driverClass;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** 基于文件的数据库：界面上填库文件路径，而不是主机与端口。 */
    public boolean isFileBased() {
        return this == SQLITE || this == H2;
    }

    /**
     * 连接对话框里那个「库」字段该叫什么。
     *
     * <p>这不是措辞讲究，是<b>填错了就连不上</b>：MySQL 那一格填的是数据库名，
     * Oracle 那一格填的是<b>服务名或 SID</b>（Oracle 的「库」是实例，
     * 一个实例里的 schema 才对应别家的库），达梦填的是模式名。
     * 三样东西写同一个标签，用户只能靠猜。
     */
    public String databaseLabel() {
        switch (this) {
            case ORACLE:
                return "服务名 / SID";
            case DM:
                return "默认模式";
            case REDIS:
                return "库编号";
            case MONGODB:
                return "默认数据库";
            case KINGBASE:
            case GAUSSDB:
                return "默认库";
            case OCEANBASE:
                // OceanBase 的租户信息拼在用户名里（用户@租户#集群），不在这一格
                return "默认库";
            default:
                return "默认库";
        }
    }

    /**
     * 这一家的「库」是 Oracle 意义上的 schema 吗。
     *
     * <p>决定连接建立后要不要把 {@code database} 当作模式去切——Oracle / 达梦
     * 的连接串里给的是实例，模式要另外切；MySQL 的连接串里给的就是库本身。
     */
    public boolean isOracleFamily() {
        return this == ORACLE || this == DM;
    }

    /**
     * 走不走 JDBC。
     *
     * <p>目前只有 Redis 不走。这个判断决定由哪个驱动模块来开连接，
     * 见 {@code com.plainly.core.db.Connections}。
     */
    public boolean isJdbc() {
        return this != REDIS && this != MONGODB;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
