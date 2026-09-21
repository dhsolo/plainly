package com.plainly.core.monitor;

import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务器状态。
 *
 * <p>刻意做得很轻：连接数、运行时长、当前会话、几个关键变量。
 * 专业的监控工具（Prometheus、pt-*、pg_stat_statements 的完整分析）做得好得多，
 * 这里只解决「手边这个库现在忙不忙、有没有人卡住」这一个问题。
 *
 * <p>各家能问到的东西差别很大，问不到的就不报——编一个「不支持」的指标显示成 0，
 * 比不显示更糟。
 */
public final class ServerMonitor {

    /** 一条指标。 */
    public record Metric(String name, String value, String note) {
    }

    /** 一个会话。 */
    public record Sessionment(String id, String user, String host, String database,
                              String state, String query, String seconds) {
    }

    /**
     * 一次采样。
     *
     * @param selfId 本工具自己这条连接的会话号；问不出来时为 null。
     *               界面靠它把自己那一行标出来——把自己掐了，
     *               表现是「点了结束会话，然后整个工具的连接断了」
     */
    public record Snapshot(List<Metric> metrics, List<Sessionment> sessions, String problem,
                           String selfId) {

        public Snapshot(List<Metric> metrics, List<Sessionment> sessions, String problem) {
            this(metrics, sessions, problem, null);
        }
    }

    private ServerMonitor() {
    }

    // ------------------------------------------------------------------ 结束会话

    /**
     * 能不能在这一家上结束会话；能就返回 null，不能则返回原因。
     *
     * <p>存在的理由和方言层的 {@code unsupportedReason} 一样：把做不到的事灰掉
     * 却不说为什么，用户只会以为软件坏了。
     */
    public static String killUnsupportedReason(DbConnection conn) {
        switch (conn.config().type()) {
            case MYSQL:
            case POSTGRESQL:
                return null;
            default:
                return conn.config().type().displayName()
                        + " 这一版没接结束会话——各家的语法和所需权限差别很大，"
                        + "没有实例验证过就不发那条语句";
        }
    }

    /**
     * PostgreSQL 才有的「只取消当前查询、保留会话」。
     *
     * <p>MySQL 的 {@code KILL QUERY} 也能做这件事，但它对用户的心智负担不一样：
     * PG 这两个函数是并列的两件事，摆出来正好；MySQL 上多一个按钮，
     * 用户还得先弄明白 KILL 和 KILL QUERY 的差别。
     */
    public static boolean supportsCancelOnly(DbConnection conn) {
        return conn.config().type() == DbType.POSTGRESQL;
    }

    /**
     * 结束一个会话。
     *
     * @param cancelOnly true 表示只取消它正在跑的那条语句，会话本身留着（仅 PostgreSQL）
     * @return 服务端的回执，用于在界面上如实显示成功与否
     */
    public static String kill(DbConnection conn, String sessionId, boolean cancelOnly) {
        // 会话号出自我们自己发的那条查询，本来就该是纯数字。这里再挡一道：
        // 这两条语句拼不了参数（MySQL 的 KILL 不接占位符），万一哪天上游变了，
        // 拼进去的就是一段可执行的 SQL
        if (!isDigits(sessionId)) {
            throw new DbException("会话号不是一个数字：" + sessionId);
        }
        String reason = killUnsupportedReason(conn);
        if (reason != null) {
            throw new DbException(reason);
        }
        switch (conn.config().type()) {
            case MYSQL:
                // KILL 不返回结果集，走 execute 即可；掉线的会话号会报 ERROR 1094
                conn.execute("KILL " + sessionId, 0);
                return "已向 MySQL 发出 KILL " + sessionId;
            case POSTGRESQL:
            default:
                String fn = cancelOnly ? "pg_cancel_backend" : "pg_terminate_backend";
                String ok = conn.scalar("SELECT " + fn + "(" + sessionId + ")");
                // 这两个函数返回布尔：false 表示那个 pid 已经不在了，或者权限不够。
                // 照抄一句「已发出」会让用户以为搞定了，实际什么也没发生
                return "true".equalsIgnoreCase(ok)
                        ? (cancelOnly ? "已取消该会话正在执行的语句" : "已结束会话 " + sessionId)
                        : "服务端拒绝了：会话 " + sessionId + " 可能已经结束，或者当前用户没有权限";
        }
    }

