package com.plainly.core.edit;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 网格里尚未提交的修改：改值、新增行、删除行。
 *
 * <h2>脏值判断为什么必须用字符串比较</h2>
 * 用数值比较的话，{@code 1.10} 和 {@code 1.1} 会被判成「没改」，用户的编辑就被悄悄丢掉了。
 * 在 Java 里这个坑还有个更出名的形式：{@code new BigDecimal("1.10").equals(new BigDecimal("1.1"))}
 * 是 {@code false}（scale 不同），而 {@code compareTo} 是 {@code 0}——
 * 两个方法选错哪一个都会出事。
 *
 * <p>这里的做法是索性不比数值：<b>原始文本 != 新文本，就是改了</b>。
 * 数据库里存的就是那个文本表示，用户看到的也是它。
 *
 * <h2>新增行的下标从哪来</h2>
 * 网格的表项是行下标（见 DataGridPane），所以新增行必须也有下标才能摆进那张表。
 * 取的是 {@code result.rows().size()} 往后的号：{@code [0, size)} 是数据库里的行，
 * {@code [size, size + n)} 是还没落库的行。这样网格、单元格面板、复制、粘贴
 * 全都不必知道「这一行是新的」——它们照旧按下标取值。
 *
 * <h2>「没填」和「填了 NULL」是两件事</h2>
 * 新增行里一个格子没动过，意味着<b>这一列不进 INSERT 的列清单</b>，
 * 由数据库去套默认值、自增序列；而用户明确置成 NULL，意味着这一列要写进去、值是 NULL。
 * 两者混为一谈的后果是自增主键被显式插了个 NULL，或者带默认值的
 * {@code created_at} 变成空——都是事后很难查的那种错。
 * 所以新增行用 Map 存：<b>键在不在</b>区分「填没填」，值是不是 null 区分「是不是 NULL」。
 */
public class EditBuffer {

    /** 单元格坐标。 */
    public record CellKey(int rowIndex, int columnIndex) {
    }

    private final Map<CellKey, String> pending = new LinkedHashMap<>();
    /** 新增行，按加入顺序。每行是「列下标 → 值」，键不存在表示这一列没填。 */
    private final List<Map<Integer, String>> newRows = new ArrayList<>();
    /** 标记要删的行，存的是结果集里的行下标。 */
    private final Set<Integer> deleted = new LinkedHashSet<>();
    private final QueryResult result;

    public EditBuffer(QueryResult result) {
        this.result = result;
    }

    public boolean isEditable() {
        return result.isEditable();
    }

    /** 数据库里那些行的行数。下标大于等于它的都是新增行。 */
    public int baseRowCount() {
        return result.rows().size();
    }

    public boolean isNewRow(int rowIndex) {
        return rowIndex >= baseRowCount();
    }

    /** 新增一行，返回它的行下标。 */
    public int addRow() {
        newRows.add(new LinkedHashMap<>());
        return baseRowCount() + newRows.size() - 1;
    }

    public int newRowCount() {
        return newRows.size();
    }

    /**
     * 撤掉一个新增行。
     *
     * <p>返回是否真的撤掉了。撤掉中间那一行会让后面几行的下标整体前移，
     * 所以调用方必须重建网格的表项，不能只 refresh。
     */
    public boolean removeNewRow(int rowIndex) {
        if (!isNewRow(rowIndex)) {
            return false;
        }
        int at = rowIndex - baseRowCount();
        if (at >= newRows.size()) {
            return false;
        }
        newRows.remove(at);
        return true;
    }

    /** 记录一次编辑。与原值文本相同则视为撤销修改。 */
    public void set(int rowIndex, int columnIndex, String newValue) {
        if (isNewRow(rowIndex)) {
            Map<Integer, String> row = newRowAt(rowIndex);
            if (row != null) {
                row.put(columnIndex, newValue);
            }
            return;
        }
        String original = result.rows().get(rowIndex).get(columnIndex);
        CellKey key = new CellKey(rowIndex, columnIndex);
        if (Objects.equals(original, newValue)) {
            pending.remove(key);
        } else {
            pending.put(key, newValue);
        }
    }

