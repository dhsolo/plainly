package com.plainly.core.task;

import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.SqlScript;
import com.plainly.core.db.Connections;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按顺序执行一个任务的步骤。
 *
 * <p>任一步失败即中止：后面的步骤往往依赖前面的结果，硬着头皮跑下去只会把
 * 「哪一步坏了」变得更难说清。
 *
 * <p>界面和命令行走的是同一个类——「导出为命令行」给出的命令必须真的能跑，
 * 否则那个按钮就是个摆设。
 */
public final class TaskRunner {

    /** 一次运行的结果。 */
    public record Result(boolean succeeded, long elapsedMillis, String message,
                         List<String> log) {
    }

    private TaskRunner() {
    }

    public static Result run(ScheduledTask task, ConnectionRegistry registry,
                             Consumer<String> progress) {
        long start = System.nanoTime();
        List<String> log = new ArrayList<>();
        int attempts = task.retries() + 1;
        String lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                for (int i = 0; i < task.steps().size(); i++) {
                    ScheduledTask.Step step = task.steps().get(i);
                    String line = "第 " + (i + 1) + " 步 · " + step.describe();
                    progress.accept(line);
                    String detail = runStep(step, registry);
                    log.add(line + (detail.isEmpty() ? "" : " · " + detail));
                }
                long millis = (System.nanoTime() - start) / 1_000_000;
                return new Result(true, millis,
                        "完成 " + task.steps().size() + " 个步骤"
                                + (attempt > 1 ? "（第 " + attempt + " 次尝试）" : ""), log);
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                log.add("失败：" + lastError);
                if (attempt < attempts) {
                    log.add("将重试（第 " + (attempt + 1) + " 次）");
                }
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Result(false, millis, lastError, log);
    }

    private static String runStep(ScheduledTask.Step step, ConnectionRegistry registry) {
        ConnectionConfig config = registry.listAll().stream()
                .filter(c -> c.id().equals(step.connectionId()))
                .findFirst()
                .orElseThrow(() -> new DbException("步骤引用的连接已经不存在了"));

        try (DbConnection conn = Connections.open(registry.resolvePassword(config))) {
            conn.useSchema(step.schema());
            switch (step.type()) {
                case RUN_SQL: {
                    List<String> statements = SqlScript.split(step.payload(),
                            config.type() == com.plainly.driver.DbType.MYSQL);
                    for (String one : statements) {
                        conn.execute(one, 0);
                    }
                    return "执行 " + statements.size() + " 条语句";
                }
                case BACKUP: {
                    return runBackup(step, conn);
                }
                case EXPORT:
                default: {
                    ExportOptions options = new ExportOptions()
                            .setFormat(formatOf(step.target()))
                            .setTableName(step.payload())
                            .setDialect(conn.dialect())
                            .setTarget(Paths.get(step.target()));
                    long rows = Exporters.export(
                            RowSource.ofTable(conn, step.schema(), step.payload(), null, 1000, -1),
                            options, n -> { });
                    return "导出 " + rows + " 行";
                }
            }
        }
    }

    /**
     * 备份一步：写一个带时间戳的 .sql，然后按保留份数删掉旧的。
     *
     * <h2>为什么文件名必须带时间戳</h2>
     * 固定文件名的「定时备份」是个陷阱：每天覆盖同一个文件，等于永远只有<b>最近一次</b>
     * 的备份。而人发现数据出问题往往是几天之后——那时最近这一次备份里
     * 已经是坏掉的数据了。
     *
     * <h2>清理为什么只删自己写的那些</h2>
     * 目标目录里可能还放着别的东西（手工备份、别的库的备份）。只删
     * 「本步骤命名规则生成的、同一个库的」文件，别的一律不碰——
     * 一个定时任务在夜里删掉用户别的文件，是无法接受的失败方式。
     */
    private static String runBackup(ScheduledTask.Step step, DbConnection conn) {
        java.nio.file.Path dir = Paths.get(step.target());
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new DbException("建不出备份目录 " + dir + "：" + e.getMessage(), e);
        }
        String prefix = safeName(step.schema()) + "-";
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        java.nio.file.Path file = dir.resolve(prefix + stamp + ".sql");

