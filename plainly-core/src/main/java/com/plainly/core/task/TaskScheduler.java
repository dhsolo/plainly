package com.plainly.core.task;

import com.plainly.core.store.ConnectionRegistry;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 进程内的调度器。
 *
 * <p>每分钟看一次有没有到点的任务。应用关掉它就停了——界面上那句
 * 「应用关掉就不会触发」不是免责声明，是这个类的真实行为，
 * 所以它必须真的存在并且真的会跑，否则那句话连同「立即运行一次」都成了摆设。
 *
 * <p>下一次运行的时间只记在内存里：进程重启后按当前时间重新推算。
 * 存盘的话就得处理「关机期间错过的那几次要不要补跑」，而补跑一个每日全量导出
 * 往往不是用户想要的——宁可不补，也不要在启动瞬间跑一串意料之外的任务。
 */
public class TaskScheduler implements AutoCloseable {

    private final TaskStore store;
    private final ConnectionRegistry registry;
    private final Consumer<String> log;
    private final Map<String, LocalDateTime> nextRuns = new HashMap<>();
    private ScheduledExecutorService executor;

    public TaskScheduler(TaskStore store, ConnectionRegistry registry, Consumer<String> log) {
        this.store = store;
        this.registry = registry;
        this.log = log;
    }

    /** 启动。守护线程：它不该拦着应用退出。 */
    public void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plainly-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, 20, 60, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<ScheduledTask> tasks = store.listAll();
            for (ScheduledTask task : tasks) {
                if (!task.enabled() || task.steps().isEmpty()) {
                    continue;
                }
                LocalDateTime due = nextRuns.get(task.id());
                if (due == null) {
                    // 第一次见到这个任务：从现在往后算，不补跑历史
                    nextRuns.put(task.id(), task.nextRunAfter(now));
                    continue;
                }
                if (now.isBefore(due)) {
                    continue;
                }
                nextRuns.put(task.id(), task.nextRunAfter(now));
                runOne(task, now);
            }
        } catch (RuntimeException e) {
            // 调度线程绝不能因为一次异常就死掉，否则后面所有任务都无声无息地不跑了
            log.accept("调度出错：" + e.getMessage());
        }
    }

    private void runOne(ScheduledTask task, LocalDateTime startedAt) {
        log.accept("计划任务开始：" + task.name());
        TaskRunner.Result result = TaskRunner.run(task, registry, line -> { });
        store.recordRun(task.id(), startedAt, result.elapsedMillis(),
                result.succeeded(), result.message());
        log.accept("计划任务" + (result.succeeded() ? "完成" : "失败") + "：" + task.name()
                + " · " + result.message());
        notifyResult(task, result, startedAt);
    }

    /**
     * 通知。
     *
     * <p>默认只在失败时发。一个每天成功的备份任务每天发一封「成功」的邮件，
     * 两周之后就没人看了，真出事那封也会被一起忽略。
     */
    private void notifyResult(ScheduledTask task, TaskRunner.Result result,
                              LocalDateTime startedAt) {
        boolean always = "true".equalsIgnoreCase(store.setting("mail.always", "false"));
        if (result.succeeded() && !always) {
            return;
        }
        MailNotifier.Config config = new MailNotifier.Config(
                store.setting("mail.host", ""),
                parsePort(store.setting("mail.port", "587")),
                "true".equalsIgnoreCase(store.setting("mail.starttls", "true")),
                store.setting("mail.user", ""),
                store.setting("mail.password", ""),
                store.setting("mail.from", ""),
                store.setting("mail.to", ""));
        if (!config.usable()) {
            return;
        }
        String subject = (result.succeeded() ? "[成功] " : "[失败] ") + task.name();
        String body = "任务：" + task.name() + "\n"
                + "开始：" + startedAt + "\n"
                + "耗时：" + result.elapsedMillis() + " ms\n"
                + "结果：" + (result.succeeded() ? "成功" : "失败") + "\n\n"
                + String.join("\n", result.log());
        String problem = MailNotifier.send(config, subject, body);
        if (problem != null) {
            // 发不出邮件不改变任务本身的成败，只记一笔
            log.accept("任务通知发送失败：" + problem);
        }
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return 587;
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
