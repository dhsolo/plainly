package com.plainly.core.store;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * 本机库的失效策略：自动记下来的东西留多久。
 *
 * <h2>分界线在「谁记的」</h2>
 * <ul>
 *   <li><b>自动记的</b>——查询历史、任务运行记录。它们不问自来，因此必须有上限：
 *       一个每五分钟跑一次的计划任务，一年能写十万行运行记录，而其中有价值的
 *       只有最近那几十条和失败的那几条；</li>
 *   <li><b>手动留的</b>——收藏、保存的标签页。是人特意按下去的，一条都不能自动删。
 *       用户按「收藏」的意思就是「别弄丢它」，工具替他清理等于毁约。</li>
 * </ul>
 *
 * <h2>为什么同时卡条数和天数</h2>
 * 只卡条数：一台闲置的机器上，三年前的十条记录会一直躺着，翻历史时净是化石。
 * 只卡天数：一次批量跑几万条语句，当天就能把库撑大，而那天并没有过去。
 * 两条都卡，才是「最近、且不太多」。
 *
 * <h2>删了还得回收</h2>
 * SQLite 删行不会让文件变小，空出来的页留着复用——所以文件只增不减，
 * 用户看到的仍是一个越来越大的 plainly.db。删够多了就 VACUUM 一次把它压回去。
 */
public final class Retention {

    /** 自动记录保留的天数。 */
    public static final int DAYS = 30;

    /** 查询历史保留的条数上限，收藏的不计入也不受限。 */
    public static final int HISTORY_ROWS = 500;

    /**
     * 每个任务保留的运行记录条数。
     *
     * <p>按任务分别算，而不是全局取最近 N 条：否则一个每分钟跑的任务，
     * 会把另一个每周跑一次的任务的记录整个挤没——而后者恰恰是更需要回头查的那个。
     */
    public static final int RUNS_PER_TASK = 200;

    /** 删够这么多行才值得回收文件空间。VACUUM 要重写整个库，不能删一行做一次。 */
    private static final int VACUUM_THRESHOLD = 200;

    private Retention() {
    }

    /**
     * 扫一遍并清理，返回删掉的行数。
     *
     * <p>失败只是没清成，不该影响调用方——它多半正在做一件用户看得见的事
     * （执行完一条 SQL、跑完一个任务），为了一次清理失败去打断它是不划算的。
     */
    public static int sweep(LocalStore store) {
        String cutoff = LocalDateTime.now().minusDays(DAYS).toString();
        int deleted = 0;
        deleted += pruneHistoryByAge(store, cutoff);
        deleted += pruneHistoryByCount(store);
        deleted += pruneRunsByAge(store, cutoff);
        deleted += pruneRunsByCount(store);
        if (deleted >= VACUUM_THRESHOLD) {
            vacuum(store);
        }
        return deleted;
    }

    /** 过期的非收藏历史。 */
    private static int pruneHistoryByAge(LocalStore store, String cutoff) {
        return update(store, "DELETE FROM query_history WHERE favorite = 0 AND executed_at < ?",
                ps -> ps.setString(1, cutoff));
    }

    /** 超出条数上限的非收藏历史。 */
    private static int pruneHistoryByCount(LocalStore store) {
        return update(store,
                "DELETE FROM query_history WHERE favorite = 0 AND id NOT IN"
                        + " (SELECT id FROM query_history WHERE favorite = 0"
                        + "  ORDER BY executed_at DESC LIMIT ?)",
                ps -> ps.setInt(1, HISTORY_ROWS));
    }

    private static int pruneRunsByAge(LocalStore store, String cutoff) {
        return update(store, "DELETE FROM task_runs WHERE started_at < ?",
                ps -> ps.setString(1, cutoff));
    }

    /** 每个任务只留最近若干条。相关子查询里带 LIMIT，SQLite 支持。 */
    private static int pruneRunsByCount(LocalStore store) {
        return update(store,
                "DELETE FROM task_runs WHERE id NOT IN"
                        + " (SELECT id FROM task_runs AS keep"
                        + "  WHERE keep.task_id = task_runs.task_id"
                        + "  ORDER BY started_at DESC LIMIT ?)",
                ps -> ps.setInt(1, RUNS_PER_TASK));
    }

    /** 把删空的页还给文件系统。 */
    private static void vacuum(LocalStore store) {
        try (Statement st = store.connection().createStatement()) {
            st.executeUpdate("VACUUM");
        } catch (SQLException ignored) {
            // 压缩失败无伤大雅：数据已经删了，只是文件暂时没缩
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static int update(LocalStore store, String sql, Binder binder) {
        try (PreparedStatement ps = store.connection().prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException ignored) {
            // 见 sweep 的注释
            return 0;
        }
    }

    /** 界面上说明策略用的一句话。写死在这里，免得改了常数忘了改文案。 */
    public static String describe() {
        return "自动保留最近 " + DAYS + " 天、至多 " + HISTORY_ROWS + " 条，收藏的不受限制";
    }
}
