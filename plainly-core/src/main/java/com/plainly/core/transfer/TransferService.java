package com.plainly.core.transfer;

import com.plainly.core.export.RowSource;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeMapper;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import com.plainly.driver.query.ConflictPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 数据传输：把表结构与数据从一个连接搬到另一个。
 *
 * <p>数据走游标流式读取，内存占用与行数无关——搬八千万行的审计表不能先把它读进内存。
 *
 * <p>值全程是字符串，到目标库绑定时才按目标列的类型转换一次。跨库搬运最容易在这里出事：
 * 从 MySQL 读出来转成 double 再写进 PostgreSQL，DECIMAL(38,10) 就只剩十五位有效数字，
 * 而两边的行数完全对得上，看起来一切正常。
 */
public final class TransferService {

    /** 目标表已存在时怎么办。 */
    public enum TargetMode {
        CREATE("建表并插入（目标不存在则创建）"),
        TRUNCATE("先清空目标表再插入"),
        APPEND("仅插入，跳过主键冲突的行");

        private final String label;

        TargetMode(String label) {
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

    /** 一列的搬运计划。 */
    public record ColumnPlan(ColumnInfo source, String targetType, boolean exact, String reason) {
    }

    /** 一张表的搬运计划。 */
    public record TablePlan(String table, long rows, List<ColumnPlan> columns, String createDdl) {

        public boolean hasWidening() {
            return columns.stream().anyMatch(c -> !c.exact());
        }
    }

    /** 搬完之后的账。 */
    public record Outcome(String table, long read, long written, String problem) {

        public boolean ok() {
            return problem == null;
        }
    }

    private TransferService() {
    }

    /**
     * 只算计划，不动数据。
     *
     * <p>建表语句和类型映射先摆出来给人看——跨库映射错了是不会报错的，
     * 只有让人在执行前核对，才有机会发现。
     */
    public static TablePlan plan(DbConnection source, String sourceSchema, String table,
                                 DbConnection target, String targetSchema) {
        TableStructure structure = source.describeTable(sourceSchema, table);
        DbType from = source.config().type();
        DbType to = target.config().type();

        List<ColumnPlan> plans = new ArrayList<>();
        List<ColumnDraft> drafts = new ArrayList<>();
        for (ColumnInfo column : structure.columns()) {
            TypeMapper.Mapping mapping = TypeMapper.mapType(column, from, to);
            plans.add(new ColumnPlan(column, mapping.target(), mapping.exact(), mapping.reason()));
            drafts.add(TypeMapper.map(column, from, to));
        }

        List<String> pk = structure.primaryKeyColumns();
        String ddl = target.dialect().createTableDdl(targetSchema, table, drafts, pk);

        long rows = -1;
        try {
            String count = source.scalar(source.dialect().countRows(sourceSchema, table));
            rows = count == null ? -1 : Long.parseLong(count.trim());
        } catch (RuntimeException e) {
            // 行数只是给人看的估算，取不到不该挡住传输
            rows = -1;
        }
        return new TablePlan(table, rows, plans, ddl);
    }

    /**
     * 真的搬。
     *
     * @param batchSize 每多少行提交一次
     * @param progress 已搬行数的回调
     */
    public static Outcome transfer(DbConnection source, String sourceSchema, TablePlan plan,
                                   DbConnection target, String targetSchema, TargetMode mode,
                                   int batchSize, Consumer<Long> progress) {
        String table = plan.table();
        try {
            prepareTarget(target, targetSchema, plan, mode);

            List<ColumnInfo> targetColumns = target.describeTable(targetSchema, table).columns();
            List<ColumnInfo> keys = targetColumns.stream().filter(ColumnInfo::primaryKey).toList();
            ConflictPolicy policy = mode == TargetMode.APPEND
                    ? ConflictPolicy.SKIP : ConflictPolicy.ABORT;
            SqlDialect.PreparedSql insert = target.dialect()
                    .buildInsert(targetSchema, table, targetColumns, keys, policy);
            if (insert == null) {
                insert = target.dialect().buildInsert(targetSchema, table, targetColumns);
            }

            long[] counters = new long[2];
            List<List<String>> batch = new ArrayList<>();
            SqlDialect.PreparedSql statement = insert;

            RowSource rows = RowSource.ofTable(source, sourceSchema, table, null, batchSize, -1);
            List<String> sourceNames = rows.columns().stream()
                    .map(c -> c.name().toLowerCase()).toList();
            int[] order = new int[targetColumns.size()];
            for (int i = 0; i < targetColumns.size(); i++) {
                order[i] = sourceNames.indexOf(targetColumns.get(i).name().toLowerCase());
            }

            rows.forEach(row -> {
                counters[0]++;
                List<String> values = new ArrayList<>(order.length);
                for (int index : order) {
                    // 目标有、源没有的列写 NULL，让目标库的默认值和约束自己说话
                    values.add(index < 0 ? null : row.get(index));
                }
                batch.add(values);
                if (batch.size() >= batchSize) {
                    counters[1] += target.executeBatch(statement, batch);
                    batch.clear();
                    progress.accept(counters[0]);
                }
            });
            if (!batch.isEmpty()) {
                counters[1] += target.executeBatch(statement, batch);
            }
            progress.accept(counters[0]);
            return new Outcome(table, counters[0], counters[1], null);
        } catch (RuntimeException e) {
            return new Outcome(table, 0, 0, e.getMessage());
        }
    }

    private static void prepareTarget(DbConnection target, String schema, TablePlan plan,
                                      TargetMode mode) {
        boolean exists = target.listTables(schema).stream()
                .map(TableInfo::name)
                .anyMatch(n -> n.equalsIgnoreCase(plan.table()));

        if (!exists) {
            target.executeDdlBatch(List.of(plan.createDdl()));
            return;
        }
        if (mode == TargetMode.TRUNCATE) {
            target.executeDdlBatch(List.of(
                    "DELETE FROM " + target.dialect().qualify(schema, plan.table())));
        }
    }

    /** 搬完逐表比对行数。数字对不上比「传输成功」四个字有用得多。 */
    public static String verify(DbConnection source, String sourceSchema,
                                DbConnection target, String targetSchema, String table) {
        String before = source.scalar(source.dialect().countRows(sourceSchema, table));
        String after = target.scalar(target.dialect().countRows(targetSchema, table));
        if (before == null || after == null) {
            return "行数取不到，无法比对";
        }
        return before.trim().equals(after.trim())
                ? "一致 · " + after + " 行"
                : "对不上 · 源 " + before + " 行，目标 " + after + " 行";
    }

    /** 源里读到的第一行，给界面做样例展示用。 */
    public static Row firstRow(DbConnection source, String schema, String table) {
        var page = source.execute(
                source.dialect().selectPage(schema, table, null, 1, 0), 1);
        return page.rows().isEmpty() ? null : page.rows().get(0);
    }

    static void checkSameShape(List<ColumnInfo> a, List<ColumnInfo> b) {
        if (a.size() != b.size()) {
            throw new DbException("源与目标的列数对不上：" + a.size() + " vs " + b.size());
        }
    }
}
