package com.plainly.core.db;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.mongo.MongoConnections;
import com.plainly.driver.redis.RedisConnections;

/**
 * 开一条连接，按类型交给对应的驱动模块。
 *
 * <h2>为什么需要这一层</h2>
 * 在 Redis 之前，「开连接」和「开 JDBC 连接」是同一件事，上层直接调
 * {@code JdbcConnections.open} 就行。Redis 没有 JDBC 驱动，也不该硬塞一个进去——
 * 它的协议是 RESP，由 {@code plainly-driver-redis} 自己实现。
 *
 * <p>MongoDB 同样不走 JDBC，但它用的是官方驱动——Mongo 的线协议带 BSON、
 * SCRAM 认证和拓扑发现，自己实现划不来，而 Redis 的 RESP2 只有五种回复类型。
 *
 * <p>于是需要一个分发点。<b>用 switch 而不是 ServiceLoader</b>：驱动就这么几种，
 * 都在同一个仓库里编译，一个显式的 switch 看一眼就知道有哪几家、走哪条路；
 * 换成服务发现，代价是同样的，可读性反而没了。
 */
public final class Connections {

    private Connections() {
    }

    public static DbConnection open(ConnectionConfig config) {
        switch (config.type()) {
            case REDIS:
                return RedisConnections.open(config);
            case MONGODB:
                return MongoConnections.open(config);
            default:
                return JdbcConnections.open(config);
        }
    }

    /** 只验证能否连通，随即关闭。返回服务端版本与握手耗时。 */
    public static String test(ConnectionConfig config) {
        switch (config.type()) {
            case REDIS:
                return RedisConnections.test(config);
            case MONGODB:
                return MongoConnections.test(config);
            default:
                return JdbcConnections.test(config);
        }
    }
}
