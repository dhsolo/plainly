package com.plainly.driver.jdbc;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DdlBatchException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** {@link DbConnection} 的 JDBC 实现。 */
public class JdbcConnection implements DbConnection {

    private final SshTunnel tunnel;
    private final ConnectionConfig config;

    /**
     * 当前这条 JDBC 连接。
     *
     * <h2>为什么不是 final</h2>
     * 服务端会掐掉闲置的会话（MySQL 的 {@code wait_timeout} 默认 8 小时，
     * 云上的代理常常几分钟就断），断开这件事<b>本地一点感觉都没有</b>——
     * 直到下一次用它，才收到「No operations allowed after connection closed」。
     *
     * <p>所以这里允许换一条新的，见 {@link #reopen()}。
     * 用 volatile：重连发生在用户线程上，而别的后台线程可能正拿着它读元数据。
     */
    private volatile Connection conn;

    /**
     * 当前切到了哪个库。重连之后要切回去——新连接是干净的，
     * 不切的话后面所有不带库名限定的语句都会打到默认库上，而且不报错。
     */
    private volatile String currentSchema;

    /** 用户是不是把这条连接切到了手动提交。重连之后要恢复这个选择。 */
    private volatile boolean manualMode;
    private final SqlDialect dialect;

    /**
     * 元数据是否要绕开驱动，自己发 SQL 取。
     *
     * <p>目前只有金仓 V8R3 会是 true：它的系统目录叫 {@code sys_catalog}，
     * 而能拿到的驱动全都按 {@code pg_catalog} 查。详见 {@link KingbaseLegacy}。
     *
     * <p><b>别的库一行都不会走到那条路上</b>——原来的 {@code DatabaseMetaData}
     * 路径保持原样，这一条只在确实撞上时才亮。
     */
    private final boolean legacyMeta;
    private final String serverVersion;

    /** 正在执行的语句，供 {@link #cancel()} 从别的线程取用。 */
    private final AtomicReference<Statement> running = new AtomicReference<>();

    /**
     * 主键缓存：key 为 catalog/schema/table，避免每次查询都问一遍元数据。
     *
     * <h2>为什么必须是并发映射</h2>
     * 契约上写着「一条连接同一时刻只服务一个查询」，但那说的是<b>语句</b>，
     * 不是这张缓存。实际跑起来，展开树、预热对象搜索索引、恢复标签页
     * 会各自在后台线程上读元数据，都会落到这里。
     *
     * <p>原来是普通 HashMap，而且用的是 {@code computeIfAbsent}——它的映射函数里
     * 还夹着一次 JDBC 往返。{@code HashMap.computeIfAbsent} 在映射函数执行期间
     * 一旦发现表被结构性修改，就抛 {@link java.util.ConcurrentModificationException}，
     * 而那个窗口正好有一整次网络往返那么长。
     *
     * <p>用户看到的是「读取 xxx 失败：ConcurrentModificationException」——
     * 一条跟他刚才的操作毫无关系、也无从复现的报错。
     */
    private final Map<String, Set<String>> pkCache = new ConcurrentHashMap<>();

    public JdbcConnection(ConnectionConfig config, Connection conn, SqlDialect dialect) {
        this(config, conn, dialect, null, false);
    }

    /** @param tunnel 走跳板机时的 SSH 隧道；直连为 null。隧道跟着连接活，也跟着连接死。 */
    public JdbcConnection(ConnectionConfig config, Connection conn, SqlDialect dialect,
                          SshTunnel tunnel) {
        this(config, conn, dialect, tunnel, false);
    }

    /**
     * @param legacyMeta 元数据要绕开驱动自己发 SQL 取。目前只有金仓 V8R3 会是 true，
     *                   见 {@link KingbaseLegacy}
     */
    public JdbcConnection(ConnectionConfig config, Connection conn, SqlDialect dialect,
                          SshTunnel tunnel, boolean legacyMeta) {
        this.config = config;
        this.conn = conn;
        this.dialect = dialect;
        this.tunnel = tunnel;
        this.legacyMeta = legacyMeta;
        this.serverVersion = readServerVersion(conn);
    }

    private static String readServerVersion(Connection conn) {
        try {
            DatabaseMetaData md = conn.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion()
                    + pagingCaveat(md);
        } catch (SQLException e) {
            return "未知";
        }
    }

    /**
     * 连上 11g 及更早的 Oracle 时，在版本号后面直说分页用不了。
     *
     * <p>{@code OracleDialect} 生成的是 12c 起才有的 {@code OFFSET ... FETCH}。
     * 在 11g 上发过去会报 ORA-00933「SQL 命令未正确结束」——一条看不出跟版本有关的错。
     * 与其等用户翻页时撞上，不如连上的那一刻就在状态栏说清楚。
     */
    private static String pagingCaveat(DatabaseMetaData md) {
        try {
            if (md.getDatabaseProductName() != null
                    && md.getDatabaseProductName().toUpperCase(java.util.Locale.ROOT)
                            .contains("ORACLE")
                    && md.getDatabaseMajorVersion() > 0 && md.getDatabaseMajorVersion() < 12) {
                return "（11g 及更早不支持 OFFSET/FETCH，网格翻页会报错，请用 SQL 编辑器）";
            }
        } catch (SQLException ignored) {
            // 问不出版本号就不加这句话
        }
        return "";
    }

    @Override
    public ConnectionConfig config() {
        return config;
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    // ------------------------------------------------------------------ 查询

    
    @Override
    public QueryResult execute(String sql, int maxRows) {
        return guarded(() -> executeOnce(sql, maxRows));
    }

    private QueryResult executeOnce(String sql, int maxRows) {
        long start = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            running.set(st);
            if (maxRows > 0) {
                // 多取一行用来判断是否被截断
                st.setMaxRows(maxRows + 1);
            }
            boolean hasResultSet = st.execute(sql);
            if (!hasResultSet) {
                int count = st.getUpdateCount();
                // 手动事务模式下记一笔「有未提交的改动」。只在这条分支记：
                // SELECT 也在事务里，但用户要的是「我改了东西还没提交」这个提醒，
                // 每查一次就亮起未提交，那盏灯很快就没人看了
                markWritten();
                return QueryResult.updated(count, millisSince(start), sql);
            }
            try (ResultSet rs = st.getResultSet()) {
                List<ColumnMeta> columns = readColumns(rs.getMetaData());
                List<Row> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (maxRows > 0 && rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    rows.add(readRow(rs, columns));
                }
                return result(columns, rows, millisSince(start), truncated, sql);
            }
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            running.set(null);
        }
    }

    /**
     * 装配结果集，顺手把「驱动压根不报来源表」这件事说清楚。
     *
     * <h2>为什么要单独处理</h2>
     * Oracle 的驱动对每一列都返回空表名，于是通用的判据会给出
     * 「结果里有不属于任何表的列（表达式、函数或聚合）」——
     * 一句和事实完全对不上的话。用户会去检查自己的 SELECT，
     * 而问题根本不在查询上，在驱动的能力上。
     *
     * <p>说实话的成本只有这几行，收益是用户不必为一句错的解释白查半天。
     */
    private QueryResult result(List<ColumnMeta> columns, List<Row> rows,
                               long millis, boolean truncated, String sql) {
        boolean noTableNames = !columns.isEmpty()
                && columns.stream().allMatch(c -> c.tableName() == null || c.tableName().isBlank());
        if (legacyMeta && noTableNames) {
            // V8R3 上 getTableName 本身就报错（见 readColumns），不是「这条 SQL 里没有表」。
            // 套用下面那句通用解释会让用户去检查自己的 SELECT，而问题根本不在 SQL 上
            return QueryResult.readOnly(columns, rows, millis, truncated, sql,
                    "这台金仓的版本比驱动老一代，驱动问不出结果集的来源表，"
                            + "所以这里定位不到写回目标。从左侧打开表来改数据——"
                            + "那条路知道自己打开的是哪张表，不受影响");
        }
        if (!dialect.reportsResultSetTableNames() && noTableNames) {
            return QueryResult.readOnly(columns, rows, millis, truncated, sql,
                    config.type().displayName()
                            + " 的驱动不报告结果集的来源表，所以这里定位不到写回目标。"
                            + "从左侧打开表来改数据——那条路知道自己打开的是哪张表");
        }
        return QueryResult.of(columns, rows, millis, truncated, sql);
    }

