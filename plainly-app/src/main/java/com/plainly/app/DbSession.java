package com.plainly.app;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.QueryResult;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一条活动连接及其元数据缓存。
 *
 * <p>缓存是必需的而不是优化：几千张表的库，每次展开树都去查一遍 information_schema
 * 会让界面直接卡死。刷新由用户显式触发。
 */
public class DbSession {

    private final ConnectionConfig config;
    private final DbConnection connection;

    /*
     * 并发映射不是优化，是正确性：树的展开、查询标签页、后台的对象搜索都会往这两张缓存里写，
     * 而它们跑在不同的线程上。普通 HashMap 在并发写下会坏掉——不是抛异常那种坏法，
     * 是安静地丢条目或者死循环，事后完全查不出来。
     * 顺序在这里没有意义（两张都是按 key 查的缓存），所以放弃 LinkedHashMap 不损失什么。
     */
    private volatile List<SchemaInfo> schemas;
    private final Map<String, List<TableInfo>> tablesBySchema = new ConcurrentHashMap<>();
    private final Map<String, TableStructure> structures = new ConcurrentHashMap<>();

    public DbSession(ConnectionConfig config, DbConnection connection) {
        this.config = config;
        this.connection = connection;
    }

    public ConnectionConfig config() {
        return config;
    }

    public DbConnection connection() {
        return connection;
    }

    public List<SchemaInfo> schemas() {
        if (schemas == null) {
            schemas = connection.listSchemas();
        }
        return schemas;
    }

    /**
     * 一个库里的表清单，带缓存。
     *
     * <p>刻意<b>不用</b> {@code computeIfAbsent}：它的映射函数是一次完整的元数据查询
     * （几千张表的库上要好几秒），而 computeIfAbsent 在整个映射函数期间都占着
     * 那个桶的锁——别的线程读同一个桶就得干等着这次网络往返。
     *
     * <p>拆开之后的代价是两个线程可能同时查同一个库，多一次往返，结果一样。
     * 用 putIfAbsent 收口，保证大家最后拿到的是同一份。
     */
    public List<TableInfo> tables(String schema) {
        List<TableInfo> cached = tablesBySchema.get(schema);
        if (cached != null) {
            return cached;
        }
        List<TableInfo> loaded = List.copyOf(connection.listTables(schema));
        List<TableInfo> raced = tablesBySchema.putIfAbsent(schema, loaded);
        return raced != null ? raced : loaded;
    }

    /**
     * 表结构缓存的键。
     *
     * <h2>为什么分隔符是 NUL 而不是空格或点</h2>
     * 库名和表名里都可能带空格。用空格拼的话，{@code "a b" + " " + "c"} 和
     * {@code "a" + " " + "b c"} 会得到同一个键——两张不同的表共用一份结构。
     * 而这种错不报错，只会让其中一张表显示成另一张的样子。NUL 不可能出现在标识符里。
     *
     * <h2>为什么必须收在这一个方法里</h2>
     * 原来这个拼法在三处各写一遍，而分隔符是源码里一个<b>看不见的字节</b>——
     * 编辑器、diff 都显示成空白，grep 甚至会把整个文件判定成二进制。
     * 于是第四处很自然地写成了真空格，读写两侧对不上：
     * {@code structure()} 存进去了，{@code cachedStructure()} 一辈子读不到，
     * 表现是「补全永远给不出字段」，而且没有任何报错。
     *
     * <p>所以写成 {@code (char) 0}：看得见，也只有这一处。
     */
    private static String structureKey(String schema, String table) {
        return schema + (char) 0 + table;
    }

    /** 一张表的结构，带缓存。不用 computeIfAbsent 的理由见 {@link #tables}。 */
    public TableStructure structure(String schema, String table) {
        String key = structureKey(schema, table);
        TableStructure cached = structures.get(key);
        if (cached != null) {
            return cached;
        }
        TableStructure loaded = connection.describeTable(schema, table);
        TableStructure raced = structures.putIfAbsent(key, loaded);
        return raced != null ? raced : loaded;
    }

