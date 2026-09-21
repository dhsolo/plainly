package com.plainly.driver.mongo;

import com.plainly.driver.DbException;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.query.FilterSpec;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 的「方言」。
 *
 * <p>Mongo 没有 SQL，所以本类的工作和 {@code RedisDialect} 一样，一半是把做不到的事
 * 说清楚，另一半是产出一段<b>明显不是 SQL 的私有指令</b>，由 {@link MongoConnection} 解读。
 *
 * <h2>指令为什么用 JSON 载荷，而不是像 Redis 那样按行切</h2>
 * Redis 的指令是按换行分段的，那是因为那个模块里没有 JSON 解析器，
 * 而它要传的东西（库号、前缀、上限）形状固定。
 *
 * <p>这里传的是<b>字段名列表</b>，而 Mongo 的字段名几乎什么字符都能放，
 * 包括换行。按行切的话，某个真实字段名迟早会把指令切错位——
 * 而切错位不会报错，只会去改一个名字不对的字段。
 *
 * <p>这个模块本来就带着 bson 的 JSON 解析器，用它最省事也最不会出错：
 *
 * <pre>
 * PLAINLY-MONGO UPDATE
 * {"db": "shop", "coll": "orders", "set": ["name", "note"], "keys": ["_id"]}
 * </pre>
 *
 * <p>第一行写成 {@code PLAINLY-MONGO} 是为了万一它漏到界面上（错误提示、历史记录、
 * 日志），一眼能看出这不是用户写的语句，而是本工具内部的东西。
 */
public class MongoDialect implements SqlDialect {

    public static final String MARKER = "PLAINLY-MONGO";

    public static final String FIND = MARKER + " FIND";
    public static final String COUNT = MARKER + " COUNT";
    public static final String UPDATE = MARKER + " UPDATE";
    public static final String DELETE = MARKER + " DELETE";
    public static final String INSERT = MARKER + " INSERT";

    /** 一条私有指令拆开之后的样子。 */
    record Instruction(String verb, Document payload) {
    }

