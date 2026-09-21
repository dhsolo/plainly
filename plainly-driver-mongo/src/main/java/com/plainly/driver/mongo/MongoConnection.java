package com.plainly.driver.mongo;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条 MongoDB 连接。
 *
 * <h2>「库 / 表 / 行 / 列」在这里各对应什么</h2>
 * <ul>
 *   <li>模式（schema）→ <b>数据库</b>；</li>
 *   <li>表（table）→ <b>集合</b>；</li>
 *   <li>行 → 一篇文档；</li>
 *   <li>列 → 抽样若干篇文档凑出来的字段并集。</li>
 * </ul>
 *
 * <p>最后一条是这里和关系库最本质的差别，也是最容易让人误会的地方：
 * <b>那些列不是集合的定义</b>。同一个集合里两篇文档的字段可以完全不同，
 * 网格只能展示抽到的那些。所以 {@code hasTableStructure()} 是关的——
 * 界面上不会出现「表结构」页去暗示存在一份字段定义。
 *
 * <h2>精度</h2>
 * 所有值经 {@link MongoValues} 转成文本，Decimal128 与 Int64 一律不经过 double。
 */
public class MongoConnection implements DbConnection {

    /** 凑列时抽多少篇文档。 */
    private static final int SAMPLE = 200;

    /** Mongo 自己的系统库，默认不显示——用户要看的是自己的数据。 */
    private static final Set<String> SYSTEM_DBS = Set.of("admin", "config", "local");

    private final ConnectionConfig config;
    private final MongoClient client;
    private final MongoDialect dialect = new MongoDialect();
    private final String version;

    /** 当前库。Mongo 没有「会话默认库」这回事，由本对象自己记着。 */
    private volatile String currentDb;

    private volatile boolean closed;