    /** 供 SQL 补全用：某张表的列名。取不到时返回空表，补全降级而不是报错。 */
    public List<ColumnInfo> columnsForCompletion(String schema, String table) {
        try {
            return structure(schema, table).columns();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 当前库里所有表名，供补全用。 */
    public List<String> tableNamesForCompletion(String schema) {
        try {
            List<String> names = new ArrayList<>();
            tables(schema).forEach(t -> names.add(t.name()));
            return names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 缓存里已有的表清单；没有就返回 {@code null}。<b>绝不发起查询。</b>
     *
     * <p>给界面线程上那些「顺手看一眼」的地方用：补全候选、撞名检查。
     * 这些地方的共同点是<b>每敲一个字符就跑一次</b>，而 {@link #tables} 在
     * 未命中缓存时是一次完整的网络往返——放在界面线程上，慢库会卡顿，
     * 连接卡死时整个窗口就冻住了。
     *
     * <p>调用方拿到 null 的正确反应是「这次少给点信息」，不是「去查一下」。
     * 真要那份数据，就在后台线程上调 {@link #tables} 把缓存捂热，
     * 回来之后再刷一次界面。
     */
    public List<TableInfo> cachedTables(String schema) {
        return tablesBySchema.get(schema);
    }

    /** 缓存里已有的表结构；没有就返回 {@code null}。绝不发起查询，理由同上。 */
    public TableStructure cachedStructure(String schema, String table) {
        return structures.get(structureKey(schema, table));
    }

    /** 补全用的表名，只取缓存里有的。没有就返回空表——补全降级，不是卡住。 */
    public List<String> cachedTableNames(String schema) {
        List<TableInfo> cached = cachedTables(schema);
        if (cached == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(cached.size());
        cached.forEach(t -> names.add(t.name()));
        return names;
    }

    /** 补全用的列，只取缓存里有的。 */
    public List<ColumnInfo> cachedColumns(String schema, String table) {
        TableStructure cached = cachedStructure(schema, table);
        return cached == null ? List.of() : cached.columns();
    }

    public void invalidate() {
        schemas = null;
        tablesBySchema.clear();
        structures.clear();
    }

    public void invalidateTable(String schema, String table) {
        structures.remove(structureKey(schema, table));
    }

    /**
     * 把一份「确定来自单表」的结果集归属到那张表上。
     *
     * <h2>为什么需要这一步</h2>
     * Oracle 的 JDBC 驱动不报结果集的来源表名，于是每一列的 tableName 都是空的，
     * 结果集被判成只读——Oracle 上<b>每一张表</b>的数据网格都改不了。
     *
     * <p>但表页是知道自己打开的是哪张表的。这里把那份已知信息补回去，
     * 顺带按真实结构标出主键列。
     *
     * <p>只在<b>确实缺失</b>时才补：驱动报得出表名的库（MySQL、PostgreSQL、达梦）
     * 原样返回，不去动它已经算对的东西。
     */
    public QueryResult attributeToTable(QueryResult result, String schema, String table) {
        if (result == null || result.columns().isEmpty()) {
            return result;
        }
        boolean missing = result.columns().stream()
                .allMatch(c -> c.tableName() == null || c.tableName().isBlank());
        if (!missing) {
            return result;
        }
        try {
            return result.attributedTo(schema, table, structure(schema, table).primaryKeyColumns());
        } catch (RuntimeException e) {
            // 读不到表结构就维持原样：只读总比标注错了强
            return result;
        }
    }

    // ------------------------------------------------------------------ DDL

    /**
     * 在某个库上执行一批 DDL。
     *
     * <h2>为什么必须先切库</h2>
     * 一条连接可能同时服务好几个标签页，各自的目标库未必相同；更常见的是连接配置里
     * 根本没填默认库，于是这条连接<b>压根没有「当前库」</b>。
     *
     * <p>多数 DDL 会把库名写进语句（{@code DROP TABLE `db`.`t`}），那种不受影响。
     * 但有一类天生写不进去——MySQL 的 {@code CREATE TRIGGER} 就是：触发器名不带库限定，
     * 它认的是<b>当前库</b>。没有当前库时服务端回一句
     * {@code No database selected}，而用户面对的是「新建触发器点了就报这个」，
     * 完全看不出跟连接有没有填默认库有关。
     *
     * <p>所以这件事不能靠每个调用方自己记得。凡是作用在某个库上的 DDL 都走这里，
     * 切库和执行绑在一起，忘不掉。
     *
     * <p><b>例外是建库和删库</b>：那时候目标库还不存在（或正要被删掉），
     * 切过去只会报错。那两处仍然直接调 {@code executeDdlBatch}。
     *
     * <p>不变量本身落在驱动契约里（{@link DbConnection#executeDdlBatch(String, List)}），
     * 那儿有测试守着；这里只是界面层用起来顺手的一层。
     */
    public int executeDdl(String schema, List<String> statements) {
        return connection.executeDdlBatch(schema, statements);
    }

    // ------------------------------------------------------------------ 事务状态

    /*
     * 事务是<b>整条连接</b>的状态，不是某一个标签页的。
     *
     * 这条连接可能同时开着一个 SQL 编辑器和三张表页，它们共用同一个物理连接，
     * 因而共用同一个事务。任何一处提交，别处的「未提交」标记都得跟着灭掉——
     * 否则用户会看着一个早就不存在的事务，然后去点「回滚」。
     *
     * Navicat 的做法是每个标签页各持一条物理连接，各管各的事务。那样更贴近直觉，
     * 代价是连接数翻几倍、每条连接的当前库要各自维护。这里选了另一条：
     * 一条连接一个事务，但把这件事在界面上说明白（见 TransactionBar 上那句提示）。
     */
    private final List<Runnable> transactionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addTransactionListener(Runnable listener) {
        transactionListeners.add(listener);
    }

    public void removeTransactionListener(Runnable listener) {
        transactionListeners.remove(listener);
    }

    /** 事务状态可能变了，通知所有在看的界面。只能在界面线程上调。 */
    public void fireTransactionChanged() {
        transactionListeners.forEach(Runnable::run);
    }

    /** 当前是不是手动事务模式。 */
    public boolean manualTransaction() {
        return connection.supportsManualCommit() && !connection.autoCommit();
    }

    /** 手动事务模式下有没有还没提交的改动。 */
    public boolean hasPendingTransaction() {
        return connection.supportsManualCommit() && connection.hasPendingTransaction();
    }
}
