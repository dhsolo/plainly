package com.plainly.driver.ddl;

import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把「库里的结构」和「编辑器里的草稿」比出一串变更。
 *
 * <p>预览与执行共用这一份结果，所以用户看到的语句就是将要跑的语句。
 */
public final class TableDiff {

    private TableDiff() {
    }

    /**
     * @param original  库中现状
     * @param drafts    编辑后的字段列表，顺序即界面上的顺序
     * @param newTableName 新表名；与原表名相同则不产生改名
     */
    public static List<TableChange> compute(TableStructure original,
                                            List<ColumnDraft> drafts,
                                            String newTableName) {
        List<TableChange> changes = new ArrayList<>();

        Map<String, ColumnInfo> originalByName = new LinkedHashMap<>();
        for (ColumnInfo c : original.columns()) {
            originalByName.put(c.name().toLowerCase(), c);
        }

        // 草稿里还留着的原始列
        Map<String, ColumnDraft> keptByOriginalName = new LinkedHashMap<>();
        for (ColumnDraft d : drafts) {
            if (!d.isNew()) {
                keptByOriginalName.put(d.originalName().toLowerCase(), d);
            }
        }

        // 1) 删除：原来有、草稿里没了
        for (ColumnInfo c : original.columns()) {
            if (!keptByOriginalName.containsKey(c.name().toLowerCase())) {
                changes.add(new TableChange.DropColumn(c));
            }
        }

        // 2) 改名 / 修改
        for (ColumnDraft d : drafts) {
            if (d.isNew()) {
                continue;
            }
            ColumnInfo from = originalByName.get(d.originalName().toLowerCase());
            if (from == null) {
                continue;
            }
            boolean renamed = !from.name().equals(d.name());
            if (renamed) {
                // 改名的变更同时带上原定义与新定义，让方言自己决定拆几条语句
                changes.add(new TableChange.RenameColumn(from, d));
            } else if (d.differsFrom(from)) {
                changes.add(new TableChange.ModifyColumn(from, d));
            }
        }

        // 3) 新增：按草稿顺序，AFTER 指向它前面那一列
        for (int i = 0; i < drafts.size(); i++) {
            ColumnDraft d = drafts.get(i);
            if (!d.isNew()) {
                continue;
            }
            String after = i > 0 ? drafts.get(i - 1).name() : null;
            changes.add(new TableChange.AddColumn(d, after));
        }

        // 4) 主键
        List<String> pkBefore = original.primaryKeyColumns();
        List<String> pkAfter = drafts.stream().filter(ColumnDraft::primaryKey)
                .map(ColumnDraft::name).toList();
        if (!Objects.equals(pkBefore, pkAfter)) {
            changes.add(new TableChange.ChangePrimaryKey(pkBefore, pkAfter));
        }

        // 5) 表改名放最后，前面的语句都还用原表名
        if (newTableName != null && !newTableName.isBlank()
                && !newTableName.equals(original.table().name())) {
            changes.add(new TableChange.RenameTable(original.table().name(), newTableName));
        }

        return changes;
    }

    /** 校验草稿本身是否自洽，返回问题列表；为空表示可以生成 DDL。 */
    public static List<String> validate(List<ColumnDraft> drafts) {
        List<String> problems = new ArrayList<>();
        if (drafts.isEmpty()) {
            problems.add("表至少要有一个字段");
            return problems;
        }

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (ColumnDraft d : drafts) {
            if (d.name().isBlank()) {
                problems.add("存在未命名的字段");
                continue;
            }
            if (!d.name().matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                problems.add("字段名 " + d.name() + " 含有需要引号包裹的字符，建议改成字母、数字与下划线");
            }
            seen.merge(d.name().toLowerCase(), 1, Integer::sum);
            if (d.nativeType().isBlank()) {
                problems.add("字段 " + d.name() + " 未指定数据类型");
            }
            if (d.scale() > 0 && d.precision() > 0 && d.scale() > d.precision()) {
                problems.add("字段 " + d.name() + " 的小数位 " + d.scale()
                        + " 大于总位数 " + d.precision());
            }
            if (d.autoIncrement() && !d.category().isExact() && d.category() != com.plainly.driver.TypeCategory.INTEGER) {
                problems.add("字段 " + d.name() + " 设为自增，但类型 " + d.nativeType() + " 不是整型");
            }
        }
        seen.forEach((name, count) -> {
            if (count > 1) {
                problems.add("字段名 " + name + " 重复了 " + count + " 次");
            }
        });
        return problems;
    }
}
