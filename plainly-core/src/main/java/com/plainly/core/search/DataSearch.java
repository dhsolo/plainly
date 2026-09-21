package com.plainly.core.search;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbConnection;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 在整个库里找一个值。
 *
 * <p>解决的是这么一件事：手上只有一个订单号 / 一个手机号 / 一个报错里蹦出来的 id，
 * 但不知道它躺在哪张表的哪一列里。逐张表点开去看，几十张表就能耗掉一下午。
 *
 * <h2>为什么只搜文本列</h2>
 * 把数字列一起搜进来，就得先把它转成文本再比较。「转成文本」这一步各家写法不同
 * （{@code CAST}、{@code ::text}、{@code CONVERT}），更要命的是转换本身会改变值的样子：
 * 存的是 {@code 1.50}，转出来可能是 {@code 1.5}；存的是 {@code 1E+2}，转出来可能是 {@code 100}。
 * 那样「搜到的」和「实际存的」就对不上了，而这个工具的全部价值恰恰在于对得上。
 * 所以宁可少搜一类列，也要在界面上把这条边界讲明白。
 *
 * <h2>为什么在 Java 里再判一次是哪一列命中</h2>
 * 数据库说这一行匹配，但它没说是哪一列匹配的。行已经取回来了，值就在手上，
 * 在这边扫一遍即可——不必为了知道「是哪一列」再往数据库发 N 次查询。
 *
 * <h2>大小写要自己统一</h2>
 * {@code LIKE} 区不区分大小写取决于列的排序规则：H2 和 PostgreSQL 区分，
 * MySQL 的默认排序规则不区分。同一个词在两个库上搜出来的结果不一样，用户完全无从察觉，
 * 只会觉得「这个功能在那个库上不好使」。所以大小写由我们显式决定
 * （{@code LOWER()} 两边都压平），并把开关摆在界面上。
 */
public final class DataSearch {

    /** 怎么算匹配。 */
    public enum Mode {
        CONTAINS("包含"),
        EQUALS("完全相同"),
        STARTS("以此开头");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * LIKE 的转义符。
     *
     * <p>不用反斜杠：MySQL 默认把反斜杠当字符串转义符，同一个反斜杠会被解释两次，
     * 写出来的语句在 MySQL 和 PostgreSQL 上行为不一样。{@code ESCAPE} 子句可以指定
     * 任意字符，挑一个罕见的即可。
     */
    private static final char ESCAPE = '!';

    /** 一条命中。 */
    public record Match(String schema, String table, String column, String value,
                        String locator) {
    }

    /**
     * 一张表的搜索结果。
     *
     * @param skipped 被跳过的原因；null 表示这张表真的搜过了
     * @param error   出错的原因；null 表示没出错
     * @param note    搜是搜了，但降级过一次；null 表示按原计划搜的
     */
    public record TableOutcome(String table, int matchedRows, int scannedColumns,
                               boolean truncated, String skipped, String error, String note) {
    }

    /** 整次搜索的结果。 */
    public record Result(List<Match> matches, List<TableOutcome> tables, long elapsedMillis,
                         boolean cancelled) {

        public int matchedTables() {
            return (int) tables.stream().filter(t -> t.matchedRows() > 0).count();
        }
    }

    private DataSearch() {
    }

