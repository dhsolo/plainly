package com.plainly.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.core.store.LocalStore;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 调度时间的推算与任务的存取。
 *
 * <p>算错下一次运行时间是不会报错的，只会到点没跑——所以界面上要给出接下来几次，
 * 而这几个数必须是对的。
 */
@DisplayName("计划任务 · 调度与存储")
class ScheduleTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("每天：今天的时间过了就顺延到明天")
    void dailyRollsOver() {
        ScheduledTask task = new ScheduledTask()
                .setTrigger(ScheduledTask.Trigger.DAILY)
                .setAtTime(LocalTime.of(3, 0));

        LocalDateTime before = LocalDateTime.of(2026, 9, 2, 1, 0);
        assertEquals(LocalDateTime.of(2026, 9, 2, 3, 0), task.nextRunAfter(before));

        LocalDateTime after = LocalDateTime.of(2026, 9, 2, 5, 0);
        assertEquals(LocalDateTime.of(2026, 9, 3, 3, 0), task.nextRunAfter(after));
    }

    @Test
    @DisplayName("每周：落在指定的那一天")
    void weeklyPicksTheDay() {
        ScheduledTask task = new ScheduledTask()
                .setTrigger(ScheduledTask.Trigger.WEEKLY)
                .setOnDay(DayOfWeek.MONDAY)
                .setAtTime(LocalTime.of(2, 0));

        // 2026-09-02 是周三
        LocalDateTime next = task.nextRunAfter(LocalDateTime.of(2026, 9, 2, 10, 0));
        assertEquals(DayOfWeek.MONDAY, next.getDayOfWeek());
        assertEquals(LocalDateTime.of(2026, 9, 7, 2, 0), next);
    }

    @Test
    @DisplayName("接下来三次是递增的，不会原地打转")
    void listsNextRuns() {
        ScheduledTask task = new ScheduledTask()
                .setTrigger(ScheduledTask.Trigger.DAILY)
                .setAtTime(LocalTime.of(7, 30));
        List<LocalDateTime> runs = task.nextRuns(LocalDateTime.of(2026, 9, 2, 8, 0), 3);
        assertEquals(3, runs.size());
        assertTrue(runs.get(0).isBefore(runs.get(1)));
        assertTrue(runs.get(1).isBefore(runs.get(2)));
    }

    @Test
    @DisplayName("停用的任务没有下一次")
    void disabledHasNoNextRun() {
        ScheduledTask task = new ScheduledTask().setEnabled(false);
        assertNull(task.nextRunAfter(LocalDateTime.now()));
    }

    @Test
    @DisplayName("存进去再读出来，步骤顺序不变")
    void savesAndLoads() {
        try (LocalStore store = new LocalStore(dir.resolve("tasks.db"))) {
            TaskStore tasks = new TaskStore(store);

            ScheduledTask task = new ScheduledTask()
                    .setName("每日导出")
                    .setTrigger(ScheduledTask.Trigger.DAILY)
                    .setAtTime(LocalTime.of(7, 30))
                    .setRetries(2)
                    .setSteps(List.of(
                            new ScheduledTask.Step(ScheduledTask.StepType.RUN_SQL,
                                    "conn-1", "shop", "DELETE FROM tmp", null),
                            new ScheduledTask.Step(ScheduledTask.StepType.EXPORT,
                                    "conn-1", "shop", "orders", "D:/out/orders.csv")));
            tasks.save(task);

            List<ScheduledTask> loaded = tasks.listAll();
            assertEquals(1, loaded.size());
            ScheduledTask back = loaded.get(0);
            assertEquals("每日导出", back.name());
            assertEquals(LocalTime.of(7, 30), back.atTime());
            assertEquals(2, back.retries());
            assertEquals(2, back.steps().size());
            assertEquals(ScheduledTask.StepType.RUN_SQL, back.steps().get(0).type());
            assertEquals("orders", back.steps().get(1).payload());
        }
    }

    @Test
    @DisplayName("再存一次是更新，不是插出第二条")
    void saveTwiceUpdates() {
        try (LocalStore store = new LocalStore(dir.resolve("tasks2.db"))) {
            TaskStore tasks = new TaskStore(store);
            ScheduledTask task = tasks.save(new ScheduledTask().setName("甲"));
            task.setName("乙");
            tasks.save(task);

            List<ScheduledTask> loaded = tasks.listAll();
            assertEquals(1, loaded.size());
            assertEquals("乙", loaded.get(0).name());
        }
    }

    @Test
    @DisplayName("运行记录按时间倒序")
    void recordsRuns() {
        try (LocalStore store = new LocalStore(dir.resolve("tasks3.db"))) {
            TaskStore tasks = new TaskStore(store);
            ScheduledTask task = tasks.save(new ScheduledTask().setName("丙"));
            tasks.recordRun(task.id(), LocalDateTime.of(2026, 9, 1, 3, 0), 1000, true, "成功");
            tasks.recordRun(task.id(), LocalDateTime.of(2026, 9, 2, 3, 0), 900, false, "磁盘满");

            List<TaskStore.Run> runs = tasks.recentRuns(task.id(), 10);
            assertEquals(2, runs.size());
            assertEquals("磁盘满", runs.get(0).message(), "最近的排最前");
            assertTrue(runs.get(1).succeeded());
        }
    }
}
