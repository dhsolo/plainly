package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.TableStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接被服务端掐掉之后，还能不能用。
 *
 * <h2>这个测试对应的真实故障</h2>
 * 工具开着放了两个小时没动，MySQL 的 {@code wait_timeout} 把会话回收了。
 * 用户点「刷新」，拿到的是：
 *
 * <pre>
 * No operations allowed after connection closed.
 * 起因：Communications link failure
 * The last packet successfully received from the server was 7,258,927 milliseconds ago.
 * </pre>
 *
 * <p>本地对此<b>一无所知</b>——连接对象还在，{@code isClosed()} 之前一直说没关。
 * 而标签页在构造时就把会话存成了字段，之后每次刷新都直接用它，
 * 再也不会经过 {@code AppContext.openSession()} 里那道探活。
 *
 * <h2>怎么在测试里造出「连接死了」</h2>
 * 不能等两小时。这里用反射把底下那条 {@code java.sql.Connection} 直接关掉——
 * 对上层来说，这和服务端把会话掐掉是同一件事：下一次用它就抛「连接已关闭」。
 *
 * <p>用反射而不是给产品代码开一个测试专用的口子：那种口子会一直留在那里，
 * 而它唯一的用途是让测试作弊。测试和被测代码同包，反射拿得到。
 */
class ReconnectTest {

    private DbConnectionUnderTest fixture;

    /** 每个用例自己一套库，免得互相看见对方建的表。 */
    private JdbcConnection open(String db) {
        JdbcConnection conn = (JdbcConnection) JdbcConnections.open(new ConnectionConfig()
                .setName("reconnect-probe")
                .setType(DbType.H2)
                .setFilePath("mem:" + db + ";DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));
        fixture = new DbConnectionUnderTest(conn);
        return conn;
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    /** 把底下那条真连接关掉，模拟服务端回收会话。 */
    private static void killUnderlying(JdbcConnection conn) throws Exception {
        Field field = JdbcConnection.class.getDeclaredField("conn");
        field.setAccessible(true);
        ((Connection) field.get(conn)).close();
    }

    private static Connection underlying(JdbcConnection conn) throws Exception {
        Field field = JdbcConnection.class.getDeclaredField("conn");
        field.setAccessible(true);
        return (Connection) field.get(conn);
    }

    // ------------------------------------------------------------------ 读

    /**
     * 用户报的就是这一条：恢复出来的表页面点刷新，走的是 {@code describeTable}。
     */
    @Test
    @DisplayName("连接被掐掉之后，读表结构照样成功——自动重连并重试")
    void describeTableSurvivesADeadConnection() throws Exception {
        JdbcConnection conn = open("plainly_reconnect_a");
        conn.execute("CREATE TABLE T_A (id BIGINT PRIMARY KEY, name VARCHAR(32))", 0);

        Connection before = underlying(conn);
        killUnderlying(conn);

        TableStructure structure = conn.describeTable("PUBLIC", "T_A");

        assertNotNull(structure);
        assertEquals(2, structure.columns().size(), "重连之后应当照常读到两列");
        assertFalse(underlying(conn) == before, "底下那条连接应当已经换成新的了");
    }

    @Test
    @DisplayName("列库、列表、取单值这些读路径同样能自愈")
    void otherReadPathsRecover() throws Exception {
        JdbcConnection conn = open("plainly_reconnect_b");
        conn.execute("CREATE TABLE T_B (id BIGINT PRIMARY KEY)", 0);

        killUnderlying(conn);
        assertFalse(conn.listSchemas().isEmpty(), "listSchemas 应当自愈");

        killUnderlying(conn);
        assertFalse(conn.listTables("PUBLIC").isEmpty(), "listTables 应当自愈");

        killUnderlying(conn);
        assertEquals("1", conn.scalar("SELECT 1"), "scalar 应当自愈");
    }

    /**
     * 重连出来的是一条干净的连接，它不知道之前切过库。
     * 不切回去的话，后面所有不带库名限定的语句都会打到默认库上——而且不报错。
     */
    @Test
    @DisplayName("重连之后要切回原来那个库")
    void schemaIsRestoredAfterReconnect() throws Exception {
        JdbcConnection conn = open("plainly_reconnect_c");
        conn.execute("CREATE SCHEMA S1", 0);
        conn.useSchema("S1");
        conn.execute("CREATE TABLE T_C (id BIGINT)", 0);

        killUnderlying(conn);

        // 不带库名限定：只有当前库真的是 S1，这条才找得到表
        assertEquals("0", conn.scalar("SELECT COUNT(*) FROM T_C"),
                "重连之后当前库应当还是 S1");
    }

    // ------------------------------------------------------------------ 写

    /**
     * 这一条守的是不能骗人。
     *
     * <p>手动事务是<b>连接级</b>的：连接一断，服务端就把没提交的改动回滚了。
     * 这时候悄悄重连再把语句跑一遍，用户会以为一切正常，
     * 而他之前那一批改动已经没了。
     */
    @Test
    @DisplayName("手动事务里断线：必须报错说清事务没了，不能假装无事发生")
    void pendingTransactionIsNotSilentlyDiscarded() throws Exception {
        JdbcConnection conn = open("plainly_reconnect_d");
        conn.execute("CREATE TABLE T_D (id BIGINT PRIMARY KEY)", 0);

        conn.setAutoCommit(false);
        conn.execute("INSERT INTO T_D VALUES (1)", 0);
        assertTrue(conn.hasPendingTransaction(), "前提：现在手上有未提交的改动");

        killUnderlying(conn);

        DbException e = assertThrows(DbException.class,
                () -> conn.describeTable("PUBLIC", "T_D"));
        assertTrue(e.getMessage().contains("没提交") || e.getMessage().contains("回滚"),
                "报错要说清事务没了，实际是：" + e.getMessage());

        // 报错之后连接本身应当已经可用了，用户重来一次就行
        assertNotNull(conn.describeTable("PUBLIC", "T_D"), "重连之后应当能正常用");
    }

    /** 只是包一层 AutoCloseable，让 @AfterEach 不必关心类型。 */
    private record DbConnectionUnderTest(JdbcConnection conn) implements AutoCloseable {
        @Override
        public void close() {
            try {
                conn.close();
            } catch (RuntimeException ignored) {
                // 测试里连接常常已经是坏的，关不掉无所谓
            }
        }
    }
}
