import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 达梦上「建完触发器，列表里看不到」——到底是哪一步断的。
 *
 * <h2>要回答的问题</h2>
 * 本工具查触发器用的是从 Oracle 继承来的那条语句：
 * {@code SELECT ... FROM ALL_TRIGGERS WHERE TABLE_OWNER = ? AND TABLE_NAME = ?}。
 * 它在 DM 上失败的方式可能有三种，而三种在界面上长得一模一样（都是空列表）：
 * <ol>
 *   <li>视图或列名对不上 —— 语句直接报错，被上层吞掉；</li>
 *   <li>视图在、列也在，但<b>过滤条件匹配不上</b> —— 比如 DM 记的属主和我们传的大小写不同，
 *       或者它把 {@code TABLE_OWNER} 记成了别的东西；</li>
 *   <li>触发器压根没建成 —— 那 DDL 那一步就该报错了，可用户说没报。</li>
 * </ol>
 *
 * <p>所以这里<b>不猜</b>：把 ALL_TRIGGERS 的列名、不带过滤的全表内容、
 * 以及几种候选视图都问一遍，拿事实说话。
 *
 * <h2>只读</h2>
 * 全程只发 SELECT，不建不改不删任何东西。口令从本机凭据库取，<b>不打印</b>。
 *
 * <pre>
 * java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/DmTriggerProbe.java "连接名" 模式名 表名
 * </pre>
 * 不给参数时列出本机所有达梦连接的名字。
 */
public class DmTriggerProbe {

    public static void main(String[] args) throws Exception {
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());

            List<ConnectionConfig> dm = new ArrayList<>();
            for (ConnectionConfig c : registry.listAll()) {
                if (c.type() == DbType.DM) {
                    dm.add(c);
                }
            }
            if (dm.isEmpty()) {
                System.out.println("本机没有保存达梦连接。");
                return;
            }
            if (args.length < 1) {
                System.out.println("用法：DmTriggerProbe \"连接名\" [模式名 表名]");
                System.out.println("只给连接名时，查这个库里所有的触发器。");
                System.out.println("本机的达梦连接：");
                dm.forEach(c -> System.out.println("  " + c.name()));
                return;
            }

            String wanted = args[0];
            // 不给模式和表名时也要能用：出问题的时候，人往往只记得「刚在哪个库上建的」，
            // 而这里真正要回答的是「这个库里到底有没有这条触发器」——不需要表名
            String schema = args.length > 1 ? args[1] : null;
            String table = args.length > 2 ? args[2] : null;
            ConnectionConfig config = dm.stream()
                    .filter(c -> c.name().equals(wanted))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                System.out.println("找不到名为 " + wanted + " 的达梦连接。");
                return;
            }

            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                System.out.println("已连接：" + db.serverVersion());
                System.out.println("目标：" + (table == null ? "（整库）" : schema + "." + table));
                System.out.println();

                if (table != null) {
                    // 本工具此刻实际会发出去的那条语句
                    String current = db.dialect().triggersQuery(schema, table);
                    System.out.println("== 一、当前用的语句");
                    System.out.println(current);
                    run(db, current);
                    System.out.println();
                }

                System.out.println("== 二、ALL_TRIGGERS 有哪些列");
                run(db, "SELECT * FROM ALL_TRIGGERS WHERE 1 = 0");

                System.out.println();
                System.out.println("== 三、ALL_TRIGGERS 里到底有些什么");
                run(db, "SELECT TRIGGER_NAME, TABLE_OWNER, TABLE_NAME, OWNER FROM ALL_TRIGGERS"
                        + (table == null ? ""
                                : " WHERE UPPER(TABLE_NAME) = UPPER('" + table + "')"));

                System.out.println();
                System.out.println("== 四、USER_TRIGGERS（只看当前用户自己的）");
                run(db, "SELECT TRIGGER_NAME, TABLE_NAME FROM USER_TRIGGERS"
                        + (table == null ? ""
                                : " WHERE UPPER(TABLE_NAME) = UPPER('" + table + "')"));

                System.out.println();
                System.out.println("== 五、DM 自己的系统表 SYSOBJECTS（TYPE$ = 'TRIG'）");
                run(db, "SELECT NAME, PID, SCHID FROM SYS.SYSOBJECTS WHERE TYPE$ = 'TRIG'");

                System.out.println();
                System.out.println("== 五之二、改用 TRIGGERING_TYPE 之后，四列各是什么值");
                run(db, "SELECT TRIGGER_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, TRIGGER_BODY"
                        + " FROM ALL_TRIGGERS"
                        + (table == null ? ""
                                : " WHERE UPPER(TABLE_NAME) = UPPER('" + table + "')"));

                System.out.println();
                System.out.println("== 五之三、走应用完全相同的代码路径");
                // 前面几条是我手写的 SQL，证明不了「界面上那一条路能不能走通」。
                // 这一段调的就是界面调的那两个方法，传的也是界面从树上拿到的那些字符串
                for (var s : db.listSchemas()) {
                    for (var t : db.listTables(s.name())) {
                        if (!t.name().equalsIgnoreCase("ss")) {
                            continue;
                        }
                        System.out.println("  树上的库名=[" + s.name() + "] 表名=[" + t.name()
                                + "] 种类=" + t.kind());
                        var listing = db.listTriggersDetailed(s.name(), t.name());
                        System.out.println("    实际发出的语句：" + listing.query());
                        System.out.println("    问题：" + listing.problem());
                        System.out.println("    取回条数：" + listing.triggers().size());
                        listing.triggers().forEach(one -> System.out.println(
                                "      [" + one.name() + "] 分类=" + one.category()));
                    }
                }

                System.out.println();
                System.out.println("== 六、当前连接看到的模式与用户");
                run(db, "SELECT SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID), USER FROM DUAL");
            }
        }
    }

    /** 跑一条查询，把列名和前若干行原样打出来；失败就把错误原样打出来。 */
    private static void run(DbConnection db, String sql) {
        if (sql == null) {
            System.out.println("  （这一家没有对应的语句）");
            return;
        }
        try {
            var result = db.execute(sql, 50);
            StringBuilder head = new StringBuilder("  列：");
            result.columns().forEach(c -> head.append(c.name()).append("  "));
            System.out.println(head);
            if (result.rows().isEmpty()) {
                System.out.println("  （0 行）");
                return;
            }
            result.rows().forEach(row -> {
                StringBuilder line = new StringBuilder("  ");
                for (int i = 0; i < result.columns().size(); i++) {
                    String v = row.get(i);
                    // 触发器体可能几十行，只取开头，免得刷屏
                    if (v != null && v.length() > 60) {
                        v = v.substring(0, 60).replace('\n', ' ') + "…";
                    }
                    line.append('[').append(v).append("] ");
                }
                System.out.println(line);
            });
        } catch (RuntimeException e) {
            System.out.println("  失败：" + e.getMessage());
        }
    }
}
