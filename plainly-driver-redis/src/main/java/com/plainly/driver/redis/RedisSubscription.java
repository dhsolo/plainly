package com.plainly.driver.redis;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbException;
import com.plainly.driver.kv.KeyValueStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 一条订阅：自己的连接、自己的线程。
 *
 * <h2>为什么非要独占一条连接</h2>
 * {@code SUBSCRIBE} 会把连接切进订阅模式，从那一刻起它<b>不再响应普通命令</b>
 * （Redis 只允许再发订阅相关的几条），只往外推消息。
 *
 * <p>所以绝不能借用正在用的那条连接——借了之后，树展不开、表打不开、
 * 状态栏一直转，而且看不出原因：连接还连着，命令也发得出去，
 * 只是服务端一律回 {@code ERR only (P|S)SUBSCRIBE / ...allowed in this context}。
 *
 * <h2>读超时要关掉</h2>
 * 普通连接设了三十秒读超时，那是为了「服务端不回话时不要卡死界面」。
 * 订阅正相反：一条消息可能几小时才来一次，空等是<b>正常状态</b>。
 * 不关掉的话，每三十秒就会报一次「连接出错」，而其实什么事都没有。
 */
public final class RedisSubscription implements KeyValueStore.Subscription {

    private static final int CONNECT_TIMEOUT_MS = 8_000;

    private final RedisClient client;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 读线程。不能是 final——它要在构造之后才建得出来（线程体要捕获 this），
     * 而这个先后关系正是上一版的 bug 来源：当时建了两个实例，
     * 线程捕获的是其中一个、返回给调用方的是另一个，于是 close() 关不掉那个线程。
     */
    private volatile Thread reader;

    private RedisSubscription(RedisClient client) {
        this.client = client;
    }

    /**
     * 开一条订阅。
     *
     * @param sink    收到消息时调用，<b>在后台线程上</b>
     * @param onError 连接断掉或协议出错时调用一次，之后这条订阅就结束了
     */
    static RedisSubscription open(ConnectionConfig config, List<String> channels,
                                  List<String> patterns, Consumer<KeyValueStore.Message> sink,
                                  Consumer<String> onError) {
        if (channels.isEmpty() && patterns.isEmpty()) {
            throw new DbException("至少要订阅一个频道");
        }
        RedisClient client = RedisClient.connect(config.host(), config.port(),
                CONNECT_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
        try {
            RedisConnections.authenticate(client, config);
            // 认证之后再关超时：认证本身该有超时，不然连错端口会一直挂着
            client.setReadTimeout(0);

            if (!channels.isEmpty()) {
                client.command(withVerb("SUBSCRIBE", channels));
            }
            if (!patterns.isEmpty()) {
                client.command(withVerb("PSUBSCRIBE", patterns));
            }
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }

        RedisSubscription subscription = new RedisSubscription(client);
        Thread thread = new Thread(() -> subscription.loop(sink, onError),
                "plainly-redis-subscribe");
        thread.setDaemon(true);
        subscription.reader = thread;
        thread.start();
        return subscription;
    }

    private static String[] withVerb(String verb, List<String> names) {
        List<String> args = new ArrayList<>(names.size() + 1);
        args.add(verb);
        args.addAll(names);
        return args.toArray(new String[0]);
    }

    /**
     * 一直读，直到被关掉或者连接断了。
     *
     * <h2>推送消息长什么样</h2>
     * 精确订阅推的是三段：{@code message}、频道、内容。
     * 通配订阅推的是四段：{@code pmessage}、命中的模式、频道、内容——
     * 多出来的那一段是模式，不认它的话频道会取错位，显示成模式本身。
     *
     * <p>另外还会收到 {@code subscribe} / {@code psubscribe} 这类确认消息，
     * 那是服务端对订阅动作本身的回执，不是数据，跳过。
     */
    private void loop(Consumer<KeyValueStore.Message> sink, Consumer<String> onError) {
        try {
            while (running.get()) {
                RedisClient.Reply reply = client.awaitPush();
                List<RedisClient.Reply> parts = reply.list();
                if (parts.size() < 3) {
                    continue;
                }
                String kind = parts.get(0).display();
                if ("message".equals(kind)) {
                    sink.accept(new KeyValueStore.Message(parts.get(1).display(), "",
                            parts.get(2).display(), System.currentTimeMillis()));
                } else if ("pmessage".equals(kind) && parts.size() >= 4) {
                    sink.accept(new KeyValueStore.Message(parts.get(2).display(),
                            parts.get(1).display(), parts.get(3).display(),
                            System.currentTimeMillis()));
                }
                // 其余的是 subscribe/unsubscribe 回执，不是数据
            }
        } catch (RuntimeException e) {
            if (running.get()) {
                // 主动关掉时也会从阻塞读里抛出来，那种不算错
                onError.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public boolean active() {
        return running.get() && !client.isClosed();
    }

    /**
     * 关掉。
     *
     * <p>先把标志放下再关连接：读线程正阻塞在 socket 上，关连接会让它抛异常醒来，
     * 而那时它得知道「这是我自己关的」，不然会报一条不存在的故障。
     */
    @Override
    public void close() {
        running.set(false);
        client.close();
        if (reader != null) {
            reader.interrupt();
        }
    }
}