    /** 把新增行的某个格子退回「没填」。对已有行没有意义，直接忽略。 */
    public void unset(int rowIndex, int columnIndex) {
        if (isNewRow(rowIndex)) {
            Map<Integer, String> row = newRowAt(rowIndex);
            if (row != null) {
                row.remove(columnIndex);
            }
        }
    }

    /** 这个格子有没有填过值。已有行恒为 true——它本来就有值。 */
    public boolean isSet(int rowIndex, int columnIndex) {
        if (!isNewRow(rowIndex)) {
            return true;
        }
        Map<Integer, String> row = newRowAt(rowIndex);
        return row != null && row.containsKey(columnIndex);
    }

    public boolean isDirty(int rowIndex, int columnIndex) {
        return pending.containsKey(new CellKey(rowIndex, columnIndex));
    }

    /** 标记 / 取消标记删除。新增行不走这里，用 {@link #removeNewRow}。 */
    public void setDeleted(int rowIndex, boolean value) {
        if (isNewRow(rowIndex)) {
            return;
        }
        if (value) {
            deleted.add(rowIndex);
        } else {
            deleted.remove(rowIndex);
        }
    }

    public boolean isDeleted(int rowIndex) {
        return deleted.contains(rowIndex);
    }

    public int deletedCount() {
        return deleted.size();
    }

    public boolean hasChanges() {
        return !pending.isEmpty() || !deleted.isEmpty() || !newRows.isEmpty();
    }

    /**
     * 待提交的改动条数。
     *
     * <p>改值按<b>格</b>数，删除和新增按<b>行</b>数——这是用户脑子里的单位：
     * 「改了 3 个格子、删了 2 行、加了 1 行」。
     */
    public int changeCount() {
        return pending.size() + deleted.size() + newRows.size();
    }

    /** 保存按钮旁边那句话。空串表示没有改动。 */
    public String describeChanges() {
        List<String> parts = new ArrayList<>();
        if (!pending.isEmpty()) {
            parts.add("改 " + pending.size() + " 格");
        }
        if (!newRows.isEmpty()) {
            parts.add("增 " + newRows.size() + " 行");
        }
        if (!deleted.isEmpty()) {
            parts.add("删 " + deleted.size() + " 行");
        }
        return String.join(" · ", parts);
    }

    /** 当前应当显示的值：改过就显示新值，否则显示原值。新增行里没填的格子返回 null。 */
    public String displayValue(int rowIndex, int columnIndex) {
        if (isNewRow(rowIndex)) {
            Map<Integer, String> row = newRowAt(rowIndex);
            return row == null ? null : row.get(columnIndex);
        }
        CellKey key = new CellKey(rowIndex, columnIndex);
        if (pending.containsKey(key)) {
            return pending.get(key);
        }
        return result.rows().get(rowIndex).get(columnIndex);
    }

    /**
     * 这个格子从库里读出来时的值。
     *
     * <p>改过的格子上，原值是界面上<b>唯一看不到</b>的东西——
     * 格子里显示的已经是新值。悬停提示里把它带上，
     * 用户不用先撤销才能想起来自己改了什么。
     *
     * <p>新增行没有原值，返回 {@code null}。
     */
    public String originalValue(int rowIndex, int columnIndex) {
        if (isNewRow(rowIndex) || rowIndex < 0 || rowIndex >= result.rows().size()) {
            return null;
        }
        return result.rows().get(rowIndex).get(columnIndex);
    }

    public void revertAll() {
        pending.clear();
        deleted.clear();
        newRows.clear();
    }

    /** 一条待执行的写回语句。 */
    public record PendingUpdate(SqlDialect.PreparedSql statement, List<String> values, int rowIndex) {
    }

    /**
     * 一批待执行的写回语句，按必须的执行顺序分好组。
     *
     * <p><b>顺序不是随便排的</b>：先删、后改、再插。用户删掉一行、又插一行同主键的新行，
     * 在业务上是「换掉这一行」，很常见；插在删之前就会撞主键冲突，
     * 而那个报错完全看不出原因——用户看到的两个动作各自都合法。
     */
    public record PendingBatch(List<PendingUpdate> deletes, List<PendingUpdate> updates,
                               List<PendingUpdate> inserts) {

        public int size() {
            return deletes.size() + updates.size() + inserts.size();
        }

        /** 按执行顺序摊平。 */
        public List<PendingUpdate> inOrder() {
            List<PendingUpdate> all = new ArrayList<>(size());
            all.addAll(deletes);
            all.addAll(updates);
            all.addAll(inserts);
            return all;
        }
    }