    MongoConnection(ConnectionConfig config, MongoClient client, String version) {
        this.config = config;
        this.client = client;
        this.version = version;
        this.currentDb = config.database() == null || config.database().isBlank()
                ? "" : config.database();
    }

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
        return "MongoDB " + version;
    }

    // ------------------------------------------------------------------ 元数据

    @Override
    public List<SchemaInfo> listSchemas() {
        List<SchemaInfo> out = new ArrayList<>();
        try {
            for (String name : client.listDatabaseNames()) {
                if (SYSTEM_DBS.contains(name)) {
                    continue;
                }
                out.add(new SchemaInfo(name, name.equals(currentDb)));
            }
        } catch (RuntimeException e) {
            throw wrap("列出数据库失败", e);
        }
        return out;
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        List<TableInfo> out = new ArrayList<>();
        try {
            MongoDatabase db = database(schema);
            for (Document c : db.listCollections()) {
                String name = c.getString("name");
                if (name == null || name.startsWith("system.")) {
                    continue;
                }
                // 视图在 Mongo 里也从 listCollections 出来，靠 type 区分。
                // 归到 VIEW 而不是 TABLE：视图是只读的，当成表会让用户
                // 一路点到「保存」才被服务端拒绝
                boolean view = "view".equals(c.getString("type"));
                long estimate = -1;
                if (!view) {
                    try {
                        estimate = db.getCollection(name).estimatedDocumentCount();
                    } catch (RuntimeException ignored) {
                        // 估算失败不该让整棵树打不开
                    }
                }
                out.add(new TableInfo(schema, name,
                        view ? ObjectKind.VIEW : ObjectKind.TABLE, "", estimate));
            }
        } catch (RuntimeException e) {
            throw wrap("列出集合失败", e);
        }
        return out;
    }

    /**
     * 「表结构」——其实是抽样得到的字段并集。
     *
     * <p>写回那条路要用它：{@code EditBuffer} 拿这里给的 {@link ColumnInfo}
     * 去配对网格里的列。所以即使 {@code hasTableStructure()} 是关的（界面不显示结构页），
     * 这个方法仍然必须给出可用的结果。
     */
    @Override
    public TableStructure describeTable(String schema, String table) {
        List<ColumnInfo> columns = new ArrayList<>();
        Map<String, Object> sample = sampleFields(schema, table);
        int ordinal = 1;
        for (Map.Entry<String, Object> e : sample.entrySet()) {
            boolean id = MongoValues.ID.equals(e.getKey());
            columns.add(new ColumnInfo(e.getKey(), MongoValues.typeName(e.getValue()),
                    MongoValues.categoryOf(e.getValue()), 0, 0,
                    !id, id, false, null, "", ordinal++));
        }
        return new TableStructure(
                new TableInfo(schema, table, ObjectKind.TABLE, "", -1), columns, List.of());
    }

    /**
     * 抽样若干篇文档，凑出字段并集。
     *
     * <h2>为什么是并集而不是第一篇</h2>
     * 只看第一篇的话，第二篇里多出来的字段在网格里<b>整列消失</b>，
     * 用户会以为那些数据不存在。并集至少保证抽到的那些都露面。
     *
     * <p>但并集也只是抽样——第 201 篇里独有的字段仍然看不见。所以
     * 界面上必须让人能看到完整文档（双击那一格），而不是只能看这张表。
     *
     * <p>{@code _id} 强制排第一：它是主键，写回全靠它。
     */
    private Map<String, Object> sampleFields(String schema, String table) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(MongoValues.ID, null);
        try {
            for (Document doc : database(schema).getCollection(table).find().limit(SAMPLE)) {
                for (Map.Entry<String, Object> e : doc.entrySet()) {
                    // 第一次见到这个字段时记下它的值，用来定类型；
                    // 后面再见到不覆盖——同名字段类型不一致时，以先见到的为准，
                    // 反正写回时会拿那一篇文档里的真实值再核一次
                    Object existing = fields.get(e.getKey());
                    if (existing == null) {
                        fields.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (RuntimeException e) {
            throw wrap("抽样读取集合失败", e);
        }
        return fields;
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

    @Override
    public QueryResult execute(String sql, int maxRows) {
        long start = System.nanoTime();
        MongoDialect.Instruction ins = MongoDialect.parse(sql);
        if (ins == null) {
            return userCommand(sql, start);
        }
        switch (ins.verb()) {
            case MongoDialect.FIND:
                return find(ins.payload(), maxRows, start, sql);
            case MongoDialect.COUNT:
                return count(ins.payload(), start, sql);
            default:
                throw new DbException("不认识的内部指令：" + ins.verb());
        }
    }

    @Override
    public QueryResult executeQuery(SqlDialect.PreparedSql statement, List<String> values,
                                    int maxRows) {
        return execute(statement.sql(), maxRows);
    }

    private QueryResult find(Document p, int maxRows, long start, String sql) {
        String db = p.getString("db");
        String coll = p.getString("coll");
        int limit = p.getInteger("limit", 0);
        int offset = p.getInteger("offset", 0);
        if (maxRows > 0 && (limit <= 0 || limit > maxRows)) {
            limit = maxRows;
        }

        List<Document> docs = new ArrayList<>();
        try {
            FindIterable<Document> it = database(db).getCollection(coll)
                    .find(p.get("filter", Document.class));
            Document sort = p.get("sort", Document.class);
            if (sort != null && !sort.isEmpty()) {
                it = it.sort(sort);
            }
            if (offset > 0) {
                it = it.skip(offset);
            }
            if (limit > 0) {
                it = it.limit(limit);
            }
            it.forEach(docs::add);
        } catch (RuntimeException e) {
            throw wrap("查询失败", e);
        }
        return toResult(db, coll, docs, System.nanoTime() - start, sql);
    }

    private QueryResult count(Document p, long start, String sql) {
        try {
            Document filter = p.get("filter", Document.class);
            long n = database(p.getString("db")).getCollection(p.getString("coll"))
                    .countDocuments(filter == null ? new Document() : filter);
            return QueryResult.of(
                    List.of(new ColumnMeta("count", "count", "Int64", TypeCategory.INTEGER,
                            0, 0, false, "", "", false, false)),
                    List.of(new Row(new String[] {String.valueOf(n)})),
                    (System.nanoTime() - start) / 1_000_000, false, sql);
        } catch (RuntimeException e) {
            throw wrap("统计文档数失败", e);
        }
    }

    /**
     * 把一批文档摆成网格。
     *
     * <h2>列是这一批文档的并集，不是集合的定义</h2>
     * 用这一批的并集而不是另去抽样，是为了让眼前这一页里出现过的字段
     * <b>都有列</b>。翻到下一页列可能会变——那正是 Mongo 的实际情况，
     * 藏起来只会让人以为数据丢了。
     */
    private QueryResult toResult(String db, String coll, List<Document> docs,
                                 long nanos, String sql) {
        Set<String> names = new LinkedHashSet<>();
        names.add(MongoValues.ID);
        for (Document d : docs) {
            names.addAll(d.keySet());
        }
        List<String> ordered = new ArrayList<>(names);

        List<ColumnMeta> columns = new ArrayList<>(ordered.size());
        for (String name : ordered) {
            Object sample = null;
            for (Document d : docs) {
                Object v = d.get(name);
                if (v != null) {
                    sample = v;
                    break;
                }
            }
            columns.add(new ColumnMeta(name, name, MongoValues.typeName(sample),
                    MongoValues.categoryOf(sample), 0, 0,
                    !MongoValues.ID.equals(name), db, coll,
                    MongoValues.ID.equals(name), false));
        }

        List<Row> rows = new ArrayList<>(docs.size());
        for (Document d : docs) {
            String[] values = new String[ordered.size()];
            for (int i = 0; i < ordered.size(); i++) {
                values[i] = MongoValues.text(d.get(ordered.get(i)));
            }
            rows.add(new Row(values));
        }
        return QueryResult.of(columns, rows, nanos / 1_000_000, false, sql);
    }

    /**
     * 用户自己在查询页里写的东西。
     *
     * <p>这一版只认<b>一段 JSON 过滤条件</b>（如 {@code {"status": "paid"}}），
     * 配合当前库和当前集合使用；不认 {@code db.coll.find(...)} 那种 shell 写法。
     *
     * <p>为什么不做 shell 语法：那是一门 JavaScript 方言，认一半比不认更糟——
     * 用户写的 {@code db.orders.find({a:1}).limit(10)} 如果 limit 被悄悄忽略，
     * 他会以为集合里就这么多数据。
     */
    private QueryResult userCommand(String sql, long start) {
        String text = sql == null ? "" : sql.trim();
        if (text.isEmpty()) {
            throw new DbException("没有可执行的内容");
        }
        if (!text.startsWith("{")) {
            throw new DbException("这一版的 MongoDB 查询页只接受一段 JSON 过滤条件，"
                    + "例如 {\"status\": \"paid\"}，作用在左侧选中的集合上。"
                    + "还不支持 db.集合.find(...) 这样的 shell 写法。");
        }
        throw new DbException("请先在左侧选中一个集合，再用过滤条件查询。"
                + "（当前查询页还没有绑定集合）");
    }

    @Override
    public String scalar(String sql) {
        QueryResult r = execute(sql, 1);
        if (r.rows().isEmpty() || r.columns().isEmpty()) {
            return null;
        }
        return r.rows().get(0).get(0);
    }

    // ------------------------------------------------------------------ 写回

    @Override
    public int executeUpdate(SqlDialect.PreparedSql statement, List<String> values) {
        MongoDialect.Instruction ins = MongoDialect.parse(statement.sql());
        if (ins == null) {
            throw new DbException("MongoDB 上只能通过网格改数据，收到的不是内部写回指令");
        }
        try {
            switch (ins.verb()) {
                case MongoDialect.UPDATE:
                    return updateOne(ins.payload(), values);
                case MongoDialect.DELETE:
                    return deleteOne(ins.payload(), values);
                case MongoDialect.INSERT:
                    return insertOne(ins.payload(), values);
                default:
                    throw new DbException("不认识的写回指令：" + ins.verb());
            }
        } catch (DbException e) {
            throw e;
        } catch (RuntimeException e) {
            throw wrap("写回失败", e);
        }
    }

    /**
     * 改一篇文档。
     *
     * <h2>两件必须做对的事</h2>
     * <ol>
     *   <li><b>类型跟着原来的走。</b>网格里的值都是字符串，直接写回去会把
     *       一个整数字段变成字符串，而且不报错。所以先把那篇文档读出来，
     *       照着每个字段原来的类型转（{@link MongoValues#toBson}）。</li>
     *   <li><b>匹配 0 篇要当失败。</b>{@code updateOne} 没匹配上不算错，
     *       返回 0 就完事——界面会照常显示「已保存」，而库里什么也没变。
     *       所以这里明确检查并报错。</li>
     * </ol>
     */
    private int updateOne(Document p, List<String> values) {
        List<String> setFields = strings(p, "set");
        List<String> keyFields = strings(p, "keys");
        MongoCollection<Document> coll = collection(p);

        Bson where = keyFilter(coll, keyFields, values, setFields.size());
        Document existing = coll.find(where).first();
        if (existing == null) {
            throw new DbException("按 " + MongoValues.ID + " 找不到要改的那篇文档——"
                    + "它可能已被别人删掉或改了主键。刷新一下再试");
        }

        Document set = new Document();
        for (int i = 0; i < setFields.size(); i++) {
            String field = setFields.get(i);
            set.append(field, MongoValues.toBson(values.get(i), existing.get(field)));
        }
        long n = coll.updateOne(where, new Document("$set", set)).getMatchedCount();
        if (n == 0) {
            throw new DbException("没有匹配到要改的文档，改动没有生效");
        }
        return (int) n;
    }

    private int deleteOne(Document p, List<String> values) {
        List<String> keyFields = strings(p, "keys");
        MongoCollection<Document> coll = collection(p);
        long n = coll.deleteOne(keyFilter(coll, keyFields, values, 0)).getDeletedCount();
        if (n == 0) {
            throw new DbException("没有匹配到要删的文档——它可能已经被删掉了");
        }
        return (int) n;
    }

    /**
     * 插一篇新文档。
     *
     * <p>没填 {@code _id} 时不自己造一个：交给服务端生成 ObjectId。
     * 自己造的话，格式和服务端的时间戳约定对不上，之后按插入时间排序就会乱。
     */
    private int insertOne(Document p, List<String> values) {
        List<String> fields = strings(p, "fields");
        Document doc = new Document();
        for (int i = 0; i < fields.size() && i < values.size(); i++) {
            String field = fields.get(i);
            String text = values.get(i);
            if (MongoValues.ID.equals(field)) {
                if (text != null && !text.isBlank()) {
                    doc.append(field, MongoValues.toId(text, null));
                }
                continue;
            }
            doc.append(field, MongoValues.inferBson(text));
        }
        if (doc.isEmpty()) {
            throw new DbException("新文档一个字段都没填");
        }
        collection(p).insertOne(doc);
        return 1;
    }

    /**
     * 按主键定位。
     *
     * @param valueOffset 主键值在 {@code values} 里从第几个开始——
     *                    改值那条路上前面还排着被 SET 的那些值
     */
    private Bson keyFilter(MongoCollection<Document> coll, List<String> keyFields,
                           List<String> values, int valueOffset) {
        List<Bson> parts = new ArrayList<>(keyFields.size());
        for (int i = 0; i < keyFields.size(); i++) {
            String field = keyFields.get(i);
            String text = values.get(valueOffset + i);
            // 拿库里的一篇文档探一下这个键的真实类型：_id 绝大多数是 ObjectId，
            // 但完全可以是字符串或整数。猜错不会报错，只会匹配不到任何文档
            Object sample = probeType(coll, field);
            parts.add(Filters.eq(field, MongoValues.toId(text, sample)));
        }
        return parts.size() == 1 ? parts.get(0) : Filters.and(parts);
    }

    private Object probeType(MongoCollection<Document> coll, String field) {
        try {
            Document any = coll.find(Filters.exists(field)).limit(1).first();
            return any == null ? null : any.get(field);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public int executeBatch(SqlDialect.PreparedSql statement, List<List<String>> rows) {
        int n = 0;
        for (List<String> row : rows) {
            n += executeUpdate(statement, row);
        }
        return n;
    }

    // ------------------------------------------------------------------ 其余契约

    @Override
    public void useSchema(String schema) {
        if (schema != null && !schema.isBlank()) {
            currentDb = schema;
        }
    }

    @Override
    public void inTransaction(List<String> statements) {
        throw new DbException("单机部署的 MongoDB 不支持多文档事务（那需要副本集）。"
                + "这里的每一次保存都是逐篇文档生效的。");
    }

    @Override
    public int executeDdlBatch(List<String> statements) {
        throw new DbException("MongoDB 没有 DDL：集合和数据库都是写进去才存在的");
    }

    /**
     * 取消正在跑的查询。
     *
     * <p>官方驱动没给「取消当前操作」的口子——真要中断得靠
     * {@code killOp} 加上操作 id，而那个 id 这条路上拿不到。
     * 与其假装取消了，不如什么也不做：界面上那个按钮会照常变灰，
     * 但数据不会出现「以为停了其实还在跑」的错觉。
     */
    @Override
    public void cancel() {
        // 见方法注释：故意不做
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        client.close();
    }

    // ------------------------------------------------------------------ 内部

    private MongoDatabase database(String schema) {
        String name = schema == null || schema.isBlank() ? currentDb : schema;
        if (name == null || name.isBlank()) {
            throw new DbException("还没选数据库");
        }
        return client.getDatabase(name);
    }

    private MongoCollection<Document> collection(Document p) {
        return database(p.getString("db")).getCollection(p.getString("coll"));
    }

    private static List<String> strings(Document p, String key) {
        List<?> raw = p.getList(key, Object.class);
        List<String> out = new ArrayList<>();
        if (raw != null) {
            raw.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    /**
     * 把驱动的异常换成本项目的，并带上一句人话。
     *
     * <p>Mongo 的报错常常只有一个错误码（如 {@code error 13 (Unauthorized)}），
     * 前面不加一句「在做什么」的话，用户看到的是一串对不上号的英文。
     */
    private static DbException wrap(String what, RuntimeException e) {
        return new DbException(what + "：" + e.getMessage(), e);
    }
}
