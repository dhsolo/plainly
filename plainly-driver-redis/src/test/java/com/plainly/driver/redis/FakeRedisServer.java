package com.plainly.driver.redis;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一台够用的假 Redis，跑在本进程的一个线程里。
 *
 * <h2>为什么值得写这个，而不是把客户端 mock 掉</h2>
 * 要验的恰恰是<b>协议本身</b>：请求编码有没有把长度写对、回复解码认不认得五种标记、
 * 管道的回复顺序对不对、二进制值会不会被 UTF-8 解坏。把客户端 mock 掉，
 * 这些一条也测不到——测的只剩下「我调了我自己写的方法」。
 *
 * <p>本类<b>按字节</b>解析进来的 RESP 请求，也<b>按字节</b>拼回复。
 * 客户端那边编码错一位，这里就会解析失败。
 *
 * <p>它不是 Redis：只实现本工具会发的那些命令，语义也只做到测试需要的程度。
 * 所以它证明的是「客户端说的是 RESP、逻辑走得通」，不是「和真 Redis 行为一致」。
 */
final class FakeRedisServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private volatile boolean running = true;

    /** db 号 → 键 → 值。值可能是 byte[]、Map、List、Set。 */
    private final Map<Integer, Map<String, Object>> databases = new ConcurrentHashMap<>();

    /** 需要口令时设置；null 表示不需要认证。 */
    private volatile String requiredPassword;

    /** {@code CONFIG} 被禁掉的托管 Redis——用来验证降级路径。 */
    private volatile boolean configDisabled;

    /**
     * 让某个键的「大小」命令谎报一个数。
     *
     * <p>为了验「元素太多就整个不导」那条规则。真造五万个元素只是让测试慢，
     * 被验的逻辑是「拿到 size 之后怎么决定」，谎报一个数测的就是那一段。
     */
    private final Map<String, Long> fakeSizes = new ConcurrentHashMap<>();

    /** 键 → 剩余秒数。没有条目就是不过期。 */
    private final Map<String, Long> ttls = new ConcurrentHashMap<>();

    /** 收到过的命令，按顺序。用来断言「确实发了管道」而不是逐条往返。 */
    private final List<String> received = java.util.Collections.synchronizedList(new ArrayList<>());

    FakeRedisServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.acceptor = new Thread(this::acceptLoop, "fake-redis");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void requirePassword(String password) {
        this.requiredPassword = password;
    }

    void disableConfig() {
        this.configDisabled = true;
    }

    void fakeSize(String key, long size) {
        fakeSizes.put(key, size);
    }

    List<String> received() {
        return received;
    }

    Map<String, Object> db(int index) {
        return databases.computeIfAbsent(index, k -> new LinkedHashMap<>());
    }

    void put(int index, String key, Object value) {
        db(index).put(key, value);
    }

    void putString(int index, String key, String value) {
        put(index, key, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 关不上就算了
        }
        acceptor.interrupt();
    }

    // ------------------------------------------------------------------ 连接

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> serve(socket), "fake-redis-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // 关了
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {
            Session session = new Session();
            while (running) {
                List<byte[]> args = readCommand(in);
                if (args == null) {
                    return;
                }
                byte[] reply = handle(session, args);
                out.write(reply);
                // 只在输入流里没有待处理数据时才 flush，模拟真实服务端的行为，
                // 顺便验证客户端能正确处理「多条回复挤在一个 TCP 包里」
                if (in.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // 客户端断开
        }
    }

    /**
     * 有序集合。
     *
     * <p>单独一个类型只为一件事：{@link #typeOf} 得能把它和哈希分开。
     * 两者在这台假服务端里都是「字段 → 值」的 Map，光看 Map 认不出来，
     * 于是 ZADD 写进去的东西读回来会变成 hash。
     */
    static final class Zset extends LinkedHashMap<String, String> {
    }

    private static final class Session {
        int db;
        boolean authenticated;
        /** MULTI 开着的时候，命令进队列而不是马上执行。 */
        boolean inMulti;
        final List<List<byte[]>> queued = new ArrayList<>();
    }

    // ------------------------------------------------------------------ 请求解码

    /** 按字节读一条 RESP 请求。读到流尾返回 null。 */
    private static List<byte[]> readCommand(InputStream in) throws IOException {
        int marker = in.read();
        if (marker < 0) {
            return null;
        }
        if (marker != '*') {
            throw new IOException("请求不是 RESP 数组，首字节是 '" + (char) marker + "'");
        }
        int count = Integer.parseInt(readLine(in));
        List<byte[]> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (in.read() != '$') {
                throw new IOException("请求参数不是批量字符串");
            }
            int length = Integer.parseInt(readLine(in));
            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) {
                    throw new IOException("参数读到一半流就断了");
                }
                read += n;
            }
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("参数后面没有 CRLF");
            }
            args.add(data);
        }
        return args;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\r') {
                in.read();
                return sb.toString();
            }
            sb.append((char) c);
        }
        throw new IOException("流在读完一行前就断了");
    }

    // ------------------------------------------------------------------ 命令

    private byte[] handle(Session session, List<byte[]> args) {
        String command = text(args.get(0)).toUpperCase(Locale.ROOT);
        received.add(command);

        // MULTI 期间除了 EXEC / DISCARD，别的命令都只入队，回一句 QUEUED。
        // 真 Redis 就是这么干的，而客户端那边正好靠这个回复数来对齐管道
        if (command.equals("MULTI")) {
            session.inMulti = true;
            session.queued.clear();
            return status("OK");
        }
        if (session.inMulti && !command.equals("EXEC") && !command.equals("DISCARD")) {
            session.queued.add(args);
            return status("QUEUED");
        }
        if (command.equals("DISCARD")) {
            session.inMulti = false;
            session.queued.clear();
            return status("OK");
        }
        if (command.equals("EXEC")) {
            session.inMulti = false;
            List<byte[]> results = new ArrayList<>();
            for (List<byte[]> queued : session.queued) {
                results.add(handle(session, queued));
            }
            session.queued.clear();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.writeBytes(bytes("*" + results.size() + "\r\n"));
            results.forEach(out::writeBytes);
            return out.toByteArray();
        }

        if (requiredPassword != null && !session.authenticated
                && !command.equals("AUTH") && !command.equals("PING")) {
            return error("NOAUTH Authentication required.");
        }

        switch (command) {
            case "PING":
                return status("PONG");
            case "AUTH":
                return handleAuth(session, args);
            case "SELECT":
                session.db = Integer.parseInt(text(args.get(1)));
                return status("OK");
            case "INFO":
                return bulk(info(args.size() > 1 ? text(args.get(1)) : "").getBytes(
                        StandardCharsets.UTF_8));
            case "CONFIG":
                return handleConfig(args);
            case "DBSIZE":
                return integer(db(session.db).size());
            case "SCAN":
                return handleScan(session, args);
            case "TYPE":
                return status(typeOf(db(session.db).get(text(args.get(1)))));
            case "TTL": {
                Long ttl = ttls.get(text(args.get(1)));
                return integer(ttl == null ? -1 : ttl);
            }
            case "EXPIRE":
                ttls.put(text(args.get(1)), Long.parseLong(text(args.get(2))));
                return integer(1);
            case "PERSIST":
                return integer(ttls.remove(text(args.get(1))) == null ? 0 : 1);
            case "EXISTS":
                return integer(db(session.db).containsKey(text(args.get(1))) ? 1 : 0);
            case "STRLEN":
                return integer(bytesOf(session, args).length);
            case "GET":
                return bulkOrNil(db(session.db).get(text(args.get(1))));
            case "GETRANGE":
                return handleGetRange(session, args);
            case "HLEN": {
                Long faked = fakeSizes.get(text(args.get(1)));
                return integer(faked != null ? faked : map(session, args).size());
            }
            case "LLEN":
                return integer(list(session, args).size());
            case "SCARD":
                return integer(set(session, args).size());
            case "ZCARD":
                return integer(map(session, args).size());
            case "HGETALL":
                return handleHgetAll(session, args);
            case "HSCAN":
                return handleHscan(session, args);
            case "SSCAN":
                return handleSscan(session, args);
            case "LRANGE":
                return handleLrange(session, args);
            case "ZRANGE":
                return handleZrange(session, args);
            case "SET":
                db(session.db).put(text(args.get(1)), args.get(2));
                return status("OK");
            case "DEL": {
                int removed = 0;
                for (int i = 1; i < args.size(); i++) {
                    String key = text(args.get(i));
                    if (db(session.db).remove(key) != null) {
                        removed++;
                    }
                    ttls.remove(key);
                }
                return integer(removed);
            }
            case "RENAME": {
                String from = text(args.get(1));
                if (!db(session.db).containsKey(from)) {
                    return error("ERR no such key");
                }
                db(session.db).put(text(args.get(2)), db(session.db).remove(from));
                return status("OK");
            }
            case "HSET": {
                Map<String, String> hash = mutableMap(session, text(args.get(1)));
                for (int i = 2; i + 1 < args.size(); i += 2) {
                    hash.put(text(args.get(i)), text(args.get(i + 1)));
                }
                return integer(hash.size());
            }
            case "RPUSH": {
                List<String> list = mutableList(session, text(args.get(1)));
                for (int i = 2; i < args.size(); i++) {
                    list.add(text(args.get(i)));
                }
                return integer(list.size());
            }
            case "SADD": {
                LinkedHashSet<String> set = mutableSet(session, text(args.get(1)));
                for (int i = 2; i < args.size(); i++) {
                    set.add(text(args.get(i)));
                }
                return integer(set.size());
            }
            case "ZADD": {
                // ZADD key 分数 成员 分数 成员…，本假服务端把有序集合存成
                // 「成员 → 分数」的 Map，顺序按插入序，够测试用
                Map<String, String> scored = mutableZset(session, text(args.get(1)));
                for (int i = 2; i + 1 < args.size(); i += 2) {
                    scored.put(text(args.get(i + 1)), text(args.get(i)));
                }
                return integer(scored.size());
            }
            default:
                return error("ERR unknown command '" + command + "'");
        }
    }

    private byte[] handleAuth(Session session, List<byte[]> args) {
        String password = text(args.get(args.size() - 1));
        if (requiredPassword == null) {
            return error("ERR Client sent AUTH, but no password is set");
        }
        if (!requiredPassword.equals(password)) {
            return error("WRONGPASS invalid username-password pair");
        }
        session.authenticated = true;
        return status("OK");
    }

    private byte[] handleConfig(List<byte[]> args) {
        if (configDisabled) {
            return error("ERR unknown command 'CONFIG'");
        }
        if (text(args.get(1)).equalsIgnoreCase("GET")
                && text(args.get(2)).equalsIgnoreCase("databases")) {
            return array(bulk(bytes("databases")), bulk(bytes("16")));
        }
        return array();
    }

    private String info(String section) {
        if (section.equalsIgnoreCase("keyspace")) {
            StringBuilder sb = new StringBuilder("# Keyspace\r\n");
            for (Map.Entry<Integer, Map<String, Object>> e : databases.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    sb.append("db").append(e.getKey()).append(":keys=")
                            .append(e.getValue().size()).append(",expires=0,avg_ttl=0\r\n");
                }
            }
            return sb.toString();
        }
        return "# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n";
    }

    /**
     * SCAN 一次全返回，游标直接回 0。
     *
     * <p>真 Redis 会分批，但分批的正确性靠的是客户端的循环——
     * {@link RedisClientTest} 里有一条专门用分批游标验证那个循环。
     */
    private byte[] handleScan(Session session, List<byte[]> args) {
        String pattern = "*";
        for (int i = 2; i + 1 < args.size(); i++) {
            if (text(args.get(i)).equalsIgnoreCase("MATCH")) {
                pattern = text(args.get(i + 1));
            }
        }
        List<byte[]> keys = new ArrayList<>();
        for (String key : db(session.db).keySet()) {
            if (globMatches(pattern, key)) {
                keys.add(bytes(key));
            }
        }
        return array(bulk(bytes("0")), arrayOfBulks(keys));
    }

    /** 够用的 glob：支持 {@code *}、{@code ?}、{@code []} 和反斜杠转义。 */
    static boolean globMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '\\':
                    if (i + 1 < pattern.length()) {
                        regex.append(java.util.regex.Pattern.quote(
                                String.valueOf(pattern.charAt(++i))));
                    }
                    break;
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '[':
                    regex.append('[');
                    break;
                case ']':
                    regex.append(']');
                    break;
                default:
                    regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
    }

    private byte[] handleGetRange(Session session, List<byte[]> args) {
        byte[] value = bytesOf(session, args);
        int from = Integer.parseInt(text(args.get(2)));
        int to = Integer.parseInt(text(args.get(3)));
        if (value.length == 0) {
            return bulk(new byte[0]);
        }
        int end = Math.min(to, value.length - 1);
        if (from > end) {
            return bulk(new byte[0]);
        }
        return bulk(Arrays.copyOfRange(value, from, end + 1));
    }

    private byte[] handleHgetAll(Session session, List<byte[]> args) {
        List<byte[]> flat = new ArrayList<>();
        map(session, args).forEach((k, v) -> {
            flat.add(bytes(k));
            flat.add(bytes(v));
        });
        return arrayOfBulks(flat);
    }

    private byte[] handleHscan(Session session, List<byte[]> args) {
        List<byte[]> flat = new ArrayList<>();
        map(session, args).forEach((k, v) -> {
            flat.add(bytes(k));
            flat.add(bytes(v));
        });
        return array(bulk(bytes("0")), arrayOfBulks(flat));
    }

    private byte[] handleSscan(Session session, List<byte[]> args) {
        List<byte[]> items = new ArrayList<>();
        set(session, args).forEach(v -> items.add(bytes(v)));
        return array(bulk(bytes("0")), arrayOfBulks(items));
    }

    private byte[] handleLrange(Session session, List<byte[]> args) {
        List<String> values = list(session, args);
        int from = normalizeIndex(text(args.get(2)), values.size());
        int to = normalizeIndex(text(args.get(3)), values.size());
        List<byte[]> items = new ArrayList<>();
        for (int i = Math.max(0, from); i <= Math.min(to, values.size() - 1); i++) {
            items.add(bytes(values.get(i)));
        }
        return arrayOfBulks(items);
    }

    /**
     * Redis 的范围下标从尾部数起：{@code -1} 是最后一个元素。
     *
     * <p>假服务端也得实现这条，否则用户在命令台里敲的 {@code LRANGE k 0 -1}
     * ——最常见的写法——在测试里会返回空表，而真 Redis 会返回全部。
     */
    private static int normalizeIndex(String raw, int size) {
        int index = Integer.parseInt(raw.trim());
        return index < 0 ? size + index : index;
    }

    private byte[] handleZrange(Session session, List<byte[]> args) {
        Map<String, String> scored = map(session, args);
        boolean withScores = args.stream().skip(4)
                .anyMatch(a -> text(a).equalsIgnoreCase("WITHSCORES"));
        List<byte[]> items = new ArrayList<>();
        int to = normalizeIndex(text(args.get(3)), scored.size());
        int i = 0;
        for (Map.Entry<String, String> e : scored.entrySet()) {
            if (to >= 0 && i++ > to) {
                break;
            }
            items.add(bytes(e.getKey()));
            if (withScores) {
                items.add(bytes(e.getValue()));
            }
        }
        return arrayOfBulks(items);
    }

    // ------------------------------------------------------------------ 取值

    @SuppressWarnings("unchecked")
    private Map<String, String> mutableMap(Session session, String key) {
        Object value = db(session.db).get(key);
        if (value instanceof Map) {
            return (Map<String, String>) value;
        }
        Map<String, String> fresh = new LinkedHashMap<>();
        db(session.db).put(key, fresh);
        return fresh;
    }

    private Zset mutableZset(Session session, String key) {
        Object value = db(session.db).get(key);
        if (value instanceof Zset z) {
            return z;
        }
        Zset fresh = new Zset();
        db(session.db).put(key, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private List<String> mutableList(Session session, String key) {
        Object value = db(session.db).get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        List<String> fresh = new ArrayList<>();
        db(session.db).put(key, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private LinkedHashSet<String> mutableSet(Session session, String key) {
        Object value = db(session.db).get(key);
        if (value instanceof LinkedHashSet) {
            return (LinkedHashSet<String>) value;
        }
        LinkedHashSet<String> fresh = new LinkedHashSet<>();
        db(session.db).put(key, fresh);
        return fresh;
    }

    private byte[] bytesOf(Session session, List<byte[]> args) {
        Object value = db(session.db).get(text(args.get(1)));
        return value instanceof byte[] b ? b : new byte[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> map(Session session, List<byte[]> args) {
        Object value = db(session.db).get(text(args.get(1)));
        return value instanceof Map ? (Map<String, String>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Session session, List<byte[]> args) {
        Object value = db(session.db).get(text(args.get(1)));
        return value instanceof List ? (List<String>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private LinkedHashSet<String> set(Session session, List<byte[]> args) {
        Object value = db(session.db).get(text(args.get(1)));
        return value instanceof LinkedHashSet ? (LinkedHashSet<String>) value
                : new LinkedHashSet<>();
    }

    private static String typeOf(Object value) {
        if (value == null) {
            return "none";
        }
        if (value instanceof byte[]) {
            return "string";
        }
        if (value instanceof LinkedHashSet) {
            return "set";
        }
        if (value instanceof List) {
            return "list";
        }
        // Zset 也是 Map，必须先判它
        if (value instanceof Zset) {
            return "zset";
        }
        if (value instanceof Map) {
            return "hash";
        }
        return "none";
    }

    // ------------------------------------------------------------------ 回复编码

    private static byte[] status(String text) {
        return bytes("+" + text + "\r\n");
    }

    private static byte[] error(String text) {
        return bytes("-" + text + "\r\n");
    }

    private static byte[] integer(long value) {
        return bytes(":" + value + "\r\n");
    }

    private static byte[] bulk(byte[] data) {
        byte[] head = bytes("$" + data.length + "\r\n");
        byte[] out = new byte[head.length + data.length + 2];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(data, 0, out, head.length, data.length);
        out[out.length - 2] = '\r';
        out[out.length - 1] = '\n';
        return out;
    }

    private static byte[] bulkOrNil(Object value) {
        if (value instanceof byte[] b) {
            return bulk(b);
        }
        return bytes("$-1\r\n");
    }

    private static byte[] array(byte[]... encodedElements) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(bytes("*" + encodedElements.length + "\r\n"));
        for (byte[] e : encodedElements) {
            out.writeBytes(e);
        }
        return out.toByteArray();
    }

    private static byte[] arrayOfBulks(List<byte[]> items) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(bytes("*" + items.size() + "\r\n"));
        for (byte[] item : items) {
            out.writeBytes(bulk(item));
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
