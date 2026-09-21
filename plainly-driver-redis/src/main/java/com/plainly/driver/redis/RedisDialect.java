package com.plainly.driver.redis;

import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.query.FilterSpec;

import java.util.List;

/**
 * Redis 的「方言」。
 *
 * <p>Redis 没有 SQL，所以本类的主要工作<b>不是生成语句，而是把做不到的事说清楚</b>。
 * {@link SqlDialect} 的接口本来就为此留了位置（{@code unsupportedReason}、
 * {@code triggerUnsupportedReason}、{@code schemaCreationUnsupportedReason}），
 * 这里把它们一条条填上。灰掉一个按钮却不说为什么，用户只会以为软件坏了。
 *
 * <h2>取数走的是一份私有约定，不是 SQL</h2>
 * 网格取数的路径是「方言拼出语句 → 连接执行」。Redis 拼不出 SQL，
 * 于是这里产出一段<b>明显不是 SQL 的指令</b>，由 {@link RedisConnection} 解读：
 *
 * <pre>
 * PLAINLY-REDIS SCAN
 * db0
 * user:
 * 200
 * 400
 * </pre>
 *
 * <p>为什么用换行分段而不是拼成一行：键的前缀里什么字符都可能有，包括空格和引号。
 * 按分隔符切一定会在某个真实的键上切错。换行分段的位置是固定的，前缀放最后一段之前，
 * 内容再古怪也不会歧义。
 *
 * <p>为什么开头写成 {@code PLAINLY-REDIS}：万一哪天它漏到界面上（错误提示、
 * 历史记录、日志），一眼能看出这不是用户写的 SQL，而是本工具内部的东西。
 */
public class RedisDialect implements SqlDialect {

    /** 私有指令的开头。见类注释。 */
    public static final String MARKER = "PLAINLY-REDIS";

    public static final String SCAN = MARKER + " SCAN";

    /**
     * 同上，但值取<b>完整</b>的。
     *
     * <p>为什么要分成两条：界面翻页一屏两百行，每个值都拉全的话，
     * 一个存着几兆内容的键就能把网格拖死，而网格里那一格本来也只显示得下一行字。
     * 导出、传输、同步不一样——那是要把数据搬走的，
     * <b>搬走一份被截断的比不搬更糟</b>：文件看着是完整的。
     */
    public static final String SCAN_FULL = MARKER + " SCAN-FULL";
    public static final String COUNT = MARKER + " COUNT";

    /**
     * 键名搜索的字段名。
     *
     * <p>模式是通过 {@code FilterSpec} 里一条「键 包含 xxx」的条件传进来的——
     * 复用现成的筛选管道，取数和计数两条路自动都带上，分页器上的总数
     * 也就跟着是「搜到多少」而不是「一共多少」。
     */
    public static final String KEY_COLUMN = com.plainly.driver.kv.KeyValueStore.KEY_COLUMN;

    static String scanFullCommand(String db, String prefix, int limit, int offset) {
        return scanFullCommand(db, prefix, limit, offset, "");
    }

    static String scanFullCommand(String db, String prefix, int limit, int offset,
                                  String pattern) {
        return SCAN_FULL + "\n" + nz(db) + "\n" + nz(prefix) + "\n" + limit
                + "\n" + Math.max(0, offset) + "\n" + nz(pattern);
    }

    static String scanCommand(String db, String prefix, int limit, int offset) {
        return scanCommand(db, prefix, limit, offset, "");
    }

    static String scanCommand(String db, String prefix, int limit, int offset, String pattern) {
        return SCAN + "\n" + nz(db) + "\n" + nz(prefix) + "\n" + limit + "\n" + Math.max(0, offset)
                + "\n" + nz(pattern);
    }

    static String countCommand(String db, String prefix) {
        return countCommand(db, prefix, "");
    }

    static String countCommand(String db, String prefix, String pattern) {
        return COUNT + "\n" + nz(db) + "\n" + nz(prefix) + "\n" + nz(pattern);
    }

