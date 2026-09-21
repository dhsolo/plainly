package com.plainly.driver.kv;

import java.util.List;

/**
 * 键值库的写入能力。
 *
 * <h2>为什么不走 {@code DbConnection.executeUpdate}</h2>
 * 那条路是给关系库设计的：{@code PreparedSql} 加一串按列绑定的值。键值库上
 * 「改一个键」的输入是「键名 + 类型 + 值 + 过期时间」，硬塞进列绑定那套里，
 * 两边都别扭，而且值里可能有任何字符（包括换行），拼进一段文本再解析必然出错。
 *
 * <p>所以单独一个接口。上层用 {@code instanceof} 判断这条连接支不支持——
 * 支持就把「新建键 / 改值 / 改名 / 删除」摆出来，不支持就当没有。
 *
 * <h2>值怎么表示</h2>
 * 一律是文本。字符串类型就是它本身；哈希、列表、集合、有序集合用 <b>JSON</b>：
 *
 * <pre>
 * hash  {"name":"张三","city":"杭州"}
 * list  ["a","b","c"]
 * set   ["红","黄"]
 * zset  [["成员","分数"],["成员2","分数2"]]
 * </pre>
 *
 * <p>为什么是 JSON 而不是「一行一个元素」这种更好打的格式：元素本身可能带换行、
 * 逗号、等号。<b>任何没有转义规则的格式，都会在某个真实的值上切错</b>，
 * 而且切错了不报错，只是存进去的东西不对。JSON 也正是导出用的格式，
 * 于是「导出改一改再灌回去」这条路自然是通的。
 */
public interface KeyValueStore {

    /**
     * 键名搜索在 {@code FilterSpec} 里占用的列名。
     *
     * <p>搜索是靠一条「这一列 = 模式」的筛选条件传下去的，走的是现成的取数管道，
     * 于是计数那一路自动也带上了——分页器上显示的是「搜到多少」而不是「一共多少」。
     * 两个数不一致的话，翻页会翻到空页。
     */
    String KEY_COLUMN = "键";

    /** 这一家支持哪些值类型，按界面上想要的顺序。 */
    List<String> valueTypes();

    /**
     * 把用户在搜索框里打的东西变成这一家认的键名模式。
     *
     * <p>为什么由驱动来做而不是界面：通配符的规矩是各家自己的
     * （Redis 是 glob，别家未必）。而且「没写通配符时要不要自动两边加星号」
     * 这个决定，得和真正执行匹配的那一侧保持一致，不能两边各猜各的。
     *
     * @return 模式；输入为空时返回空串，表示不过滤
     */
    String searchPattern(String text);

    /**
     * 读一个键的<b>完整</b>内容。键不存在返回 {@code null}。
     *
     * <p>强调完整：浏览用的那份是预览（字符串截前几百字节、集合只取头几个），
     * 拿预览去编辑再保存，等于把用户的数据截断——这是本接口存在的主要理由之一。
     */
    Entry read(String schema, String key);

    boolean exists(String schema, String key);

    /**
     * 写一个键：不存在就新建，存在就<b>整体替换</b>。
     *
     * <p>没有「部分更新」：哈希改一个字段这种事，在这个界面上的做法是把整个
     * 哈希读出来、改完再写回去。做成部分更新会引出「界面上没显示的字段要不要保留」
     * 这类问题，而用户看不到那些字段，答案对他就是不可预期的。
     */
    void write(String schema, Entry entry);

    /**
     * 改名。
     *
     * <p><b>目标名已存在时会覆盖它</b>——这是 Redis {@code RENAME} 的语义，
     * 不是本工具加的。调用方应当先用 {@link #exists} 问一句再决定要不要确认。
     */
    void rename(String schema, String from, String to);

    /** 删除若干键，返回真正删掉的个数（有些键可能在这中间已经没了）。 */
    int delete(String schema, List<String> keys);

    // ------------------------------------------------------------------ 发布订阅

    /** 这一家支不支持发布订阅。不支持时下面两个方法不该被调用。 */
    default boolean supportsPubSub() {
        return false;
    }

    /**
     * 往一个频道发一条消息。
     *
     * @return 收到这条消息的订阅者数。<b>0 不是失败</b>——它就是「当时没人在听」。
     *         这一点值得在界面上说清楚：发布是即发即弃的，没有订阅者时消息直接丢掉，
     *         不会像队列那样存起来等人来取。
     */
    default long publish(String channel, String message) {
        throw new UnsupportedOperationException("这一家不支持发布订阅");
    }

    /**
     * 订阅若干频道，消息到达时回调。
     *
     * <h2>为什么必须另开一条连接</h2>
     * Redis 的 {@code SUBSCRIBE} 会把连接切进订阅模式：从那一刻起它<b>不再响应普通命令</b>，
     * 只往外推消息。拿正在用的那条连接去订阅，等于把它废掉——树展不开、表打不开，
     * 而且看不出原因。所以实现方必须自己另开一条，并在 {@code close()} 时关掉它。
     *
     * @param channels 精确频道名
     * @param patterns 通配频道（Redis 的 {@code PSUBSCRIBE}，如 {@code news.*}）
     * @param sink     收到消息时调用。<b>在后台线程上</b>，要动界面的话自己切回去
     * @param onError  连接断开或协议出错时调用一次，随后订阅即告终止
     */
    default Subscription subscribe(List<String> channels, List<String> patterns,
                                   java.util.function.Consumer<Message> sink,
                                   java.util.function.Consumer<String> onError) {
        throw new UnsupportedOperationException("这一家不支持发布订阅");
    }

