import com.plainly.app.view.sql.SqlContext;
import com.plainly.core.sql.SqlScopes;
import java.util.List;

/**
 * 作用域感知补全的探针。
 *
 * <p>不开界面：这一层的对错全在 {@link SqlContext} 的解析上，
 * 而它是纯逻辑。把几段真实写法喂进去，打印每个位置解析出来的东西。
 *
 * <p>每段 SQL 里用 {@code |} 标出光标。重点看的是<b>该解析不出来的时候
 * 是不是真的解析不出来</b>——补错了比补不出来危险得多。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ScopeProbe.java</pre>
 */
public class ScopeProbe {

    public static void main(String[] args) {
        check("外层引用子查询里的别名（应当解析不出来）",
                "SELECT * FROM orders o WHERE o.user_id IN (SELECT c.id FROM customers c) AND c.|",
                "c");

        check("子查询里引用外层别名（相关子查询，应当解析得出）",
                "SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = o.|)",
                "o");

        check("两个子查询各自把不同的表叫 t —— 光标在第一个里",
                "SELECT * FROM a WHERE x IN (SELECT t.id FROM orders t WHERE t.|) "
                        + "AND y IN (SELECT t.id FROM customers t)",
                "t");

        check("同上，光标在第二个里",
                "SELECT * FROM a WHERE x IN (SELECT t.id FROM orders t) "
                        + "AND y IN (SELECT t.id FROM customers t WHERE t.|)",
                "t");

        check("派生表的列来自它的 SELECT 列表",
                "SELECT s.| FROM (SELECT id, amount AS total FROM orders) s",
                "s");

        check("派生表写 SELECT *，如实标注",
                "SELECT s.| FROM (SELECT * FROM orders) s",
                "s");

        check("CTE",
                "WITH recent AS (SELECT id, amount FROM orders) SELECT recent.| FROM recent",
                "recent");

        check("字符串里的括号不算结构",
                "SELECT o.| FROM orders o WHERE o.note = 'a ( b'",
                "o");

        check("EXTRACT(YEAR FROM x) 里的 FROM 不是子句边界",
                "SELECT EXTRACT(YEAR FROM o.created_at) AS y FROM orders o WHERE o.|",
                "o");
    }

    private static void check(String title, String marked, String alias) {
        int caret = marked.indexOf('|');
        String sql = marked.replace("|", "");
        SqlContext ctx = SqlContext.at(sql, caret);

        SqlScopes.Relation r = SqlScopes.resolve(
                SqlScopes.scopeAt(SqlScopes.analyze(sql), caret), alias);

        System.out.println("── " + title);
        System.out.println("   " + marked);
        System.out.println("   限定符 " + alias + " → "
                + (r == null ? "解析不出来（这个位置看不见它）" : describe(r)));
        List<SqlScopes.Relation> visible = ctx.visibleRelations();
        StringBuilder sb = new StringBuilder();
        visible.forEach(v -> sb.append(v.alias()).append(' '));
        System.out.println("   这个位置看得见：" + sb.toString().trim()
                + (ctx.inSubquery() ? "   （光标在子查询里）" : ""));
        System.out.println();
    }

    private static String describe(SqlScopes.Relation r) {
        if (r.kind() == SqlScopes.Kind.TABLE) {
            return "表 " + r.table();
        }
        return r.kind().label() + " · 列 " + r.columns()
                + (r.star() ? " · 还有 SELECT * 带出来的" : "");
    }
}
