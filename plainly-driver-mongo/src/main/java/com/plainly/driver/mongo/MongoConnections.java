package com.plainly.driver.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

/** 按 {@link ConnectionConfig} 建立 MongoDB 连接。 */
public final class MongoConnections {

    private static final int CONNECT_TIMEOUT_MS = 8_000;

    /**
     * 选服务器的等待上限。
     *
     * <p>这个值不设的话，默认要等 30 秒——连错端口时界面会整整卡半分钟，
     * 而用户早就以为软件死了。8 秒足够一次本地或同机房的握手。
     */
    private static final int SELECT_TIMEOUT_MS = 8_000;

    private MongoConnections() {
    }

    public static DbConnection open(ConnectionConfig config) {
        MongoClient client = MongoClients.create(settings(config));
        try {
            Document build = client.getDatabase(authDatabase(config))
                    .runCommand(new Document("buildInfo", 1));
            String version = String.valueOf(build.get("version"));
            return new MongoConnection(config, client, version);
        } catch (RuntimeException e) {
            client.close();
            throw new DbException(explain(config, e), e);
        }
    }

    /**
     * 拼连接设置。
     *
     * <h2>「主机」那一格允许直接粘一整条连接串</h2>
     * 副本集、TLS、readPreference 这些都只能靠连接串表达，一格一格拆成界面控件
     * 既拆不全，也会在某个选项上和官方语义对不上。所以：以 {@code mongodb://}
     * 或 {@code mongodb+srv://} 开头就整条交给驱动，其余情况按主机/端口拼。
     */
    static MongoClientSettings settings(ConnectionConfig config) {
        String host = config.host() == null ? "" : config.host().trim();
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(s -> s.serverSelectionTimeout(
                        SELECT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(
                        CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        if (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://")) {
            return builder.applyConnectionString(new ConnectionString(host)).build();
        }

        String uri = "mongodb://" + (host.isEmpty() ? "127.0.0.1" : host)
                + ":" + (config.port() > 0 ? config.port() : 27017);
        builder.applyConnectionString(new ConnectionString(uri));

        String user = config.user();
        if (user != null && !user.isBlank()) {
            builder.credential(MongoCredential.createCredential(
                    user.trim(), authDatabase(config),
                    config.password() == null ? new char[0] : config.password().toCharArray()));
        }
        return builder.build();
    }

    /**
     * 认证库。
     *
     * <h2>为什么不能直接用「默认库」那一格</h2>
     * Mongo 的用户是<b>建在某个库里</b>的，认证要去那个库验，而它和你想浏览的库
     * 常常不是同一个——绝大多数部署把用户建在 {@code admin} 里。
     * 拿业务库去认证会得到「Authentication failed」，而账号密码明明是对的。
     *
     * <p>所以这里默认 {@code admin}；要用别的库认证，就在「主机」那一格
     * 写完整连接串并带上 {@code ?authSource=...}。
     */
    static String authDatabase(ConnectionConfig config) {
        return "admin";
    }

    /**
     * 把驱动的报错换成能照着做的话。
     *
     * <p>Mongo 连不上时给的多半是一长串拓扑描述（{@code Timed out after 8000 ms
     * while waiting for a server that matches...}），里面没有一个字告诉用户
     * 该改哪一格。
     */
    private static String explain(ConnectionConfig config, RuntimeException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        if (raw.contains("Authentication failed") || raw.contains("(Unauthorized)")
                || raw.contains("requires authentication")) {
            return "认证没通过。注意 MongoDB 的用户是建在某个库里的，"
                    + "本工具默认拿 admin 库认证；如果这个账号建在别的库里，"
                    + "请在「主机」那一格填完整连接串并带上 ?authSource=库名。原始报错：" + raw;
        }
        if (raw.contains("Timed out") || raw.contains("No server chosen")) {
            return "连不上 " + config.host() + ":" + config.port()
                    + "。确认服务在跑、端口没被防火墙挡住；副本集请在「主机」那一格"
                    + "填完整连接串。原始报错：" + raw;
        }
        return "连接 MongoDB 失败：" + raw;
    }

    /** 只验证能否连通，随即关闭。 */
    public static String test(ConnectionConfig config) {
        long start = System.nanoTime();
        try (DbConnection c = open(config)) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return c.serverVersion() + " · 握手 " + ms + " ms";
        }
    }
}
