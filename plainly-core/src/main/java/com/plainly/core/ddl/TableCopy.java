package com.plainly.core.ddl;

import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 照着一张现有的表建一张新表：语句怎么拼。
 *
 * <h2>为什么不用 CREATE TABLE ... LIKE / AS SELECT</h2>
 * 各家的写法不通用，而且都各缺一块：MySQL 的 {@code LIKE} 带索引但不带数据，
 * {@code AS SELECT} 带数据但把主键、自增、索引全丢了；PostgreSQL 的
 * {@code INCLUDING ALL} 又和别家完全不是一个语法。
 * 从已经读到的表结构自己拼，一套代码覆盖所有库，而且用户能在执行前看到全文。
 *
 * <h2>索引必须改名</h2>
 * MySQL 的索引名只在表内唯一，PostgreSQL / Oracle 的索引名在整个 schema 内唯一。
 * 照抄原名，在后两家上第二张表建到一半就会撞名报错，
 * 而前面的 CREATE TABLE 已经生效了——留下一张缺索引的半成品。
 *
 * <h2>没复制的东西必须说出来</h2>
 * 外键、触发器、表级注释、自增当前值都不在这里。它们各自有各自的麻烦
 * （外键指向的表未必也复制了，触发器复制过去会对着新表重复写日志），
 * 与其猜用户想要哪种，不如不做——但界面上要写清楚，
 * 否则用户会以为拿到了一张一模一样的表。
 */
public final class TableCopy {

    /** 索引名的长度上限。MySQL 是 64，取小一点留出改名的余量。 */
    private static final int MAX_INDEX_NAME = 60;

    /**
     * 复制的选项。
     *
     * @param targetSchema 建到哪个库；和源库相同就是同库复制
     * @param newName      新表名
     * @param withIndexes  是否连非主键索引一起建
     * @param withData     是否把数据也灌过去
     */
    public record Options(String targetSchema, String newName,
                          boolean withIndexes, boolean withData) {
    }

    /**
     * 拼好的语句。
     *
     * @param ddl    建表和建索引，按顺序执行
     * @param insert 灌数据那一条；不复制数据时为 null
     */
    public record Plan(List<String> ddl, String insert) {

        /** 全部语句，按执行顺序。预览框里显示的就是这个。 */
        public List<String> all() {
            List<String> out = new ArrayList<>(ddl);
            if (insert != null) {
                out.add(insert);
            }
            return out;
        }
    }

    private TableCopy() {
    }

    /**
     * 源表里那个自增列的名字；没有就返回 null。
     *
     * <p>只可能有一个：所有支持自增的库都限制一张表最多一个自增列。
     */
    public static String autoIncrementColumn(TableStructure source) {
        for (ColumnInfo c : source.columns()) {
            if (c.autoIncrement()) {
                return c.name();
            }
        }
        return null;
    }

    /**
     * 灌完数据之后，把新表的自增计数器推到已有数据之后。
     *
     * <p>为什么非做不可：数据是连同主键值一起显式插进去的，多数库的计数器不会因此前移。
     * 不推的话，复制出来的表看着完全正常，直到有人往里插第一条新数据——撞主键。
     * 那时离复制这一步已经很远，没人会想到是它留下的。
     *
     * @param maxValue 新表里该列的当前最大值
     * @return 要执行的语句；没有自增列、或这一家不需要时返回 null
     */
    public static String restartAutoIncrement(SqlDialect dialect, TableStructure source,
                                              Options options, long maxValue) {
        String column = autoIncrementColumn(source);
        if (column == null) {
            return null;
        }
        return dialect.restartAutoIncrementDdl(options.targetSchema(), options.newName(),
                column, maxValue + 1);
    }

    public static Plan build(SqlDialect dialect, String sourceSchema, TableStructure source,
                             Options options) {
        List<String> ddl = new ArrayList<>();

        List<ColumnDraft> drafts = new ArrayList<>();
        for (ColumnInfo c : source.columns()) {
            drafts.add(ColumnDraft.of(c));
        }
        ddl.add(dialect.createTableDdl(options.targetSchema(), options.newName(),
                drafts, source.primaryKeyColumns()));

        if (options.withIndexes()) {
            for (IndexInfo index : source.indexes()) {
                if (index.primary()) {
                    // 主键已经写在 CREATE TABLE 里了，再建一次会撞名
                    continue;
                }
                ddl.add(dialect.createIndexDdl(options.targetSchema(), options.newName(),
                        new IndexInfo(renameIndex(index.name(), source.table().name(),
                                options.newName()),
                                index.columns(), index.unique(), false)));
            }
        }

        String insert = options.withData()
                ? insertSelect(dialect, sourceSchema, source, options)
                : null;
        return new Plan(ddl, insert);
    }

    /**
     * 把索引名改到不会和原表撞。
     *
     * <p>名字里含原表名的，把那一段换成新表名——{@code idx_orders_uid} 变
     * {@code idx_orders_copy_uid}，看得出它是什么。不含的（{@code uk_uid} 这种）
     * 就前缀新表名。两种做法的共同点是<b>保留原来那部分</b>：
     * 换成 {@code idx_1}、{@code idx_2} 也能避开撞名，但一年后没人知道它索引的是哪一列。
     */
    static String renameIndex(String indexName, String oldTable, String newTable) {
        String name = indexName == null || indexName.isBlank() ? "idx" : indexName;
        String renamed = name.toLowerCase(Locale.ROOT).contains(oldTable.toLowerCase(Locale.ROOT))
                ? replaceIgnoreCase(name, oldTable, newTable)
                : newTable + "_" + name;
        return renamed.length() <= MAX_INDEX_NAME
                ? renamed
                : renamed.substring(0, MAX_INDEX_NAME);
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        int at = text.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
        return text.substring(0, at) + replacement + text.substring(at + target.length());
    }

    /**
     * 灌数据。
     *
     * <p>列名写全，不用 {@code INSERT INTO t SELECT * FROM s}：星号按<b>位置</b>对齐，
     * 一旦两张表的列序不同（复制到另一个库、那边已经有同名表时就会发生），
     * 数据会安静地串列——每一行都写进去了，每一个值都在错的地方。
     */
    private static String insertSelect(SqlDialect dialect, String sourceSchema,
                                       TableStructure source, Options options) {
        StringBuilder cols = new StringBuilder();
        for (ColumnInfo c : source.columns()) {
            if (cols.length() > 0) {
                cols.append(", ");
            }
            cols.append(dialect.quote(c.name()));
        }
        return "INSERT INTO " + dialect.qualify(options.targetSchema(), options.newName())
                + " (" + cols + ") SELECT " + cols
                + " FROM " + dialect.qualify(sourceSchema, source.table().name());
    }
}
