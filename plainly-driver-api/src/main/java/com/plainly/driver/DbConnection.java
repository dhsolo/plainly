package com.plainly.driver;

import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.List;

/**
 * 一条已建立的数据库连接。
 *
 * <p>实现不保证线程安全：一条连接同一时刻只服务一个查询。
 * 并发由上层的连接池 / 每标签页一条连接来解决。
 */
public interface DbConnection extends AutoCloseable {

    ConnectionConfig config();

    SqlDialect dialect();

    /** 服务端版本描述，用于状态栏。 */
    String serverVersion();

    /**
     * 执行一条查询或 DML。
     *
     * @param maxRows 取回行数上限，{@code <= 0} 表示不限
     */
    QueryResult execute(String sql, int maxRows);

    /** 执行参数化语句，值以文本传入，按列的 {@link TypeCategory} 决定绑定方式。 */
    int executeUpdate(SqlDialect.PreparedSql statement, List<String> values);

    /**
     * 带参数的查询。
     *
     * <p>筛选条件里的值走这里绑定，不进 SQL 文本——这是精度不丢的前提。
     */
    QueryResult executeQuery(SqlDialect.PreparedSql statement, List<String> values, int maxRows);

    /**
     * 批量执行同一条语句。
     *
     * <p>导入几万行时逐条往返，光网络往返就能占掉大半时间。
     *
     * @param rows 每行一组值，顺序与语句里的占位符一致
     * @return 影响的行数；驱动给不出确切数字时按成功执行的语句数计
     */
    int executeBatch(SqlDialect.PreparedSql statement, List<List<String>> rows);

    /**
     * 取消当前正在执行的语句。可从其他线程调用。
     * <p>底层是 {@code Statement.cancel()}，是否真的中断取决于数据库和驱动。
     */
    void cancel();

    List<SchemaInfo> listSchemas();

    List<TableInfo> listTables(String schema);

    TableStructure describeTable(String schema, String table);

    /** 本表持有的外键：本表的列指向别的表。 */
    List<DbObjects.ForeignKeyInfo> listForeignKeys(String schema, String table);

    /**
     * 指向本表的外键：别的表的列指向本表。
     *
     * <p>删数据之前最该看的就是这一栏——被人引用着的行删不掉，或者会连带删掉别人的数据。
     */
    List<DbObjects.ForeignKeyInfo> listReferencingKeys(String schema, String table);

    /** 挂在本表上的触发器。取不到时返回空表，不抛异常。 */
    List<DbObjects.TriggerInfo> listTriggers(String schema, String table);

    /**
     * 触发器列表，外加「为什么是空的」。
     *
     * <p>{@link #listTriggers} 取不到时返回空表，这对调用方是方便的，
     * 但对<b>用户</b>是有害的：刚建完一个触发器、回到列表看到空的，
     * 他只能怀疑是创建没成功——而真实原因可能是权限不够，或者这一家的
     * 数据字典视图列名和我们发的那条语句对不上。
     *
     * <p>所以要看列表的界面走这个方法，把原因和实际发出去的语句一并拿到。
     * 默认实现退化成没有诊断信息，行为和原来一致。
     */
    default DbObjects.TriggerListing listTriggersDetailed(String schema, String table) {
        return DbObjects.TriggerListing.of(listTriggers(schema, table), null);
    }

    /**
     * 按名字片段找列。
     *
     * <p>为什么要有这个方法，而不是让上层逐表 {@link #describeTable} 再过滤：
     * 几千张表的库里那是几千次往返，搜索框会在用户敲第二个字符时就卡死。
     * JDBC 的 {@code getColumns} 本来就支持名字模式，一次调用扫完一个库。
     *
     * <p>{@code fragment} 是<b>纯文本</b>，不是模式：其中的 {@code _} 和 {@code %}
     * 由实现负责转义。不转义的话，搜 {@code user_id} 会把 {@code userXid} 一起搜出来。
     *
     * <p>这是辅助功能，取不到就返回空表——权限不足或驱动不支持时应当安静地
     * 降级成「只搜表名」，而不是让整个搜索框报错。
     *
     * @param limit 最多返回多少条
     */
    default List<DbObjects.ColumnRef> searchColumns(String schema, String fragment, int limit) {
        return List.of();
    }

