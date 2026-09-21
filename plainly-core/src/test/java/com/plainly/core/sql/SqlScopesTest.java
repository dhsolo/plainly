package com.plainly.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 作用域分析。
 *
 * <p>每条测试里的 {@code |} 标出光标的位置——写测试时能一眼看出在问哪儿，
 * 比数下标可靠得多。
 */
@DisplayName("SQL 作用域 · 别名在哪儿有效，就只在哪儿补得出来")
class SqlScopesTest {

    /** 把 {@code |} 当成光标，返回去掉它之后的文本与光标下标。 */
    private static Object[] cursor(String marked) {
        int caret = marked.indexOf('|');
        return new Object[]{marked.replace("|", ""), caret < 0 ? 0 : caret};
    }

    private static SqlScopes.Relation resolveAt(String marked, String alias) {
        Object[] c = cursor(marked);
        SqlScopes.Scope root = SqlScopes.analyze((String) c[0]);
        SqlScopes.Scope here = SqlScopes.scopeAt(root, (Integer) c[1]);
        return SqlScopes.resolve(here, alias);
    }

    private static List<SqlScopes.Relation> visibleAt(String marked) {
        Object[] c = cursor(marked);
        SqlScopes.Scope root = SqlScopes.analyze((String) c[0]);
        return SqlScopes.visible(SqlScopes.scopeAt(root, (Integer) c[1]));
    }

    // ------------------------------------------------------------------ 基本

    @Test
    @DisplayName("单表：别名和表名都能当限定符")
    void plainTable() {
        String sql = "SELECT o.| FROM orders o";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertEquals("orders", resolveAt(sql, "orders").table());
    }

    @Test
    @DisplayName("JOIN 的每张表都看得见，带库名限定的只留表名")
    void joins() {
        String sql = "SELECT | FROM shop.orders o JOIN shop.customers AS c ON c.id = o.user_id";
        assertEquals("shop.orders", resolveAt(sql, "o").table());
        assertEquals("shop.customers", resolveAt(sql, "c").table());
        // 带库名的那份留着（界面上要显示），查元数据时用 simpleTable()
        assertEquals("shop.orders", resolveAt(sql, "orders").table());
        assertEquals("orders", resolveAt(sql, "orders").simpleTable());
    }

    // ------------------------------------------------------------------ 作用域

    @Test
    @DisplayName("子查询里的别名，在外层不该看得见")
    void subqueryAliasIsNotVisibleOutside() {
        String sql = "SELECT o.| FROM orders o "
                + "WHERE o.user_id IN (SELECT c.id FROM customers c)";
        assertNotNull(resolveAt(sql, "o"));
        assertNull(resolveAt(sql, "c"),
                "c 只活在子查询里。在外层把 customers 的字段补出来，写出的 SQL 是跑不通的");
    }

    @Test
    @DisplayName("子查询里能看见外层的别名——相关子查询本来就靠这个")
    void outerAliasIsVisibleInside() {
        String sql = "SELECT * FROM orders o "
                + "WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = o.|)";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertEquals("customers", resolveAt(sql, "c").table());
    }

    @Test
    @DisplayName("两个子查询各自把不同的表叫 t，互不干扰")
    void sameAliasInSiblingScopes() {
        String sql = "SELECT * FROM a "
                + "WHERE x IN (SELECT t.id FROM orders t WHERE t.|) "
                + "AND y IN (SELECT t.id FROM customers t)";
        assertEquals("orders", resolveAt(sql, "t").table(),
                "光标在第一个子查询里，t 就是 orders");

        String second = "SELECT * FROM a "
                + "WHERE x IN (SELECT t.id FROM orders t) "
                + "AND y IN (SELECT t.id FROM customers t WHERE t.|)";
        assertEquals("customers", resolveAt(second, "t").table(),
                "光标在第二个子查询里，t 就是 customers");
    }

    @Test
    @DisplayName("内层同名的遮住外层的")
    void innerShadowsOuter() {
        String sql = "SELECT * FROM orders t WHERE x IN (SELECT t.id FROM customers t WHERE t.|)";
        assertEquals("customers", resolveAt(sql, "t").table());
    }

    // ------------------------------------------------------------------ 派生表

    @Test
    @DisplayName("FROM (SELECT ...) x：x 的字段来自子查询的 SELECT 列表")
    void derivedTableColumns() {
        String sql = "SELECT s.| FROM (SELECT id, amount AS total, user_id FROM orders) s";
        SqlScopes.Relation s = resolveAt(sql, "s");
        assertEquals(SqlScopes.Kind.DERIVED, s.kind());
        assertEquals(List.of("id", "total", "user_id"), s.columns());
        assertFalse(s.star());
        assertNull(s.table(), "派生表不指向某一张具体的表");
    }

    @Test
    @DisplayName("派生表里写 SELECT * 时如实标注，不假装知道有哪些列")
    void derivedTableWithStar() {
        String sql = "SELECT s.| FROM (SELECT * FROM orders) s";
        SqlScopes.Relation s = resolveAt(sql, "s");
        assertTrue(s.star());
        assertTrue(s.columns().isEmpty());
    }

    @Test
    @DisplayName("派生表内部仍然能看见它自己的表")
    void insideDerivedTable() {
        String sql = "SELECT * FROM (SELECT o.| FROM orders o) s";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertNull(resolveAt(sql, "s"), "s 是外层才有的名字，子查询内部还看不见它");
    }

