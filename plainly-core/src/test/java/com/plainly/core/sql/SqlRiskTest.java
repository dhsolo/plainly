package com.plainly.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 整表 UPDATE / DELETE 的识别。
 *
 * <p>这里有两类错，代价完全不对等：
 * <ul>
 *   <li><b>误报</b>——把带条件的语句拦下来问一句。用户按个确定，损失一秒；</li>
 *   <li><b>漏报</b>——把不带条件的语句放过去。一张表被清空，没有回滚可按。</li>
 * </ul>
 * 所以下面的用例里，「该拦的拦住了」那几条比「不该拦的没拦」更要紧。
 */
@DisplayName("SQL · 影响面识别")
class SqlRiskTest {

    private SqlRisk.Risk risk(String sql) {
        return SqlRisk.of(0, sql);
    }

    @Test
    @DisplayName("没有 WHERE 的 UPDATE 和 DELETE 都要拦")
    void catchesBareUpdateAndDelete() {
        SqlRisk.Risk update = risk("UPDATE orders SET status = 1");
        assertNotNull(update);
        assertEquals(SqlRisk.Kind.UPDATE_ALL, update.kind());
        assertEquals("orders", update.target(), "要说清楚是哪张表——用户据此判断值不值得按确定");

        SqlRisk.Risk delete = risk("delete from orders");
        assertNotNull(delete);
        assertEquals(SqlRisk.Kind.DELETE_ALL, delete.kind());
        assertEquals("orders", delete.target());
    }

    @Test
    @DisplayName("有 WHERE 就放行，哪怕条件恒真")
    void allowsAnyWhere() {
        assertNull(risk("UPDATE orders SET status = 1 WHERE id = 3"));
        assertNull(risk("DELETE FROM orders WHERE 1 = 1"),
                "WHERE 1=1 同样是整表，但那是用户明确写下的，不是漏写");
    }

    @Test
    @DisplayName("字符串里的 where 不算条件")
    void whereInsideLiteralIsNotACondition() {
        SqlRisk.Risk r = risk("UPDATE t SET note = 'where to go'");
        assertNotNull(r, "把字面量里的 where 当条件，等于放行一条整表更新");
        assertEquals(SqlRisk.Kind.UPDATE_ALL, r.kind());
    }

    @Test
    @DisplayName("注释掉的 where 不算条件")
    void whereInsideCommentIsNotACondition() {
        assertNotNull(risk("DELETE FROM t -- where id = 1"));
        assertNotNull(risk("DELETE FROM t /* WHERE id = 1 */"));
        assertNotNull(risk("DELETE FROM t # where id = 1"));
    }

    @Test
    @DisplayName("子查询里的 WHERE 不算顶层条件")
    void whereInsideSubqueryDoesNotCount() {
        SqlRisk.Risk r = risk("UPDATE t SET x = (SELECT max(v) FROM u WHERE u.k = 1)");
        assertNotNull(r, "被更新的仍是 t 的每一行，子查询的条件管不着它");
        assertEquals("t", r.target());
    }

    @Test
    @DisplayName("子查询之后回到顶层的 WHERE 仍然算数")
    void whereAfterSubqueryStillCounts() {
        assertNull(risk("UPDATE t SET x = (SELECT 1 FROM u WHERE u.k = 1) WHERE t.id = 9"),
                "括号闭合之后深度要退回 0，否则真条件被当成子查询的");
    }

    @Test
    @DisplayName("明确写了 LIMIT 的不拦——影响多少行是用户自己填的数")
    void limitIsAnExplicitBound() {
        assertNull(risk("DELETE FROM logs LIMIT 100"));
        assertNull(risk("UPDATE logs SET seen = 1 ORDER BY id LIMIT 10"));
    }

    @Test
    @DisplayName("查询和插入不拦")
    void ignoresHarmlessStatements() {
        assertNull(risk("SELECT * FROM orders"));
        assertNull(risk("INSERT INTO orders (id) VALUES (1)"));
        assertNull(risk("CREATE TABLE t (id int)"));
    }

    @Test
    @DisplayName("DROP DATABASE 要拦，DROP TABLE 不拦")
    void dropSchemaOnly() {
        SqlRisk.Risk r = risk("DROP DATABASE IF EXISTS shop_prod");
        assertNotNull(r);
        assertEquals(SqlRisk.Kind.DROP_SCHEMA, r.kind());
        assertEquals("shop_prod", r.target(), "IF EXISTS 要跳过去，不然会把 IF 当成库名");

        assertNull(risk("DROP TABLE tmp_import"),
                "迁移脚本里满是 DROP TABLE，每条都问一遍就没人肯看提示了");
    }

    @Test
    @DisplayName("反引号包起来的表名认得出来")
    void quotedIdentifiers() {
        SqlRisk.Risk r = risk("DELETE FROM `orders`");
        assertNotNull(r);
    }

    @Test
    @DisplayName("多表删除报的是 FROM 后面那个真表名")
    void multiTableDelete() {
        SqlRisk.Risk r = risk("DELETE a FROM orders a JOIN users b ON a.uid = b.id");
        assertNotNull(r);
        assertEquals("orders", r.target(), "a 是别名，报出来用户认不出是哪张表");
    }

    @Test
    @DisplayName("一批语句里，返回的下标要对得上原来的位置")
    void scanKeepsPositions() {
        List<SqlRisk.Risk> risks = SqlRisk.scan(List.of(
                "SELECT 1",
                "UPDATE a SET x = 1",
                "DELETE FROM b WHERE id = 1",
                "DELETE FROM c"));
        assertEquals(2, risks.size());
        assertEquals(1, risks.get(0).index());
        assertEquals(3, risks.get(1).index());
        assertEquals("第 4 条 · 整表删除：c", risks.get(1).describe());
    }

    @Test
    @DisplayName("空语句和空白不当成风险")
    void blankIsNotARisk() {
        assertNull(risk(""));
        assertNull(risk("   \n  "));
        assertNull(SqlRisk.of(0, null));
        assertEquals(0, SqlRisk.scan(List.of()).size());
    }
}
