package com.plainly.core.security;

import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户与权限的<b>查看</b>。
 *
 * <h2>为什么这一版只读不写</h2>
 * 「谁能动这张表」是排查线上问题时最常问的一件事，而它现在完全看不到——
 * 得另开一个客户端去 {@code SHOW GRANTS}。查看解决的正是这个高频需求。
 *
 * <p>改权限则是另一回事：MySQL 的 {@code GRANT ... ON *.* TO} 和 8.0 的角色、
 * PostgreSQL 的 role/GRANT/默认权限、Oracle 的 profile 与系统权限，
 * 三家的模型差得很远。而这是<b>发错一条就可能让生产库上的应用连不上</b>的地方——
 * 拿一个没有实例验证过的方言层去发 GRANT，比不提供这个功能危险得多。
 * 所以这一版明说只读。
 *
 * <h2>权限不足是常态，不是异常</h2>
 * 应用账号通常读不到 {@code mysql.user}。那时候要给的是「你这个账号看不到用户列表」，
 * 而不是一条 SQLException 的堆栈——前者用户知道该换个账号，后者只会以为工具坏了。
 */
public final class PrivilegeService {

    /** 一个数据库用户 / 角色。 */
    public record User(String name, String host, String note) {

        /** 界面上显示的完整身份。MySQL 的用户是「名字 + 来源主机」两截，缺一不可。 */
        public String display() {
            return host == null || host.isBlank() ? name : name + "@" + host;
        }
    }

    /** 一条授权。{@code scope} 是它作用在什么上，{@code detail} 是权限本身。 */
    public record Grant(String scope, String detail) {
    }

    private PrivilegeService() {
    }

    /** 这一家能不能查用户；能就返回 null。 */
    public static String unsupportedReason(DbConnection conn) {
        switch (conn.config().type()) {
            case MYSQL:
            case POSTGRESQL:
                return null;
            default:
                return conn.config().type().displayName()
                        + " 这一版没接用户与权限查看。各家的权限模型差别很大，"
                        + "没有真实实例验证过的查询，读出来的东西不可信";
        }
    }

    // ------------------------------------------------------------------ 用户

    public static List<User> listUsers(DbConnection conn) {
        String reason = unsupportedReason(conn);
        if (reason != null) {
            throw new DbException(reason);
        }
        switch (conn.config().type()) {
            case MYSQL:
                return mysqlUsers(conn);
            case POSTGRESQL:
            default:
                return postgresUsers(conn);
        }
    }

    private static List<User> mysqlUsers(DbConnection conn) {
        List<User> out = new ArrayList<>();
        try {
            QueryResult r = conn.execute(
                    "SELECT User, Host FROM mysql.user ORDER BY User, Host", 500);
            for (Row row : r.rows()) {
                out.add(new User(row.get(0), row.get(1), ""));
            }
            return out;
        } catch (RuntimeException e) {
            // 应用账号读不到 mysql.user 是常态。退一步问 information_schema——
            // 那张视图只列出「当前账号看得见的」授权，虽然不全，但比一条报错有用
            try {
                QueryResult r = conn.execute(
                        "SELECT DISTINCT GRANTEE FROM information_schema.USER_PRIVILEGES"
                                + " ORDER BY GRANTEE", 500);
                for (Row row : r.rows()) {
                    out.add(splitGrantee(row.get(0)));
                }
                if (out.isEmpty()) {
                    throw new DbException("读不到用户列表：当前账号没有 mysql 库的读权限");
                }
                return out;
            } catch (DbException inner) {
                throw inner;
            } catch (RuntimeException inner) {
                throw new DbException("读不到用户列表：当前账号没有 mysql 库的读权限，"
                        + "information_schema 也问不出来（" + inner.getMessage() + "）");
            }
        }
    }

    /** {@code 'app'@'%'} 拆成名字和主机。拆不开就整段当名字，不猜。 */
    private static User splitGrantee(String grantee) {
        if (grantee == null) {
            return new User("", "", "");
        }
        int at = grantee.lastIndexOf("'@'");
        if (grantee.startsWith("'") && at > 0 && grantee.endsWith("'")) {
            return new User(grantee.substring(1, at),
                    grantee.substring(at + 3, grantee.length() - 1),
                    "来自 information_schema，可能不全");
        }
        return new User(grantee, "", "来自 information_schema，可能不全");
    }