    /**
     * 一个库里<b>所有</b>列的名字、类型和注释。
     *
     * <p>给对象搜索建索引用。为什么要整批取而不是每次按片段查：
     * <ul>
     *   <li>按片段查只能查<b>列名</b>——JDBC 的名字模式匹配不了注释。而中文库里列名多是
     *       英文（{@code createTime}），注释才是人真正会去搜的那个词（「创建时间」）；</li>
     *   <li>每敲一个字符都发一轮查询，多库环境下一次搜索十几次往返；整批取一次之后
     *       全在内存里匹配，后续零往返。</li>
     * </ul>
     *
     * <p>代价是第一次要把整库的列元数据拉下来。所以调用方应当缓存结果，
     * 并且放在后台做。
     */
    default List<DbObjects.ColumnRef> listColumns(String schema) {
        return List.of();
    }

    /**
     * 流式执行一条查询：逐行推送，不把结果堆在内存里。
     *
     * <h2>为什么要有它，而不是把 {@link #execute} 的上限调大</h2>
     * {@code execute} 会把取回的行全部装进 {@link QueryResult}，行数由调用方给的上限兜着。
     * 那对界面是对的——网格只显示一屏，取回两百万行没有意义，还会 OOM。
     *
     * <p>但<b>导出</b>要的正是全部。原来查询结果的导出只能把网格里已有的那些行写出去，
     * 用户想导两百万行，拿到的是一千行——而且他多半不会数。
     * 这个方法让导出重新执行一遍语句并逐行往外推，内存占用与结果集大小无关。
     *
     * <h2>调用方必须知道的两件事</h2>
     * <ul>
     *   <li><b>语句会被再执行一次</b>。代价是又跑一遍查询，收益是拿到完整结果；</li>
     *   <li><b>数据可能已经变了</b>。这一次执行和上一次之间隔着若干时间，
     *       两次的结果未必一致。界面上要说清楚。</li>
     * </ul>
     *
     * <p>默认实现退化成一次性全取——非关系型驱动没有游标这回事，
     * 与其让它们各自造一个假的流式，不如老实地一次取完，行为仍然正确。
     *
     * @return 推送出去的行数
     */
    default long stream(String sql, RowStream sink) {
        QueryResult result = execute(sql, 0);
        sink.columns(result.columns());
        result.rows().forEach(sink::row);
        return result.rows().size();
    }

    /**
     * 流式查询的接收方。
     *
     * <p>列信息单独一个回调，而不是让 {@link #stream} 返回它：
     * 列要在第一行之前就交出去（导出的表头得先写），而这时行还一条都没读。
     */
    interface RowStream {

        /** 列信息，在第一行之前调用一次。 */
        void columns(List<ColumnMeta> columns);

        void row(Row row);
    }

    /** 单值查询，用于 COUNT(*) 这类。返回原始文本。 */
    String scalar(String sql);

    /**
     * 把连接的当前库切到 {@code schema}。
     *
     * <p>为什么必需：一条连接可能同时服务多个查询标签页，而各标签页的目标库未必相同。
     * 不带库名限定的 SQL（{@code SELECT * FROM orders}）跑在哪个库上，
     * 取决于连接<b>此刻</b>的 catalog/schema——执行前不切，就会静默跑到别的库上去。
     */
    void useSchema(String schema);

    /** 在一个事务里执行若干条语句，失败整体回滚。仅适用于 DML。 */
    void inTransaction(List<String> statements);

    // ------------------------------------------------------------------ 手动事务

    /**
     * 这条连接能不能交给用户手动控制事务。
     *
     * <p>默认 {@code false}。键值库没有事务这回事，摆出「提交 / 回滚」两个按钮
     * 只会让人以为刚才那些写还能撤回来。
     */
    default boolean supportsManualCommit() {
        return false;
    }

    /** 当前是不是自动提交。手动事务模式下为 {@code false}。 */
    default boolean autoCommit() {
        return true;
    }

    /**
     * 切换自动提交。
     *
     * <p>从手动切回自动时，如果手上还有没提交的改动，实现<b>必须拒绝</b>而不是照做：
     * JDBC 在 {@code setAutoCommit(true)} 时会把未提交的事务隐式提交掉。
     * 用户点这个开关的意思是「不想再手动管了」，不是「把刚才那些都提交」——
     * 两者的差别可能是一批本来要回滚的改动就这么落了库。
     */
    default void setAutoCommit(boolean value) {
        throw new DbException("这个数据库不支持手动事务");
    }

