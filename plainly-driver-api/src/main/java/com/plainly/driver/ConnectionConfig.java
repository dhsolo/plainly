package com.plainly.driver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一份连接配置。
 *
 * <p>密码<b>不在</b>本对象内长期驻留：连接注册表持有的实例密码字段为 {@code null}，
 * 真正的口令在建立连接的那一刻才从凭据库解密取出，塞进一个临时副本。
 * 参见 {@code com.plainly.core.store.CredentialStore}。
 */
public final class ConnectionConfig {

    private String id;
    private String name;
    private DbType type = DbType.MYSQL;
    private String host = "127.0.0.1";
    private int port = 3306;
    private String user = "";
    private String password;
    private String database = "";
    /** SQLite 的库文件路径。 */
    private String filePath = "";
    private String timezone = "Asia/Shanghai";
    private String charset = "utf8mb4";
    /**
     * 标记色。生产库标红是成本极低、收益极高的一道防线——
     * 误操作往往不是不懂，而是没注意自己正连在哪个库上。
     */
    private String color = "";
    /**
     * 所属分组。空串表示不归任何分组，在树上仍留在顶层。
     *
     * <p>只有一层，不做嵌套目录：连接列表长到需要两层结构的时候，问题多半出在
     * 命名上而不是层级上。一层已经能把「生产 / 测试 / 客户 A」分开，
     * 而每多一层，用户就多一次「它到底在哪儿」。
     */
    private String group = "";
    private boolean readOnly;
    private boolean savePassword = true;
    private final Map<String, String> extraProperties = new LinkedHashMap<>();

    public ConnectionConfig() {
    }

    public ConnectionConfig copy() {
        ConnectionConfig c = new ConnectionConfig();
        c.id = id;
        c.name = name;
        c.type = type;
        c.host = host;
        c.port = port;
        c.user = user;
        c.password = password;
        c.database = database;
        c.filePath = filePath;
        c.timezone = timezone;
        c.charset = charset;
        // 标记色和分组原来漏在这里：copy() 是 withPassword() 的底子，
        // 而那条路上出来的副本一路走到界面，颜色掉了就等于生产库的红标没了
        c.color = color;
        c.group = group;
        c.readOnly = readOnly;
        c.savePassword = savePassword;
        c.extraProperties.putAll(extraProperties);
        return c;
    }

    /** 返回一个带上口令的临时副本，仅用于建立连接。 */
    public ConnectionConfig withPassword(String pwd) {
        ConnectionConfig c = copy();
        c.password = pwd;
        return c;
    }

    public String id() {
        return id;
    }

    public ConnectionConfig setId(String id) {
        this.id = id;
        return this;
    }

    public String name() {
        return name;
    }

    public ConnectionConfig setName(String name) {
        this.name = name;
        return this;
    }

    public DbType type() {
        return type;
    }

    public ConnectionConfig setType(DbType type) {
        this.type = Objects.requireNonNull(type);
        return this;
    }

    public String host() {
        return host;
    }

    public ConnectionConfig setHost(String host) {
        this.host = host;
        return this;
    }

    public int port() {
        return port;
    }

    public ConnectionConfig setPort(int port) {
        this.port = port;
        return this;
    }

    public String user() {
        return user;
    }

    public ConnectionConfig setUser(String user) {
        this.user = user;
        return this;
    }

    public String password() {
        return password;
    }

    public ConnectionConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    public String database() {
        return database;
    }

    public ConnectionConfig setDatabase(String database) {
        this.database = database;
        return this;
    }

    public String filePath() {
        return filePath;
    }

    public ConnectionConfig setFilePath(String filePath) {
        this.filePath = filePath;
        return this;
    }

    public String timezone() {
        return timezone;
    }

    public ConnectionConfig setTimezone(String timezone) {
        this.timezone = timezone;
        return this;
    }

    public String charset() {
        return charset;
    }

    public ConnectionConfig setCharset(String charset) {
        this.charset = charset;
        return this;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public String color() {
        return color;
    }

    public ConnectionConfig setColor(String color) {
        this.color = color == null ? "" : color;
        return this;
    }

    public String group() {
        return group;
    }

    public ConnectionConfig setGroup(String group) {
        this.group = group == null ? "" : group.trim();
        return this;
    }

    public ConnectionConfig setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public boolean savePassword() {
        return savePassword;
    }

    public ConnectionConfig setSavePassword(boolean savePassword) {
        this.savePassword = savePassword;
        return this;
    }

    public Map<String, String> extraProperties() {
        return extraProperties;
    }

    /** 界面上用于标识这条连接的短描述。 */
    public String describe() {
        if (type.isFileBased()) {
            return filePath;
        }
        return user + "@" + host + ":" + port + (database.isBlank() ? "" : "/" + database);
    }

    @Override
    public String toString() {
        return name == null || name.isBlank() ? describe() : name;
    }
}