    /**
     * 从筛选条件里把键名模式取出来。
     *
     * <p>只认「键」这一列上的条件，别的列一律忽略——值上的条件 Redis 的
     * {@code SCAN} 根本不认，认一半比不认更危险（用户会拿一份没筛干净的结果当结论）。
     */
    static String patternOf(FilterSpec filter) {
        if (filter == null) {
            return "";
        }
        for (FilterSpec.Condition c : filter.conditions()) {
            if (KEY_COLUMN.equals(c.column()) && c.value() != null) {
                return c.value();
            }
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ 取数

    /**
     * 取一页——<b>带完整的值</b>。
     *
     * <p>接口上这两个 {@code selectPage} 的分工在别家是「带不带筛选」，
     * 在这里还多一层：本变体的调用方全是<b>批量把数据读走</b>的
     * （{@code RowSource.ofTable} 导出、{@code TransferService}、{@code DataSyncService}），
     * 它们要的是能落盘、能灌进别处的完整值；另一个变体的调用方是界面网格，
     * 要的是一屏显示得下的预览。
     */
    @Override
    public String selectPage(String schema, String table, String orderBy, int limit, int offset) {
        return scanFullCommand(schema, table, limit, offset);
    }

    /**
     * 只认「键」这一列上的条件，别的一律不认。
     *
     * <p>Redis 能筛的只有键名（{@code SCAN MATCH}），值上的条件服务端根本不认。
     * 把能下推的那部分下推、剩下的悄悄忽略，用户会看到一份<b>看着像筛过</b>的结果，
     * 然后据此判断「这个条件没有匹配的数据」——所以界面上那个通用的「筛选」按钮
     * 在 Redis 上是不给的，取而代之的是工具条上专门的键名搜索框。
     */
    @Override
    public PreparedSql selectPage(String schema, String table, FilterSpec filter,
                                  List<ColumnInfo> columns, int limit, int offset) {
        return new PreparedSql(
                scanCommand(schema, table, limit, offset, patternOf(filter)), List.of());
    }

    @Override
    public String countRows(String schema, String table) {
        return countCommand(schema, table);
    }

    @Override
    public PreparedSql countRows(String schema, String table, FilterSpec filter,
                                 List<ColumnInfo> columns) {
        return new PreparedSql(countCommand(schema, table, patternOf(filter)), List.of());
    }

    // ------------------------------------------------------------------ 标识符

    /**
     * Redis 的键没有「引用」这回事——它就是一串字节，原样发过去。
     *
     * <p>加引号反而会出事：真去 {@code GET "user:1"} 找的是带引号那个键，
     * 而库里存的是不带引号的，结果是「查不到」，且看不出原因。
     */
    @Override
    public String quote(String identifier) {
        return identifier;
    }

    @Override
    public String limitOffset(int limit, int offset) {
        return "";
    }

    // ------------------------------------------------------------------ 能力边界

    /** Redis 没有表结构：没有列、没有类型、没有索引、没有外键、没有触发器。 */
    @Override
    public boolean hasTableStructure() {
        return false;
    }

    @Override
    public boolean supports(TableChange change) {
        return false;
    }

    @Override
    public String unsupportedReason(TableChange change) {
        return "Redis 里没有表结构可改：键空间只是按前缀分的组，不是表，也没有列定义。"
                + "要改数据请直接用命令（SET / HSET / DEL），在 SQL 编辑器里发";
    }

    @Override
    public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
        throw new com.plainly.driver.DbException(unsupportedReason(change));
    }

    @Override
    public String schemaCreationUnsupportedReason() {
        return "Redis 的库是编号固定的（默认 0–15，由服务端的 databases 配置决定），"
                + "不能新建，也不能改名。要加库得改服务端配置并重启。";
    }

    @Override
    public String triggerUnsupportedReason() {
        return "Redis 没有触发器。要在数据变化时做点什么，靠的是键空间通知"
                + "（notify-keyspace-events）加一个订阅方，那是服务端配置和另一个进程的事。";
    }

    @Override
    public String createTriggerTemplate(String schema, String table, String triggerName) {
        return null;
    }

    @Override
    public String explainQuery(String sql) {
        return null;
    }

    @Override
    public String viewDefinitionQuery(String schema, String view) {
        return null;
    }

    @Override
    public String triggersQuery(String schema, String table) {
        return null;
    }

    @Override
    public String createIndexDdl(String schema, String table, IndexInfo index) {
        throw new com.plainly.driver.DbException("Redis 没有索引：键本身就是索引，"
                + "按值查找要靠自己另建一个集合来维护");
    }

    @Override
    public String dropIndexDdl(String schema, String table, String indexName) {
        throw new com.plainly.driver.DbException("Redis 没有索引");
    }

    /**
     * 「清空」这一组键。
     *
     * <p>这里<b>不</b>返回 {@code FLUSHDB}：那会清掉整个库，而用户点的是某一个键空间。
     * 差一个前缀就是全库没了，这种事不能靠提示语来防。
     */
    @Override
    public String truncateTableDdl(String schema, String table) {
        throw new com.plainly.driver.DbException(
                "本工具不提供「清空键空间」：Redis 只有 FLUSHDB（清掉整个库）和逐个 DEL，"
                + "前者和你点的这一组键不是一回事。要删请在 SQL 编辑器里自己发 DEL 命令。");
    }

    @Override
    public String dropTableDdl(String schema, String table) {
        throw new com.plainly.driver.DbException(
                "「键空间」是本工具按前缀分出来的组，服务端并不存在这样一个对象，删不了它。"
                + "要删其中的键请用 DEL 命令。");
    }

    @Override
    public String columnDefinition(ColumnDraft column) {
        throw new com.plainly.driver.DbException("Redis 没有列定义");
    }

    /** 一条命令就是一条命令，没有事务性 DDL 这回事。 */
    @Override
    public boolean supportsTransactionalDdl() {
        return false;
    }
}
