package com.plainly.core.export;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;

import java.util.List;
import java.util.function.Consumer;

/**
 * 导出的数据来源。
 *
 * <p>刻意做成推送式而不是「先返回 List 再导出」：
 * 全表导出动辄百万行，一次性全部读进内存会直接 OOM。
 */
public interface RowSource {

    List<ColumnMeta> columns();

    /** 逐行推送。实现方负责分批取数。 */
    void forEach(Consumer<Row> consumer);

    /** 总行数估算，用于进度条；未知返回 -1。 */
    long estimatedTotal();

    /** 已经在内存里的结果集（导出「当前页」或「选中行」时用）。 */
    static RowSource of(QueryResult result) {
        return new RowSource() {
            @Override
            public List<ColumnMeta> columns() {
                return result.columns();
            }

            @Override
            public void forEach(Consumer<Row> consumer) {
                result.rows().forEach(consumer);
            }

            @Override
            public long estimatedTotal() {
                return result.rows().size();
            }
        };
    }

    /**
     * 重新执行一条查询并逐行流出。
     *
     * <p>给「导出查询结果」用。网格里那份结果是<b>按行数上限截过</b>的（默认一千行），
     * 拿它去导出，用户要两百万行、拿到一千行，而且多半不会数。
     * 这里重新执行同一条语句、开游标逐行推——内存占用与结果集大小无关。
     *
     * <p>{@code columns} 由调用方传进来，取自上一次执行的结果：导出要先写表头，
     * 而那时游标还一行没读。省掉一次「先跑一遍拿列名」的探测查询。
     *
     * <p><b>两次执行之间数据可能已经变了</b>，导出的是<b>这一刻</b>的结果，
     * 未必和屏幕上那份一致。这一点必须由界面告诉用户，不能藏在这里。
     *
     * @param schema   执行前把连接切到这个库。同一条连接会被多个标签页共用，
     *                 不切回来，不带库名限定的语句会跑到别的库上去
     * @param estimate 行数估算，只用于进度条；未知给 -1
     */
    static RowSource ofQuery(DbConnection conn, String schema, String sql,
                             List<ColumnMeta> columns, long estimate) {
        return new RowSource() {
            @Override
            public List<ColumnMeta> columns() {
                return columns;
            }

            @Override
            public void forEach(Consumer<Row> consumer) {
                // 同一条连接可能被别的标签页切到了别的库。不切回来，
                // 一条不带库名限定的 SELECT 会安静地跑到另一个库上——
                // 导出照样成功，导出的是别人的数据
                conn.useSchema(schema);
                conn.stream(sql, new DbConnection.RowStream() {
                    @Override
                    public void columns(List<ColumnMeta> streamed) {
                        // 两次执行之间表结构被改过的话，列数会对不上，
                        // 而按位置写下去的每一个值都会落在错的列里——安静地错。
                        // 宁可在这里停住，也不要导出一份看起来正常的错数据
                        if (streamed.size() != columns.size()) {
                            throw new DbException("重新执行时结果的列数变了（原 "
                                    + columns.size() + " 列，现在 " + streamed.size()
                                    + " 列），导出已中止");
                        }
                    }

                    @Override
                    public void row(Row row) {
                        consumer.accept(row);
                    }
                });
            }

            @Override
            public long estimatedTotal() {
                return estimate;
            }
        };
    }

    /**
     * 全表读出：一条 SELECT，游标逐行推，内存占用与总行数无关。
     *
     * <h2>为什么不再分页</h2>
     * 原来是 {@code LIMIT n OFFSET k} 一页页取。OFFSET 要让数据库先数过前面 k 行再扔掉，
     * 第一页很快、第一万页要扫一千万行——总耗时随表大小平方增长，几百万行的表
     * 导出、传输、备份能拖上几十分钟。而且没有 ORDER BY 的分页之间顺序不保证，
     * 同一行可能在两页里各出现一次、另一行一次都不出现，行数对得上也不代表搬对了。
     *
     * <p>流式读取期间这条连接被占着（MySQL 上发不了别的语句），
     * 所以调用方应当给它一条<b>专用连接</b>，不要拿界面在用的那条。
     *
     * <p>Redis、MongoDB 不认这条 SELECT，仍走它们自己方言里的分页；
     * 指定了排序的也走分页。
     */
    static RowSource ofTable(DbConnection conn, String schema, String table,
                             String orderBy, int batchSize, long total) {
        if (!conn.config().type().isJdbc() || (orderBy != null && !orderBy.isBlank())) {
            return ofTablePaged(conn, schema, table, orderBy, batchSize, total);
        }
        QueryResult probe = conn.execute(
                conn.dialect().selectPage(schema, table, null, 1, 0), 1);
        String sql = "SELECT * FROM " + conn.dialect().qualify(schema, table);
        return ofQuery(conn, schema, sql, probe.columns(), total);
    }

    /**
     * 全表分页扫描。
     *
     * <p>比 {@link #ofTable} 慢得多（见那边的说明），只在流式读不了时用：
     * 非关系库，或者读写共用同一条连接——一边开着游标一边往同一条连接写，
     * MySQL 直接拒绝，PostgreSQL 上提交会把游标关掉。
     */
    static RowSource ofTablePaged(DbConnection conn, String schema, String table,
                                  String orderBy, int batchSize, long total) {
        QueryResult probe = conn.execute(
                conn.dialect().selectPage(schema, table, orderBy, 1, 0), 1);
        return new RowSource() {
            @Override
            public List<ColumnMeta> columns() {
                return probe.columns();
            }

            @Override
            public void forEach(Consumer<Row> consumer) {
                int offset = 0;
                while (true) {
                    QueryResult page = conn.execute(
                            conn.dialect().selectPage(schema, table, orderBy, batchSize, offset),
                            batchSize);
                    if (page.rows().isEmpty()) {
                        return;
                    }
                    page.rows().forEach(consumer);
                    if (page.rows().size() < batchSize) {
                        return;
                    }
                    offset += batchSize;
                }
            }

            @Override
            public long estimatedTotal() {
                return total;
            }
        };
    }
}