    private static List<User> postgresUsers(DbConnection conn) {
        List<User> out = new ArrayList<>();
        QueryResult r = conn.execute(
                "SELECT rolname, rolsuper, rolcanlogin, rolvaliduntil"
                        + " FROM pg_roles ORDER BY rolname", 500);
        for (Row row : r.rows()) {
            List<String> notes = new ArrayList<>();
            if ("true".equalsIgnoreCase(row.get(1))) {
                notes.add("超级用户");
            }
            if (!"true".equalsIgnoreCase(row.get(2))) {
                // 不能登录的是「组角色」。分不清这一点，会以为一堆用户密码丢了
                notes.add("不能登录（组角色）");
            }
            if (row.get(3) != null && !row.get(3).isBlank()) {
                notes.add("有效期至 " + row.get(3));
            }
            out.add(new User(row.get(0), "", String.join(" · ", notes)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 一个用户手上有哪些权限。
     *
     * <p>读不到时抛出带原因的异常，由界面照原样显示——把「没权限看」显示成
     * 「这个用户没有任何权限」，是这里最容易犯也最误导人的错。
     */
    public static List<Grant> grantsOf(DbConnection conn, User user) {
        switch (conn.config().type()) {
            case MYSQL:
                return mysqlGrants(conn, user);
            case POSTGRESQL:
                return postgresGrants(conn, user);
            default:
                throw new DbException(unsupportedReason(conn));
        }
    }

    private static List<Grant> mysqlGrants(DbConnection conn, User user) {
        String who = "'" + mysqlLiteral(user.name()) + "'@'"
                + mysqlLiteral(user.host() == null || user.host().isBlank() ? "%" : user.host())
                + "'";
        List<Grant> out = new ArrayList<>();
        QueryResult r = conn.execute("SHOW GRANTS FOR " + who, 500);
        for (Row row : r.rows()) {
            String text = row.get(0);
            out.add(new Grant(scopeOfMysqlGrant(text), text));
        }
        return out;
    }

    /** 从一条 GRANT 语句里认出它作用在哪儿：{@code ... ON `db`.`t` TO ...} 里的中间那段。 */
    private static String scopeOfMysqlGrant(String text) {
        if (text == null) {
            return "";
        }
        int on = text.indexOf(" ON ");
        int to = text.indexOf(" TO ", on < 0 ? 0 : on);
        if (on < 0 || to < 0 || to <= on) {
            return "";
        }
        return text.substring(on + 4, to).trim();
    }

    private static List<Grant> postgresGrants(DbConnection conn, User user) {
        String who = "'" + sqlLiteral(user.name()) + "'";
        List<Grant> out = new ArrayList<>();

        // 属于哪些角色。PG 的权限大量通过角色继承而来，只看表权限会漏掉一大半
        try {
            QueryResult roles = conn.execute(
                    "SELECT r.rolname FROM pg_auth_members m"
                            + " JOIN pg_roles r ON r.oid = m.roleid"
                            + " JOIN pg_roles u ON u.oid = m.member"
                            + " WHERE u.rolname = " + who + " ORDER BY r.rolname", 200);
            for (Row row : roles.rows()) {
                out.add(new Grant("角色成员", "属于角色 " + row.get(0)));
            }
        } catch (RuntimeException ignored) {
            // 读不到角色关系不影响下面的表权限
        }

        QueryResult r = conn.execute(
                "SELECT table_schema, table_name, privilege_type"
                        + " FROM information_schema.table_privileges"
                        + " WHERE grantee = " + who
                        + " ORDER BY table_schema, table_name, privilege_type", 2000);
        for (Row row : r.rows()) {
            out.add(new Grant(row.get(0) + "." + row.get(1), row.get(2)));
        }
        if (out.isEmpty()) {
            out.add(new Grant("", "没有直接授予的表权限。"
                    + "注意 PostgreSQL 的权限也可能来自 PUBLIC 或所属角色，这里看不到那些"));
        }
        return out;
    }

    // ------------------------------------------------------------------ 字面量

    /**
     * MySQL 字符串字面量里的转义。
     *
     * <p>用户名出自我们自己发的查询，但这条语句拼不了占位符
     * （{@code SHOW GRANTS FOR ?} 不成立），所以还是老老实实转义。
     * MySQL 默认把反斜杠当转义符，只处理单引号是不够的。
     */
    private static String mysqlLiteral(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "''");
    }

    /** 标准 SQL 字符串字面量：只有单引号需要翻倍。 */
    private static String sqlLiteral(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
