package com.plainly.driver.ddl;

import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.List;

/**
 * 库级别的一项结构差异。
 *
 * <p>与 {@link TableChange} 的关系：一个 {@link AlterTable} 内部装的就是一串
 * {@code TableChange}，直接复用单表设计器那套引擎和它的方言实现。
 * 结构同步没有另起炉灶，只是把「一张表的 diff」扩成「一个库的 diff」。
 */
public sealed interface SchemaChange {

    /** 涉及的对象名，用于列表展示与排序。 */
    String objectName();

    String describe();

    TableChange.Risk risk();

    /**
     * 执行顺序。数字小的先执行。
     *
     * <p>顺序不是美观问题而是正确性问题：删索引必须早于删列（列被索引引用时删不掉），
     * 建表必须早于建索引，而删表放最后——它不可逆，前面任何一步失败都应该让它没机会执行。
     */
    int order();

    /** 源库有、目标库没有的表。 */
    record CreateTable(TableStructure table) implements SchemaChange {
        @Override
        public String objectName() {
            return table.table().name();
        }

        @Override
        public String describe() {
            return "新建表 " + table.table().name()
                    + "（" + table.columns().size() + " 个字段）";
        }

        @Override
        public TableChange.Risk risk() {
            return TableChange.Risk.SAFE;
        }

        @Override
        public int order() {
            return 20;
        }
    }

    /** 目标库有、源库没有的表。 */
    record DropTable(TableInfo table) implements SchemaChange {
        @Override
        public String objectName() {
            return table.name();
        }

        @Override
        public String describe() {
            return "删除表 " + table.name() + "（该表全部数据将永久丢失）";
        }

        @Override
        public TableChange.Risk risk() {
            return TableChange.Risk.DESTRUCTIVE;
        }

        @Override
        public int order() {
            return 50;
        }
    }

    /** 两边都有但定义不同的表。 */
    record AlterTable(String table, List<TableChange> changes) implements SchemaChange {
        @Override
        public String objectName() {
            return table;
        }

        @Override
        public String describe() {
            return "修改表 " + table + "（" + changes.size() + " 处）";
        }

        @Override
        public TableChange.Risk risk() {
            TableChange.Risk worst = TableChange.Risk.SAFE;
            for (TableChange c : changes) {
                if (c.risk().ordinal() > worst.ordinal()) {
                    worst = c.risk();
                }
            }
            return worst;
        }

        @Override
        public int order() {
            return 30;
        }
    }

    record CreateIndex(String table, IndexInfo index) implements SchemaChange {
        @Override
        public String objectName() {
            return index.name();
        }

        @Override
        public String describe() {
            return "新建索引 " + index.name() + " 于 " + table
                    + " (" + String.join(", ", index.columns()) + ")";
        }

        @Override
        public TableChange.Risk risk() {
            // 唯一索引可能因既有重复数据而建不上
            return index.unique() ? TableChange.Risk.DATA_DEPENDENT : TableChange.Risk.SAFE;
        }

        @Override
        public int order() {
            return 40;
        }
    }

    record DropIndex(String table, IndexInfo index) implements SchemaChange {
        @Override
        public String objectName() {
            return index.name();
        }

        @Override
        public String describe() {
            return "删除索引 " + index.name() + " 于 " + table;
        }

        @Override
        public TableChange.Risk risk() {
            // 删索引不丢数据，但可能让线上查询突然变慢
            return TableChange.Risk.DATA_DEPENDENT;
        }

        @Override
        public int order() {
            return 10;
        }
    }
}
