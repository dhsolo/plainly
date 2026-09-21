package com.plainly.core.sync;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据同步：逐行比对两张表，生成把目标改成源的 INSERT / UPDATE / DELETE。
 *
 * <p>与结构同步是两件事：那边比的是列和索引，这边比的是每一行的值。
 *
 * <p>比对靠把两侧按键读进内存的哈希表，不靠数据库的排序。看起来更费内存，
 * 但归并比对要求两侧的排序完全一致，而不同库、不同排序规则下
 * {@code 'a'} 与 {@code 'A'} 的先后可能相反——那样归并会凭空造出成对的
 * INSERT 和 DELETE，比多占点内存危险得多。行数超过上限时明说，不硬撑。
 */
public final class DataSyncService {

    /** 单侧最多读多少行。超过就该改用数据传输，而不是在这里把内存吃光。 */
    public static final int MAX_ROWS = 500_000;

    /** 数值怎么算「相同」。 */
    public enum NumericMode {
        TEXT("按文本，严格",
                "两侧 scale 不同时 1.10 与 1.1 文本不同、数值相同，严格模式会算成差异并生成 UPDATE"
                        + "——多数时候这正是你要的：把目标的存储形式也对齐。"),
        NUMERIC("按数值，忽略末尾零",
                "只比业务值。1.10 与 1.1 视为相同，不生成 UPDATE。");

        private final String label;
        private final String note;

        NumericMode(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一行差异要做的事。 */
    public enum Action {
        INSERT("仅源有 · 将 INSERT"),
        UPDATE("两侧不同 · 将 UPDATE"),
        DELETE("仅目标有 · 将 DELETE");

        private final String label;

        Action(String label) {
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
     * 一行差异。
     *
     * @param changedColumns UPDATE 时真正变了的列；其余动作为空
     */
    public record RowDiff(Action action, List<String> keyValues, List<String> sourceValues,
                          List<String> targetValues, Set<String> changedColumns) {
    }

    /**
     * 比对结果。
     *
     * @param columns 参与比对的列（以源为准）
     * @param same 两侧一致、不需要动的行数
     */
    public record DiffResult(List<String> columns, List<String> keyColumns,
                             List<RowDiff> diffs, long same) {

        public long count(Action action) {
            return diffs.stream().filter(d -> d.action() == action).count();
        }
    }

    private DataSyncService() {
    }

    /**
     * 比对。
     *
     * @param keyColumns 比对键；通常是主键，也可以指定别的唯一列
     */
    public static DiffResult compare(DbConnection source, String sourceSchema, String sourceTable,
                                     DbConnection target, String targetSchema, String targetTable,
                                     List<String> keyColumns, NumericMode mode) {
        if (keyColumns.isEmpty()) {
            throw new DbException("没有比对键，无法逐行比对——请指定一列或几列能唯一定位行的字段");
        }

        QueryResult sourceRows = readAll(source, sourceSchema, sourceTable);
        QueryResult targetRows = readAll(target, targetSchema, targetTable);

        List<String> columns = sourceRows.columns().stream().map(ColumnMeta::name).toList();
        List<String> targetNames = targetRows.columns().stream().map(ColumnMeta::name).toList();
        List<String> shared = columns.stream()
                .filter(c -> containsIgnoreCase(targetNames, c))
                .toList();
        for (String key : keyColumns) {
            if (!containsIgnoreCase(shared, key)) {
                throw new DbException("比对键 " + key + " 在两侧不是共有的列");
            }
        }

        int[] sourceIndex = indexesOf(sourceRows, shared);
        int[] targetIndex = indexesOf(targetRows, shared);
        int[] sourceKeyIndex = indexesOf(sourceRows, keyColumns);
        int[] targetKeyIndex = indexesOf(targetRows, keyColumns);
        boolean[] numeric = numericFlags(sourceRows, shared);

        Map<String, List<String>> targetByKey = new LinkedHashMap<>();
        for (Row row : targetRows.rows()) {
            targetByKey.put(keyOf(row, targetKeyIndex), valuesOf(row, targetIndex));
        }

        List<RowDiff> diffs = new ArrayList<>();
        long same = 0;
        Set<String> seen = new LinkedHashSet<>();

        for (Row row : sourceRows.rows()) {
            String key = keyOf(row, sourceKeyIndex);
            seen.add(key);
            List<String> sourceValues = valuesOf(row, sourceIndex);
            List<String> targetValues = targetByKey.get(key);

            if (targetValues == null) {
                diffs.add(new RowDiff(Action.INSERT, keyParts(row, sourceKeyIndex),
                        sourceValues, null, Set.of()));
                continue;
            }
            Set<String> changed = new LinkedHashSet<>();
            for (int i = 0; i < shared.size(); i++) {
                if (!equalValues(sourceValues.get(i), targetValues.get(i), numeric[i], mode)) {
                    changed.add(shared.get(i));
                }
            }
            if (changed.isEmpty()) {
                same++;
            } else {
                diffs.add(new RowDiff(Action.UPDATE, keyParts(row, sourceKeyIndex),
                        sourceValues, targetValues, changed));
            }
        }

        for (Row row : targetRows.rows()) {
            String key = keyOf(row, targetKeyIndex);
            if (!seen.contains(key)) {
                diffs.add(new RowDiff(Action.DELETE, keyParts(row, targetKeyIndex),
                        null, valuesOf(row, targetIndex), Set.of()));
            }
        }
        return new DiffResult(shared, keyColumns, diffs, same);
    }

    /**
     * 两个值算不算相同。
     *
     * <p>{@code 1.10} 和 {@code 1.1} 文本不同、数值相同。哪种算「相同」没有唯一答案：
     * 想把目标的存储形式也对齐就用文本，只关心业务值就用数值。所以这是个选项，
     * 而不是我们替用户定死的规则。
     */
    static boolean equalValues(String a, String b, boolean numeric, NumericMode mode) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (mode == NumericMode.NUMERIC && numeric) {
            try {
                return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
            } catch (NumberFormatException e) {
                return a.equals(b);
            }
        }
        return a.equals(b);
    }

    /**
     * 生成同步脚本。
     *
     * <p>全参数化，值从不进 SQL 文本——同步搬的往往正是金额这类高精度列，
     * 拼进语句就等于把精度交给驱动的字面量解析去决定。
     */
    public static List<Statement> script(SqlDialect dialect, String schema, String table,
                                         DiffResult result, List<ColumnInfo> targetColumns,
                                         List<RowDiff> selected) {
        List<ColumnInfo> keys = result.keyColumns().stream()
                .map(name -> lookup(targetColumns, name))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (keys.size() != result.keyColumns().size()) {
            throw new DbException("目标表里找不到全部比对键列");
        }

        List<Statement> out = new ArrayList<>();
        for (RowDiff diff : selected) {
            switch (diff.action()) {
                case INSERT: {
                    List<ColumnInfo> columns = new ArrayList<>();
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < result.columns().size(); i++) {
                        ColumnInfo info = lookup(targetColumns, result.columns().get(i));
                        if (info != null) {
                            columns.add(info);
                            values.add(diff.sourceValues().get(i));
                        }
                    }
                    out.add(new Statement(diff, dialect.buildInsert(schema, table, columns), values));
                    break;
                }
                case UPDATE: {
                    List<ColumnInfo> setColumns = new ArrayList<>();
                    List<String> values = new ArrayList<>();
                    for (String name : diff.changedColumns()) {
                        ColumnInfo info = lookup(targetColumns, name);
                        if (info != null) {
                            setColumns.add(info);
                            values.add(diff.sourceValues().get(indexOf(result.columns(), name)));
                        }
                    }
                    values.addAll(diff.keyValues());
                    out.add(new Statement(diff,
                            dialect.buildUpdate(schema, table, setColumns, keys), values));
                    break;
                }
                case DELETE:
                default:
                    out.add(new Statement(diff, dialect.buildDelete(schema, table, keys),
                            new ArrayList<>(diff.keyValues())));
                    break;
            }
        }
        return out;
    }

    /** 一条待执行的语句，连着它来自哪一行差异。 */
    public record Statement(RowDiff diff, SqlDialect.PreparedSql sql, List<String> values) {
    }

    /** 逐条执行。返回实际影响的行数。 */
    public static int apply(DbConnection target, List<Statement> statements) {
        int affected = 0;
        for (Statement s : statements) {
            affected += target.executeUpdate(s.sql(), s.values());
        }
        return affected;
    }

    // ------------------------------------------------------------------ 内部

    private static QueryResult readAll(DbConnection conn, String schema, String table) {
        QueryResult result = conn.execute(
                conn.dialect().selectPage(schema, table, null, MAX_ROWS, 0), MAX_ROWS);
        if (result.truncated()) {
            throw new DbException(table + " 超过 " + MAX_ROWS
                    + " 行，逐行比对需要把两侧都读进内存。这个量级请改用数据传输。");
        }
        return result;
    }

    private static int[] indexesOf(QueryResult result, List<String> names) {
        int[] out = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            out[i] = -1;
            for (int c = 0; c < result.columns().size(); c++) {
                if (result.columns().get(c).name().equalsIgnoreCase(names.get(i))) {
                    out[i] = c;
                    break;
                }
            }
        }
        return out;
    }

