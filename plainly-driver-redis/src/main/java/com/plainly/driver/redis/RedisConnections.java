package com.plainly.driver.redis;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;

/** 按 {@link ConnectionConfig} 建立 Redis 连接。 */
public final class RedisConnections {

    private static final int CONNECT_TIMEOUT_MS = 8_000;

    /**
     * 读超时。
     *
     * <p>给得比建连宽：{@code SCAN} 一个大库要走很多轮，管道一次也可能带回几千条回复。
     * 但不能不设——不设的话，服务端不回话时界面会一直卡着，且没有任何提示。
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    private RedisConnections() {
    }

    public static DbConnection open(ConnectionConfig config) {
        RedisClient client = RedisClient.connect(config.host(), config.port(),
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        try {
            authenticate(client, config);
            String db = normalizeDb(config.database());
            client.command("SELECT", db.substring(2));
            return new RedisConnection(config, client, db);
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /**
     * 认证。
     *
     * <p>两种形态：Redis 6 起支持 ACL 用户（{@code AUTH 用户 口令}），
     * 之前只有一个全局口令（{@code AUTH 口令}）。用户名填了就用前者。
     *
     * <p>没设口令的服务端上发 {@code AUTH} 会直接报错，所以只在填了口令时才发。
     */
    static void authenticate(RedisClient client, ConnectionConfig config) {
        String password = config.password();
        if (password == null || password.isEmpty()) {
            // 顺手确认一下这确实是台 Redis：连错端口时，PING 的报错比后面某条
            // 命令莫名其妙的解析失败好懂得多
            client.command("PING");
            return;
        }
        String user = config.user();
        if (user != null && !user.isBlank()) {
            client.command("AUTH", user.trim(), password);
        } else {
            client.command("AUTH", password);
        }
    }

    /** 库编号。界面上填的可能是「0」也可能是「db0」，两种都认。 */
    static String normalizeDb(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "db0";
        }
        String digits = value.startsWith("db") || value.startsWith("DB")
                ? value.substring(2) : value;
        try {
            int index = Integer.parseInt(digits.trim());
            if (index < 0) {
                throw new NumberFormatException();
            }
            return "db" + index;
        } catch (NumberFormatException e) {
            throw new DbException("库编号得是个非负整数（比如 0 或 db0），填的是：" + raw);
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
}