    /**
     * 生成改值的写回语句。每个被修改的行一条 UPDATE，全部参数化。
     *
     * @param structureColumns 表的真实结构，用于拿到绑定所需的类型信息
     */
    public List<PendingUpdate> buildUpdates(SqlDialect dialect, String schema, String table,
                                            List<ColumnInfo> structureColumns) {
        return buildBatch(dialect, schema, table, structureColumns).updates();
    }

    /**
     * 生成整批写回语句：删除、修改、新增。
     *
     * <p>三种语句都要求结果集可编辑。严格说 INSERT 不需要主键，没有主键的表照样插得进去——
     * 但一张连主键都定位不到的结果集，插进去的行在网格里也刷不出来、之后也改不了删不了，
     * 单独放行它只会让「能插不能改」成为一个要解释的例外。
     */
    public PendingBatch buildBatch(SqlDialect dialect, String schema, String table,
                                   List<ColumnInfo> structureColumns) {
        if (!isEditable()) {
            throw new DbException("该结果集不可编辑：列不同源于单表，或定位不到主键");
        }

        List<ColumnMeta> metas = result.columns();
        List<ColumnInfo> keyColumns = new ArrayList<>();
        for (ColumnMeta m : metas) {
            if (m.partOfKey()) {
                keyColumns.add(findColumn(structureColumns, m.name()));
            }
        }
        if (keyColumns.isEmpty()) {
            throw new DbException("定位不到主键或唯一键，拒绝生成写回语句");
        }

        return new PendingBatch(
                buildDeletes(dialect, schema, table, metas, keyColumns),
                buildUpdateStatements(dialect, schema, table, metas, keyColumns, structureColumns),
                buildInserts(dialect, schema, table, metas, structureColumns));
    }

    private List<PendingUpdate> buildDeletes(SqlDialect dialect, String schema, String table,
                                             List<ColumnMeta> metas, List<ColumnInfo> keyColumns) {
        List<PendingUpdate> out = new ArrayList<>(deleted.size());
        SqlDialect.PreparedSql sql = dialect.buildDelete(schema, table, keyColumns);
        for (int rowIndex : deleted) {
            Row row = result.rows().get(rowIndex);
            List<String> values = new ArrayList<>(keyColumns.size());
            // 主键用的是数据库里的原值，不是编辑后的值：
            // 用户既改了主键又勾了删除时，要删的仍然是原来那一行
            for (int i = 0; i < metas.size(); i++) {
                if (metas.get(i).partOfKey()) {
                    values.add(row.get(i));
                }
            }
            out.add(new PendingUpdate(sql, values, rowIndex));
        }
        return out;
    }

