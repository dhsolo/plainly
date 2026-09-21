package com.plainly.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 定时备份这一步。
 *
 * <p>这里守两条会在夜里安静出事的规矩：
 * <ul>
 *   <li><b>文件名必须带时间戳</b>。固定文件名的定时备份等于永远只有最近一次——
 *       而数据出问题往往几天后才被发现，那时最近这份里已经是坏数据了；</li>
 *   <li><b>清理只碰自己写的文件</b>。目录里可能还放着别的库的备份、手工备份。
 *       一个定时任务在夜里删掉用户别的文件，是不能接受的失败方式。</li>
 * </ul>
 */
@DisplayName("计划任务 · 定时备份")
class BackupStepTest {

    @TempDir
    Path dir;

    private LocalStore local;

    @AfterEach
    void close() {
        if (local != null) {
            local.close();
        }
    }

    private ConnectionRegistry registry(String h2File) {
        local = new LocalStore(dir.resolve("plainly.db"));
        ConnectionRegistry registry = new ConnectionRegistry(local,
                new CredentialStore.UnprotectedCredentialStore());
        registry.save(new ConnectionConfig()
                .setId("c1")
                .setName("备份源")
                .setType(DbType.H2)
                .setFilePath(h2File)
                .setUser("sa")
                .setSavePassword(false));
        return registry;
    }

    private static ScheduledTask backupTask(Path target, int keep) {
        return new ScheduledTask()
                .setName("每日备份")
                .setSteps(List.of(new ScheduledTask.Step(
                        ScheduledTask.StepType.BACKUP, "c1", "PUBLIC",
                        String.valueOf(keep), target.toString())));
    }

    @Test
    @DisplayName("备份文件名带时间戳，不覆盖上一次的那份")
    void fileNameCarriesTimestamp() throws Exception {
        ConnectionRegistry registry = registry("mem:plainly_backup_step;DB_CLOSE_DELAY=-1");
        try (var conn = com.plainly.core.db.Connections.open(
                registry.resolvePassword(registry.listAll().get(0)))) {
            conn.execute("DROP TABLE IF EXISTS BK", 0);
            conn.execute("CREATE TABLE BK (ID INT PRIMARY KEY)", 0);
            conn.execute("INSERT INTO BK VALUES (1)", 0);
        }

        Path target = dir.resolve("backups");
        TaskRunner.Result result = TaskRunner.run(backupTask(target, 7), registry, m -> { });
        assertTrue(result.succeeded(), String.join(" / ", result.log()));

        List<Path> files;
        try (var s = Files.list(target)) {
            files = s.toList();
        }
        assertEquals(1, files.size());
        String name = files.get(0).getFileName().toString();
        assertTrue(name.startsWith("PUBLIC-"), name);
        assertTrue(name.endsWith(".sql"), name);
        // 「PUBLIC-」加上 yyyyMMdd-HHmmss 再加上 .sql
        assertEquals("PUBLIC-".length() + 15 + 4, name.length(), name);
    }

    @Test
    @DisplayName("保留份数生效，而且只删自己写的那些文件")
    void pruneKeepsOnlyItsOwnFiles() throws Exception {
        ConnectionRegistry registry = registry("mem:plainly_backup_prune;DB_CLOSE_DELAY=-1");
        try (var conn = com.plainly.core.db.Connections.open(
                registry.resolvePassword(registry.listAll().get(0)))) {
            conn.execute("DROP TABLE IF EXISTS BK", 0);
            conn.execute("CREATE TABLE BK (ID INT PRIMARY KEY)", 0);
        }

        Path target = dir.resolve("backups");
        Files.createDirectories(target);
        // 别的东西：另一个库的备份、一份手工备份、一个不相干的文件
        Files.writeString(target.resolve("OTHERDB-20250101-000000.sql"), "-- 别的库");
        Files.writeString(target.resolve("手工备份.sql"), "-- 手工");
        Files.writeString(target.resolve("readme.txt"), "别删我");
        // 三份「本步骤写的」旧备份
        Files.writeString(target.resolve("PUBLIC-20250101-000000.sql"), "-- 旧 1");
        Files.writeString(target.resolve("PUBLIC-20250102-000000.sql"), "-- 旧 2");
        Files.writeString(target.resolve("PUBLIC-20250103-000000.sql"), "-- 旧 3");

        // 保留 2 份：跑完之后加上新写的这份，一共只该剩 2 份 PUBLIC-*
        TaskRunner.Result result = TaskRunner.run(backupTask(target, 2), registry, m -> { });
        assertTrue(result.succeeded(), String.join(" / ", result.log()));

        List<String> names;
        try (var s = Files.list(target)) {
            names = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        long mine = names.stream().filter(n -> n.startsWith("PUBLIC-")).count();
        assertEquals(2, mine, "保留 2 份，实得：" + names);

        assertTrue(names.contains("OTHERDB-20250101-000000.sql"), "别的库的备份不能删：" + names);
        assertTrue(names.contains("手工备份.sql"), "手工备份不能删：" + names);
        assertTrue(names.contains("readme.txt"), "不相干的文件不能删：" + names);
        // 留下的必须是最新的那些：最旧的 20250101 该被清掉
        assertTrue(!names.contains("PUBLIC-20250101-000000.sql"), "最旧的那份该被清掉：" + names);
    }

    @Test
    @DisplayName("保留份数填了看不懂的东西时按默认值，绝不当成 0 份")
    void badKeepCountFallsBackToDefault() {
        ScheduledTask.Step step = new ScheduledTask.Step(
                ScheduledTask.StepType.BACKUP, "c1", "PUBLIC", "随便写的", "D:/x");
        assertEquals(ScheduledTask.DEFAULT_KEEP, step.keepCount());

        ScheduledTask.Step zero = new ScheduledTask.Step(
                ScheduledTask.StepType.BACKUP, "c1", "PUBLIC", "0", "D:/x");
        assertEquals(ScheduledTask.DEFAULT_KEEP, zero.keepCount(),
                "0 份意味着连刚写的那份也删掉，那不可能是用户的本意");
    }
}