    private Row readRow(ResultSet rs, List<ColumnMeta> columns) throws SQLException {
        String[] values = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            values[i] = CellReader.read(rs, i + 1, columns.get(i).category());
        }
        return new Row(values);
    }

    /**
     * 每批从服务端取回多少行。
     *
     * <p>太小则往返次数多，太大则一批就把内存占满、流式的意义没了。
     * 一千行在两者之间，且和导出的批量写入节奏对得上。
     */
    private static final int STREAM_FETCH = 1000;

    /**
     * 流式查询。
     *
     * <h2>各家开启游标的条件完全不同，而且不满足时不会报错</h2>
     * <ul>
     *   <li><b>MySQL</b>：只有 {@code setFetchSize(Integer.MIN_VALUE)} 才真的流式。
     *       给一个正数（哪怕 1000）它照样把整个结果集拉进客户端内存——
     *       没有任何提示，只是某一刻 OOM；</li>
     *   <li><b>PostgreSQL</b>：必须<b>关掉自动提交</b>，否则 fetchSize 被完全忽略，
     *       同样是安静地全量拉回；</li>
     *   <li>其余几家给正的 fetchSize 就够。</li>
     * </ul>
     * 这三条都属于「写错了也能跑通、只是没生效」，所以必须在这里写明白。
     *
     * <p>结果集声明成只进只读：流式游标本来就不支持回滚定位，
     * 声明清楚能让驱动挑最省的实现。
     */
    @Override
    public long stream(String sql, RowStream sink) {
        boolean postgres = config.type() == DbType.POSTGRESQL;
        boolean autoCommitTurnedOff = false;
        try {
            if (postgres && conn.getAutoCommit()) {
                conn.setAutoCommit(false);
                autoCommitTurnedOff = true;
            }
            try (Statement st = conn.createStatement(
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                running.set(st);
                st.setFetchSize(config.type() == DbType.MYSQL
                        ? Integer.MIN_VALUE : STREAM_FETCH);
                try (ResultSet rs = st.executeQuery(sql)) {
                    List<ColumnMeta> columns = readColumns(rs.getMetaData());
                    sink.columns(columns);
                    long count = 0;
                    while (rs.next()) {
                        sink.row(readRow(rs, columns));
                        count++;
                    }
                    return count;
                }
            }
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            running.set(null);
            if (autoCommitTurnedOff) {
                // 一定要还回去：这条连接接着还要服务界面上的其它操作，
                // 留在手动提交状态下，后面每一次写都会「成功」却不落库
                try {
                    conn.rollback();
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 还不回去也不该盖掉上面真正的异常
                }
            }
        }
    }

    
    @Override
    public String scalar(String sql) {
        return reading(() -> scalarOnce(sql));
    }

    private String scalarOnce(String sql) {
        try (Statement st = conn.createStatement()) {
            running.set(st);
            try (ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) {
                    return null;
                }
                TypeCategory cat = TypeCategories.of(rs.getMetaData().getColumnType(1),
                        rs.getMetaData().getColumnTypeName(1));
                return CellReader.read(rs, 1, cat);
            }
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            running.set(null);
        }
    }

    
    @Override
    public void useSchema(String schema) {
        reading(() -> {
            useSchemaOnce(schema);
            return null;
        });
    }

    private void useSchemaOnce(String schema) {
        if (schema == null || schema.isBlank() || config.type() == DbType.SQLITE) {
            // SQLite 只有一个库，没有可切的东西
            return;
        }
        try {
            if (usesCatalogs()) {
                conn.setCatalog(schema);
            } else {
                conn.setSchema(schema);
            }
            currentSchema = schema;
        } catch (SQLException e) {
            throw new DbException("切换到 " + schema + " 失败：" + e.getMessage(), e.getSQLState(), e);
        }
    }

    @Override
    public int executeUpdate(SqlDialect.PreparedSql statement, List<String> values) {
        if (config.readOnly()) {
            throw new DbException("该连接为只读模式，写操作已被拦截");
        }
        List<ColumnInfo> bound = statement.boundColumns();
        if (bound.size() != values.size()) {
            throw new DbException("绑定参数数量不匹配：期望 " + bound.size() + "，实得 " + values.size());
        }
        try (PreparedStatement ps = conn.prepareStatement(statement.sql())) {
            running.set(ps);
            for (int i = 0; i < values.size(); i++) {
                CellReader.bind(ps, i + 1, values.get(i), bound.get(i).category());
            }
            int affected = ps.executeUpdate();
            markWritten();
            return affected;
        } catch (SQLException e) {
            throw wrap(e, statement.sql());
        } finally {
            running.set(null);
        }
    }

    
    @Override
    public QueryResult executeQuery(SqlDialect.PreparedSql statement, List<String> values,
                                    int maxRows) {
        return reading(() -> executeQueryOnce(statement, values, maxRows));
    }

    private QueryResult executeQueryOnce(SqlDialect.PreparedSql statement,
                                         List<String> values, int maxRows) {
        List<ColumnInfo> bound = statement.boundColumns();
        if (bound.size() != values.size()) {
            throw new DbException("绑定参数数量不匹配：期望 " + bound.size() + "，实得 " + values.size());
        }
        long start = System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(statement.sql())) {
            running.set(ps);
            if (maxRows > 0) {
                ps.setMaxRows(maxRows + 1);
            }
            for (int i = 0; i < values.size(); i++) {
                CellReader.bind(ps, i + 1, values.get(i), bound.get(i).category());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ColumnMeta> columns = readColumns(rs.getMetaData());
                List<Row> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (maxRows > 0 && rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    rows.add(readRow(rs, columns));
                }
                return result(columns, rows, millisSince(start), truncated, statement.sql());
            }
        } catch (SQLException e) {
            throw wrap(e, statement.sql());
        } finally {
            running.set(null);
        }
    }

    @Override
    public int executeBatch(SqlDialect.PreparedSql statement, List<List<String>> rows) {
        if (config.readOnly()) {
            throw new DbException("该连接为只读模式，写操作已被拦截");
        }
        if (rows.isEmpty()) {
            return 0;
        }
        List<ColumnInfo> bound = statement.boundColumns();
        try (PreparedStatement ps = conn.prepareStatement(statement.sql())) {
            running.set(ps);
            for (List<String> row : rows) {
                if (row.size() != bound.size()) {
                    throw new DbException("绑定参数数量不匹配：期望 " + bound.size()
                            + "，实得 " + row.size());
                }
                for (int i = 0; i < row.size(); i++) {
                    CellReader.bind(ps, i + 1, row.get(i), bound.get(i).category());
                }
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            markWritten();
            int affected = 0;
            for (int c : counts) {
                // SUCCESS_NO_INFO(-2) 表示成功但不知道影响几行，按一行计
                affected += c >= 0 ? c : (c == Statement.SUCCESS_NO_INFO ? 1 : 0);
            }
            return affected;
        } catch (SQLException e) {
            throw wrap(e, statement.sql());
        } finally {
            running.set(null);
        }
    }

    // ------------------------------------------------------------------ 手动事务

    /**
     * 手动事务里有没有还没提交的写。
     *
     * <p>为什么要自己记而不是问驱动：JDBC 没有「当前事务是否为空」这个问题的答案。
     * 而这个标记的用处全在界面上——状态栏要不要亮、关窗口要不要拦、
     * 切回自动提交要不要先问一句，靠的都是它。
     */
    private volatile boolean pendingTx;

    @Override
    public boolean supportsManualCommit() {
        return true;
    }

    @Override
    public boolean autoCommit() {
        try {
            return conn.getAutoCommit();
        } catch (SQLException e) {
            // 问不出来就按自动提交报告：那是更安全的假设——
            // 界面不会因此以为有一批改动还攥在手里而去拦用户
            return true;
        }
    }

    @Override
    public void setAutoCommit(boolean value) {
        try {
            if (conn.getAutoCommit() == value) {
                return;
            }
            if (value && pendingTx) {
                // setAutoCommit(true) 会把未提交的事务隐式提交掉。
                // 用户点这个开关的意思是「不想再手动管了」，不是「把刚才那些都提交」
                throw new DbException("手上还有没提交的改动。先「提交」或「回滚」，再切回自动提交");
            }
            conn.setAutoCommit(value);
            manualMode = !value;
            pendingTx = false;
        } catch (SQLException e) {
            throw wrap(e, value ? "SET AUTOCOMMIT ON" : "SET AUTOCOMMIT OFF");
        }
    }

    @Override
    public boolean hasPendingTransaction() {
        return pendingTx && !autoCommit();
    }

    @Override
    public void commit() {
        try {
            if (conn.getAutoCommit()) {
                throw new DbException("当前是自动提交模式，没有事务可提交");
            }
            conn.commit();
            pendingTx = false;
        } catch (SQLException e) {
            throw wrap(e, "COMMIT");
        }
    }

    @Override
    public void rollback() {
        try {
            if (conn.getAutoCommit()) {
                throw new DbException("当前是自动提交模式，没有事务可回滚");
            }
            conn.rollback();
            pendingTx = false;
        } catch (SQLException e) {
            throw wrap(e, "ROLLBACK");
        }
    }

    /** 手动事务模式下，一次写之后把「有未提交改动」记上。 */
    private void markWritten() {
        try {
            if (!conn.getAutoCommit()) {
                pendingTx = true;
            }
        } catch (SQLException ignored) {
            // 问不出自动提交状态时不记：宁可少拦一次，也不要凭空拦住用户
        }
    }

    @Override
    public void inTransaction(List<String> statements) {
        if (config.readOnly()) {
            throw new DbException("该连接为只读模式，写操作已被拦截");
        }
        // 用户自己开着一个事务时，不能在这里 commit/rollback——那会连他手上
        // 还没提交的改动一起提交或一起回滚掉。改用保存点：这一批仍然是原子的，
        // 失败只退回到这一批开始之前，用户之前做的事一点不动
        if (!autoCommit()) {
            runOnSavepoint(statements);
            return;
        }
        boolean auto = true;
        try {
            auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                running.set(st);
                for (String sql : statements) {
                    st.execute(sql);
                }
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败时保留原始异常，它才是根因
            }
            throw wrap(e, String.join("; ", statements));
        } finally {
            running.set(null);
            try {
                conn.setAutoCommit(auto);
            } catch (SQLException ignored) {
                // 连接已经不可用，close 时会再报
            }
        }
    }

    /**
     * 在保存点里跑一批语句：失败只退回这一批之前，不碰用户手上已有的事务。
     *
     * <p>驱动不支持保存点时不硬来，也不悄悄降级成「跑一半算一半」——
     * 那正是最难查的那种结果。直说做不到，让用户先把手上的事务了结。
     */
    private void runOnSavepoint(List<String> statements) {
        java.sql.Savepoint savepoint;
        try {
            if (!conn.getMetaData().supportsSavepoints()) {
                throw new DbException("当前有未提交的事务，而这个驱动不支持保存点，"
                        + "没法把这一批变更单独隔出来。请先提交或回滚，再执行");
            }
            savepoint = conn.setSavepoint("plainly_batch");
        } catch (SQLException e) {
            throw wrap(e, "SAVEPOINT");
        }
        try (Statement st = conn.createStatement()) {
            running.set(st);
            for (String sql : statements) {
                st.execute(sql);
            }
            pendingTx = true;
        } catch (SQLException e) {
            try {
                conn.rollback(savepoint);
            } catch (SQLException ignored) {
                // 回滚到保存点失败时保留原始异常，它才是根因
            }
            throw wrap(e, String.join("; ", statements));
        } finally {
            running.set(null);
            try {
                conn.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // 有些驱动不支持显式释放；事务提交或回滚时它自然消失
            }
        }
    }

    @Override
    public int executeDdlBatch(List<String> statements) {
        if (config.readOnly()) {
            throw new DbException("该连接为只读模式，结构变更已被拦截");
        }
        if (statements.isEmpty()) {
            return 0;
        }
        // 这一家的 DDL 会隐式提交，而用户手上正开着一个事务——发出去就等于
        // 替他把那批还没想好的改动提交了，而且撤不回来。宁可拦下来让他先决定
        if (!dialect.supportsTransactionalDdl() && hasPendingTransaction()) {
            throw new DbException("这个数据库的 DDL 会隐式提交，"
                    + "而你手上还有没提交的改动——执行结构变更会把它们一并提交，且无法撤销。"
                    + "请先「提交」或「回滚」，再来改结构");
        }
        return dialect.supportsTransactionalDdl()
                ? executeDdlTransactional(statements)
                : executeDdlSequential(statements);
    }

    /** 支持事务性 DDL：整批一个事务，失败全回滚。 */
    private int executeDdlTransactional(List<String> statements) {
        // 用户自己开着事务时同样走保存点，理由和 inTransaction 一样
        if (!autoCommit()) {
            runOnSavepoint(statements);
            return statements.size();
        }
        boolean auto = true;
        int executed = 0;
        try {
            auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                running.set(st);
                for (String sql : statements) {
                    st.execute(sql);
                    executed++;
                }
            }
            conn.commit();
            return executed;
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败时保留原始异常，它才是根因
            }
            throw new DdlBatchException(executed, statements.size(),
                    statements.get(Math.min(executed, statements.size() - 1)), true, e);
        } finally {
            running.set(null);
            try {
                conn.setAutoCommit(auto);
            } catch (SQLException ignored) {
                // 连接已不可用，close 时会再报
            }
        }
    }

    /**
     * 不支持事务性 DDL：逐条执行。
     *
     * <p>刻意<b>不</b>包在事务里。包了也没用——DDL 会隐式提交——
     * 反而会让人误以为有回滚保护。失败时如实告知已经执行到第几条。
     */
    private int executeDdlSequential(List<String> statements) {
        int executed = 0;
        try (Statement st = conn.createStatement()) {
            running.set(st);
            for (String sql : statements) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    throw new DdlBatchException(executed, statements.size(), sql, false, e);
                }
                executed++;
            }
            return executed;
        } catch (SQLException e) {
            throw new DdlBatchException(executed, statements.size(),
                    statements.get(Math.min(executed, statements.size() - 1)), false, e);
        } finally {
            running.set(null);
        }
    }

    @Override
    public void cancel() {
        Statement st = running.get();
        if (st != null) {
            try {
                st.cancel();
            } catch (SQLException e) {
                throw new DbException("取消查询失败：" + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------- 结果集元数据

    // -------------------------------------------------------------- 外键 / 触发器

    
    @Override
    public List<DbObjects.ForeignKeyInfo> listForeignKeys(String schema, String table) {
        return reading(() -> listForeignKeysOnce(schema, table));
    }

    private List<DbObjects.ForeignKeyInfo> listForeignKeysOnce(String schema, String table) {
        return readKeys(schema, table, true);
    }

    
    @Override
    public List<DbObjects.ForeignKeyInfo> listReferencingKeys(String schema, String table) {
        return reading(() -> listReferencingKeysOnce(schema, table));
    }

    private List<DbObjects.ForeignKeyInfo> listReferencingKeysOnce(String schema, String table) {
        return readKeys(schema, table, false);
    }

    /**
     * 外键走 JDBC 标准元数据，两个方向共用一套读法。
     *
     * <p>一条外键可能跨多列，元数据是一列一行，靠 {@code FK_NAME} 归组、{@code KEY_SEQ} 排序；
     * 直接按行读会把复合外键拆成几条毫无意义的单列外键。
     */
    private List<DbObjects.ForeignKeyInfo> readKeys(String schema, String table, boolean imported) {
        String catalog = usesCatalogs() ? schema : null;
        String schemaName = usesCatalogs() ? null : schema;
        Map<String, List<Object[]>> grouped = new LinkedHashMap<>();
        try (ResultSet rs = imported
                ? conn.getMetaData().getImportedKeys(catalog, schemaName, table)
                : conn.getMetaData().getExportedKeys(catalog, schemaName, table)) {
            while (rs.next()) {
                String name = safe(rs.getString("FK_NAME"));
                grouped.computeIfAbsent(name.isBlank() ? "(未命名)" : name, k -> new ArrayList<>())
                        .add(new Object[] {
                                rs.getShort("KEY_SEQ"),
                                safe(rs.getString("FKCOLUMN_NAME")),
                                safe(rs.getString("PKCOLUMN_NAME")),
                                safe(rs.getString(usesCatalogs() ? "FKTABLE_CAT" : "FKTABLE_SCHEM")),
                                safe(rs.getString("FKTABLE_NAME")),
                                safe(rs.getString(usesCatalogs() ? "PKTABLE_CAT" : "PKTABLE_SCHEM")),
                                safe(rs.getString("PKTABLE_NAME")),
                                rs.getShort("UPDATE_RULE"),
                                rs.getShort("DELETE_RULE")});
            }
        } catch (SQLException e) {
            // 外键取不到不该挡住看表：当作没有
            return List.of();
        }

        List<DbObjects.ForeignKeyInfo> out = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> entry : grouped.entrySet()) {
            List<Object[]> rows = entry.getValue();
            rows.sort(Comparator.comparingInt(r -> (Short) r[0]));
            Object[] head = rows.get(0);
            out.add(new DbObjects.ForeignKeyInfo(
                    entry.getKey(),
                    (String) head[3], (String) head[4],
                    rows.stream().map(r -> (String) r[1]).toList(),
                    (String) head[5], (String) head[6],
                    rows.stream().map(r -> (String) r[2]).toList(),
                    ruleName((Short) head[7]), ruleName((Short) head[8])));
        }
        return out;
    }

    /** JDBC 用数字表示级联规则，这里翻回 SQL 里的写法。 */
    private static String ruleName(short rule) {
        switch (rule) {
            case DatabaseMetaData.importedKeyCascade: return "CASCADE";
            case DatabaseMetaData.importedKeyRestrict: return "RESTRICT";
            case DatabaseMetaData.importedKeySetNull: return "SET NULL";
            case DatabaseMetaData.importedKeySetDefault: return "SET DEFAULT";
            case DatabaseMetaData.importedKeyNoAction: return "NO ACTION";
            default: return "";
        }
    }

    
    @Override
    public List<DbObjects.TriggerInfo> listTriggers(String schema, String table) {
        return reading(() -> listTriggersOnce(schema, table));
    }

    private List<DbObjects.TriggerInfo> listTriggersOnce(String schema, String table) {
        return listTriggersDetailed(schema, table).triggers();
    }

    /**
     * 触发器列表，连同「为什么是空的」。
     *
     * <p>失败仍然不往上抛——一张表的触发器读不出来，不该让整张表打不开。
     * 但原因必须带回去：空列表和读不到，在界面上本来长得一模一样。
     */
    @Override
    public DbObjects.TriggerListing listTriggersDetailed(String schema, String table) {
        String sql = dialect.triggersQuery(schema, table);
        if (sql == null) {
            String why = dialect.triggerUnsupportedReason();
            return DbObjects.TriggerListing.unsupported(why != null ? why
                    : "这个数据库没有可查询的触发器视图");
        }
        List<DbObjects.TriggerInfo> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new DbObjects.TriggerInfo(
                        safe(rs.getString(1)), safe(rs.getString(2)),
                        safe(rs.getString(3)), safe(rs.getString(4))));
            }
        } catch (SQLException e) {
            return DbObjects.TriggerListing.failed(
                    "读不到触发器列表：" + e.getMessage(), sql);
        }
        return DbObjects.TriggerListing.of(out, sql);
    }

    @Override
    public List<DbObjects.ColumnRef> searchColumns(String schema, String fragment, int limit) {
        if (fragment == null || fragment.isBlank() || limit <= 0) {
            return List.of();
        }
        // 去重键：同一个列在几种大小写写法下会被查出来两遍
        Map<String, DbObjects.ColumnRef> found = new LinkedHashMap<>();
        try {
            DatabaseMetaData md = conn.getMetaData();
            String catalogArg = usesCatalogs() ? schema : null;
            String schemaArg = usesCatalogs() ? null : schema;
            String escape = md.getSearchStringEscape();

            for (String variant : caseVariants(fragment)) {
                if (found.size() >= limit) {
                    break;
                }
                String pattern = "%" + escapeMetaPattern(variant, escape) + "%";
                try (ResultSet rs = md.getColumns(catalogArg, schemaArg, "%", pattern)) {
                    while (rs.next() && found.size() < limit) {
                        String table = rs.getString("TABLE_NAME");
                        String column = rs.getString("COLUMN_NAME");
                        found.putIfAbsent(table + "\u0000" + column,
                                new DbObjects.ColumnRef(schema, table, column,
                                        safe(rs.getString("TYPE_NAME"))));
                    }
                }
            }
        } catch (SQLException e) {
            // 搜索是辅助功能：权限不足或驱动不认这个调用时，安静降级成「只搜表名」
            return List.of();
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public List<DbObjects.ColumnRef> listColumns(String schema) {
        List<DbObjects.ColumnRef> out = new ArrayList<>();
        try {
            DatabaseMetaData md = conn.getMetaData();
            String catalogArg = usesCatalogs() ? schema : null;
            String schemaArg = usesCatalogs() ? null : schema;
            // 一次把整个库的列都拿下来。名字模式给 "%"，注释在结果里，
            // 上层才有可能按注释搜——JDBC 的模式参数只认列名
            try (ResultSet rs = md.getColumns(catalogArg, schemaArg, "%", "%")) {
                while (rs.next()) {
                    out.add(new DbObjects.ColumnRef(schema,
                            rs.getString("TABLE_NAME"),
                            rs.getString("COLUMN_NAME"),
                            safe(rs.getString("TYPE_NAME")),
                            safe(rs.getString("REMARKS"))));
                }
            }
        } catch (SQLException e) {
            // 建索引失败不该让搜索框整个坏掉，降级成「只搜表名」
            return List.of();
        }
        return out;
    }

    /**
     * 要试哪几种大小写。
     *
     * <p>{@code getColumns} 的名字模式是<b>区分大小写</b>的，而列名在库里以什么大小写存着
     * 各家不同：H2 和 Oracle 存成大写，PostgreSQL 存成小写，MySQL 存成建表时写的样子。
     * 用户在搜索框里敲的是 {@code amount}，库里存的是 {@code AMOUNT}——只按原样查，
     * 一条都搜不到，而且不会有任何报错，看起来就像这个功能根本没做。
     *
     * <p>所以按「原样 → 大写 → 小写」依次试，去重合并。已经凑够条数就不再往下试，
     * 常见情形（全小写输入命中小写库）只多花一次调用。
     */
    private static List<String> caseVariants(String fragment) {
        List<String> out = new ArrayList<>(3);
        out.add(fragment);
        String upper = fragment.toUpperCase(Locale.ROOT);
        if (!out.contains(upper)) {
            out.add(upper);
        }
        String lower = fragment.toLowerCase(Locale.ROOT);
        if (!out.contains(lower)) {
            out.add(lower);
        }
        return out;
    }

    /**
     * 元数据的名字模式里 {@code _} 和 {@code %} 是通配符。
     *
     * <p>不转义的话，搜 {@code user_id} 会把 {@code userXid} 一并搜出来——
     * 下划线在表名列名里太常见了，这不是边角情况，是默认情况。
     * 转义符由驱动自己报（{@code getSearchStringEscape}），不写死成反斜杠。
     */
    private static String escapeMetaPattern(String text, String escape) {
        if (escape == null || escape.isEmpty()) {
            // 驱动说它没有转义符，那就只能原样传：多搜出来几条，好过一条都搜不到
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                sb.append(escape);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private List<ColumnMeta> readColumns(ResultSetMetaData md) throws SQLException {
        int n = md.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String table = safe(text(md, i, ResultSetMetaData::getTableName));
            String catalog = safe(text(md, i, ResultSetMetaData::getCatalogName));
            String schema = safe(text(md, i, ResultSetMetaData::getSchemaName));
            String name = md.getColumnName(i);
            int sqlType = columnType(md, i);
            String typeName = typeNameOf(md, i, sqlType);

            boolean partOfKey = !table.isBlank()
                    && primaryKeysOf(catalog, schema, table).contains(name.toLowerCase());

            columns.add(new ColumnMeta(
                    name,
                    md.getColumnLabel(i),
                    typeName,
                    TypeCategories.of(sqlType, typeName),
                    number(md, i, ResultSetMetaData::getPrecision),
                    number(md, i, ResultSetMetaData::getScale),
                    nullable(md, i),
                    // 模式名各家放的位置不一样：MySQL 把库名放在 catalog 里、
                    // schema 恒为空，别家反过来。哪个非空取哪个
                    schema.isBlank() ? catalog : schema,
                    table,
                    partOfKey,
                    autoIncrement(md, i)));
        }
        return columns;
    }

    /**
     * 结果集元数据里那几个「会整条炸掉」的方法。
     *
     * <h2>为什么要单独包一层</h2>
     * 金仓 V8R3 上，{@code getTableName}、{@code getColumnTypeName}、
     * {@code isNullable}、{@code isAutoIncrement} 会报
     * {@code relation "PG_CATALOG.PG_class" does not exist}；
     * 而列类型冷门时，连 {@code getColumnType}、{@code getPrecision} 也会去查
     * {@code pg_type}。驱动为了回答这些要翻系统目录，而那两张表在这一代叫
     * {@code sys_class} / {@code sys_type}。只有列数、列名、标签是协议里
     * 直接带回来的，一定拿得到。
     *
     * <p>这些里没有一个是<b>读数据</b>必需的：拿不到类型号就按文本读，
     * 而按文本读正是本项目在精度上最保守的那条路。所以失败时退回保守默认值、
     * 让查询继续，而不是让整条 SELECT 报一句和用户的 SQL 毫无关系的错。
     *
     * <p><b>只在 {@link #legacyMeta} 为真时才吞。</b>别的库上这几个方法失败
     * 是真的出了问题，吞掉会把缺陷藏起来——那比报错糟得多。
     */
    private <T> T guarded(Reader<T> reader, T fallback) throws SQLException {
        try {
            return reader.read();
        } catch (SQLException e) {
            if (legacyMeta) {
                return fallback;
            }
            throw e;
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read() throws SQLException;
    }

    @FunctionalInterface
    private interface MetaText {
        String read(ResultSetMetaData md, int column) throws SQLException;
    }

    private String text(ResultSetMetaData md, int i, MetaText f) throws SQLException {
        return guarded(() -> f.read(md, i), "");
    }

    @FunctionalInterface
    private interface MetaNumber {
        int read(ResultSetMetaData md, int column) throws SQLException;
    }

    /** 取不到就当 0——精度和小数位只影响显示，不影响按文本读回来的那个值。 */
    private int number(ResultSetMetaData md, int i, MetaNumber f) throws SQLException {
        return guarded(() -> f.read(md, i), 0);
    }

    /**
     * 列的 JDBC 类型号。
     *
     * <h2>这一个也会炸，而且比别的更容易漏掉</h2>
     * 驱动手里静态认得的只有那几十个常见类型；碰上不认得的（比如 {@code regrole}），
     * 它要去系统目录查 {@code pg_type} 才能回答——在金仓 V8R3 上那张表叫
     * {@code sys_type}，于是整条 SELECT 报
     * {@code relation "PG_CATALOG.PG_type" does not exist}。
     *
     * <p>第一版漏了这一个，因为当时是拿 INT / NUMERIC / VARCHAR 的表测的——
     * 那几种驱动静态就认得，根本不会去查目录。<b>要挑一张列类型冷门的表才试得出来。</b>
     *
     * <p>取不到就当 {@code OTHER}：那会让这一列按文本读，
     * 正是「不知道是什么就原样搬过来」，精度上最安全的一种。
     */
    private int columnType(ResultSetMetaData md, int i) throws SQLException {
        final int column = i;
        return guarded(() -> md.getColumnType(column), java.sql.Types.OTHER);
    }

    /**
     * 类型名。取不到时按类型号给一个标准名（{@code INTEGER}、{@code NUMERIC}…）。
     *
     * <p>不能返回空串：类型名会参与分类，虽然类型号已经够用，
     * 但它还要显示给用户看，空着比给个标准名差。
     */
    private String typeNameOf(ResultSetMetaData md, int i, int sqlType) throws SQLException {
        String name = guarded(() -> md.getColumnTypeName(i), null);
        if (name != null) {
            return name;
        }
        try {
            return java.sql.JDBCType.valueOf(sqlType).getName();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 取不到就当可空——显示上宽松一点，总比把一个可空列说成非空强。 */
    private boolean nullable(ResultSetMetaData md, int i) throws SQLException {
        return guarded(() -> md.isNullable(i) != ResultSetMetaData.columnNoNulls, true);
    }

    /** 取不到就当不是自增。这一栏只影响显示，猜错的代价比猜「是」小。 */
    private boolean autoIncrement(ResultSetMetaData md, int i) throws SQLException {
        return guarded(() -> md.isAutoIncrement(i), false);
    }

    /**
     * 一张表的主键列名。
     *
     * <p>刻意<b>不用</b> {@code computeIfAbsent}：它的映射函数里有一次 JDBC 往返，
     * 而 computeIfAbsent 在整个映射函数期间都占着那个桶的锁。放在并发映射上，
     * 别的线程会被这次网络往返堵住；放在普通 HashMap 上，直接抛 CME。
     *
     * <p>所以拆成「先查、没有就自己去读、读完再放回去」。代价是两个线程可能同时
     * 读同一张表的主键——多一次往返而已，结果一样。
     */
    private Set<String> primaryKeysOf(String catalog, String schema, String table) {
        String key = catalog + "/" + schema + "/" + table;
        Set<String> cached = pkCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> loaded = loadPrimaryKeys(catalog, schema, table);
        Set<String> raced = pkCache.putIfAbsent(key, loaded);
        return raced != null ? raced : loaded;
    }

    private Set<String> loadPrimaryKeys(String catalog, String schema, String table) {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(
                blankToNull(catalog), blankToNull(schema), table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        } catch (SQLException e) {
            // 取不到主键不是致命错误：结果集降级为只读即可
            return Set.of();
        }
        return keys;
    }

    // ------------------------------------------------------------------ 结构

    /** MySQL 用 catalog 表示「库」，PostgreSQL/H2 用 schema。 */
    /**
     * Oracle 回收站里的表。
     *
     * <p>{@code DROP TABLE} 之后表并没有真的消失，它改名成 {@code BIN$...} 留在回收站里，
     * 而 {@code getTables()} 照样把它列出来。不滤掉的话，一个删过几次表的库，
     * 树上会挂着一堆谁也看不懂的 {@code BIN$dR3k...==$0}。
     *
     * <p>只认这一个前缀，不做更宽的判断：Oracle 的系统表名里 {@code $} 很常见，
     * 按 {@code $} 过滤会顺手把用户自己的表也藏掉。
     */
    private boolean isRecycled(String tableName) {
        return config.type().isOracleFamily()
                && tableName != null && tableName.startsWith("BIN$");
    }

    private boolean usesCatalogs() {
        return config.type() == DbType.MYSQL;
    }

    @Override
    public List<SchemaInfo> listSchemas() {
        return reading(this::listSchemasOnce);
    }

    private List<SchemaInfo> listSchemasOnce() {
        List<SchemaInfo> result = new ArrayList<>();
        try {
            if (legacyMeta) {
                return KingbaseLegacy.schemas(conn, currentSchemaName());
            }
            DatabaseMetaData md = conn.getMetaData();
            String current = currentSchemaName();
            if (config.type() == DbType.SQLITE) {
                result.add(new SchemaInfo("main", true));
                return result;
            }
            try (ResultSet rs = usesCatalogs() ? md.getCatalogs() : md.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (isSystemSchema(name)) {
                        continue;
                    }
                    result.add(new SchemaInfo(name, name.equalsIgnoreCase(current)));
                }
            }
        } catch (SQLException e) {
            throw wrap(e, "listSchemas");
        }
        return result;
    }

    private String currentSchemaName() {
        try {
            return usesCatalogs() ? safe(conn.getCatalog()) : safe(conn.getSchema());
        } catch (SQLException e) {
            return "";
        }
    }

    private boolean isSystemSchema(String name) {
        String n = name.toLowerCase();
        switch (config.type()) {
            case MYSQL:
                return n.equals("information_schema") || n.equals("performance_schema")
                        || n.equals("mysql") || n.equals("sys");
            case POSTGRESQL:
                return n.startsWith("pg_") || n.equals("information_schema");
            case H2:
                return n.equals("information_schema");
            case ORACLE:
                return ORACLE_SYSTEM_SCHEMAS.contains(name.toUpperCase(java.util.Locale.ROOT))
                        || n.startsWith("apex_") || n.startsWith("flows_")
                        || n.startsWith("rdsadmin");
            case DM:
                // 注意 SYSDBA 不在里面：它是达梦默认管理用户的模式，
                // 用户的表多半就建在那儿。当成系统模式过滤掉，人打开工具会发现自己的表没了
                return n.equals("sys") || n.equals("syssso") || n.equals("sysauditor")
                        || n.equals("sysjob") || n.equals("ctisys");
            default:
                return false;
        }
    }

    /**
     * Oracle 装完就自带的那几十个模式。
     *
     * <p>为什么要写死一张表而不是「按某个规则判断」：Oracle 的 schema 就是 user，
     * {@code getSchemas()} 会把实例里所有用户都列出来——一个装了 APEX 和空间组件的库
     * 能列出四五十个，用户自己的那一两个模式淹在里面根本找不着。
     * 而这些名字没有统一前缀，只能一个个列。
     *
     * <p>反过来，宁可漏过滤也不能错过滤：{@code SCOTT}、{@code HR} 这种演示模式
     * 不在表里，因为确实有人把业务表建在那儿。
     */
    private static final java.util.Set<String> ORACLE_SYSTEM_SCHEMAS = java.util.Set.of(
            "SYS", "SYSTEM", "SYSAUX", "OUTLN", "DBSNMP", "APPQOSSYS", "AUDSYS",
            "GSMADMIN_INTERNAL", "GSMCATUSER", "GSMUSER", "XDB", "WMSYS", "CTXSYS",
            "ORDSYS", "ORDDATA", "ORDPLUGINS", "SI_INFORMTN_SCHEMA", "MDSYS", "MDDATA",
            "OLAPSYS", "DVSYS", "DVF", "LBACSYS", "OJVMSYS", "DBSFWUSER", "GGSYS",
            "REMOTE_SCHEDULER_AGENT", "SYS$UMF", "ANONYMOUS", "XS$NULL", "PDBADMIN",
            "DIP", "ORACLE_OCM", "EXFSYS", "SYSMAN", "TSMSYS", "DMSYS", "WKSYS",
            "WKPROXY", "WK_TEST", "OWBSYS", "OWBSYS_AUDIT",
            "SPATIAL_CSW_ADMIN_USR", "SPATIAL_WFS_ADMIN_USR", "FLOWS_FILES");

    
    @Override
    public List<TableInfo> listTables(String schema) {
        return reading(() -> listTablesOnce(schema));
    }

    private List<TableInfo> listTablesOnce(String schema) {
        List<TableInfo> tables = new ArrayList<>();
        try {
            if (legacyMeta) {
                // 序列也在这一条里出来了，不必再走下面的 sequencesQuery
                return KingbaseLegacy.tables(conn, schema);
            }
            DatabaseMetaData md = conn.getMetaData();
            String catalogArg = usesCatalogs() ? schema : null;
            String schemaArg = usesCatalogs() ? null : schema;
            Map<String, Long> estimates = rowEstimates(schema);
            // 「MATERIALIZED VIEW」是 PostgreSQL 的类型名。不认识这个类型的驱动
            // 只会当它没匹配上，不会报错，所以一并传过去比分两次查省一次往返
            try (ResultSet rs = md.getTables(catalogArg, schemaArg, "%",
                    new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) {
                    String type = rs.getString("TABLE_TYPE");
                    String name = rs.getString("TABLE_NAME");
                    if (isRecycled(name)) {
                        continue;
                    }
                    tables.add(new TableInfo(
                            schema,
                            name,
                            kindOf(type),
                            safe(rs.getString("REMARKS")),
                            estimates.getOrDefault(name, -1L)));
                }
            }
        } catch (SQLException e) {
            throw wrap(e, "listTables(" + schema + ")");
        }
        // 序列和事件不在 JDBC 的元数据接口里，各家自己查。读不到就当没有——
        // 一个辅助的对象分组不该让「展开这个库」整个失败
        tables.addAll(listByQuery(dialect.sequencesQuery(schema), schema, ObjectKind.SEQUENCE));
        tables.addAll(listByQuery(dialect.eventsQuery(schema), schema, ObjectKind.EVENT));
        return tables;
    }

    /** JDBC 给的类型名 → 本工具的对象类别。 */
    private static ObjectKind kindOf(String jdbcType) {
        if (jdbcType == null) {
            return ObjectKind.TABLE;
        }
        String upper = jdbcType.toUpperCase(Locale.ROOT);
        if (upper.contains("MATERIALIZED")) {
            return ObjectKind.MATERIALIZED_VIEW;
        }
        return upper.contains("VIEW") ? ObjectKind.VIEW : ObjectKind.TABLE;
    }

    /**
     * 用一条「名称、说明」两列的查询列出某类对象。
     *
     * @param sql 方言给的语句；null 表示这一家没有这类对象，直接返回空表
     */
    private List<TableInfo> listByQuery(String sql, String schema, ObjectKind kind) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<TableInfo> out = new ArrayList<>();
        try {
            QueryResult r = execute(sql, 1000);
            for (Row row : r.rows()) {
                if (row.get(0) == null) {
                    continue;
                }
                out.add(new TableInfo(schema, row.get(0), kind, safe(row.get(1)), -1));
            }
        } catch (RuntimeException e) {
            // 权限不足、视图不存在（版本差异）都会走到这里。序列列表读不出来
            // 不该让整个库展不开——表才是这棵树的主体
            return List.of();
        }
        return out;
    }

    /**
     * 一个库里各表的行数估算，取不到时返回空表。
     *
     * <p>为什么整批取而不是每张表查一次：见
     * {@link SqlDialect#tableRowCountQuery(String)}——那边讲了为什么不能用 COUNT(*)。
     *
     * <p><b>失败必须安静。</b>这是一列锦上添花的信息，而读统计信息要权限
     * （PostgreSQL 的 pg_class、SQL Server 的 sys.partitions 都可能读不到）。
     * 为了显示不出行数就让整棵树展不开，是把主次弄反了。
     */
    private Map<String, Long> rowEstimates(String schema) {
        String sql = dialect.tableRowCountQuery(schema);
        if (sql == null) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long rows = rs.getLong(2);
                // NULL（没收集过统计信息）和负数（PG14 起「从未 ANALYZE」是 -1）
                // 都是「不知道」。当成 0 会在树上写出一个「空表」，那是假话
                if (name != null && !rs.wasNull() && rows >= 0) {
                    out.put(name, rows);
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            return Map.of();
        }
        return out;
    }

    
    @Override
    public TableStructure describeTable(String schema, String table) {
        return reading(() -> describeTableOnce(schema, table));
    }

    private TableStructure describeTableOnce(String schema, String table) {
        try {
            if (legacyMeta) {
                TableInfo info = listTablesOnce(schema).stream()
                        .filter(t -> t.name().equals(table)).findFirst()
                        .orElse(new TableInfo(schema, table, ObjectKind.TABLE, "", -1L));
                return KingbaseLegacy.structure(conn, schema, table, info);
            }
            DatabaseMetaData md = conn.getMetaData();
            String catalogArg = usesCatalogs() ? schema : null;
            String schemaArg = usesCatalogs() ? null : schema;

            Set<String> pks = new LinkedHashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(catalogArg, schemaArg, table)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }

            List<ColumnInfo> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(catalogArg, schemaArg, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String typeName = rs.getString("TYPE_NAME");
                    int sqlType = rs.getInt("DATA_TYPE");
                    TypeCategory category = TypeCategories.of(sqlType, typeName);
                    columns.add(new ColumnInfo(
                            name,
                            typeName,
                            category,
                            rs.getInt("COLUMN_SIZE"),
                            rs.getInt("DECIMAL_DIGITS"),
                            "YES".equalsIgnoreCase(safe(rs.getString("IS_NULLABLE"))),
                            pks.contains(name),
                            "YES".equalsIgnoreCase(safe(rs.getString("IS_AUTOINCREMENT"))),
                            // 各家驱动给的 COLUMN_DEF 不是一回事，归一成能直接拼进 DDL 的字面量
                            dialect.normalizeDefault(rs.getString("COLUMN_DEF"), category),
                            safe(rs.getString("REMARKS")),
                            rs.getInt("ORDINAL_POSITION")));
                }
            }

            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            Map<String, Boolean> indexUnique = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(catalogArg, schemaArg, table, false, false)) {
                while (rs.next()) {
                    String idx = rs.getString("INDEX_NAME");
                    String col = rs.getString("COLUMN_NAME");
                    if (idx == null || col == null) {
                        continue;
                    }
                    indexColumns.computeIfAbsent(idx, k -> new ArrayList<>()).add(col);
                    indexUnique.putIfAbsent(idx, !rs.getBoolean("NON_UNIQUE"));
                }
            } catch (SQLException ignored) {
                // 部分驱动对视图取索引会报错，忽略即可
            }

            // 主键索引按「列集合是否等于主键列」判定，而不是按名字猜。
            // 各家给主键索引起的名字完全不同：MySQL 是 PRIMARY、PostgreSQL 是 <表>_pkey、
            // H2 是 PRIMARY_KEY_8，靠名字匹配必然漏判，进而在结构同步里比出假的索引差异。
            Set<String> pkLower = new LinkedHashSet<>();
            pks.forEach(c -> pkLower.add(c.toLowerCase()));

            List<IndexInfo> indexes = new ArrayList<>();
            indexColumns.forEach((name, cols) -> {
                Set<String> colsLower = new LinkedHashSet<>();
                cols.forEach(c -> colsLower.add(c.toLowerCase()));
                boolean isPrimary = !pkLower.isEmpty() && pkLower.equals(colsLower);
                indexes.add(new IndexInfo(
                        name, cols, Boolean.TRUE.equals(indexUnique.get(name)), isPrimary));
            });

            TableInfo info = new TableInfo(schema, table, ObjectKind.TABLE, "", -1);
            return new TableStructure(info, columns, indexes);
        } catch (SQLException e) {
            throw wrap(e, "describeTable(" + schema + "." + table + ")");
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public boolean isBusy() {
        return running.get() != null;
    }

    @Override
    public boolean isClosed() {
        try {
            return conn.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * 探活的等待上限。
     *
     * <p>给得短，是因为这一下卡在用户和「打开这张表」之间。连接是好的话，
     * 一次 ping 通常几毫秒（局域网实测 7 ms）；真断了的话，等三秒也等不出别的结果。
     */
    private static final int ALIVE_TIMEOUT_SECONDS = 3;

    /**
     * 真去问一次对端。
     *
     * <p>{@code isValid} 发的是驱动自己的探活包（MySQL 上是一个 ping），
     * 服务端把会话掐了它会返回 false，而 {@link #isClosed()} 这时仍然是 false。
     *
     * <p>顺带的一个副作用是好的：MySQL 驱动在探活失败之后会把这条连接标记成已关闭，
     * 于是后续的 {@code isClosed()} 也开始说实话了。
     */
    @Override
    public boolean isAlive() {
        try {
            return !conn.isClosed() && conn.isValid(ALIVE_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new DbException("关闭连接失败：" + e.getMessage(), e);
        } finally {
            // 先关 JDBC 再关隧道：反过来的话最后几个包会打在已经断掉的通道上
            if (tunnel != null) {
                tunnel.close();
            }
        }
    }

    // ------------------------------------------------------------------ 断线重连

    /**
     * 这个异常是不是「连接没了」。
     *
     * <h2>为什么不只看一种判据</h2>
     * 各家驱动报同一件事的说法完全不同：MySQL 给
     * {@code Communications link failure}（SQLState 08S01）或
     * {@code No operations allowed after connection closed}（08003），
     * PostgreSQL 给 {@code terminating connection due to administrator command}，
     * Oracle 给 {@code ORA-03113 通信通道的文件结束}。
     *
     * <p>SQL 标准把 08 这一类留给「连接异常」，所以先看 SQLState；
     * 但驱动并不总是填对它（尤其是包了好几层之后），所以再兜一层类型和关键字。
     * 判宽一点的代价只是多重连一次，判窄了的代价是用户卡在一条永远不会自愈的连接上。
     */
    private boolean isConnectionLost(Throwable error) {
        /*
         * 先问连接自己。这是唯一一条<b>与各家措辞无关</b>的判据：
         * 只要 Connection 自己说已经关了，那就是关了，不管异常里写的是什么。
         *
         * 必须放在最前面。H2 报的是「The object is already closed [90007-214]」——
         * SQLState 是 90007，不是标准的 08 类，消息也和别家一个词都不沾。
         * 靠关键字去认，等于每接一家新库就要再补一条，而漏掉的那次不会报错，
         * 只会让用户卡在一条永远不自愈的连接上。
         *
         * 反过来也不能只靠它：服务端把会话掐掉之后，驱动在下一次真正用它之前
         * 并不知情，那时 isClosed() 仍然是 false，得靠下面的 SQLState 和关键字。
         */
        try {
            if (conn.isClosed()) {
                return true;
            }
        } catch (SQLException ignored) {
            return true;
        }
        return matchesLostSignature(error);
    }

    /** 从异常本身认。连接还没被标记成关闭时，只能靠这个。 */
    private static boolean matchesLostSignature(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 12; depth++) {
            if (t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof java.sql.SQLRecoverableException
                    || t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            String state = null;
            if (t instanceof SQLException se) {
                state = se.getSQLState();
            } else if (t instanceof DbException de) {
                state = de.sqlState();
            }
            if (state != null && state.startsWith("08")) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && matchesLostMarker(message)) {
                return true;
            }
            Throwable next = t.getCause();
            if (next == t) {
                break;
            }
            t = next;
        }
        return false;
    }

    private static boolean matchesLostMarker(String message) {
        String low = message.toLowerCase(java.util.Locale.ROOT);
        return low.contains("communications link failure")
                || low.contains("no operations allowed after")
                || low.contains("connection is closed")
                || low.contains("connection closed")
                || low.contains("closed connection")
                || low.contains("connection reset")
                || low.contains("broken pipe")
                || low.contains("terminating connection")
                || low.contains("ora-03113")
                || low.contains("ora-03114")
                || low.contains("ora-02396");
    }

    /**
     * 这条语句是不是<b>压根没发出去</b>。
     *
     * <h2>为什么要分这一下</h2>
     * 决定了敢不敢自动重试。驱动在发送之前就发现连接已关闭（08003、
     * 「No operations allowed after connection closed」）时，服务端根本没收到东西，
     * 重试是安全的——包括写语句。
     *
     * <p>而「链路在执行途中断了」是另一回事：语句可能已经在服务端跑完了，
     * 只是回包没到。这种情况下重试一条 UPDATE 就可能<b>改两次</b>。
     * 分不清的时候一律当成「可能已经发出去了」。
     */
    private boolean neverReachedServer(Throwable error) {
        try {
            if (conn.isClosed()) {
                return true;
            }
        } catch (SQLException ignored) {
            return true;
        }
        Throwable t = error;
        for (int depth = 0; t != null && depth < 12; depth++) {
            String state = t instanceof SQLException se ? se.getSQLState()
                    : (t instanceof DbException de ? de.sqlState() : null);
            if ("08003".equals(state)) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String low = message.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("no operations allowed after")
                        || low.contains("connection is closed")
                        || low.contains("closed connection")) {
                    return true;
                }
            }
            Throwable next = t.getCause();
            if (next == t) {
                break;
            }
            t = next;
        }
        return false;
    }

    /**
     * 换一条新连接，并把原来的状态搬过去。
     *
     * <p>搬的是「库」和「手动提交」两样——它们是用户在这条连接上做过的选择，
     * 新连接不知道。不搬的话表现得极其难查：语句莫名其妙打到默认库上，
     * 或者用户以为还在事务里，其实每条都已经自动提交了。
     *
     * <p><b>搬不过来的是未提交的事务</b>：它在服务端断开的那一刻就被回滚了，
     * 这里只能把标记清掉，由调用方去告诉用户。
     */
    private synchronized void reopen() {
        Connection dead = conn;
        try {
            if (dead != null && !dead.isClosed()) {
                dead.close();
            }
        } catch (SQLException ignored) {
            // 已经死了，关不关得掉都无所谓
        }
        Connection fresh = JdbcConnections.reopenRaw(config, tunnel);
        try {
            if (manualMode) {
                fresh.setAutoCommit(false);
            }
        } catch (SQLException ignored) {
            // 恢复不了手动提交不致命，界面上那个开关还会显示真实状态
        }
        conn = fresh;
        pendingTx = false;
        if (currentSchema != null && !currentSchema.isBlank()) {
            try {
                useSchema(currentSchema);
            } catch (RuntimeException ignored) {
                // 库可能已经被删了。让后续语句自己报错，那时的信息更具体
            }
        }
    }

    /**
     * 跑一段读操作；连接断了就重连再跑一次。
     *
     * <h2>为什么只有读能这样</h2>
     * 读没有副作用，重跑一次最坏是多花一次往返。写不行——见
     * {@link #neverReachedServer}。
     */
    private <T> T reading(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException first) {
            if (!isConnectionLost(first)) {
                throw first;
            }
            boolean hadTransaction = pendingTx;
            reopen();
            if (hadTransaction) {
                throw transactionLost(first);
            }
            return action.get();
        }
    }

    /**
     * 跑一段可能带副作用的操作；连接断了就重连，
     * 但<b>只有在确定语句没发出去</b>时才重跑。
     */
    private <T> T guarded(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException first) {
            if (!isConnectionLost(first)) {
                throw first;
            }
            boolean hadTransaction = pendingTx;
            boolean safe = neverReachedServer(first);
            reopen();
            if (hadTransaction) {
                throw transactionLost(first);
            }
            if (!safe) {
                throw new DbException(
                        "连接在执行期间断开，已自动重建。这条语句是否已经生效无法确定"
                        + "——请先刷新确认，再决定要不要重来。\n原始报错：" + rootText(first),
                        first);
            }
            return action.get();
        }
    }

    private static DbException transactionLost(Throwable cause) {
        return new DbException(
                "连接已断开（多半是服务端的空闲超时把它掐了），现在已经自动重建。"
                + "但手动事务里那些还没提交的改动已经没了——服务端在断开时把它们回滚了。"
                + "请刷新确认数据后重来。\n原始报错：" + rootText(cause), cause);
    }

    private static String rootText(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t.getCause() != null && t.getCause() != t && depth < 12; depth++) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ------------------------------------------------------------------ 工具

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static DbException wrap(SQLException e, String sql) {
        String msg = e.getMessage();
        if (sql != null && sql.length() <= 200) {
            msg = msg + "\n语句：" + sql;
        }
        String hint = mismatchHint(e, sql);
        if (hint != null) {
            msg = msg + "\n\n" + hint;
        }
        return new DbException(msg, e.getSQLState(), e);
    }

    /**
     * 报错点名的对象和我们操作的对象对不上时，多说一句。
     *
     * <p>触发器里的语句出错，数据库只会把触发器里的名字报出来——甚至是那条语句里的
     * 别名——而附上的语句原文仍是我们发出去的那一条。用户看到
     * 「UPDATE `db`.`orders` …」配一句「Table 'db.t' doesn't exist」，
     * 只会得出「这工具生成的 SQL 是错的」，然后去查一个根本不存在的问题。
     * 所以一旦发现两个名字对不上，就直接把方向指出去。
     */
    static String mismatchHint(SQLException e, String sql) {
        if (!"42S02".equals(e.getSQLState())) {
            return null;
        }
        String missing = quotedName(e.getMessage());
        String target = targetTable(sql);
        if (missing == null || target == null) {
            return null;
        }
        String bare = missing.substring(missing.lastIndexOf('.') + 1);
        if (bare.equalsIgnoreCase(target)) {
            return null;
        }
        return "提示：数据库说不存在的是 " + missing + "，而本次操作的是 " + target
                + "。两者对不上，通常是 " + target
                + " 上的触发器或视图引用了已经不存在的对象——语句本身没有问题，"
                + "去查这张表的触发器定义。";
    }

    /** 从 {@code Table 'nj.t' doesn't exist} 这类消息里取出引号中的名字。 */
    static String quotedName(String message) {
        if (message == null) {
            return null;
        }
        int start = message.indexOf('\'');
        if (start < 0) {
            return null;
        }
        int end = message.indexOf('\'', start + 1);
        return end < 0 ? null : message.substring(start + 1, end);
    }

    /** 语句操作的是哪张表。只认最常见的三种写法，认不出就返回 null。 */
    static String targetTable(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = TARGET_TABLE.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        // 去掉库名限定与各家的引号，只留下表名本身
        name = name.substring(name.lastIndexOf('.') + 1);
        return name.replaceAll("[`\"\\[\\]]", "");
    }

    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?is)^\\s*(?:UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([`\"\\[\\]\\w.]+)");
}