    /**
     * 认一条私有指令；不是本工具发的就返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常，是因为调用方还要接住另一种输入：用户在查询页里
     * 自己写的 Mongo 命令。两者都从同一个入口进来。
     */
    static Instruction parse(String text) {
        if (text == null || !text.startsWith(MARKER)) {
            return null;
        }
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return null;
        }
        String verb = text.substring(0, nl).trim();
        try {
            return new Instruction(verb, Document.parse(text.substring(nl + 1)));
        } catch (RuntimeException e) {
            throw new DbException("内部指令解析失败：" + text, e);
        }
    }

    private static String command(String verb, Document payload) {
        return verb + "\n" + payload.toJson();
    }

    // ------------------------------------------------------------------ 取数

    static String findCommand(String db, String coll, int limit, int offset,
                              Document filter, Document sort) {
        return command(FIND, new Document("db", db).append("coll", coll)
                .append("limit", limit).append("offset", Math.max(0, offset))
                .append("filter", filter == null ? new Document() : filter)
                .append("sort", sort == null ? new Document() : sort));
    }

    static String countCommand(String db, String coll, Document filter) {
        return command(COUNT, new Document("db", db).append("coll", coll)
                .append("filter", filter == null ? new Document() : filter));
    }

    @Override
    public String selectPage(String schema, String table, String orderBy, int limit, int offset) {
        return findCommand(schema, table, limit, offset, new Document(), sortOf(orderBy));
    }

    @Override
    public PreparedSql selectPage(String schema, String table, FilterSpec filter,
                                  List<ColumnInfo> columns, int limit, int offset) {
        return new PreparedSql(
                findCommand(schema, table, limit, offset, filterOf(filter), sortOf(filter)),
                List.of());
    }

    @Override
    public String countRows(String schema, String table) {
        return countCommand(schema, table, new Document());
    }

    @Override
    public PreparedSql countRows(String schema, String table, FilterSpec filter,
                                 List<ColumnInfo> columns) {
        return new PreparedSql(countCommand(schema, table, filterOf(filter)), List.of());
    }

    /**
     * 把通用筛选条件翻成 Mongo 的查询文档。
     *
     * <h2>翻不动的条件必须当场拒绝，不能忽略</h2>
     * 少下推一个条件，用户拿到的是一份<b>看着像筛过</b>的结果，
     * 然后据此得出「没有符合条件的数据」这种结论。宁可报错说这条筛不了。
     *
     * <p>「包含」翻成正则而不是 {@code $text}：后者要先建全文索引，没索引时直接报错，
     * 而用户只是想找一段子串。正则不需要索引（代价是全表扫，量大时慢——
     * 慢是看得见的，查不到是看不见的）。
     */
    static Document filterOf(FilterSpec filter) {
        Document out = new Document();
        if (filter == null || filter.conditions().isEmpty()) {
            return out;
        }
        List<Document> ands = new ArrayList<>();
        List<Document> ors = new ArrayList<>();
        for (FilterSpec.Condition c : filter.conditions()) {
            Document one = conditionOf(c);
            if (c.combiner() == FilterSpec.Combiner.OR && !ands.isEmpty()) {
                ors.add(one);
            } else {
                ands.add(one);
            }
        }
        if (!ors.isEmpty()) {
            // 有 OR 的时候整体语义是「(全部 AND 的) 或 (任意一个 OR 的)」。
            // 这和 SQL 里不带括号的 AND/OR 优先级不完全一样，所以界面上
            // 混用两种连接词时，筛选对话框会提示按这个语义解释
            List<Document> branches = new ArrayList<>();
            branches.add(ands.size() == 1 ? ands.get(0) : new Document("$and", ands));
            branches.addAll(ors);
            out.append("$or", branches);
            return out;
        }
        if (ands.size() == 1) {
            return ands.get(0);
        }
        return out.append("$and", ands);
    }

    private static Document conditionOf(FilterSpec.Condition c) {
        String field = c.column();
        String v = c.value();
        switch (c.operator()) {
            case EQ:
                return new Document(field, MongoValues.inferBson(v));
            case NE:
                return new Document(field, new Document("$ne", MongoValues.inferBson(v)));
            case GT:
                return new Document(field, new Document("$gt", MongoValues.inferBson(v)));
            case GE:
                return new Document(field, new Document("$gte", MongoValues.inferBson(v)));
            case LT:
                return new Document(field, new Document("$lt", MongoValues.inferBson(v)));
            case LE:
                return new Document(field, new Document("$lte", MongoValues.inferBson(v)));
            case LIKE:
                return new Document(field, new Document("$regex", quoteRegex(v)));
            case NOT_LIKE:
                return new Document(field,
                        new Document("$not", new Document("$regex", quoteRegex(v))));
            case BETWEEN:
                return new Document(field, new Document("$gte", MongoValues.inferBson(v))
                        .append("$lte", MongoValues.inferBson(c.value2())));
            case IS_NULL:
                // Mongo 里「字段是 null」和「压根没这个字段」是两件事，
                // 而 {field: null} 两者都匹配——这正是用户在网格里看到 (NULL) 时想找的
                return new Document(field, (Object) null);
            case IS_NOT_NULL:
                return new Document(field, new Document("$ne", null));
            default:
                throw new DbException("MongoDB 上还不支持「" + c.operator().label() + "」这个条件");
        }
    }

    /**
     * 把用户输入当成<b>纯文本</b>去匹配，而不是正则。
     *
     * <p>用户在「包含」里写的是要找的内容，不是正则表达式。不转义的话，
     * 一个再普通不过的搜索词（{@code a.b}、{@code 1+1}、{@code (x)}）
     * 会被当成正则里的元字符，匹配出一堆不相干的东西——或者直接语法错。
     */
    static String quoteRegex(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if ("\\.[]{}()*+?^$|/".indexOf(ch) >= 0) {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    static Document sortOf(FilterSpec filter) {
        Document sort = new Document();
        if (filter != null) {
            for (FilterSpec.Sort s : filter.sorts()) {
                sort.append(s.column(), s.descending() ? -1 : 1);
            }
        }
        return sort;
    }

    /** 导出/传输那条路给的是一段 SQL 式的 ORDER BY 文本，这里只认最简单的形式。 */
    static Document sortOf(String orderBy) {
        Document sort = new Document();
        if (orderBy == null || orderBy.isBlank()) {
            return sort;
        }
        for (String part : orderBy.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean desc = t.toUpperCase(java.util.Locale.ROOT).endsWith(" DESC");
            String name = t.replaceAll("(?i)\\s+(ASC|DESC)$", "").trim();
            if (!name.isEmpty()) {
                sort.append(name, desc ? -1 : 1);
            }
        }
        return sort;
    }

    // ------------------------------------------------------------------ 写回

    @Override
    public PreparedSql buildUpdate(String schema, String table,
                                   List<ColumnInfo> setColumns, List<ColumnInfo> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new DbException("定位不到 _id，无法生成写回指令");
        }
        return new PreparedSql(command(UPDATE, new Document("db", schema).append("coll", table)
                .append("set", names(setColumns)).append("keys", names(keyColumns))),
                concat(setColumns, keyColumns));
    }

    @Override
    public PreparedSql buildDelete(String schema, String table, List<ColumnInfo> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new DbException("定位不到 _id，无法生成删除指令");
        }
        return new PreparedSql(command(DELETE, new Document("db", schema).append("coll", table)
                .append("keys", names(keyColumns))), List.copyOf(keyColumns));
    }

    @Override
    public PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns) {
        return new PreparedSql(command(INSERT, new Document("db", schema).append("coll", table)
                .append("fields", names(columns))), List.copyOf(columns));
    }

    private static List<String> names(List<ColumnInfo> columns) {
        List<String> out = new ArrayList<>(columns.size());
        columns.forEach(c -> out.add(c.name()));
        return out;
    }

    private static List<ColumnInfo> concat(List<ColumnInfo> a, List<ColumnInfo> b) {
        List<ColumnInfo> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    // ------------------------------------------------------------------ 标识符

    /**
     * Mongo 的集合名和字段名没有「引用」这回事，原样用。
     *
     * <p>加引号会真的去找一个带引号的名字，然后「查不到」，且看不出原因。
     */
    @Override
    public String quote(String identifier) {
        return identifier;
    }

    @Override
    public String limitOffset(int limit, int offset) {
        return "";
    }

    // ------------------------------------------------------------------ 能力边界

    /**
     * 集合没有固定结构。
     *
     * <p>这个开关关掉之后，界面上「表结构 / 索引 / 外键 / 触发器」那几个页
     * 都不会出现——它们在这里没有对应物。网格里那些「列」是本工具
     * <b>抽样若干篇文档凑出来的</b>，不是集合的定义，同一个集合里
     * 两篇文档的字段完全可以不一样。
     */
    @Override
    public boolean hasTableStructure() {
        return false;
    }

    @Override
    public boolean supports(TableChange change) {
        return false;
    }

    @Override
    public String unsupportedReason(TableChange change) {
        return "MongoDB 的集合没有结构可改：字段挂在每一篇文档上，不挂在集合上。"
                + "网格里的列是抽样文档凑出来的，改它没有对应的操作。"
                + "要改数据请直接改文档。";
    }

    @Override
    public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
        throw new DbException(unsupportedReason(change));
    }

    @Override
    public String schemaCreationUnsupportedReason() {
        // Mongo 的库是写进去才存在的，没有 CREATE DATABASE
        return "MongoDB 的数据库不用建：往一个不存在的库里写第一篇文档，它就出现了；"
                + "库里没有任何集合时它又会消失。所以这里没有「新建数据库」这个动作。";
    }

    @Override
    public String triggerUnsupportedReason() {
        return "MongoDB 没有触发器。要在数据变化时做点什么，靠的是变更流"
                + "（Change Streams），那需要副本集，而且是另一个进程的事。";
    }

    @Override
    public String createTriggerTemplate(String schema, String table, String triggerName) {
        return null;
    }

    @Override
    public String explainQuery(String sql) {
        return null;
    }

    @Override
    public String viewDefinitionQuery(String schema, String view) {
        return null;
    }

    @Override
    public String triggersQuery(String schema, String table) {
        return null;
    }

    @Override
    public String createIndexDdl(String schema, String table, IndexInfo index) {
        throw new DbException("这一版还没做 MongoDB 的索引管理");
    }

    @Override
    public String dropIndexDdl(String schema, String table, String indexName) {
        throw new DbException("这一版还没做 MongoDB 的索引管理");
    }

    @Override
    public String truncateTableDdl(String schema, String table) {
        throw new DbException("要清空一个集合，请删掉它再让它自己重新出现，"
                + "或者逐条删除文档——本工具不提供一键清空。");
    }

    @Override
    public String dropTableDdl(String schema, String table) {
        throw new DbException("这一版还没做删除集合");
    }

    @Override
    public String columnDefinition(ColumnDraft column) {
        throw new DbException("MongoDB 没有列定义");
    }

    @Override
    public boolean supportsTransactionalDdl() {
        return false;
    }

    /**
     * 结果集的来源集合，驱动这边是知道的。
     *
     * <p>这一条开着，网格才判得出「这份结果能写回哪个集合」。
     */
    @Override
    public boolean reportsResultSetTableNames() {
        return true;
    }
}