    /**
     * 手动事务里有没有还没提交的改动。
     *
     * <p>用于状态栏的提示和关窗口前的拦截。判据是「上次提交/回滚之后有没有执行过写语句」，
     * 不是去问数据库——多数驱动问不出来。
     */
    default boolean hasPendingTransaction() {
        return false;
    }

    default void commit() {
        throw new DbException("这个数据库不支持手动事务");
    }

    default void rollback() {
        throw new DbException("这个数据库不支持手动事务");
    }

    /**
     * 执行一批 DDL。
     *
     * <p>与 {@link #inTransaction} 分开是因为 DDL 的事务语义各家不同：
     * PostgreSQL / SQLite 可以整批回滚，MySQL / Oracle / H2 的 DDL 会隐式提交。
     * 本方法按 {@link SqlDialect#supportsTransactionalDdl()} 选择执行方式，
     * 失败时抛出 {@link DdlBatchException}，其中说明库当前处于哪种状态。
     *
     * @return 成功执行的语句条数
     */
    int executeDdlBatch(List<String> statements);

    /**
     * 在指定的库上执行一批 DDL。
     *
     * <h2>为什么要有带库名的这一版</h2>
     * 多数 DDL 会把库名写进语句（{@code DROP TABLE `db`.`t`}），在哪个库上执行都一样。
     * 但有一类天生写不进去——MySQL 的 {@code CREATE TRIGGER} 是最典型的：
     * 触发器名不带库限定，它认的是<b>当前库</b>。
     *
     * <p>而一条连接很可能<b>没有</b>当前库：连接配置里不填默认库是很常见的做法。
     * 那时服务端回一句 {@code No database selected}，用户看到的却是
     * 「新建触发器点了就报这个」——完全看不出跟连接有没有填默认库有关。
     *
     * <p>所以这件事不该靠每个调用方自己记得先切库。凡是作用在某个库上的 DDL
     * 都走这个方法，切库和执行绑在一起，忘不掉。
     *
     * <p><b>建库和删库不能走这里</b>：那时目标库还不存在，或者正要被删掉，
     * 切过去只会报错。那两处仍然用不带库名的那一版。
     */
    default int executeDdlBatch(String schema, List<String> statements) {
        useSchema(schema);
        return executeDdlBatch(statements);
    }

    /**
     * 这条连接此刻是不是正在执行语句。
     *
     * <p>给后台的辅助功能用（对象搜索会顺着已连接的连接读元数据）：一条连接同一时刻
     * 只服务一个查询，辅助功能撞上用户正在跑的语句时应当让路、跳过这一条，
     * 而不是把人家的查询挤掉或者干脆卡在那里等。
     */
    default boolean isBusy() {
        return false;
    }

    boolean isClosed();

    /**
     * 这条连接<b>现在</b>还能用吗。
     *
     * <h2>和 isClosed() 不是一回事，这个区别会真的坑到人</h2>
     * {@link #isClosed()} 只回答「本地有没有调过 close()」。服务端把会话掐掉——
     * MySQL 的 {@code wait_timeout} 到点、DBA 执行 KILL、防火墙清理空闲连接、
     * 笔记本合盖再打开——它一概不知道，仍然返回 {@code false}。
     *
     * <p>于是界面一直以为自己连着：树上挂着「在线」，右键菜单里的「连接」
     * 因为「已经连着了」而是灰的，用户点它当然没有任何反应。
     * 这正是「长时间断开之后连不上」的成因。
     *
     * <p>本方法会真的问一次对端，所以<b>有一次网络往返的代价</b>。
     * 只能在明确的用户动作上调用（点「连接」、打开一张表），
     * <b>绝不能</b>放进单元格渲染那种每帧都跑的地方。
     *
     * <p>默认实现退化成 {@code !isClosed()}——认不出远端断开，但不会更糟。
     */
    default boolean isAlive() {
        return !isClosed();
    }

    @Override
    void close();

    /** 便捷方法：某张表的字段列表。 */
    default List<ColumnInfo> columnsOf(String schema, String table) {
        return describeTable(schema, table).columns();
    }
}