    /** 一条订阅。关掉它就断开那条专用连接。 */
    interface Subscription extends AutoCloseable {
        @Override
        void close();

        /** 还在收消息吗。连接断了之后为 false。 */
        boolean active();
    }

    /**
     * 收到的一条消息。
     *
     * @param pattern 命中的通配模式；精确订阅时为空。留着它是因为一个 {@code news.*}
     *                能收到很多频道的消息，不记下是哪条模式匹上的，
     *                用户对着一屏消息分不清自己订了什么
     */
    record Message(String channel, String pattern, String payload, long receivedAtMillis) {
    }

    // ------------------------------------------------------------------ 队列

    /** 这一家支不支持把某个键当队列来观察。 */
    default boolean supportsQueueInspect() {
        return false;
    }

    /**
     * 看一眼队列现在什么样。
     *
     * <h2>只看不取</h2>
     * 实现<b>绝不允许</b>用 {@code LPOP}、{@code RPOP}、{@code XREADGROUP} 这类
     * 会消费的命令。监听工具把别人的消息取走，是这个功能能犯的最严重的错误：
     * 消息没了、消费方收不到、而且没有任何痕迹指向这个工具。
     * 只能用 {@code LLEN}/{@code LRANGE}/{@code XLEN}/{@code XRANGE}/{@code XINFO}
     * 这些纯读的命令。
     *
     * @param peek 队头最多看几条
     */
    default QueueSnapshot inspectQueue(String schema, String key, int peek) {
        throw new UnsupportedOperationException("这一家不支持队列观察");
    }

    /**
     * 某一刻队列的样子。
     *
     * @param depth   还剩多少条没被取走
     * @param head    队头的若干条，<b>只是看，没有取走</b>
     * @param groups  消费组；只有 Stream 有，列表队列是空的
     */
    record QueueSnapshot(String key, String type, long depth, List<String> head,
                         List<ConsumerGroup> groups, long sampledAtMillis) {
    }

    /**
     * Stream 的一个消费组。
     *
     * @param pending 已经投出去、还没被确认（XACK）的条数。这个数一直涨，
     *                通常意味着消费方处理失败或者根本没在跑——而队列长度可能看着很正常
     * @param lag     还没投给这个组的条数。Redis 7 起 {@code XINFO GROUPS} 直接给，
     *                更早的版本给不出，那时是 -1
     */
    record ConsumerGroup(String name, long consumers, long pending, String lastDelivered,
                         long lag) {
    }

    // ------------------------------------------------------------------ 分布式锁

    /** 这一家支不支持按锁的方式查看和释放。 */
    default boolean supportsLocks() {
        return false;
    }

    /**
     * 按模式列出像锁的键。
     *
     * <p>「像锁」是<b>用户说了算</b>的：这一层不去猜哪些键是锁。
     * Redis 里锁就是一个普通的、带过期时间的字符串键，和缓存长得一模一样，
     * 猜错的话，用户会对着一屏缓存键以为那是锁——或者更糟，把缓存当锁释放掉。
     * 所以模式由用户给（常见的是 {@code lock:*}）。
     */
    default List<LockInfo> scanLocks(String schema, String pattern, int limit) {
        throw new UnsupportedOperationException("这一家不支持锁视图");
    }

    /**
     * 释放一把锁——<b>只有持有者还是它的时候</b>。
     *
     * <h2>为什么不能先 GET 再 DEL</h2>
     * 那两步之间有窗口：锁可能正好过期，另一个进程立刻拿到了它。
     * 这时候 DEL 删掉的是<b>别人刚拿到的锁</b>，而那个进程还以为自己持有着，
     * 于是两个进程同时进了临界区——分布式锁最经典的事故，而且事后极难查。
     *
     * <p>所以实现必须让「比对」和「删除」在服务端一步完成
     * （Redis 上是一段 Lua 脚本）。
     *
     * @param expectedHolder 期望的持有者，就是界面上显示给用户看的那个值
     * @return 真的删掉了返回 true；持有者已经变了（或锁已经没了）返回 false，
     *         <b>这不是错误</b>，是「你看到的那把锁已经不在了」
     */
    default boolean releaseLock(String schema, String key, String expectedHolder) {
        throw new UnsupportedOperationException("这一家不支持锁视图");
    }

    /**
     * 一把锁。
     *
     * @param holder     持有者。按惯例锁的值会写成能认出持有方的东西
     *                   （机器名、线程号、UUID）；写成固定值的话这里也只能如实显示
     * @param ttlSeconds 剩余秒数；{@code -1} 表示<b>没有设过期时间</b>，
     *                   那通常是个 bug——持有方一旦崩了，这把锁永远不会自己释放
     */
    record LockInfo(String key, String holder, long ttlSeconds) {

        /** 没有过期时间的锁，值得单独提醒。 */
        public boolean neverExpires() {
            return ttlSeconds < 0;
        }
    }

    /**
     * 一个键的全部内容。
     *
     * @param ttlSeconds 剩余生存时间；{@code < 0} 表示不过期
     */
    record Entry(String key, String type, String value, long ttlSeconds) {

        public boolean persistent() {
            return ttlSeconds < 0;
        }
    }
}
