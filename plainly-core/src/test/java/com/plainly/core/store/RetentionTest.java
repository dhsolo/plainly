package com.plainly.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 失效策略。
 *
 * <p>这里最要紧的一条是「该留的没被删」：删多了不会报错，只会在某天用户去翻
 * 一条收藏的语句时发现它不见了——那时候已经找不回来。所以每个用例都同时断言
 * 「过期的删掉了」和「收藏的还在」。
 */
@DisplayName("本机库 · 失效策略")
class RetentionTest {

    @TempDir
    Path dir;

    private LocalStore local;

    /**
     * 一个建好全部表的本机库。
     *
     * <p>{@code task_runs} 是 {@link com.plainly.core.task.TaskStore} 建的，
     * 所以这里也得把它建出来——真实运行时 AppContext 同样是先有 TaskStore 再清理。
     */
    private LocalStore store() {
        local = new LocalStore(dir.resolve("plainly.db"));
        new com.plainly.core.task.TaskStore(local);
        return local;
    }

    @AfterEach
    void closeStore() {
        if (local != null) {
            local.close();
            local = null;
        }
    }

    private void insertHistory(LocalStore store, String sql, LocalDateTime when, boolean favorite) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO query_history (connection_id, sql_text, executed_at, elapsed_ms,"
                        + " row_count, succeeded, error_text, favorite) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "c1");
            ps.setString(2, sql);
            ps.setString(3, when.toString());
            ps.setLong(4, 1);
            ps.setLong(5, 1);
            ps.setInt(6, 1);
            ps.setString(7, null);
            ps.setInt(8, favorite ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertRun(LocalStore store, String taskId, LocalDateTime when) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO task_runs (task_id, started_at, elapsed_ms, succeeded, message)"
                        + " VALUES (?,?,?,?,?)")) {
            ps.setString(1, taskId);
            ps.setString(2, when.toString());
            ps.setLong(3, 5);
            ps.setInt(4, 1);
            ps.setString(5, "ok");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(LocalStore store, String table) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("过期的历史删掉，收藏的留着")
    void expiresOldHistoryButKeepsFavorites() {
        LocalStore store = store();
        LocalDateTime old = LocalDateTime.now().minusDays(Retention.DAYS + 5);
        insertHistory(store, "SELECT 1", old, false);
        insertHistory(store, "SELECT 2", old, true);
        insertHistory(store, "SELECT 3", LocalDateTime.now(), false);

        Retention.sweep(store);

        assertEquals(2, count(store, "query_history"), "只该删掉那条过期的非收藏");
        assertTrue(exists(store, "SELECT 2"), "收藏的不能被自动删——用户按收藏就是让它别丢");
        assertTrue(exists(store, "SELECT 3"));
    }

    @Test
    @DisplayName("条数超上限时，多出来的老记录被掐掉")
    void capsHistoryRows() {
        LocalStore store = store();
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < Retention.HISTORY_ROWS + 40; i++) {
            insertHistory(store, "SELECT " + i, base.minusMinutes(i), false);
        }
        Retention.sweep(store);
        assertEquals(Retention.HISTORY_ROWS, count(store, "query_history"));
        // 掐的是最老的那一头，最近的必须还在
        assertTrue(exists(store, "SELECT 0"));
    }

    @Test
    @DisplayName("条数上限只管非收藏：收藏再多也不动")
    void rowCapIgnoresFavorites() {
        LocalStore store = store();
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < 20; i++) {
            insertHistory(store, "FAV " + i, base.minusMinutes(i), true);
        }
        for (int i = 0; i < Retention.HISTORY_ROWS + 10; i++) {
            insertHistory(store, "PLAIN " + i, base.minusMinutes(i), false);
        }
        Retention.sweep(store);
        assertEquals(Retention.HISTORY_ROWS + 20, count(store, "query_history"),
                "收藏不计入上限，也不受上限影响");
    }

    @Test
    @DisplayName("过期的任务运行记录删掉")
    void expiresOldRuns() {
        LocalStore store = store();
        insertRun(store, "t1", LocalDateTime.now().minusDays(Retention.DAYS + 1));
        insertRun(store, "t1", LocalDateTime.now());
        Retention.sweep(store);
        assertEquals(1, count(store, "task_runs"));
    }

    @Test
    @DisplayName("运行记录按任务分别限量，跑得勤的不会把跑得少的挤没")
    void capsRunsPerTask() {
        LocalStore store = store();
        LocalDateTime base = LocalDateTime.now();
        // 一个高频任务写满上限还多出 50 条
        for (int i = 0; i < Retention.RUNS_PER_TASK + 50; i++) {
            insertRun(store, "busy", base.minusMinutes(i));
        }
        // 一个低频任务只有 3 条
        for (int i = 0; i < 3; i++) {
            insertRun(store, "rare", base.minusHours(i));
        }

        Retention.sweep(store);

        assertEquals(Retention.RUNS_PER_TASK, countRuns(store, "busy"));
        assertEquals(3, countRuns(store, "rare"), "低频任务的记录不该被高频任务挤掉");
    }

    @Test
    @DisplayName("没什么可删时也不出错")
    void sweepOnEmptyStore() {
        LocalStore store = store();
        assertEquals(0, Retention.sweep(store));
    }

    private boolean exists(LocalStore store, String sql) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT COUNT(*) FROM query_history WHERE sql_text = ?")) {
            ps.setString(1, sql);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRuns(LocalStore store, String taskId) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT COUNT(*) FROM task_runs WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
