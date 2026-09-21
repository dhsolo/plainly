package com.plainly.driver.redis;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.kv.KeyValueStore;
import com.plainly.driver.meta.DbObjects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把一台 Redis 当成 {@link DbConnection} 来浏览。
 *
 * <h2>对应关系，以及它在哪儿是<b>比喻</b></h2>
 * <table border="1">
 *   <tr><td>库（schema）</td><td>db0…db15。这个是真的：Redis 的库就是编号的命名空间</td></tr>
 *   <tr><td>表（table）</td><td><b>比喻</b>。是本工具按键名第一个分隔符切出来的前缀组，
 *       {@code user:1}、{@code user:2} 归到「user」。服务端并不存在这样一个对象</td></tr>
 *   <tr><td>行</td><td>一个键，连同它的类型、TTL、大小和值预览</td></tr>
 *   <tr><td>列</td><td>固定五列。<b>不是</b>值的结构——哈希里有几个字段，
 *       在这五列里是看不出来的，得点开那一格</td></tr>
 * </table>
 *
 * <p>「表」是比喻这件事必须在界面上说清楚，所以树上那一层写的是<b>「键空间」</b>
 * 而不是「表」，删除、清空、改结构这些操作一律由 {@link RedisDialect} 明确拒绝
 * 并给出理由。把比喻当成事实，用户会去点「删除表」，然后期待某种并不存在的语义。
 *
 * <h2>翻页为什么用快照</h2>
 * Redis 只有 {@code SCAN} 这一种安全的遍历方式，而它<b>不保证顺序</b>，
 * 也不接受「跳到第 400 个」。如果每翻一页重扫一次，两次扫描之间键会移动，
 * 结果是有的键翻两遍、有的一遍也看不到——而且看不出来。
 *
 * <p>所以第一次打开一个键空间时扫一遍、把键名存成快照，之后翻页都在快照上切片，
 * 快照有效期 {@value #SNAPSHOT_TTL_MILLIS} 毫秒。代价是数据可能有几十秒的滞后，
 * 这一点在网格的状态栏里说明。<b>值</b>不进快照，永远是翻到哪页读哪页的实时值。
 */
public class RedisConnection implements DbConnection, KeyValueStore {

    /** 一个键空间最多扫多少个键。超过就截断，并在界面上说明。 */
    static final int MAX_KEYS = 50_000;

    /** 键名快照的有效期。 */
    static final long SNAPSHOT_TTL_MILLIS = 30_000;

    /** 一次 SCAN 向服务端要多少个键。太小则往返次数多，太大则单次阻塞久。 */
    private static final int SCAN_BATCH = 500;

    /** 值预览最多取多少字节 / 多少个元素。 */
    private static final int PREVIEW_BYTES = 200;
    private static final int PREVIEW_ELEMENTS = 5;

    /**
     * 导出完整值时，一个集合最多取多少个元素。
     *
     * <p>超过就<b>整个拒绝</b>，写一句「元素过多」，而不是取前几万个。
     * 截一半写进文件是这里最不能做的事：文件看不出被截过，
     * 拿它当备份的人要到恢复那天才发现。
     */
    private static final int MAX_FULL_ELEMENTS = 50_000;

    /**
     * 值这一列取到什么程度。
     *
     * <p>两种模式的<b>列名不一样</b>（「值预览」/「值」），
     * 这样导出的文件头一眼能看出取的是哪一种。
     */
    private enum ValueMode {
        /** 界面网格：字符串取前 200 字节，集合取前 5 个元素。 */
        PREVIEW,
        /** 导出 / 传输 / 同步：完整取，集合写成 JSON。 */
        FULL
    }

    /**
     * 一条连接同时只允许一个操作在跟服务端说话。
     *
     * <h2>为什么必须有这把锁</h2>
     * {@link DbConnection} 的契约写着「实现不保证线程安全」，但界面并<b>没有</b>
     * 遵守：打开一个表时，取数和计数是两个任务一起丢进线程池的（见
     * {@code TableTabPane#loadPage} 与 {@code loadCount}），后台建搜索索引时
     * 还会再来一路。
     *
     * <p>JDBC 驱动自己内部同步，所以这个违规在关系库上一直没露馅。
     * RESP 不行：两个线程往同一个 socket 上交叉写命令、交叉读回复，
     * 拿到的就是<b>别人那条命令的回复</b>——表现是时好时坏的「读取失败」，
     * 或者更糟，一份看着正常、其实张冠李戴的数据。
     *
     * <p>所以锁的粒度是<b>整个逻辑操作</b>，不是单条命令：
     * 「先 SELECT 再 SCAN」中间要是被别人插一条 SELECT，扫的就是另一个库了。
     *
     * <p>{@link #close()} 刻意不参与这把锁：连接卡在读上时，从另一个线程
     * 关掉 socket 正是唯一能把它弄醒的办法。
     */
    private final Object lock = new Object();

    private final ConnectionConfig config;
    private final RedisClient client;
    private final RedisDialect dialect = new RedisDialect();
    private final String serverVersion;

    /** 当前 SELECT 到哪个库。Redis 的库是连接状态，切错了就读到别的库上去。 */
    private String currentDb;

    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

    private volatile boolean busy;
    private volatile boolean closed;

    RedisConnection(ConnectionConfig config, RedisClient client, String currentDb) {
        this.config = config;
        this.client = client;
        this.currentDb = currentDb;
        this.serverVersion = readVersion(client);
    }

    private static String readVersion(RedisClient client) {
        try {
            String info = client.command("INFO", "server").display();
            String version = valueFromInfo(info, "redis_version");
            String mode = valueFromInfo(info, "redis_mode");
            return "Redis " + (version == null ? "?" : version)
                    + (mode == null || "standalone".equals(mode) ? "" : "（" + mode + "）");
        } catch (RuntimeException e) {
            return "Redis 未知版本";
        }
    }

    private static String valueFromInfo(String info, String key) {
        if (info == null) {
            return null;
        }
        for (String line : info.split("\r?\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 基本信息

    @Override
    public ConnectionConfig config() {
        return config;
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public boolean isClosed() {
        return closed || client.isClosed();
    }

    @Override
    public void close() {
        closed = true;
        client.close();
    }

    /**
     * Redis 没有「取消正在执行的语句」。
     *
     * <p>它是单线程处理命令的：命令一旦开始执行就会跑完，中途没有中断点。
     * 真要停下一条慢命令只能 {@code CLIENT KILL} 掉整条连接——那等于断线重连，
     * 不是取消。这里什么都不做，而不是假装取消成功了。
     */
    @Override
    public void cancel() {
        // 见方法注释：做不到，且不装作做到了
    }

    // ------------------------------------------------------------------ 库

    /**
     * 库列表。
     *
     * <p>数量取自 {@code CONFIG GET databases}；托管的 Redis 常把 {@code CONFIG}
     * 禁掉，那就退回按 {@code INFO keyspace} 里出现过的库来列，再兜底到 16 个。
     * 顺带把每个库的键数放进注释里——16 个空库列在树上，哪个有数据得一个个点开才知道。
     */
    @Override
    public List<SchemaInfo> listSchemas() {
        synchronized (lock) {
            return listSchemasLocked();
        }
    }

    private List<SchemaInfo> listSchemasLocked() {
        int count = databaseCount();
        Map<String, String> keyCounts = keyspaceCounts();
        List<SchemaInfo> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "db" + i;
            out.add(new SchemaInfo(name, name.equals(currentDb)));
        }
        // CONFIG 被禁、又有编号超出默认范围的库时，别把它们漏掉
        for (String name : keyCounts.keySet()) {
            if (out.stream().noneMatch(s -> s.name().equals(name))) {
                out.add(new SchemaInfo(name, name.equals(currentDb)));
            }
        }
        return out;
    }

    private int databaseCount() {
        try {
            List<RedisClient.Reply> pair = client.command("CONFIG", "GET", "databases").list();
            if (pair.size() == 2) {
                return Math.max(1, Math.min(1024, Integer.parseInt(pair.get(1).display().trim())));
            }
        } catch (RuntimeException ignored) {
            // CONFIG 被禁是常态，不是错误
        }
        Map<String, String> counts = keyspaceCounts();
        int max = 16;
        for (String name : counts.keySet()) {
            try {
                max = Math.max(max, Integer.parseInt(name.substring(2)) + 1);
            } catch (RuntimeException ignored) {
                // 名字不是 dbN 就跳过
            }
        }
        return max;
    }

    /** {@code INFO keyspace} 给的是 {@code db0:keys=12,expires=1,avg_ttl=0}。 */
    private Map<String, String> keyspaceCounts() {
        Map<String, String> out = new TreeMap<>();
        try {
            String info = client.command("INFO", "keyspace").display();
            if (info == null) {
                return out;
            }
            for (String line : info.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (!line.startsWith("db") || colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon);
                for (String part : line.substring(colon + 1).split(",")) {
                    if (part.startsWith("keys=")) {
                        out.put(name, part.substring(5));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 读不到就当没有
        }
        return out;
    }

    @Override
    public void useSchema(String schema) {
        synchronized (lock) {
            useSchemaLocked(schema);
        }
    }

    private void useSchemaLocked(String schema) {
        if (schema == null || schema.isBlank() || schema.equals(currentDb)) {
            return;
        }
        String index = schema.startsWith("db") ? schema.substring(2) : schema;
        client.command("SELECT", index);
        currentDb = schema;
    }

    // ------------------------------------------------------------------ 键空间

    /**
     * 把一个库里的键按前缀分组。
     *
     * <p>分组的依据是<b>第一个分隔符</b>（先看 {@code :}，没有再看 {@code _}）。
     * 这不是 Redis 的规矩，是 Redis 用户的普遍习惯（{@code user:1}、{@code sess:abc}），
     * Redis 自己对键名没有任何结构上的要求。所以这一层永远只是个视图，
     * 一个键从来不「属于」某个键空间。
     */
    @Override
    public List<TableInfo> listTables(String schema) {
        synchronized (lock) {
            return listTablesLocked(schema);
        }
    }

    private List<TableInfo> listTablesLocked(String schema) {
        useSchemaLocked(schema);
        Snapshot all = snapshotOf(schema, ALL_KEYS, "");
        Map<String, Integer> groups = new TreeMap<>();
        for (String key : all.keys) {
            groups.merge(prefixOf(key), 1, Integer::sum);
        }
        List<TableInfo> out = new ArrayList<>();
        // 「全部键」排在最前：搜索多半不知道前缀，得有个整库的入口
        out.add(new TableInfo(schema, ALL_KEYS, ObjectKind.TABLE,
                all.keys.size() + " 个键" + (all.truncated ? "（已达扫描上限）" : ""),
                all.keys.size()));
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            out.add(new TableInfo(schema, e.getKey(), ObjectKind.TABLE,
                    e.getValue() + " 个键" + (all.truncated ? "（已达扫描上限）" : ""),
                    e.getValue()));
        }
        return out;
    }

    /**
     * 树上那个「（全部键）」节点的名字。
     *
     * <p>为什么要有它：键空间是按前缀分的组，而<b>搜索常常不知道前缀</b>——
     * 只记得键名里带 1001，不记得它属于哪个命名空间。有了这个节点，
     * 搜索就有了一个「整个库」的落脚点。
     *
     * <p>带括号是为了跟真实的前缀分开。真有人把键名起成这样的话，那一组会和
     * 本节点合并显示——代价可以接受，换来的是不用再发明一套转义规则。
     */
    public static final String ALL_KEYS = "（全部键）";

    /** 树上那个名字对应的扫描前缀。「全部键」对应空前缀，也就是整个库。 */
    static String prefixFor(String keyspace) {
        return ALL_KEYS.equals(keyspace) ? "" : keyspace;
    }

    /** 键名里第一个分隔符之前的部分；没有分隔符的键自成一组。 */
    static String prefixOf(String key) {
        int colon = key.indexOf(':');
        if (colon > 0) {
            return key.substring(0, colon);
        }
        int underscore = key.indexOf('_');
        if (underscore > 0) {
            return key.substring(0, underscore);
        }
        return key;
    }

    /**
     * 键空间的「结构」：固定五列。
     *
     * <p>这五列描述的是<b>键</b>，不是值。哈希里有哪些字段、列表里装的是什么，
     * 得点开「值」那一格才看得到——一个键空间里的键完全可以类型都不一样，
     * 硬要给它们凑一张列定义出来，凑出来的东西是假的。
     */
    @Override
    public TableStructure describeTable(String schema, String table) {
        return new TableStructure(
                new TableInfo(schema, table, ObjectKind.TABLE, "键空间（按前缀分组，非真实对象）", -1),
                List.of(
                        column(0, "键", "KEY", TypeCategory.STRING, true),
                        column(1, "类型", "TYPE", TypeCategory.STRING, false),
                        column(2, "TTL", "TTL", TypeCategory.STRING, false),
                        column(3, "大小", "SIZE", TypeCategory.STRING, false),
                        column(4, "值预览", "VALUE", TypeCategory.STRING, false)),
                List.of());
    }

    private static ColumnInfo column(int ordinal, String name, String type,
                                     TypeCategory category, boolean key) {
        return new ColumnInfo(name, type, category, 0, 0, !key, key, false, null, "", ordinal);
    }

    @Override
    public List<DbObjects.ForeignKeyInfo> listForeignKeys(String schema, String table) {
        return List.of();
    }

    @Override
    public List<DbObjects.ForeignKeyInfo> listReferencingKeys(String schema, String table) {
        return List.of();
    }

    @Override
    public List<DbObjects.TriggerInfo> listTriggers(String schema, String table) {
        return List.of();
    }

    // ------------------------------------------------------------------ 取数

    /**
     * 执行一条<b>Redis 命令</b>。
     *
     * <p>SQL 编辑器在 Redis 连接上就是个命令行控制台，输入什么就发什么。
     * 分词按空白切，支持用引号把带空格的参数括起来——{@code SET greeting "hello world"}。
     */
    @Override
    public QueryResult execute(String sql, int maxRows) {
        synchronized (lock) {
            return executeLocked(sql, maxRows);
        }
    }

    private QueryResult executeLocked(String sql, int maxRows) {
        // 批量导出走的是 RowSource.ofTable，它把方言产出的指令交给 execute(String)。
        // 不在这儿认出来，这段文本就会被当成一条 Redis 命令原样发出去，
        // 服务端回一句 unknown command 'PLAINLY-REDIS'——「全部键」导出根本跑不了
        if (sql.startsWith(RedisDialect.MARKER)) {
            return runInternal(sql, maxRows);
        }
        long start = System.nanoTime();
        String[] args = tokenize(sql);
        if (args.length == 0) {
            throw new DbException("空命令");
        }
        busy = true;
        try {
            RedisClient.Reply reply = client.raw(args);
            if (reply.kind() == RedisClient.Kind.ERROR) {
                throw new DbException(reply.text());
            }
            // 改动数据的命令会让快照过期
            snapshots.clear();
            return renderReply(reply, millisSince(start), sql, maxRows);
        } finally {
            busy = false;
        }
    }

    /**
     * 把一条命令切成参数。
     *
     * <p>为什么不能简单地按空格切：{@code SET msg "hello world"} 的值里就有空格。
     * 支持单双引号，引号内的空白原样保留。
     */
    static String[] tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            current.append(c);
            inToken = true;
        }
        if (inToken) {
            out.add(current.toString());
        }
        return out.toArray(new String[0]);
    }

    /** 把一条回复摆成网格能显示的样子。 */
    private QueryResult renderReply(RedisClient.Reply reply, long millis, String statement,
                                    int maxRows) {
        if (reply.kind() == RedisClient.Kind.ARRAY) {
            List<ColumnMeta> columns = List.of(
                    meta("序号", TypeCategory.INTEGER),
                    meta("值", TypeCategory.STRING));
            List<Row> rows = new ArrayList<>();
            List<RedisClient.Reply> items = reply.list();
            boolean truncated = false;
            for (int i = 0; i < items.size(); i++) {
                if (maxRows > 0 && rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                rows.add(new Row(new String[] {String.valueOf(i + 1), items.get(i).display()}));
            }
            return QueryResult.readOnly(columns, rows, millis, truncated, statement,
                    REASON_READ_ONLY);
        }
        return QueryResult.readOnly(List.of(meta("结果", TypeCategory.STRING)),
                List.of(new Row(new String[] {reply.display()})),
                millis, false, statement, REASON_READ_ONLY);
    }

    /**
     * 网格里为什么不能直接改。
     *
     * <p>注意这句话说的是<b>网格</b>，不是「不能写」：新建键、改值、改名、删除
     * 都由 {@link KeyValueStore} 那几个方法支持，界面上是工具条里的按钮。
     *
     * <p>不让在格子里直接改，是因为「值预览」这一列本来就是截断的
     * （字符串取前 200 字节、集合取前 5 个元素）。在截断的内容上编辑再保存，
     * 等于把用户剩下的数据抹掉——而且他看不出来。所以改值一律走编辑框，
     * 那里显示的是重新读回来的完整值。
     */
    private static final String REASON_READ_ONLY =
            "这一列是预览（截断过），在格子里直接改会把剩下的内容抹掉。"
            + "请用工具条上的「改值」——那里显示的是完整的值。";

    private static ColumnMeta meta(String name, TypeCategory category) {
        return new ColumnMeta(name, name, "STRING", category, 0, 0, true, "", "", false, false);
    }

    /**
     * 网格取数。收到的是 {@link RedisDialect} 产出的私有指令，不是 SQL。
     */
    @Override
    public QueryResult executeQuery(SqlDialect.PreparedSql statement, List<String> values,
                                    int maxRows) {
        synchronized (lock) {
            return executeQueryLocked(statement, maxRows);
        }
    }

    private QueryResult executeQueryLocked(SqlDialect.PreparedSql statement, int maxRows) {
        String sql = statement.sql();
        if (!sql.startsWith(RedisDialect.MARKER)) {
            return executeLocked(sql, maxRows);
        }
        return runInternal(sql, maxRows);
    }

    /** 解读本工具自己那套私有指令（见 {@link RedisDialect} 的类注释）。 */
    private QueryResult runInternal(String sql, int maxRows) {
        String[] parts = sql.split("\n", -1);
        String verb = parts[0];
        String db = parts.length > 1 ? parts[1] : currentDb;
        String keyspace = parts.length > 2 ? parts[2] : "";

        if (verb.equals(RedisDialect.COUNT)) {
            long start = System.nanoTime();
            int total = snapshotOf(db, keyspace, parts.length > 3 ? parts[3] : "").keys.size();
            return QueryResult.readOnly(List.of(meta("COUNT", TypeCategory.INTEGER)),
                    List.of(new Row(new String[] {String.valueOf(total)})),
                    millisSince(start), false, sql, REASON_READ_ONLY);
        }
        int limit = parts.length > 3 ? parseInt(parts[3], 200) : 200;
        int offset = parts.length > 4 ? parseInt(parts[4], 0) : 0;
        // 模式放最后一段：键名理论上可以带换行，模式是人打的不会有，
        // 但万一有，把剩下的段拼回去也不会丢
        String pattern = parts.length > 5
                ? String.join("\n", java.util.Arrays.copyOfRange(parts, 5, parts.length)) : "";
        ValueMode mode = verb.equals(RedisDialect.SCAN_FULL) ? ValueMode.FULL : ValueMode.PREVIEW;
        return keyPage(db, keyspace, pattern, limit, offset, sql, mode);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 一页键，连同类型、TTL、大小和值。 */
    private QueryResult keyPage(String db, String keyspace, String pattern, int limit, int offset,
                                String statement, ValueMode mode) {
        long start = System.nanoTime();
        busy = true;
        try {
            useSchemaLocked(db);
            Snapshot snapshot = snapshotOf(db, keyspace, pattern);
            List<String> keys = snapshot.keys;
            int from = Math.min(Math.max(0, offset), keys.size());
            int to = limit > 0 ? Math.min(from + limit, keys.size()) : keys.size();
            List<String> page = keys.subList(from, to);

            List<String> types = typesOf(page);
            List<String[]> details = detailsOf(page, types, mode);

            List<Row> rows = new ArrayList<>(page.size());
            for (int i = 0; i < page.size(); i++) {
                rows.add(new Row(new String[] {
                        page.get(i), types.get(i), details.get(i)[0],
                        details.get(i)[1], details.get(i)[2]}));
            }
            List<ColumnMeta> columns = List.of(
                    meta("键", TypeCategory.STRING),
                    meta("类型", TypeCategory.STRING),
                    meta("TTL", TypeCategory.STRING),
                    meta("大小", TypeCategory.STRING),
                    // 列名跟着模式变：导出的文件头上写着「值」还是「值预览」，
                    // 拿到文件的人一眼就知道这份数据完不完整
                    meta(mode == ValueMode.FULL ? "值" : "值预览", TypeCategory.STRING));
            return QueryResult.readOnly(columns, rows, millisSince(start),
                    to < keys.size(), statement, REASON_READ_ONLY);
        } finally {
            busy = false;
        }
    }

    /** 一次管道问完这一页所有键的类型。 */
    private List<String> typesOf(List<String> keys) {
        List<String[]> commands = new ArrayList<>(keys.size());
        for (String key : keys) {
            commands.add(new String[] {"TYPE", key});
        }
        List<String> out = new ArrayList<>(keys.size());
        for (RedisClient.Reply reply : client.pipeline(commands)) {
            String type = reply.display();
            out.add(type == null || type.isBlank() ? "none" : type);
        }
        return out;
    }

    /**
     * 一次管道问完这一页每个键的 TTL、大小、值预览。
     *
     * <p>值预览按类型选命令，而且<b>一律带上限</b>：{@code GETRANGE} 只取前几百字节，
     * 集合类只取前几个元素。少了这层限制，网格里随便一屏就可能把几十兆数据拉过来，
     * 而它们连显示都显示不出来。
     *
     * @return 每个键三段：TTL、大小、值预览
     */
    private List<String[]> detailsOf(List<String> keys, List<String> types, ValueMode mode) {
        List<String[]> commands = new ArrayList<>(keys.size() * 3);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            commands.add(new String[] {"TTL", key});
            commands.add(sizeCommand(key, types.get(i)));
            commands.add(mode == ValueMode.FULL
                    ? fullCommand(key, types.get(i)) : previewCommand(key, types.get(i)));
        }
        List<RedisClient.Reply> replies = client.pipeline(commands);

        List<String[]> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            RedisClient.Reply ttl = replies.get(i * 3);
            RedisClient.Reply size = replies.get(i * 3 + 1);
            RedisClient.Reply value = replies.get(i * 3 + 2);
            String type = types.get(i);
            out.add(new String[] {
                    describeTtl(ttl.display()),
                    describeSize(size, type),
                    mode == ValueMode.FULL
                            ? fullValue(value, type, size)
                            : describePreview(value, type, size)});
        }
        return out;
    }

    /**
     * 取完整值的命令。
     *
     * <p>和预览那一套的区别只有一个字：<b>不设上限</b>。
     * 集合类的量由 {@link #fullValue} 在拿到 size 之后再把关——
     * 命令这一层不知道有多少个元素，管不了。
     */
    private static String[] fullCommand(String key, String type) {
        switch (type) {
            case "string":
                return new String[] {"GET", key};
            case "hash":
                return new String[] {"HGETALL", key};
            case "list":
                return new String[] {"LRANGE", key, "0", "-1"};
            case "set":
                return new String[] {"SMEMBERS", key};
            case "zset":
                return new String[] {"ZRANGE", key, "0", "-1", "WITHSCORES"};
            case "stream":
                return new String[] {"XRANGE", key, "-", "+"};
            default:
                return unknownTypeCommand(key);
        }
    }

    /**
     * 完整值，落盘用。
     *
     * <p>集合类写成 JSON 而不是网格里那行摘要：{@code name = 张三, city = 杭州}
     * 是给眼睛看的，逗号和等号既可能是分隔符也可能是数据本身，机器读不回去。
     *
     * <p>元素太多时<b>整个不导</b>，写一句说明。取前五万个写进去是最坏的做法：
     * 文件看不出被截过。
     */
    private static String fullValue(RedisClient.Reply reply, String type,
                                    RedisClient.Reply size) {
        if (reply.kind() == RedisClient.Kind.ERROR) {
            return "[读不到：" + reply.text() + "]";
        }
        if (type.equals("string")) {
            return reply.display();
        }
        if (tooMany(size)) {
            return "[元素过多：" + size.display() + " 个，未导出。"
                    + "请用 redis-cli --scan 之类的工具单独处理这个键]";
        }
        List<String> items = new ArrayList<>();
        reply.list().forEach(item -> items.add(item.display()));
        switch (type) {
            case "hash":
                return RedisJson.object(items);
            case "zset":
                return RedisJson.scored(items);
            case "list":
            case "set":
                return RedisJson.array(items);
            case "stream":
                return streamJson(reply);
            default:
                return describeUnknown(type);
        }
    }

    /**
     * 网格里那一行 Stream 摘要：{@code 1710000000000-0 f1=v1 · …}。
     *
     * <p>只取 ID 和字段，够看出「最近进来的是什么」——队列和消息流上，
     * 人第一眼要确认的就是这个。
     */
    private static String streamPreview(RedisClient.Reply reply) {
        StringBuilder sb = new StringBuilder();
        for (RedisClient.Reply entry : reply.list()) {
            List<RedisClient.Reply> parts = entry.list();
            if (parts.size() < 2) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(parts.get(0).display()).append(' ');
            List<RedisClient.Reply> fields = parts.get(1).list();
            for (int i = 0; i + 1 < fields.size(); i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(fields.get(i).display()).append('=').append(fields.get(i + 1).display());
            }
        }
        return sb.length() == 0 ? "[空流]" : sb.toString();
    }

    /**
     * 把 {@code XRANGE} 的回复写成 JSON。
     *
     * <h2>Stream 的回复是两层嵌套的</h2>
     * 外层每一项是一条消息，里面又是两段：消息 ID，和一个「字段、值、字段、值…」
     * 铺平了的数组。别的类型都是一层，只有它是两层——照一层的写法处理，
     * 拿到的会是一串 {@code [N 个元素]}，看着像数据其实什么都没有。
     *
     * <p>写成 {@code [{"id": "...", "fields": {...}}, ...]}：ID 单独一项，
     * 因为它不是用户写进去的字段，是服务端发的号，混进 fields 里会和真字段撞名。
     */
    private static String streamJson(RedisClient.Reply reply) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RedisClient.Reply entry : reply.list()) {
            List<RedisClient.Reply> parts = entry.list();
            if (parts.size() < 2) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            List<String> fields = new ArrayList<>();
            parts.get(1).list().forEach(f -> fields.add(f.display()));
            sb.append("{\"id\": ").append(RedisJson.string(parts.get(0).display()))
                    .append(", \"fields\": ").append(RedisJson.object(fields)).append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * 这个字符串是不是比预览取的还长。
     *
     * <p>长的话在末尾加个省略号。不加的话，一个 300 字节的值和一个正好 200 字节的值
     * 在网格里长得一模一样，看不出哪个还有后文。
     */
    private static boolean truncatedByBytes(RedisClient.Reply size) {
        try {
            return Long.parseLong(String.valueOf(size.display()).trim()) > PREVIEW_BYTES;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean tooMany(RedisClient.Reply size) {
        try {
            return Long.parseLong(String.valueOf(size.display()).trim()) > MAX_FULL_ELEMENTS;
        } catch (RuntimeException e) {
            return false; // 数不出来就照常导，总比无缘无故拒绝好
        }
    }

    private static String[] sizeCommand(String key, String type) {
        switch (type) {
            case "string":
                return new String[] {"STRLEN", key};
            case "hash":
                return new String[] {"HLEN", key};
            case "list":
                return new String[] {"LLEN", key};
            case "set":
                return new String[] {"SCARD", key};
            case "zset":
                return new String[] {"ZCARD", key};
            case "stream":
                return new String[] {"XLEN", key};
            default:
                return new String[] {"EXISTS", key};
        }
    }

    private static String[] previewCommand(String key, String type) {
        switch (type) {
            case "string":
                return new String[] {"GETRANGE", key, "0", String.valueOf(PREVIEW_BYTES - 1)};
            case "hash":
                return new String[] {"HSCAN", key, "0", "COUNT", String.valueOf(PREVIEW_ELEMENTS)};
            case "list":
                return new String[] {"LRANGE", key, "0", String.valueOf(PREVIEW_ELEMENTS - 1)};
            case "set":
                return new String[] {"SSCAN", key, "0", "COUNT", String.valueOf(PREVIEW_ELEMENTS)};
            case "zset":
                return new String[] {"ZRANGE", key, "0", String.valueOf(PREVIEW_ELEMENTS - 1),
                        "WITHSCORES"};
            case "stream":
                // - 和 + 是「从最小 ID 到最大 ID」的写法
                return new String[] {"XRANGE", key, "-", "+",
                        "COUNT", String.valueOf(PREVIEW_ELEMENTS)};
            default:
                return unknownTypeCommand(key);
        }
    }

    /**
     * 遇到不认识的类型时发什么。
     *
     * <h2>原来这里发的是 {@code TYPE}，那是个会骗人的兜底</h2>
     * {@code TYPE} 的回复是类型名本身。于是一个 stream 键在网格里的「值」那一列
     * 显示的是 {@code stream} 四个字母——<b>看着像有值，其实是类型名</b>。
     * 真机探针（{@code tools/RedisGapProbe.java}）就是这么发现 stream 没被支持的：
     * 服务端说它是 stream，工具读到的「值」也是 stream，一眼看不出哪里不对。
     *
     * <p>换成 {@code EXISTS}：回复是 1，配合下面的 {@link #describeUnknown} 显示成
     * 一句明确的「这个类型还没做」。宁可说不知道，也不要给一个像模像样的错答案。
     */
    private static String[] unknownTypeCommand(String key) {
        return new String[] {"EXISTS", key};
    }

    /** 不认识的类型在网格里显示成什么。 */
    private static String describeUnknown(String type) {
        return "[" + type + " 类型，这一版还没做展示。用 SQL 标签页发命令可以看它]";
    }

    /**
     * TTL 的三种含义要分开说。
     *
     * <p>{@code -1} 是「永不过期」，{@code -2} 是「键已经不存在了」。
     * 原样显示成 -1 / -2，看的人只会以为是脏数据。
     */
    static String describeTtl(String raw) {
        if (raw == null) {
            return "";
        }
        switch (raw.trim()) {
            case "-1":
                return "永不过期";
            case "-2":
                return "已过期";
            default:
                return raw.trim() + " 秒";
        }
    }

    private static String describeSize(RedisClient.Reply reply, String type) {
        String value = reply.display();
        if (value == null) {
            return "";
        }
        switch (type) {
            case "string":
                return value + " 字节";
            case "hash":
                return value + " 个字段";
            case "list":
            case "zset":
                return value + " 个元素";
            case "set":
                return value + " 个成员";
            default:
                return "";
        }
    }

    /** 把预览回复摆成一行文本。HSCAN / SSCAN 回的是 {@code [游标, [元素...]]}，要剥一层。 */
    private static String describePreview(RedisClient.Reply reply, String type,
                                          RedisClient.Reply size) {
        if (reply.kind() == RedisClient.Kind.ERROR) {
            return "[读不到：" + reply.text() + "]";
        }
        if (type.equals("string")) {
            // previewText 而不是 display：这一段是从第 200 个字节切下来的，
            // 尾巴上多半剩着半个汉字。用严格解码的 display，整格会变成
            // 「[二进制 200 B ...]」——一段中文看着像数据坏了
            String text = reply.previewText();
            if (text == null) {
                return reply.display();   // 确实不是 UTF-8，按二进制摘要显示
            }
            return truncatedByBytes(size) ? text + "…" : text;
        }
        if (type.equals("stream")) {
            return streamPreview(reply);
        }
        if (!reply.list().isEmpty() || reply.kind() == RedisClient.Kind.ARRAY) {
            // 正常的集合类型，继续往下走
        } else {
            // 不认识的类型：兜底命令发的是 EXISTS，回复是个 1，
            // 直接显示出来就成了「值 = 1」这种假数据
            return describeUnknown(type);
        }
        List<RedisClient.Reply> items = reply.list();
        if ((type.equals("hash") || type.equals("set")) && items.size() == 2) {
            items = items.get(1).list();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(type.equals("hash") && i % 2 == 1 ? " = " : ", ");
            }
            sb.append(items.get(i).display());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 快照

    private static final class Snapshot {
        final List<String> keys;
        final boolean truncated;
        final long takenAt;

        Snapshot(List<String> keys, boolean truncated) {
            this.keys = keys;
            this.truncated = truncated;
            this.takenAt = System.currentTimeMillis();
        }

        boolean stale() {
            return System.currentTimeMillis() - takenAt > SNAPSHOT_TTL_MILLIS;
        }
    }

    /**
     * 缓存键的分隔符。
     *
     * <p>用 {@code \0} 而不是空格：分隔的三段里，<b>搜索模式是用户随手打进去的</b>，
     * 里面完全可能有空格。用空格分隔的话，
     * （键空间 {@code a b}、模式 {@code c}）和（键空间 {@code a}、模式 {@code b c}）
     * 会算出同一个缓存键，于是换了搜索词却拿到上一次的结果——而且看不出来。
     * {@code \0} 不可能出现在这几段里。同样的写法在 {@code DbSession} 和
     * {@code DataSyncService} 里也用着。
     */
    private static final char CACHE_SEP = '\0';

    private Snapshot snapshotOf(String db, String keyspace, String pattern) {
        String cacheKey = db + CACHE_SEP + keyspace + CACHE_SEP + pattern;
        Snapshot cached = snapshots.get(cacheKey);
        if (cached != null && !cached.stale()) {
            return cached;
        }
        Snapshot fresh = scan(db, keyspace, pattern);
        snapshots.put(cacheKey, fresh);
        return fresh;
    }

    /**
     * 用 {@code SCAN} 走一遍键。
     *
     * <p>不用 {@code KEYS *}：那条命令会让服务端在整个键空间上阻塞，
     * 几百万键的生产库上足以把它卡到超时。{@code SCAN} 是分批的、可中断的，
     * 代价是不保证顺序、也可能重复返回同一个键——所以这里用 {@code LinkedHashSet} 去重。
     */
    private Snapshot scan(String db, String keyspace, String pattern) {
        useSchemaLocked(db);
        String prefix = prefixFor(keyspace);
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        String cursor = "0";
        boolean truncated = false;

        // SCAN MATCH 只收<b>一个</b>模式，而这儿常常有两个条件要同时满足：
        // 键空间的前缀，和用户搜的那个。两个 glob 没法 AND 成一个，
        // 所以把更能筛掉东西的发给服务端——有搜索词时用搜索词，因为它通常比前缀更挑，
        // 而且这样五万条的上限卡的是「命中的键」而不是「扫过的键」，
        // 能搜到的范围大得多。另一个条件在本地再过一遍，保证结果是对的
        String match;
        java.util.regex.Pattern localFilter = null;
        if (!RedisGlob.matchesEverything(pattern)) {
            match = pattern;
            if (!prefix.isEmpty()) {
                localFilter = RedisGlob.toRegex(escapeGlob(prefix) + "*");
            }
        } else {
            match = prefix.isEmpty() ? "*" : escapeGlob(prefix) + "*";
        }
        do {
            RedisClient.Reply reply = client.command(
                    "SCAN", cursor, "MATCH", match, "COUNT", String.valueOf(SCAN_BATCH));
            List<RedisClient.Reply> parts = reply.list();
            if (parts.size() != 2) {
                break;
            }
            cursor = parts.get(0).display();
            for (RedisClient.Reply item : parts.get(1).list()) {
                String key = item.display();
                if (key != null && (localFilter == null || localFilter.matcher(key).matches())) {
                    keys.add(key);
                }
                if (keys.size() >= MAX_KEYS) {
                    truncated = true;
                    break;
                }
            }
        } while (!"0".equals(cursor) && !truncated);
        return new Snapshot(new ArrayList<>(keys), truncated);
    }

    /**
     * 把前缀里的通配符转义掉。
     *
     * <p>{@code SCAN MATCH} 的模式认 {@code *}、{@code ?}、{@code []}。
     * 一个真的叫 {@code report[2024]} 的键，不转义就会被当成字符集匹配，
     * 结果是这一组键<b>一个也扫不出来</b>，看着像库里没有这些键。
     */
    static String escapeGlob(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 键值写入

    /**
     * 一次命令里最多带多少个元素。
     *
     * <p>一个上万字段的哈希，拼成一条 {@code HSET} 就是一个几兆的命令：
     * 服务端要一次性收完、解析完才开始干活，期间它是<b>阻塞</b>的
     * （Redis 处理命令是单线程的）。分批发出去，每批小一点，别人还能插空。
     */
    private static final int WRITE_CHUNK = 500;

    @Override
    public List<String> valueTypes() {
        return List.of("string", "hash", "list", "set", "zset");
    }

    // ------------------------------------------------------------------ 分布式锁

    /**
     * 比对持有者再删，两步在服务端一次做完。
     *
     * <p>这段脚本是 Redis 官方文档里释放锁的标准写法。它<b>必须</b>是脚本，
     * 不能拆成客户端的 GET + DEL：那两步之间锁可能过期并被别人拿到，
     * 删掉的就成了别人刚拿到的锁。
     */
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) else return 0 end";

    @Override
    public boolean supportsLocks() {
        return true;
    }

    @Override
    public List<KeyValueStore.LockInfo> scanLocks(String schema, String pattern, int limit) {
        synchronized (lock) {
            useSchemaLocked(schema);
            String match = pattern == null || pattern.isBlank() ? "*" : pattern.trim();
            List<String> keys = scanKeys(match, Math.max(1, limit));
            if (keys.isEmpty()) {
                return List.of();
            }
            // 一次管道问完所有键的值和 TTL：一把一把地问，几十把锁就是几十次往返
            List<String[]> commands = new ArrayList<>(keys.size() * 2);
            for (String key : keys) {
                commands.add(new String[] {"GET", key});
                commands.add(new String[] {"TTL", key});
            }
            List<RedisClient.Reply> replies = client.pipeline(commands);

            List<KeyValueStore.LockInfo> out = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                RedisClient.Reply value = replies.get(i * 2);
                RedisClient.Reply ttl = replies.get(i * 2 + 1);
                if (value.isNil()) {
                    // 扫到之后、取值之前就过期了。锁本来就是短命的，这很正常
                    continue;
                }
                out.add(new KeyValueStore.LockInfo(keys.get(i), value.display(),
                        asLong(ttl)));
            }
            return out;
        }
    }

    /**
     * 按模式扫一批键。
     *
     * <p>用 {@code SCAN} 而不是 {@code KEYS}：后者会阻塞整个服务端，
     * 键多的时候能卡住几秒——那是在生产库上绝不能做的事，而看锁的场景
     * 恰恰多半发生在生产库上。
     */
    private List<String> scanKeys(String match, int limit) {
        List<String> out = new ArrayList<>();
        String cursor = "0";
        do {
            RedisClient.Reply reply = client.command("SCAN", cursor,
                    "MATCH", match, "COUNT", "200");
            List<RedisClient.Reply> parts = reply.list();
            if (parts.size() < 2) {
                break;
            }
            cursor = parts.get(0).display();
            for (RedisClient.Reply key : parts.get(1).list()) {
                out.add(key.display());
                if (out.size() >= limit) {
                    return out;
                }
            }
        } while (!"0".equals(cursor));
        return out;
    }

    @Override
    public boolean releaseLock(String schema, String key, String expectedHolder) {
        synchronized (lock) {
            useSchemaLocked(schema);
            RedisClient.Reply reply = client.command("EVAL", RELEASE_SCRIPT, "1",
                    key, expectedHolder == null ? "" : expectedHolder);
            snapshots.clear();
            return asLong(reply) > 0;
        }
    }

    // ------------------------------------------------------------------ 队列

    @Override
    public boolean supportsQueueInspect() {
        return true;
    }

    /**
     * 看一眼队列。
     *
     * <h2>用到的命令一条都不消费</h2>
     * {@code LLEN}/{@code LRANGE}/{@code XLEN}/{@code XRANGE}/{@code XINFO GROUPS}
     * 全是纯读的。这里<b>永远不会</b>出现 {@code LPOP}、{@code RPOP}、
     * {@code XREADGROUP}——那些会把消息从队列里取走，而消费方再也收不到，
     * 且没有任何痕迹指向这个工具。
     *
     * <p>列表队列看的是队头（{@code LRANGE 0 n}）。为什么是队头不是队尾：
     * 生产方常用 {@code RPUSH} 从尾部塞、消费方用 {@code LPOP} 从头部取，
     * 所以队头那几条正是「下一批会被处理的」——积压时最想看的就是它们。
     */
    @Override
    public KeyValueStore.QueueSnapshot inspectQueue(String schema, String key, int peek) {
        synchronized (lock) {
            useSchemaLocked(schema);
            String type = client.command("TYPE", key).display();
            int count = Math.max(1, peek);
            long now = System.currentTimeMillis();

            if ("list".equals(type)) {
                long depth = asLong(client.command("LLEN", key));
                List<String> head = new ArrayList<>();
                client.command("LRANGE", key, "0", String.valueOf(count - 1)).list()
                        .forEach(r -> head.add(r.display()));
                return new KeyValueStore.QueueSnapshot(key, type, depth, head, List.of(), now);
            }
            if ("stream".equals(type)) {
                long depth = asLong(client.command("XLEN", key));
                List<String> head = new ArrayList<>();
                client.command("XRANGE", key, "-", "+", "COUNT", String.valueOf(count)).list()
                        .forEach(entry -> head.add(describeStreamEntry(entry)));
                return new KeyValueStore.QueueSnapshot(key, type, depth, head,
                        consumerGroups(key), now);
            }
            throw new DbException("「" + key + "」是 " + type
                    + " 类型，不是队列。队列观察只支持 list 和 stream。");
        }
    }

    /**
     * 消费组。
     *
     * <p>取不到就返回空表而不是抛异常：这个键可能压根没建过消费组，
     * 那是正常情况，不该让整个观察失败。
     */
    private List<KeyValueStore.ConsumerGroup> consumerGroups(String key) {
        List<KeyValueStore.ConsumerGroup> out = new ArrayList<>();
        RedisClient.Reply reply;
        try {
            reply = client.raw("XINFO", "GROUPS", key);
        } catch (RuntimeException e) {
            return out;
        }
        if (reply.kind() == RedisClient.Kind.ERROR) {
            return out;
        }
        for (RedisClient.Reply group : reply.list()) {
            List<RedisClient.Reply> flat = group.list();
            String name = "";
            long consumers = 0;
            long pending = 0;
            String lastDelivered = "";
            // lag 是 Redis 7 才有的；更早的版本给不出，用 -1 表示「不知道」
            // 而不是 0——0 会被读成「没有积压」，那是编出来的好消息
            long lag = -1;
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                String field = flat.get(i).display();
                RedisClient.Reply value = flat.get(i + 1);
                switch (field == null ? "" : field) {
                    case "name" -> name = value.display();
                    case "consumers" -> consumers = asLong(value);
                    case "pending" -> pending = asLong(value);
                    case "last-delivered-id" -> lastDelivered = value.display();
                    case "lag" -> lag = value.isNil() ? -1 : asLong(value);
                    default -> { }
                }
            }
            out.add(new KeyValueStore.ConsumerGroup(name, consumers, pending, lastDelivered, lag));
        }
        return out;
    }

    /** 一条 Stream 消息压成一行：{@code 1710000000000-0  f1=v1, f2=v2}。 */
    private static String describeStreamEntry(RedisClient.Reply entry) {
        List<RedisClient.Reply> parts = entry.list();
        if (parts.size() < 2) {
            return entry.display();
        }
        StringBuilder sb = new StringBuilder(parts.get(0).display()).append("  ");
        List<RedisClient.Reply> fields = parts.get(1).list();
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fields.get(i).display()).append('=').append(fields.get(i + 1).display());
        }
        return sb.toString();
    }

    private static long asLong(RedisClient.Reply reply) {
        try {
            return Long.parseLong(String.valueOf(reply.display()).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 发布订阅

    @Override
    public boolean supportsPubSub() {
        return true;
    }

    @Override
    public long publish(String channel, String message) {
        synchronized (lock) {
            RedisClient.Reply reply = client.command("PUBLISH", channel, message);
            try {
                return Long.parseLong(String.valueOf(reply.display()).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * 订阅走的是<b>另一条</b>连接，不是这一条。理由见 {@link RedisSubscription}。
     *
     * <p>所以这里不加 {@code synchronized}：它压根不碰 {@link #client}。
     */
    @Override
    public KeyValueStore.Subscription subscribe(
            List<String> channels, List<String> patterns,
            java.util.function.Consumer<KeyValueStore.Message> sink,
            java.util.function.Consumer<String> onError) {
        return RedisSubscription.open(config(), channels, patterns, sink, onError);
    }

    @Override
    public String searchPattern(String text) {
        return RedisGlob.fromSearchText(text);
    }

    @Override
    public KeyValueStore.Entry read(String schema, String key) {
        synchronized (lock) {
            useSchemaLocked(schema);
            String type = client.command("TYPE", key).display();
            if (type == null || type.isBlank() || type.equals("none")) {
                return null;
            }
            RedisClient.Reply size = client.command(sizeCommand(key, type));
            RedisClient.Reply value = client.command(fullCommand(key, type));
            String ttl = client.command("TTL", key).display();
            long seconds;
            try {
                seconds = Long.parseLong(String.valueOf(ttl).trim());
            } catch (RuntimeException e) {
                seconds = -1;
            }
            return new KeyValueStore.Entry(key, type, fullValue(value, type, size), seconds);
        }
    }

    @Override
    public boolean exists(String schema, String key) {
        synchronized (lock) {
            useSchemaLocked(schema);
            return "1".equals(client.command("EXISTS", key).display());
        }
    }

    /**
     * 写一个键：整体替换，然后设过期时间。
     *
     * <h2>为什么先 DEL 再写，而且要放进 MULTI</h2>
     * 类型可能变（本来是 list，改成 hash），而 Redis 不允许对已有键用另一种类型的
     * 命令——不先删就直接报 WRONGTYPE。集合类也一样：不删的话，
     * 新值是<b>并进</b>旧值而不是替换，旧的元素会留下来，而界面上显示的是新值，
     * 两边对不上。
     *
     * <p>{@code MULTI}/{@code EXEC} 在这里不是当事务用的（Redis 的 MULTI
     * 没有回滚，这一点在 {@code inTransaction} 那儿明确拒绝过）。用它是为了
     * <b>执行期间不被别的客户端插进来</b>：删掉和写回之间要是有别人来读，
     * 会读到一个不存在的键。
     */
    @Override
    public void write(String schema, KeyValueStore.Entry entry) {
        List<String[]> commands = buildWrite(entry);
        synchronized (lock) {
            useSchemaLocked(schema);
            List<String[]> batch = new ArrayList<>();
            batch.add(new String[] {"MULTI"});
            batch.addAll(commands);
            batch.add(new String[] {"EXEC"});
            checkAll(client.pipeline(batch));
            snapshots.clear();
        }
    }

    /**
     * 拼出写一个键要发的那串命令。
     *
     * <p>拆成单独一个方法是为了<b>先解析、再动手</b>：值的格式不对时在这里就抛出来，
     * 那时候还什么都没删。放进 write 里边解析边发，遇到格式错误就会停在
     * 「旧值已经删了、新值还没写」的半截状态上。
     */
    private List<String[]> buildWrite(KeyValueStore.Entry entry) {
        String key = entry.key();
        String type = entry.type();
        String value = entry.value() == null ? "" : entry.value();
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"DEL", key});

        if (type.equals("string")) {
            out.add(new String[] {"SET", key, value});
        } else {
            List<String> items = elementsOf(type, value);
            if (items.isEmpty()) {
                throw new DbException("Redis 里没有空的" + typeLabel(type)
                        + "：元素全删光，这个键本身就不存在了。"
                        + "确实想删掉整个键的话，请用「删除键」。");
            }
            String command = collectionCommand(type);
            for (int i = 0; i < items.size(); i += chunkOf(type)) {
                List<String> slice = items.subList(i, Math.min(i + chunkOf(type), items.size()));
                List<String> args = new ArrayList<>(slice.size() + 2);
                args.add(command);
                args.add(key);
                args.addAll(slice);
                out.add(args.toArray(new String[0]));
            }
        }

        if (entry.ttlSeconds() >= 0) {
            out.add(new String[] {"EXPIRE", key, String.valueOf(entry.ttlSeconds())});
        }
        return out;
    }

    /** 把用户填的值解析成要发给服务端的参数序列。 */
    private static List<String> elementsOf(String type, String value) {
        switch (type) {
            case "hash":
                return RedisJsonReader.flatObject(value);
            case "list":
            case "set":
                return RedisJsonReader.array(value);
            case "zset": {
                // 导出写的是 [成员, 分数]，而 ZADD 要的是「分数 成员」，这里对调
                List<String> pairs = RedisJsonReader.scored(value);
                List<String> out = new ArrayList<>(pairs.size());
                for (int i = 0; i + 1 < pairs.size(); i += 2) {
                    out.add(pairs.get(i + 1));
                    out.add(pairs.get(i));
                }
                return out;
            }
            default:
                throw new DbException("不认识的类型：" + type);
        }
    }

    private static String collectionCommand(String type) {
        switch (type) {
            case "hash":
                return "HSET";
            case "list":
                return "RPUSH";
            case "set":
                return "SADD";
            case "zset":
                return "ZADD";
            default:
                throw new DbException("不认识的类型：" + type);
        }
    }

    /** 成对出现的类型（哈希、有序集合）分批时不能把一对切开。 */
    private static int chunkOf(String type) {
        return type.equals("hash") || type.equals("zset") ? WRITE_CHUNK * 2 : WRITE_CHUNK;
    }

    private static String typeLabel(String type) {
        switch (type) {
            case "hash":
                return "哈希";
            case "list":
                return "列表";
            case "set":
                return "集合";
            case "zset":
                return "有序集合";
            default:
                return type;
        }
    }

    @Override
    public void rename(String schema, String from, String to) {
        synchronized (lock) {
            useSchemaLocked(schema);
            client.command("RENAME", from, to);
            snapshots.clear();
        }
    }

    @Override
    public int delete(String schema, List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            useSchemaLocked(schema);
            int deleted = 0;
            for (int i = 0; i < keys.size(); i += WRITE_CHUNK) {
                List<String> slice = keys.subList(i, Math.min(i + WRITE_CHUNK, keys.size()));
                List<String> args = new ArrayList<>(slice.size() + 1);
                args.add("DEL");
                args.addAll(slice);
                String count = client.command(args.toArray(new String[0])).display();
                try {
                    deleted += Integer.parseInt(String.valueOf(count).trim());
                } catch (RuntimeException ignored) {
                    // 数不出来就不计，返回值只用来报个数
                }
            }
            snapshots.clear();
            return deleted;
        }
    }

    /**
     * 管道里任何一条报错都要抛出来。
     *
     * <p>{@code pipeline} 刻意不抛（浏览时某个键刚好被别人删了，不该让整页读不出来），
     * 但<b>写入不一样</b>：一条失败就意味着这个键现在处于什么状态说不准，
     * 必须让用户知道，而不是安静地回一句「保存成功」。
     */
    private static void checkAll(List<RedisClient.Reply> replies) {
        for (RedisClient.Reply reply : replies) {
            if (reply.kind() == RedisClient.Kind.ERROR) {
                throw new DbException("写入失败：" + reply.text());
            }
        }
    }

    // ------------------------------------------------------------------ 写入：一律拒绝

    @Override
    public int executeUpdate(SqlDialect.PreparedSql statement, List<String> values) {
        throw new DbException(REASON_READ_ONLY);
    }

    @Override
    public int executeBatch(SqlDialect.PreparedSql statement, List<List<String>> rows) {
        throw new DbException(REASON_READ_ONLY);
    }

    @Override
    public void inTransaction(List<String> statements) {
        throw new DbException("Redis 的 MULTI/EXEC 不是事务回滚：命令进队列后一起执行，"
                + "中间某条失败其余照样生效，没有 ROLLBACK。本工具不拿它冒充事务。");
    }

    @Override
    public int executeDdlBatch(List<String> statements) {
        throw new DbException("Redis 没有 DDL");
    }

    @Override
    public String scalar(String sql) {
        QueryResult r = execute(sql, 1);
        return r.rows().isEmpty() ? null : r.rows().get(0).get(0);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
