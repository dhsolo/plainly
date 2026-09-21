package com.plainly.driver.ddl;

import com.plainly.driver.meta.DbObjects.ColumnInfo;

import java.util.List;

/**
 * 一次表结构变更。
 *
 * <p>做成显式的变更列表而不是「新旧结构对比后现算」，是为了让
 * <b>预览里看到的语句就是将要执行的语句</b>——两者由同一份数据生成，
 * 不存在预览一套、执行另一套的可能。
 */
public sealed interface TableChange {

    /** 变更的风险等级，界面据此决定是否额外提示。 */
    enum Risk {
        /** 纯新增，不影响既有数据。 */
        SAFE,
        /** 可能因既有数据不兼容而失败（收窄类型、加非空约束等）。 */
        DATA_DEPENDENT,
        /** 会丢数据或需要重建表。 */
        DESTRUCTIVE
    }

    /** 给人看的一句话描述。 */
    String describe();

    Risk risk();

    record AddColumn(ColumnDraft column, String afterColumn) implements TableChange {
        @Override
        public String describe() {
            return "新增字段 " + column.name() + " " + column.fullType();
        }

        @Override
        public Risk risk() {
            // 加非空且无默认值的列，表里已有数据时会失败
            return !column.nullable() && column.defaultValue() == null
                    ? Risk.DATA_DEPENDENT : Risk.SAFE;
        }
    }

    record DropColumn(ColumnInfo column) implements TableChange {
        @Override
        public String describe() {
            return "删除字段 " + column.name() + "（该列数据将永久丢失）";
        }

        @Override
        public Risk risk() {
            return Risk.DESTRUCTIVE;
        }
    }

    /**
     * 改名。同时携带原定义与新定义：改名往往和改类型一起发生，
     * MySQL 用一条 {@code CHANGE COLUMN} 就能同时完成，
     * PostgreSQL 则要拆成 {@code RENAME} 加若干 {@code ALTER}，
     * 而 SQLite 只能改名、改不了定义——由各方言自己决定怎么落地。
     */
    record RenameColumn(ColumnInfo from, ColumnDraft to) implements TableChange {
        /** 除名字外，定义是否也变了。 */
        public boolean definitionAlsoChanged() {
            return to.differsFrom(from);
        }

        @Override
        public String describe() {
            String base = "重命名字段 " + from.name() + " → " + to.name();
            return definitionAlsoChanged()
                    ? base + "，同时修改定义为 " + to.fullType() : base;
        }

        @Override
        public Risk risk() {
            return definitionAlsoChanged() ? Risk.DATA_DEPENDENT : Risk.SAFE;
        }
    }

    record ModifyColumn(ColumnInfo from, ColumnDraft to) implements TableChange {
        @Override
        public String describe() {
            StringBuilder sb = new StringBuilder("修改字段 ").append(to.name()).append("：");
            if (!from.displayType().equalsIgnoreCase(to.fullType())) {
                sb.append(from.displayType()).append(" → ").append(to.fullType()).append(' ');
            }
            if (from.nullable() != to.nullable()) {
                sb.append(to.nullable() ? "改为可空 " : "改为非空 ");
            }
            return sb.toString().trim();
        }

        @Override
        public Risk risk() {
            boolean narrowing = from.precision() > 0 && to.precision() > 0
                    && to.precision() < from.precision();
            boolean addingNotNull = from.nullable() && !to.nullable();
            boolean typeChanged = !from.nativeType().equalsIgnoreCase(to.nativeType());
            return narrowing || addingNotNull || typeChanged
                    ? Risk.DATA_DEPENDENT : Risk.SAFE;
        }
    }

    record ChangePrimaryKey(List<String> from, List<String> to) implements TableChange {

        /** 是不是把主键整个去掉，而不是换一组列。 */
        public boolean isDropping() {
            return to.isEmpty() && !from.isEmpty();
        }

        @Override
        public String describe() {
            if (isDropping()) {
                // 「主键 [id] → []」太容易一眼扫过去了。这是这张表上后果最重的一种改动，
                // 描述里就得把后果写出来
                return "删除主键 [" + String.join(", ", from)
                        + "]，之后这张表将没有主键（行不再有唯一标识，"
                        + "本工具的网格也会因此变成只读）";
            }
            if (from.isEmpty()) {
                return "新建主键 [" + String.join(", ", to) + "]";
            }
            return "主键 [" + String.join(", ", from) + "] → [" + String.join(", ", to) + "]";
        }

        /**
         * 删主键和换主键不是一个量级的事。
         *
         * <p>换一组列，坏了还能换回去；<b>整个删掉之后就未必回得去了</b>——
         * 没有主键约束护着，重复行随时可能进来，再想加回主键就会被既有数据挡住。
         * 而且 InnoDB 删主键是<b>整表重建</b>，大表上要停很久。
         * {@link Risk#DESTRUCTIVE} 的定义正是「会丢数据或需要重建表」。
         */
        @Override
        public Risk risk() {
            return isDropping() ? Risk.DESTRUCTIVE : Risk.DATA_DEPENDENT;
        }
    }

    record RenameTable(String from, String to) implements TableChange {
        @Override
        public String describe() {
            return "重命名表 " + from + " → " + to;
        }

        @Override
        public Risk risk() {
            // 引用了旧表名的视图、存储过程、应用代码都会断
            return Risk.DATA_DEPENDENT;
        }
    }
}