    private static boolean[] numericFlags(QueryResult result, List<String> names) {
        boolean[] out = new boolean[names.size()];
        for (int i = 0; i < names.size(); i++) {
            for (ColumnMeta meta : result.columns()) {
                if (meta.name().equalsIgnoreCase(names.get(i))) {
                    out[i] = meta.category().isNumeric();
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 键的字符串形式。
     *
     * <p>用不可能出现在值里的分隔符拼起来，免得 {@code ("a|b", "c")} 和
     * {@code ("a", "b|c")} 撞成同一个键。
     */
    /**
     * 拼复合键时的分隔符。
     *
     * <p>用 NUL：它不可能出现在字段值里，所以 {@code "a|b" + "c"} 撞
     * {@code "a" + "b|c"} 那类问题在这里不存在。
     *
     * <p>写成 {@code (char) 0} 而不是直接敲一个 NUL 字符——后者在源码里是个
     * 看不见的字节，grep 会把整个文件当成二进制，diff 里也完全看不出来。
     */
    private static final char KEY_SEPARATOR = 0;

    private static String keyOf(Row row, int[] indexes) {
        StringBuilder sb = new StringBuilder();
        for (int index : indexes) {
            sb.append(index < 0 ? "" : String.valueOf(row.get(index))).append(KEY_SEPARATOR);
        }
        return sb.toString();
    }

    private static List<String> keyParts(Row row, int[] indexes) {
        List<String> out = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            out.add(index < 0 ? null : row.get(index));
        }
        return out;
    }

    private static List<String> valuesOf(Row row, int[] indexes) {
        List<String> out = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            out.add(index < 0 ? null : row.get(index));
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }

    private static int indexOf(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(value)) {
                return i;
            }
        }
        return -1;
    }

    private static ColumnInfo lookup(List<ColumnInfo> columns, String name) {
        for (ColumnInfo c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }
}