    /**
     * 搜。
     *
     * @param perTableLimit 每张表最多取回多少行。上限是必需的：一个常见片段配一张千万行的表，
     *                      没有上限就是把整个库拉进内存
     * @param ignoreCase    忽略大小写。<b>这一项必须给用户选，而且默认要开</b>：
     *                      {@code LIKE} 区不区分大小写取决于列的排序规则，
     *                      H2 和 PostgreSQL 区分、MySQL 默认不区分——同一个词在两个库上
     *                      搜出来的结果不一样，而用户完全无从察觉。开着它就统一成
     *                      「都不区分」；代价是 {@code LOWER()} 用不上索引，
     *                      所以「以此开头」这种本来能走索引的模式留了关掉的余地
     * @param progress      每开始搜一张表回调一次，用于界面上的进度
     * @param cancelled     每张表之间检查一次；用户按了取消就不再往下搜
     */
    public static Result run(DbConnection conn, String schema, List<String> tables,
                             String needle, Mode mode, int perTableLimit, boolean ignoreCase,
                             Consumer<String> progress, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        List<Match> matches = new ArrayList<>();
        List<TableOutcome> outcomes = new ArrayList<>();
        boolean stopped = false;

        SqlDialect dialect = conn.dialect();
        String raw = needle == null ? "" : needle;
        String pattern = pattern(ignoreCase ? raw.toLowerCase(Locale.ROOT) : raw, mode);
        String compare = ignoreCase ? raw.toLowerCase(Locale.ROOT) : raw;

        for (String table : tables) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stopped = true;
                break;
            }
            if (progress != null) {
                progress.accept(table);
            }
            try {
                outcomes.add(searchOne(conn, dialect, schema, table, pattern, compare,
                        mode, perTableLimit, ignoreCase, matches));
            } catch (RuntimeException e) {
                // 一张表搜不了（权限、锁、驱动毛病）不该让剩下的都白搜
                outcomes.add(new TableOutcome(table, 0, 0, false, null, rootMessage(e), null));
            }
        }
        return new Result(matches, outcomes,
                (System.nanoTime() - start) / 1_000_000L, stopped);
    }

    private static TableOutcome searchOne(DbConnection conn, SqlDialect dialect, String schema,
                                          String table, String pattern, String compare,
                                          Mode mode, int limit, boolean ignoreCase,
                                          List<Match> sink) {
        TableStructure structure = conn.describeTable(schema, table);

        List<ColumnInfo> stringColumns = structure.columns().stream()
                .filter(c -> c.category() == TypeCategory.STRING)
                .toList();
        List<ColumnInfo> jsonColumns = structure.columns().stream()
                .filter(c -> c.category() == TypeCategory.JSON)
                .toList();

        List<ColumnInfo> searchable = new ArrayList<>(stringColumns);
        searchable.addAll(jsonColumns);
        if (searchable.isEmpty()) {
            return new TableOutcome(table, 0, 0, false, "没有文本列", null, null);
        }

        try {
            return query(conn, dialect, schema, table, structure, searchable, pattern, compare,
                    mode, limit, ignoreCase, sink, null);
        } catch (RuntimeException first) {
            // PostgreSQL 的 jsonb 不能直接和文本做 LIKE，LOWER() 也不接受它。
            // 因为一个 JSON 列就让整张表搜不了太亏——退回只搜纯文本列，并说明降过级
            if (jsonColumns.isEmpty() || stringColumns.isEmpty()) {
                throw first;
            }
            return query(conn, dialect, schema, table, structure, stringColumns, pattern,
                    compare, mode, limit, ignoreCase, sink,
                    "JSON 列在这个库上不能直接做文本比较，已跳过它们只搜纯文本列");
        }
    }

    private static TableOutcome query(DbConnection conn, SqlDialect dialect, String schema,
                                      String table, TableStructure structure,
                                      List<ColumnInfo> textColumns,
                                      String pattern, String compare, Mode mode, int limit,
                                      boolean ignoreCase, List<Match> sink, String note) {
        // 取回的列 = 主键列 + 文本列。主键是为了把命中的行指回去；
        // 没有主键时下面退化成「第几行」，那对用户帮助有限，但总比什么都不说强。
        List<ColumnInfo> pk = structure.columns().stream()
                .filter(ColumnInfo::primaryKey).toList();
        Map<String, ColumnInfo> projection = new LinkedHashMap<>();
        pk.forEach(c -> projection.put(c.name(), c));
        textColumns.forEach(c -> projection.put(c.name(), c));

        StringBuilder sql = new StringBuilder("SELECT ");
        boolean first = true;
        for (ColumnInfo c : projection.values()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append(dialect.quote(c.name()));
        }
        sql.append(" FROM ").append(dialect.qualify(schema, table)).append(" WHERE ");
        for (int i = 0; i < textColumns.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            String column = dialect.quote(textColumns.get(i).name());
            // LOWER 是标准 SQL，各家都有；代价是用不上索引，而这个功能本来就是全表扫
            sql.append(ignoreCase ? "LOWER(" + column + ")" : column)
                    .append(" LIKE ? ESCAPE ").append(quotedEscapeChar());
        }

        List<String> values = new ArrayList<>(textColumns.size());
        for (int i = 0; i < textColumns.size(); i++) {
            values.add(pattern);
        }

        QueryResult result = conn.executeQuery(
                new SqlDialect.PreparedSql(sql.toString(), textColumns), values, limit);

        Set<String> pkNames = new LinkedHashSet<>();
        pk.forEach(c -> pkNames.add(c.name()));
        Set<String> searched = new LinkedHashSet<>();
        textColumns.forEach(c -> searched.add(c.name()));

        int rowNumber = 0;
        for (Row row : result.rows()) {
            rowNumber++;
            String locator = locator(result.columns(), row, pkNames, rowNumber);
            for (int i = 0; i < result.columns().size(); i++) {
                ColumnMeta meta = result.columns().get(i);
                if (!searched.contains(meta.name())) {
                    continue; // 主键列是为了定位才取的，本身不参与匹配
                }
                if (hits(row.get(i), compare, mode, ignoreCase)) {
                    sink.add(new Match(schema, table, meta.name(), row.get(i), locator));
                }
            }
        }
        return new TableOutcome(table, result.rows().size(), textColumns.size(),
                result.truncated(), null, null, note);
    }

    private static String quotedEscapeChar() {
        return "'" + ESCAPE + "'";
    }

    /** 命中行的「回去怎么找」：有主键就写主键，没有就只能说第几行。 */
    private static String locator(List<ColumnMeta> columns, Row row, Set<String> pkNames,
                                  int rowNumber) {
        if (pkNames.isEmpty()) {
            return "第 " + rowNumber + " 行（该表无主键，定位不到具体行）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (!pkNames.contains(columns.get(i).name())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            String v = row.get(i);
            sb.append(columns.get(i).name()).append(" = ").append(v == null ? "NULL" : v);
        }
        return sb.toString();
    }

    /**
     * 在 Java 这边判一次是哪一列命中。
     *
     * <p>数据库只说了「这一行匹配」，没说是哪一列。行已经在手上，扫一遍就知道了，
     * 不必为此再往数据库发 N 次查询。
     *
     * <p>大小写的处理必须和发出去的 SQL 保持一致，否则会出现
     * 「数据库说这行匹配，界面上却一列都不高亮」——那看起来就像软件坏了。
     */
    static boolean hits(String value, String needle, Mode mode, boolean ignoreCase) {
        if (value == null) {
            return false;
        }
        String v = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
        switch (mode) {
            case EQUALS:
                return v.equals(needle);
            case STARTS:
                return v.startsWith(needle);
            case CONTAINS:
            default:
                return v.contains(needle);
        }
    }

    /** 把用户敲的文本变成 LIKE 模式，其中的 % 和 _ 按字面处理。 */
    static String pattern(String needle, Mode mode) {
        String escaped = escapeLike(needle == null ? "" : needle);
        switch (mode) {
            case EQUALS:
                return escaped;
            case STARTS:
                return escaped + "%";
            case CONTAINS:
            default:
                return "%" + escaped + "%";
        }
    }

    /** 用户搜的 {@code 50%} 就是 {@code 50%}，不是「50 开头的任意值」。 */
    static String escapeLike(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' || c == '_' || c == ESCAPE) {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }
}