        var result = com.plainly.core.backup.BackupService.backup(
                conn, step.schema(), file, true, m -> { });
        int removed = prune(dir, prefix, step.keepCount());
        return "备份 " + result.tables() + " 张表 · " + result.rows() + " 行 → " + file.getFileName()
                + (removed > 0 ? "（清理了 " + removed + " 份旧备份）" : "");
    }

    /** 只保留最近 {@code keep} 份，返回删掉的份数。 */
    private static int prune(java.nio.file.Path dir, String prefix, int keep) {
        try (var stream = java.nio.file.Files.list(dir)) {
            List<java.nio.file.Path> mine = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(".sql");
                    })
                    // 文件名里的时间戳是定长的，按名字倒序就是按时间倒序
                    .sorted(java.util.Comparator.comparing(
                            (java.nio.file.Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            int removed = 0;
            for (int i = keep; i < mine.size(); i++) {
                try {
                    java.nio.file.Files.deleteIfExists(mine.get(i));
                    removed++;
                } catch (java.io.IOException ignored) {
                    // 删不掉（被占用、只读）不该让整个备份步骤算失败：
                    // 备份本身已经成功了，那才是这一步的目的
                }
            }
            return removed;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /**
     * Windows 文件名里不能出现的字符。
     *
     * <p>写成常量而不是内联的字面量：这串里有反斜杠和双引号，
     * 在源码里各要转义一次，内联写出来极易看错。
     */
    private static final String FORBIDDEN_IN_FILENAME = "\\/:*?\"<>|";

    /** 库名进文件名前先洗一遍：Windows 上路径里不能有这些字符。 */
    private static String safeName(String schema) {
        if (schema == null || schema.isBlank()) {
            return "backup";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : schema.toCharArray()) {
            sb.append(FORBIDDEN_IN_FILENAME.indexOf(c) >= 0 ? '_' : c);
        }
        return sb.toString();
    }

    /** 按目标文件的后缀挑格式，省得再让用户选一次。 */
    private static ExportOptions.Format formatOf(String target) {
        String lower = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return ExportOptions.Format.XLSX;
        }
        if (lower.endsWith(".json")) {
            return ExportOptions.Format.JSON;
        }
        if (lower.endsWith(".sql")) {
            return ExportOptions.Format.SQL_INSERT;
        }
        return ExportOptions.Format.CSV;
    }

    /**
     * 命令行入口。
     *
     * <p>用法：{@code java -cp ... com.plainly.core.task.TaskRunner <任务名>}
     *
     * <p>这条命令是给 Windows 计划任务用的。应用关掉之后进程内的调度当然不会触发，
     * 想要真正的无人值守就得靠系统的调度器——所以这个入口必须真的能跑。
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法：TaskRunner <任务名>");
            System.exit(2);
            return;
        }
        String wanted = args[0];
        try (com.plainly.core.store.LocalStore store = new com.plainly.core.store.LocalStore()) {
            TaskStore tasks = new TaskStore(store);
            /*
             * 走 forCurrentPlatform()，不要写死 DpapiCredentialStore。
             *
             * 写死的后果分两种，都不报「这是平台问题」：
             *   · 非 Windows 上，DPAPI 根本没有，这一行直接抛，整个命令行入口起不来；
             *   · Windows 上缺 JNA 时也一样——而界面那边走的是 forCurrentPlatform()，
             *     会安静退回兜底实现。于是同一台机器上，界面读得出口令、
             *     计划任务读不出，两边看起来毫无关联。
             *
             * 口令是用哪个实现加密的，就得用哪个实现解；选择权只能在一个地方。
             */
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    com.plainly.core.store.CredentialStore.forCurrentPlatform());
            ScheduledTask task = tasks.listAll().stream()
                    .filter(t -> t.name().equals(wanted))
                    .findFirst()
                    .orElse(null);
            if (task == null) {
                System.err.println("没有叫「" + wanted + "」的任务");
                System.exit(3);
                return;
            }
            LocalDateTime started = LocalDateTime.now();
            Result result = run(task, registry, System.out::println);
            tasks.recordRun(task.id(), started, result.elapsedMillis(),
                    result.succeeded(), result.message());
            System.out.println(result.succeeded() ? "成功：" + result.message()
                    : "失败：" + result.message());
            System.exit(result.succeeded() ? 0 : 1);
        }
    }
}