    private List<PendingUpdate> buildUpdateStatements(SqlDialect dialect, String schema, String table,
                                                      List<ColumnMeta> metas, List<ColumnInfo> keyColumns,
                                                      List<ColumnInfo> structureColumns) {
        // 按行归并：同一行改了 3 个字段应当是一条 UPDATE，不是三条
        Map<Integer, List<Integer>> byRow = new LinkedHashMap<>();
        pending.keySet().forEach(k ->
                byRow.computeIfAbsent(k.rowIndex(), r -> new ArrayList<>()).add(k.columnIndex()));

        List<PendingUpdate> updates = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : byRow.entrySet()) {
            int rowIndex = entry.getKey();
            // 这一行已经勾了删除，改它没有意义——DELETE 先跑，UPDATE 影响 0 行。
            // 发一条注定影响 0 行的语句，只会让「已保存 N 行」这个数字对不上
            if (deleted.contains(rowIndex)) {
                continue;
            }
            Row row = result.rows().get(rowIndex);

            List<ColumnInfo> setColumns = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (int columnIndex : entry.getValue()) {
                setColumns.add(findColumn(structureColumns, metas.get(columnIndex).name()));
                values.add(pending.get(new CellKey(rowIndex, columnIndex)));
            }

            // WHERE 用的是主键的「原始值」，不是编辑后的值——
            // 否则用户改了主键就再也定位不到那一行
            for (int i = 0; i < metas.size(); i++) {
                if (metas.get(i).partOfKey()) {
                    values.add(row.get(i));
                }
            }

            updates.add(new PendingUpdate(
                    dialect.buildUpdate(schema, table, setColumns, keyColumns), values, rowIndex));
        }
        return updates;
    }

    /**
     * 新增行的 INSERT。
     *
     * <p>只把<b>填过</b>的列写进列清单，剩下的交给数据库套默认值和自增序列。
     * 一列都没填的行不会静静插一条全默认值的记录，而是当场拒绝：
     * 那多半是用户点了「新增行」又忘了填。
     */
    private List<PendingUpdate> buildInserts(SqlDialect dialect, String schema, String table,
                                             List<ColumnMeta> metas, List<ColumnInfo> structureColumns) {
        List<PendingUpdate> out = new ArrayList<>(newRows.size());
        for (int i = 0; i < newRows.size(); i++) {
            Map<Integer, String> row = newRows.get(i);
            if (row.isEmpty()) {
                throw new DbException("第 " + (i + 1) + " 个新增行一个字段都没填，"
                        + "不知道要插什么。填上值，或者把它撤掉");
            }
            List<ColumnInfo> columns = new ArrayList<>(row.size());
            List<String> values = new ArrayList<>(row.size());
            for (Map.Entry<Integer, String> cell : row.entrySet()) {
                int columnIndex = cell.getKey();
                if (columnIndex < 0 || columnIndex >= metas.size()) {
                    continue;
                }
                columns.add(findColumn(structureColumns, metas.get(columnIndex).name()));
                values.add(cell.getValue());
            }
            out.add(new PendingUpdate(dialect.buildInsert(schema, table, columns), values,
                    baseRowCount() + i));
        }
        return out;
    }

    /**
     * 新增行里那些「必须填却没填」的列。
     *
     * <p>NOT NULL、没有默认值、又不是自增——这三条同时成立的列不填，数据库一定退回来。
     * 在这里先说，比拿一条约束报错去让用户猜是哪一行哪一列强得多。
     */
    public List<String> missingRequired(List<ColumnInfo> structureColumns) {
        List<ColumnMeta> metas = result.columns();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < newRows.size(); i++) {
            Map<Integer, String> row = newRows.get(i);
            if (row.isEmpty()) {
                continue;   // 空行由 buildInserts 单独说
            }
            List<String> missing = new ArrayList<>();
            for (int c = 0; c < metas.size(); c++) {
                ColumnInfo info = lookupOrNull(structureColumns, metas.get(c).name());
                if (info == null || info.nullable() || info.autoIncrement()) {
                    continue;
                }
                if (info.defaultValue() != null && !info.defaultValue().isBlank()) {
                    continue;
                }
                if (!row.containsKey(c)) {
                    missing.add(info.name());
                } else if (row.get(c) == null) {
                    missing.add(info.name() + "（填的是 NULL）");
                }
            }
            if (!missing.isEmpty()) {
                problems.add("第 " + (i + 1) + " 个新增行缺：" + String.join("、", missing));
            }
        }
        return problems;
    }

    private Map<Integer, String> newRowAt(int rowIndex) {
        int at = rowIndex - baseRowCount();
        return at >= 0 && at < newRows.size() ? newRows.get(at) : null;
    }

    private static ColumnInfo lookupOrNull(List<ColumnInfo> columns, String name) {
        for (ColumnInfo c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private static ColumnInfo findColumn(List<ColumnInfo> columns, String name) {
        ColumnInfo found = lookupOrNull(columns, name);
        if (found != null) {
            return found;
        }
        // 结构里找不到（视图、表达式列）时给个保守的默认，按字符串绑定
        return new ColumnInfo(name, "VARCHAR", TypeCategory.STRING, 0, 0,
                true, false, false, null, "", 0);
    }
}
