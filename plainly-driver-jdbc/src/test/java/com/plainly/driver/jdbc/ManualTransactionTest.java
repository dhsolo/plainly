package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动事务。
 *
 * <p>这里守的是三条「错了不会报错」的行为：
 * <ul>
 *   <li>回滚必须真的把改动撤掉。撤不掉的话用户会以为撤了，然后拿着一份
 *       他以为不存在的数据继续干活；</li>
 *   <li>手上有未提交改动时切回自动提交必须被<b>拒绝</b>。JDBC 的
 *       {@code setAutoCommit(true)} 会把未提交的事务隐式提交掉——
 *       那正是用户点这个开关时最不想要的结果；</li>
 *   <li>用户开着事务时，内部的批量执行（导入、结构同步）不能 commit/rollback 整条连接，
 *       只能退回到自己这一批之前。否则一次导入失败会把用户手上的活儿一起回滚。</li>
 * </ul>
 */
@DisplayName("手动事务 · 在 H2 上实际执行")
class ManualTransactionTest {

    private static DbConnection conn;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("tx-probe")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_tx;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @BeforeEach
    void reset() {
        // 上一个用例可能留在手动模式里，先把连接恢复成干净的自动提交状态
        if (!conn.autoCommit()) {
            conn.rollback();
            conn.setAutoCommit(true);
        }
        conn.execute("DROP TABLE IF EXISTS TX_PROBE", 0);
        conn.execute("CREATE TABLE TX_PROBE (ID INT PRIMARY KEY, NAME VARCHAR(32))", 0);
    }

    private static long rowCount() {
        return Long.parseLong(conn.scalar("SELECT COUNT(*) FROM TX_PROBE"));
    }

    @Test
    @DisplayName("回滚真的把改动撤掉，提交真的落库")
    void rollbackAndCommit() {
        conn.setAutoCommit(false);
        conn.execute("INSERT INTO TX_PROBE VALUES (1, '甲')", 0);
        assertEquals(1, rowCount(), "同一个事务里应当看得见自己刚插的行");

        conn.rollback();
        assertEquals(0, rowCount(), "回滚之后那一行必须不见了");

        conn.execute("INSERT INTO TX_PROBE VALUES (2, '乙')", 0);
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(1, rowCount(), "提交之后那一行必须留下");
    }

    @Test
    @DisplayName("有未提交改动时切回自动提交被拒绝——那一步会隐式提交")
    void refuseAutoCommitWithPendingWork() {
        conn.setAutoCommit(false);
        conn.execute("INSERT INTO TX_PROBE VALUES (3, '丙')", 0);
        assertTrue(conn.hasPendingTransaction());

        DbException e = assertThrows(DbException.class, () -> conn.setAutoCommit(true));
        assertTrue(e.getMessage().contains("先「提交」或「回滚」"), e.getMessage());
        // 被拒之后连接必须还在手动模式里，而且那一行还在事务里没落库
        assertFalse(conn.autoCommit());

        conn.rollback();
        assertFalse(conn.hasPendingTransaction());
        conn.setAutoCommit(true);
        assertEquals(0, rowCount());
    }

    @Test
    @DisplayName("未提交标记跟着提交 / 回滚灭掉，不会一直亮着")
    void pendingFlagFollowsCommit() {
        assertFalse(conn.hasPendingTransaction(), "自动提交模式下不该有未提交事务");

        conn.setAutoCommit(false);
        assertFalse(conn.hasPendingTransaction(), "刚开事务还没写东西，不该说有未提交改动");

        conn.execute("INSERT INTO TX_PROBE VALUES (4, '丁')", 0);
        assertTrue(conn.hasPendingTransaction());

        conn.commit();
        assertFalse(conn.hasPendingTransaction());
        conn.setAutoCommit(true);
    }

    @Test
    @DisplayName("用户事务里跑批量：失败只退回这一批，用户之前的改动一点不动")
    void batchFailureKeepsUserWork() {
        conn.setAutoCommit(false);
        conn.execute("INSERT INTO TX_PROBE VALUES (10, '用户自己插的')", 0);

        // 这一批第二条会撞主键：整批必须退回，但上面那行得留着
        assertThrows(RuntimeException.class, () -> conn.inTransaction(List.of(
                "INSERT INTO TX_PROBE VALUES (11, '批量一')",
                "INSERT INTO TX_PROBE VALUES (10, '撞主键')")));

        assertEquals(1, rowCount(),
                "批量整体退回，用户自己那一行必须还在——不能被连坐回滚");
        conn.rollback();
        conn.setAutoCommit(true);
        assertEquals(0, rowCount());
    }

    @Test
    @DisplayName("用户事务里跑批量：成功的那些留在同一个事务里，一起等着提交")
    void batchSuccessJoinsUserTransaction() {
        conn.setAutoCommit(false);
        conn.execute("INSERT INTO TX_PROBE VALUES (20, '用户自己插的')", 0);
        conn.inTransaction(List.of("INSERT INTO TX_PROBE VALUES (21, '批量一')"));

        assertEquals(2, rowCount());
        // 关键：批量执行完不能自己提交掉——用户还没决定
        conn.rollback();
        conn.setAutoCommit(true);
        assertEquals(0, rowCount(), "批量不该替用户提交");
    }

    @Test
    @DisplayName("会隐式提交 DDL 的库上，带着未提交事务改结构会被拦下来")
    void ddlBlockedWhileTransactionOpen() {
        // H2 的 DDL 隐式提交，supportsTransactionalDdl() 为 false
        assertFalse(conn.dialect().supportsTransactionalDdl());

        conn.setAutoCommit(false);
        conn.execute("INSERT INTO TX_PROBE VALUES (30, '还没决定要不要留')", 0);

        DbException e = assertThrows(DbException.class,
                () -> conn.executeDdlBatch(List.of("ALTER TABLE TX_PROBE ADD COLUMN NOTE VARCHAR(8)")));
        assertTrue(e.getMessage().contains("隐式提交"), e.getMessage());

        conn.rollback();
        conn.setAutoCommit(true);
    }
}
