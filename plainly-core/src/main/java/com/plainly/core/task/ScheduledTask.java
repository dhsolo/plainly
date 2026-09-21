package com.plainly.core.task;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个计划任务。
 *
 * <p>只收录本工具真的做得到的步骤：执行 SQL、导出数据、备份整库。
 * <b>还原</b>不在这里：无人值守地往一个库里灌数据，是这个工具里最不该自动化的一件事。
 */
public class ScheduledTask {

    /** 步骤类型。 */
    public enum StepType {
        RUN_SQL("执行 SQL"),
        EXPORT("导出数据"),
        /**
         * 备份整库到目录。
         *
         * <p>走的是界面上那个「备份与还原」同一套 {@code BackupService}，
         * 不调 mysqldump 这类外部程序——那要求用户机器上装了对应版本的客户端，
         * 而「装了但版本不对」的失败方式极难排查。
         */
        BACKUP("备份整库");

        private final String label;

        StepType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 一个步骤。
     *
     * @param connectionId 在哪个连接上执行
     * @param payload 执行 SQL 时是脚本；导出时是「表名」；备份时是<b>保留份数</b>
     * @param target 导出时是目标文件；备份时是目标<b>目录</b>；执行 SQL 时为空
     */
    public record Step(StepType type, String connectionId, String schema,
                       String payload, String target) {

        public String describe() {
            return switch (type) {
                case RUN_SQL -> "执行 SQL · " + schema;
                case EXPORT -> "导出 " + schema + "." + payload + " → " + target;
                case BACKUP -> "备份 " + schema + " → " + target
                        + "（保留最近 " + keepCount() + " 份）";
            };
        }

        /**
         * 备份保留几份。
         *
         * <p>解析不出来就按 7 份：一个看不懂的数字不该变成「一份都不留」——
         * 那会在某个夜里把用户所有的备份删光，而且不报错。
         */
        public int keepCount() {
            try {
                int n = Integer.parseInt(payload == null ? "" : payload.trim());
                return n > 0 ? n : DEFAULT_KEEP;
            } catch (NumberFormatException e) {
                return DEFAULT_KEEP;
            }
        }
    }

    /** 备份默认保留份数。一周——出事之后回头找，通常也就是这几天的事。 */
    public static final int DEFAULT_KEEP = 7;

    /** 触发方式。 */
    public enum Trigger {
        DAILY("每天"), WEEKLY("每周"), INTERVAL("每隔一段时间");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id;
    private String name = "";
    private boolean enabled = true;
    private Trigger trigger = Trigger.DAILY;
    private LocalTime atTime = LocalTime.of(3, 0);
    private DayOfWeek onDay = DayOfWeek.MONDAY;
    private int intervalMinutes = 60;
    private int retries;
    private int retryIntervalMinutes = 10;
    private List<Step> steps = new ArrayList<>();

    public String id() {
        return id;
    }

    public ScheduledTask setId(String id) {
        this.id = id;
        return this;
    }

    public String name() {
        return name;
    }

    public ScheduledTask setName(String name) {
        this.name = name;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    public ScheduledTask setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Trigger trigger() {
        return trigger;
    }

    public ScheduledTask setTrigger(Trigger trigger) {
        this.trigger = trigger;
        return this;
    }

    public LocalTime atTime() {
        return atTime;
    }

    public ScheduledTask setAtTime(LocalTime atTime) {
        this.atTime = atTime;
        return this;
    }

    public DayOfWeek onDay() {
        return onDay;
    }

    public ScheduledTask setOnDay(DayOfWeek onDay) {
        this.onDay = onDay;
        return this;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public ScheduledTask setIntervalMinutes(int minutes) {
        this.intervalMinutes = Math.max(1, minutes);
        return this;
    }

    public int retries() {
        return retries;
    }

    public ScheduledTask setRetries(int retries) {
        this.retries = Math.max(0, retries);
        return this;
    }

    public int retryIntervalMinutes() {
        return retryIntervalMinutes;
    }

    public ScheduledTask setRetryIntervalMinutes(int minutes) {
        this.retryIntervalMinutes = Math.max(1, minutes);
        return this;
    }

    public List<Step> steps() {
        return steps;
    }

    public ScheduledTask setSteps(List<Step> steps) {
        this.steps = new ArrayList<>(steps);
        return this;
    }

    /**
     * 从某个时刻算起，下一次该在什么时候跑。
     *
     * <p>用本机时区。跨时区的调度要连时区一起存，这里没做——
     * 与其给个可能算错的时区选项，不如明确只按本机时间。
     */
    public LocalDateTime nextRunAfter(LocalDateTime from) {
        if (!enabled) {
            return null;
        }
        switch (trigger) {
            case INTERVAL:
                return from.plusMinutes(intervalMinutes);
            case WEEKLY: {
                LocalDateTime candidate = from.toLocalDate().atTime(atTime);
                while (candidate.getDayOfWeek() != onDay || !candidate.isAfter(from)) {
                    candidate = candidate.plusDays(1);
                }
                return candidate;
            }
            case DAILY:
            default: {
                LocalDateTime candidate = from.toLocalDate().atTime(atTime);
                return candidate.isAfter(from) ? candidate : candidate.plusDays(1);
            }
        }
    }

    /** 接下来几次的时间，界面上给人核对用——写错的调度往往要等到没跑才发现。 */
    public List<LocalDateTime> nextRuns(LocalDateTime from, int count) {
        List<LocalDateTime> out = new ArrayList<>();
        LocalDateTime cursor = from;
        for (int i = 0; i < count; i++) {
            LocalDateTime next = nextRunAfter(cursor);
            if (next == null) {
                break;
            }
            out.add(next);
            cursor = next;
        }
        return out;
    }

    /** 人话描述这个调度。 */
    public String describeSchedule() {
        return switch (trigger) {
            case DAILY -> "每天 " + atTime;
            case WEEKLY -> "每周" + weekdayName(onDay) + " " + atTime;
            case INTERVAL -> "每隔 " + intervalMinutes + " 分钟";
        };
    }

    private static String weekdayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }
}
