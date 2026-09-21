package com.plainly.driver;

import java.util.Collections;
import java.util.List;

/**
 * 一次查询的结果。
 *
 * <p>DML/DDL 语句返回 {@code columns} 为空、{@code updateCount >= 0} 的结果；
 * SELECT 返回 {@code updateCount = -1}。
 */
public final class QueryResult {

    private final List<ColumnMeta> columns;
    private final List<Row> rows;
    private final int updateCount;
    private final long elapsedMillis;
    private final boolean truncated;
    private final String statement;

    /**
     * 由驱动直接给定的只读原因；null 表示按列的元数据自行判断。
     *
     * <p>给非关系型的驱动用。Redis 的一屏「键 / 类型 / TTL / 值」不是某张表的行，
     * 按主键那套规则去判，会得出「表 xxx 没有主键」这种驴唇不对马嘴的解释——
     * 用户照着去建主键，只会更糊涂。
     */
    private final String forcedReadOnlyReason;

    private QueryResult(List<ColumnMeta> columns, List<Row> rows, int updateCount,
                        long elapsedMillis, boolean truncated, String statement) {
        this(columns, rows, updateCount, elapsedMillis, truncated, statement, null);
    }

    private QueryResult(List<ColumnMeta> columns, List<Row> rows, int updateCount,
                        long elapsedMillis, boolean truncated, String statement,
                        String forcedReadOnlyReason) {
        this.forcedReadOnlyReason = forcedReadOnlyReason;
        this.columns = Collections.unmodifiableList(columns);
        this.rows = Collections.unmodifiableList(rows);
        this.updateCount = updateCount;
        this.elapsedMillis = elapsedMillis;
        this.truncated = truncated;
        this.statement = statement;
    }

    public static QueryResult of(List<ColumnMeta> columns, List<Row> rows,
                                 long elapsedMillis, boolean truncated, String statement) {
        return new QueryResult(columns, rows, -1, elapsedMillis, truncated, statement);
    }

    public static QueryResult updated(int updateCount, long elapsedMillis, String statement) {
        return new QueryResult(List.of(), List.of(), updateCount, elapsedMillis, false, statement);
    }

    /**
     * 一份只读的结果，并自带解释。
     *
     * <p>给列的元数据说明不了情况的驱动用——理由由驱动自己写，
     * 界面照原样显示。
     */
    public static QueryResult readOnly(List<ColumnMeta> columns, List<Row> rows,
                                       long elapsedMillis, boolean truncated, String statement,
                                       String reason) {
        return new QueryResult(columns, rows, -1, elapsedMillis, truncated, statement, reason);
    }

    public List<ColumnMeta> columns() {
        return columns;
    }

    public List<Row> rows() {
        return rows;
    }

    /** 受影响行数；SELECT 为 -1。 */
    public int updateCount() {
        return updateCount;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    /** 是否因为达到取回上限而被截断（还有更多行没取）。 */
    public boolean truncated() {
        return truncated;
    }

    public String statement() {
        return statement;
    }

    public boolean isResultSet() {
        return updateCount < 0;
    }

    /** 结果集是否可编辑：所有列同源于一张表，且能定位到主键。 */
    public boolean isEditable() {
        return readOnlyReason() == null;
    }

    /**
     * 不能编辑的原因；能编辑时返回 null。
     *
     * <p>「双击没反应」和「这张表没有主键所以改不了」，对用户是两件完全不同的事：
     * 前者像软件坏了，后者知道该去建个主键。判据本身就摆在这儿，
     * 顺手把理由一起说清楚，比让人自己猜省事得多。
     */
    public String readOnlyReason() {
        if (forcedReadOnlyReason != null) {
            return forcedReadOnlyReason;
        }
        if (!isResultSet()) {
            return "这不是查询结果";
        }
        if (columns.isEmpty()) {
            return "结果集没有列";
        }
        String table = null;
        String schema = null;
        boolean hasKey = false;
        for (ColumnMeta c : columns) {
            if (c.tableName() == null || c.tableName().isBlank()) {
                return "结果里有不属于任何表的列（表达式、函数或聚合），改了没处回写";
            }
            if (table == null) {
                table = c.tableName();
                schema = nz(c.schemaName());
            } else if (!table.equals(c.tableName())) {
                return "结果来自多张表（" + table + "、" + c.tableName() + "），改了不知道该写回哪张";
            } else if (!schema.equals(nz(c.schemaName()))) {
                // 同名不同模式：SELECT * FROM s1.t JOIN s2.t 两边表名一样，
                // 只比表名会把它当成单表放行，然后把两张表的列一起写进其中一张
                return "结果来自两个模式里的同名表（" + schema + "." + table
                        + "、" + c.schemaName() + "." + c.tableName() + "），改了不知道该写回哪张";
            }
            if (c.partOfKey()) {
                hasKey = true;
            }
        }
        if (!hasKey) {
            return "表 " + table + " 没有主键，定位不到要改的是哪一行";
        }
        return null;
    }

    /**
     * 把结果集的列重新归到某张表名下，并按给定的主键列重算「是不是主键」。
     *
     * <h2>什么时候需要它</h2>
     * Oracle 的 JDBC 驱动不报结果集的来源表名（见
     * {@code SqlDialect.reportsResultSetTableNames}），于是每一列的 tableName 都是空的，
     * 结果集因此被判成只读——Oracle 上<b>每一张表</b>的数据网格都改不了。
     *
     * <p>但打开一张表的时候，<b>调用方是知道</b>自己在看哪张表的。
     * 这个方法就是把那份已知信息补回结果集里。
     *
     * <p><b>只能用在确定单表来源的查询上</b>（表页的分页查询就是）。
     * 拿它去标注一个联表查询，等于凭空造出一个「可以写回」的假象，
     * 那比只读糟得多。
     *
     * @param keyColumns 主键列名，不区分大小写
     */
    public QueryResult attributedTo(String schemaName, String tableName,
                                    java.util.Collection<String> keyColumns) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        keyColumns.forEach(k -> keys.add(k.toLowerCase(java.util.Locale.ROOT)));

        List<ColumnMeta> rebuilt = new java.util.ArrayList<>(columns.size());
        for (ColumnMeta c : columns) {
            rebuilt.add(new ColumnMeta(c.name(), c.label(), c.nativeType(), c.category(),
                    c.precision(), c.scale(), c.nullable(), schemaName, tableName,
                    keys.contains(c.name().toLowerCase(java.util.Locale.ROOT)),
                    c.autoIncrement()));
        }
        return new QueryResult(rebuilt, rows, updateCount, elapsedMillis, truncated, statement);
    }

    /** 写回目标：模式名 + 表名。模式名可能为空（驱动不报，写回时就不加限定）。 */
    public record Source(String schema, String table) { }

    /**
     * 这份结果集该往哪张表写回；不能写回时返回 {@code null}。
     *
     * <p>判据就是 {@link #readOnlyReason()}——能写回，就说明所有列同属一张表，
     * 取第一列的归属即可。两者共用同一套判断，不会出现「说能改，却找不到目标」。
     */
    public Source source() {
        if (!isEditable()) {
            return null;
        }
        ColumnMeta first = columns.get(0);
        return new Source(nz(first.schemaName()), first.tableName());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public int indexOf(String columnLabel) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).label().equals(columnLabel)) {
                return i;
            }
        }
        return -1;
    }
}
