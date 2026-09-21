package com.plainly.driver.ddl;

import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 比对两个库的结构，算出「把目标改成源」需要哪些变更。
 *
 * <p>方向固定为<b>以源为准</b>：源库是想要的样子，目标库是被改的一方。
 * 这个方向必须在界面上写死并反复提示——反了就是把测试库的结构刷到生产库上。
 *
 * <p>单表内部的差异直接委托给 {@link TableDiff}，与结构设计器共用同一套引擎。
 * 这意味着设计器里验证过的每一条规则（括号参数语义、主键隐含非空、风险分级）
 * 在结构同步里自动成立，不需要second implementation。
 */
public final class SchemaDiff {

    private SchemaDiff() {
    }

    /**
     * @param source 源库的全部表结构
     * @param target 目标库的全部表结构
     */
    public static List<SchemaChange> compute(List<TableStructure> source,
                                             List<TableStructure> target) {
        Map<String, TableStructure> sourceByName = byName(source);
        Map<String, TableStructure> targetByName = byName(target);

        List<SchemaChange> changes = new ArrayList<>();

        // 源有、目标没有 → 建表
        for (TableStructure s : source) {
            if (!targetByName.containsKey(key(s))) {
                changes.add(new SchemaChange.CreateTable(s));
                // 新表自带的索引随建表一起创建，不单独产生 CreateIndex
            }
        }

        // 目标有、源没有 → 删表
        for (TableStructure t : target) {
            if (!sourceByName.containsKey(key(t))) {
                changes.add(new SchemaChange.DropTable(t.table()));
            }
        }

        // 两边都有 → 逐表比字段与索引
        for (TableStructure s : source) {
            TableStructure t = targetByName.get(key(s));
            if (t == null) {
                continue;
            }
            List<TableChange> tableChanges = diffColumns(s, t);
            if (!tableChanges.isEmpty()) {
                changes.add(new SchemaChange.AlterTable(t.table().name(), tableChanges));
            }
            changes.addAll(diffIndexes(s, t));
        }

        // 按执行顺序排，同序内按对象名排，让预览稳定可比对
        changes.sort(Comparator.comparingInt(SchemaChange::order)
                .thenComparing(SchemaChange::objectName, String.CASE_INSENSITIVE_ORDER));
        return changes;
    }

    /**
     * 字段差异。
     *
     * <p>这里是复用 {@link TableDiff} 的接缝所在，也是唯一容易搞错的地方：
     * {@code TableDiff} 靠 {@link ColumnDraft#originalName()} 去原结构里定位列。
     * 所以草稿必须以<b>目标库的列名</b>为锚、以<b>源库的定义</b>为内容——
     * 直接拿源库的 {@code ColumnDraft.of} 会让所有列都定位不到，新增列被静默漏掉。
     */
    private static List<TableChange> diffColumns(TableStructure source, TableStructure target) {
        Map<String, ColumnInfo> targetColumns = new LinkedHashMap<>();
        for (ColumnInfo c : target.columns()) {
            targetColumns.put(c.name().toLowerCase(Locale.ROOT), c);
        }

        List<ColumnDraft> drafts = new ArrayList<>();
        for (ColumnInfo s : source.columns()) {
            ColumnInfo t = targetColumns.get(s.name().toLowerCase(Locale.ROOT));
            drafts.add(t == null ? ColumnDraft.newFrom(s) : ColumnDraft.rebase(t, s));
        }

        // 目标库多出来的列：不放进草稿，TableDiff 就会算成 DropColumn
        return TableDiff.compute(target, drafts, target.table().name());
    }

    /**
     * 索引差异。
     *
     * <p>主键索引不在这里处理——它由 {@link TableChange.ChangePrimaryKey} 负责，
     * 两边都管会生成互相冲突的 DDL。
     */
    private static List<SchemaChange> diffIndexes(TableStructure source, TableStructure target) {
        List<SchemaChange> changes = new ArrayList<>();
        String table = target.table().name();

        Map<String, IndexInfo> sourceIdx = indexesByName(source);
        Map<String, IndexInfo> targetIdx = indexesByName(target);

        for (Map.Entry<String, IndexInfo> e : sourceIdx.entrySet()) {
            IndexInfo t = targetIdx.get(e.getKey());
            if (t == null) {
                changes.add(new SchemaChange.CreateIndex(table, e.getValue()));
            } else if (!sameIndex(e.getValue(), t)) {
                // 索引没有 ALTER，改定义只能先删后建
                changes.add(new SchemaChange.DropIndex(table, t));
                changes.add(new SchemaChange.CreateIndex(table, e.getValue()));
            }
        }
        for (Map.Entry<String, IndexInfo> e : targetIdx.entrySet()) {
            if (!sourceIdx.containsKey(e.getKey())) {
                changes.add(new SchemaChange.DropIndex(table, e.getValue()));
            }
        }
        return changes;
    }

    private static Map<String, IndexInfo> indexesByName(TableStructure s) {
        Map<String, IndexInfo> map = new LinkedHashMap<>();
        for (IndexInfo i : s.indexes()) {
            if (i.primary()) {
                continue;
            }
            map.put(i.name().toLowerCase(Locale.ROOT), i);
        }
        return map;
    }

    /** 列顺序在多数引擎里影响索引选择性，所以按顺序比而不是按集合比。 */
    private static boolean sameIndex(IndexInfo a, IndexInfo b) {
        if (a.unique() != b.unique() || a.columns().size() != b.columns().size()) {
            return false;
        }
        for (int i = 0; i < a.columns().size(); i++) {
            if (!a.columns().get(i).equalsIgnoreCase(b.columns().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, TableStructure> byName(List<TableStructure> list) {
        Map<String, TableStructure> map = new LinkedHashMap<>();
        list.forEach(s -> map.put(key(s), s));
        return map;
    }

    private static String key(TableStructure s) {
        return s.table().name().toLowerCase(Locale.ROOT);
    }

    /** 变更涉及的对象名集合，供界面做勾选过滤。 */
    public static Set<String> objectNames(List<SchemaChange> changes) {
        Set<String> names = new LinkedHashSet<>();
        changes.forEach(c -> names.add(c.objectName()));
        return names;
    }
}
