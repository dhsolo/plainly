import com.plainly.app.view.sql.SqlContext;

/** 补全的限定符解析：哪些写法能认出「这是在补某张表的字段」。 */
public class SqlCtxProbe {
    public static void main(String[] args) {
        String[] cases = {
            "select * from ORDERS where ORDERS.",
            "select * from ORDERS o where o.",
            "select ORDERS. from ORDERS",
            "select * from PUBLIC.ORDERS o where o.",
            "select o. from ORDERS o",
        };
        for (String sql : cases) {
            int caret = sql.indexOf('.') + 1;
            // 取最后一个点之后的位置更贴近真实击键
            caret = sql.lastIndexOf('.') + 1;
            SqlContext ctx = SqlContext.at(sql, caret);
            System.out.println("[" + sql + "]  caret=" + caret);
            System.out.println("    isQualified=" + ctx.isQualified()
                    + "  qualifier=" + ctx.qualifier()
                    + "  word=[" + ctx.word() + "]"
                    + "  resolved=" + (ctx.resolved() == null ? "null"
                        : ctx.resolved().kind() + "/" + ctx.resolved().simpleTable()));
        }
    }
}