    @Test
    @DisplayName("表达式列没有可靠的名字，就不给它编一个")
    void unnamedExpressionIsSkipped() {
        String sql = "SELECT s.| FROM (SELECT COUNT(*), id FROM orders GROUP BY id) s";
        assertEquals(List.of("id"), resolveAt(sql, "s").columns(),
                "COUNT(*) 的默认列名各家都不一样，编出来换个库就是错的");
    }

    // ------------------------------------------------------------------ CTE

    @Test
    @DisplayName("WITH 定义的名字，在主查询里看得见")
    void cte() {
        String sql = "WITH recent AS (SELECT id, amount FROM orders WHERE id > 100) "
                + "SELECT r.| FROM recent r";
        SqlScopes.Relation r = resolveAt(sql, "r");
        assertEquals(SqlScopes.Kind.TABLE, r.kind(),
                "r 是给 recent 起的别名，先按表引用认出来");

        SqlScopes.Relation recent = resolveAt(sql, "recent");
        assertEquals(SqlScopes.Kind.CTE, recent.kind());
        assertEquals(List.of("id", "amount"), recent.columns());
    }

    @Test
    @DisplayName("多个 CTE 都要认出来")
    void multipleCtes() {
        String sql = "WITH a AS (SELECT id FROM t1), b AS (SELECT name FROM t2) "
                + "SELECT | FROM a JOIN b ON 1=1";
        assertEquals(SqlScopes.Kind.CTE, resolveAt(sql, "a").kind());
        assertEquals(SqlScopes.Kind.CTE, resolveAt(sql, "b").kind());
        assertEquals(List.of("name"), resolveAt(sql, "b").columns());
    }

    // ------------------------------------------------------------------ 干扰项

    @Test
    @DisplayName("字符串里的括号不算结构")
    void parenthesisInsideStringLiteral() {
        String sql = "SELECT o.| FROM orders o WHERE o.note = 'a ( b ) c'";
        assertEquals("orders", resolveAt(sql, "o").table());
    }

    @Test
    @DisplayName("注释里的 FROM 不算数")
    void commentedOutFrom() {
        String sql = "SELECT o.| FROM orders o -- FROM customers c\n";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertNull(resolveAt(sql, "c"));
    }

    @Test
    @DisplayName("IN (1,2,3) 和函数调用不是作用域")
    void nonQueryParenthesesAreNotScopes() {
        String sql = "SELECT COUNT(o.id) FROM orders o WHERE o.status IN ('A','B') AND o.|";
        assertEquals("orders", resolveAt(sql, "o").table());
    }

    @Test
    @DisplayName("EXTRACT(YEAR FROM d) 里的 FROM 不是子句边界，也不该凭空多出一张表")
    void fromInsideFunctionCall() {
        String sql = "SELECT EXTRACT(YEAR FROM o.created_at) AS y, o.id FROM orders o WHERE o.|";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertNull(resolveAt(sql, "created_at"),
                "created_at 是个列名，把它登记成表会跟着补出一堆不存在的东西");

        List<String> aliases = visibleAt(sql).stream()
                .map(SqlScopes.Relation::alias).toList();
        assertEquals(List.of("o", "orders"), aliases);
    }

    @Test
    @DisplayName("TRIM(... FROM s) 同理")
    void trimFromIsNotAClause() {
        String sql = "SELECT TRIM(BOTH ' ' FROM o.name) FROM orders o WHERE o.|";
        assertNull(resolveAt(sql, "name"));
        assertEquals("orders", resolveAt(sql, "o").table());
    }

    @Test
    @DisplayName("加了括号的连接仍然认得出来——那个左括号前面不是函数名")
    void parenthesisedJoinStillWorks() {
        String sql = "SELECT | FROM (orders o JOIN customers c ON c.id = o.user_id)";
        assertEquals("orders", resolveAt(sql, "o").table());
        assertEquals("customers", resolveAt(sql, "c").table());
    }

    @Test
    @DisplayName("解析不出来的限定符返回 null，不从别处捡一个同名的")
    void unknownQualifier() {
        assertNull(resolveAt("SELECT x.| FROM orders o", "x"));
    }

    @Test
    @DisplayName("能看见的关系由内向外列出")
    void visibleOrder() {
        String sql = "SELECT * FROM orders o WHERE x IN (SELECT c.id FROM customers c WHERE |)";
        List<String> aliases = visibleAt(sql).stream().map(SqlScopes.Relation::alias).toList();
        assertEquals("c", aliases.get(0), "内层的排前面");
        assertTrue(aliases.contains("o"));
    }

    @Test
    @DisplayName("递归 CTE 引用自己是合法的，不该被挡掉")
    void recursiveCteCanReferenceItself() {
        String sql = "WITH RECURSIVE tree AS ("
                + "SELECT id, parent_id FROM node WHERE parent_id IS NULL "
                + "UNION ALL SELECT n.id, n.parent_id FROM node n JOIN tree.| ON 1=1) "
                + "SELECT * FROM tree";
        assertNotNull(resolveAt(sql, "tree"),
                "WITH RECURSIVE 的整个意义就在于能引用自己");
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInput() {
        assertTrue(SqlScopes.visible(SqlScopes.analyze("")).isEmpty());
        assertTrue(SqlScopes.visible(SqlScopes.analyze(null)).isEmpty());
    }

    @Test
    @DisplayName("没写完的 SQL 也要能分析——补全本来就发生在写到一半的时候")
    void incompleteSql() {
        String sql = "SELECT o.| FROM orders o WHERE x IN (SELECT c.id FROM customers c";
        assertEquals("orders", resolveAt(sql, "o").table());
    }
}
