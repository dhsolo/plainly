package com.plainly.core.task;

import com.plainly.core.store.LocalStore;
import com.plainly.driver.DbException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务与运行记录的本地存储。
 *
 * <p>步骤存成一行一条的子表，不塞 JSON：日后要按连接查「哪些任务会动这个库」，
 * 有结构的数据查得出来，一坨 JSON 查不出来。
 */
public class TaskStore {

    /** 一次运行的记录。 */
    public record Run(long id, String taskId, LocalDateTime startedAt, long elapsedMillis,
                      boolean succeeded, String message) {
    }

    private final LocalStore store;

    public TaskStore(LocalStore store) {
        this.store = store;
        migrate();
    }

    private void migrate() {
        try (Statement st = store.connection().createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS tasks (
                      id             TEXT PRIMARY KEY,
                      name           TEXT NOT NULL,
                      enabled        INTEGER NOT NULL DEFAULT 1,
                      trigger_type   TEXT NOT NULL,
                      at_time        TEXT,
                      on_day         TEXT,
                      interval_min   INTEGER,
                      retries        INTEGER NOT NULL DEFAULT 0,
                      retry_interval INTEGER NOT NULL DEFAULT 10
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS task_steps (
                      task_id       TEXT NOT NULL,
                      step_order    INTEGER NOT NULL,
                      step_type     TEXT NOT NULL,
                      connection_id TEXT,
                      schema_name   TEXT,
                      payload       TEXT,
                      target        TEXT,
                      PRIMARY KEY (task_id, step_order)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS task_runs (
                      id          INTEGER PRIMARY KEY AUTOINCREMENT,
                      task_id     TEXT NOT NULL,
                      started_at  TEXT NOT NULL,
                      elapsed_ms  INTEGER NOT NULL,
                      succeeded   INTEGER NOT NULL,
                      message     TEXT
                    )""");
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_runs_task ON task_runs(task_id, started_at DESC)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS task_settings (
                      k TEXT PRIMARY KEY,
                      v TEXT
                    )""");
        } catch (SQLException e) {
            throw new DbException("初始化任务表失败：" + e.getMessage(), e);
        }
    }

    public List<ScheduledTask> listAll() {
        List<ScheduledTask> out = new ArrayList<>();
        try (Statement st = store.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tasks ORDER BY name")) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new DbException("读取任务失败：" + e.getMessage(), e);
        }
        out.forEach(t -> t.setSteps(loadSteps(t.id())));
        return out;
    }

    private ScheduledTask read(ResultSet rs) throws SQLException {
        ScheduledTask task = new ScheduledTask()
                .setId(rs.getString("id"))
                .setName(rs.getString("name"))
                .setEnabled(rs.getInt("enabled") != 0)
                .setTrigger(ScheduledTask.Trigger.valueOf(rs.getString("trigger_type")))
                .setIntervalMinutes(rs.getInt("interval_min"))
                .setRetries(rs.getInt("retries"))
                .setRetryIntervalMinutes(rs.getInt("retry_interval"));
        String at = rs.getString("at_time");
        if (at != null && !at.isBlank()) {
            task.setAtTime(LocalTime.parse(at));
        }
        String day = rs.getString("on_day");
        if (day != null && !day.isBlank()) {
            task.setOnDay(DayOfWeek.valueOf(day));
        }
        return task;
    }

    private List<ScheduledTask.Step> loadSteps(String taskId) {
        List<ScheduledTask.Step> out = new ArrayList<>();
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT * FROM task_steps WHERE task_id = ? ORDER BY step_order")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ScheduledTask.Step(
                            ScheduledTask.StepType.valueOf(rs.getString("step_type")),
                            rs.getString("connection_id"),
                            rs.getString("schema_name"),
                            rs.getString("payload"),
                            rs.getString("target")));
                }
            }
        } catch (SQLException e) {
            throw new DbException("读取任务步骤失败：" + e.getMessage(), e);
        }
        return out;
    }

    public ScheduledTask save(ScheduledTask task) {
        if (task.id() == null || task.id().isBlank()) {
            task.setId(UUID.randomUUID().toString());
        }
        try {
            try (PreparedStatement ps = store.connection().prepareStatement(
                    "INSERT INTO tasks (id, name, enabled, trigger_type, at_time, on_day,"
                            + " interval_min, retries, retry_interval)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)"
                            + " ON CONFLICT(id) DO UPDATE SET name=excluded.name,"
                            + " enabled=excluded.enabled, trigger_type=excluded.trigger_type,"
                            + " at_time=excluded.at_time, on_day=excluded.on_day,"
                            + " interval_min=excluded.interval_min, retries=excluded.retries,"
                            + " retry_interval=excluded.retry_interval")) {
                ps.setString(1, task.id());
                ps.setString(2, task.name());
                ps.setInt(3, task.enabled() ? 1 : 0);
                ps.setString(4, task.trigger().name());
                ps.setString(5, task.atTime().toString());
                ps.setString(6, task.onDay().name());
                ps.setInt(7, task.intervalMinutes());
                ps.setInt(8, task.retries());
                ps.setInt(9, task.retryIntervalMinutes());
                ps.executeUpdate();
            }
            try (PreparedStatement del = store.connection().prepareStatement(
                    "DELETE FROM task_steps WHERE task_id = ?")) {
                del.setString(1, task.id());
                del.executeUpdate();
            }
            try (PreparedStatement ps = store.connection().prepareStatement(
                    "INSERT INTO task_steps (task_id, step_order, step_type, connection_id,"
                            + " schema_name, payload, target) VALUES (?,?,?,?,?,?,?)")) {
                for (int i = 0; i < task.steps().size(); i++) {
                    ScheduledTask.Step step = task.steps().get(i);
                    ps.setString(1, task.id());
                    ps.setInt(2, i);
                    ps.setString(3, step.type().name());
                    ps.setString(4, step.connectionId());
                    ps.setString(5, step.schema());
                    ps.setString(6, step.payload());
                    ps.setString(7, step.target());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new DbException("保存任务失败：" + e.getMessage(), e);
        }
        return task;
    }

    public void delete(String taskId) {
        try (PreparedStatement a = store.connection().prepareStatement(
                "DELETE FROM task_steps WHERE task_id = ?");
             PreparedStatement b = store.connection().prepareStatement(
                     "DELETE FROM tasks WHERE id = ?")) {
            a.setString(1, taskId);
            a.executeUpdate();
            b.setString(1, taskId);
            b.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除任务失败：" + e.getMessage(), e);
        }
    }

    public void recordRun(String taskId, LocalDateTime startedAt, long elapsedMillis,
                          boolean succeeded, String message) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO task_runs (task_id, started_at, elapsed_ms, succeeded, message)"
                        + " VALUES (?,?,?,?,?)")) {
            ps.setString(1, taskId);
            ps.setString(2, startedAt.toString());
            ps.setLong(3, elapsedMillis);
            ps.setInt(4, succeeded ? 1 : 0);
            ps.setString(5, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("记录运行历史失败：" + e.getMessage(), e);
        }
        // 运行记录原来一条不删：一个每五分钟跑一次的任务，一年写十万行。
        // 跟着每次写入清理是划算的——任务本来就不密集，不像执行 SQL 那样一秒好几次
        com.plainly.core.store.Retention.sweep(store);
    }

    /** 一处放杂项设置的地方（SMTP 之类）。值不多，不值得为它们各建一张表。 */
    public String setting(String key, String fallback) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT v FROM task_settings WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException e) {
            return fallback;
        }
    }

    public void setSetting(String key, String value) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO task_settings (k, v) VALUES (?,?)"
                        + " ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("保存设置失败：" + e.getMessage(), e);
        }
    }

    public List<Run> recentRuns(String taskId, int limit) {
        List<Run> out = new ArrayList<>();
        String sql = taskId == null
                ? "SELECT * FROM task_runs ORDER BY started_at DESC LIMIT ?"
                : "SELECT * FROM task_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement ps = store.connection().prepareStatement(sql)) {
            if (taskId == null) {
                ps.setInt(1, limit);
            } else {
                ps.setString(1, taskId);
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Run(rs.getLong("id"), rs.getString("task_id"),
                            LocalDateTime.parse(rs.getString("started_at")),
                            rs.getLong("elapsed_ms"), rs.getInt("succeeded") != 0,
                            rs.getString("message")));
                }
            }
        } catch (SQLException e) {
            throw new DbException("读取运行历史失败：" + e.getMessage(), e);
        }
        return out;
    }
}