    /** 纯数字。写成循环而不是正则：这一处的意图是「只允许数字」，越直白越好审。 */
    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** 本工具自己这条连接的会话号。问不出来返回 null——它只用于标注，缺了不影响别的。 */
    private static String selfId(DbConnection conn) {
        try {
            switch (conn.config().type()) {
                case MYSQL:
                    return conn.scalar("SELECT CONNECTION_ID()");
                case POSTGRESQL:
                    return conn.scalar("SELECT pg_backend_pid()");
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Snapshot sample(DbConnection conn) {
        switch (conn.config().type()) {
            case MYSQL:
                return mysql(conn);
            case POSTGRESQL:
                return postgres(conn);
            default:
                return new Snapshot(basics(conn), List.of(),
                        conn.config().type().displayName()
                                + " 没有可查询的会话视图，只能给出基本信息");
        }
    }

    /** 任何库都答得上来的那点东西。 */
    private static List<Metric> basics(DbConnection conn) {
        List<Metric> out = new ArrayList<>();
        out.add(new Metric("服务器版本", conn.serverVersion(), ""));
        out.add(new Metric("连接目标", conn.config().host() + ":" + conn.config().port(), ""));
        return out;
    }

    private static Snapshot mysql(DbConnection conn) {
        List<Metric> metrics = basics(conn);
        addStatus(conn, metrics, "Threads_connected", "当前连接数", "");
        addStatus(conn, metrics, "Threads_running", "正在执行的线程", "持续大于核数说明在排队");
        addStatus(conn, metrics, "Uptime", "运行时长（秒）", "");
        addStatus(conn, metrics, "Slow_queries", "慢查询累计", "自上次重启以来");
        addStatus(conn, metrics, "Innodb_row_lock_waits", "行锁等待累计", "涨得快说明有热点行");
        addVariable(conn, metrics, "max_connections", "最大连接数", "");
        addVariable(conn, metrics, "long_query_time", "慢查询阈值（秒）", "");

        List<Sessionment> sessions = new ArrayList<>();
        try {
            QueryResult r = conn.execute(
                    "SELECT ID, USER, HOST, DB, COMMAND, STATE, TIME, INFO"
                            + " FROM information_schema.PROCESSLIST"
                            + " ORDER BY TIME DESC", 200);
            for (Row row : r.rows()) {
                sessions.add(new Sessionment(row.get(0), row.get(1), row.get(2), row.get(3),
                        nz(row.get(4)) + (row.get(5) == null ? "" : " · " + row.get(5)),
                        nz(row.get(7)), nz(row.get(6))));
            }
        } catch (RuntimeException e) {
            return new Snapshot(metrics, List.of(), "读不到会话列表：" + e.getMessage()
                    + "（需要 PROCESS 权限）");
        }
        return new Snapshot(metrics, sessions, null, selfId(conn));
    }

    private static Snapshot postgres(DbConnection conn) {
        List<Metric> metrics = basics(conn);
        addScalar(conn, metrics, "SELECT count(*) FROM pg_stat_activity", "当前连接数", "");
        addScalar(conn, metrics,
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active'",
                "正在执行", "");
        addScalar(conn, metrics,
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                "等锁的会话", "大于零说明有阻塞");
        addScalar(conn, metrics, "SHOW max_connections", "最大连接数", "");
        addScalar(conn, metrics,
                "SELECT date_trunc('second', now() - pg_postmaster_start_time())::text",
                "运行时长", "");

        List<Sessionment> sessions = new ArrayList<>();
        try {
            QueryResult r = conn.execute(
                    "SELECT pid, usename, client_addr, datname, state,"
                            + " extract(epoch from now() - query_start)::int, query"
                            + " FROM pg_stat_activity ORDER BY query_start", 200);
            for (Row row : r.rows()) {
                sessions.add(new Sessionment(row.get(0), row.get(1), nz(row.get(2)),
                        row.get(3), nz(row.get(4)), nz(row.get(6)), nz(row.get(5))));
            }
        } catch (RuntimeException e) {
            return new Snapshot(metrics, List.of(), "读不到会话列表：" + e.getMessage());
        }
        return new Snapshot(metrics, sessions, null, selfId(conn));
    }

    private static void addStatus(DbConnection conn, List<Metric> out, String name,
                                  String label, String note) {
        try {
            QueryResult r = conn.execute(
                    "SHOW GLOBAL STATUS LIKE '" + name + "'", 1);
            if (!r.rows().isEmpty()) {
                out.add(new Metric(label, r.rows().get(0).get(1), note));
            }
        } catch (RuntimeException e) {
            // 权限不足就跳过这一条，不编一个假数字
        }
    }

    private static void addVariable(DbConnection conn, List<Metric> out, String name,
                                    String label, String note) {
        try {
            QueryResult r = conn.execute("SHOW VARIABLES LIKE '" + name + "'", 1);
            if (!r.rows().isEmpty()) {
                out.add(new Metric(label, r.rows().get(0).get(1), note));
            }
        } catch (RuntimeException e) {
            // 同上
        }
    }

    private static void addScalar(DbConnection conn, List<Metric> out, String sql,
                                  String label, String note) {
        try {
            String value = conn.scalar(sql);
            if (value != null) {
                out.add(new Metric(label, value, note));
            }
        } catch (RuntimeException e) {
            // 同上
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
