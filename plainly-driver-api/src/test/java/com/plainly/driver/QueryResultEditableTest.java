package com.plainly.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这份查询结果能不能改，以及改了写回哪张表」。
 *
 * <h2>为什么这一条要单独钉住</h2>
 * 判错的两个方向代价完全不对称。判成不可改，用户少一个便利；
 * 判成可改而目标是错的，就是<b>往错的表里写数据</b>。所以每一条放行的理由
 * 都得有测试压着，尤其是那些「看着像单表、其实不是」的情况。
 */
class QueryResultEditableTest {

    @Test
    @DisplayName("同源单表且带主键：可以改，写回目标就是那张表")
    void singleTableWithKeyIsEditable() {
        QueryResult r = of(
                col("id", "shop", "orders", true),
                col("name", "shop", "orders", false));

        assertTrue(r.isEditable());
        assertNull(r.readOnlyReason());
        assertEquals(new QueryResult.Source("shop", "orders"), r.source());
    }

    @Test
    @DisplayName("有表达式列：改了没处回写")
    void expressionColumnBlocksEditing() {
        QueryResult r = of(
                col("id", "shop", "orders", true),
                col("calc", "", "", false));

        assertFalse(r.isEditable());
        assertTrue(r.readOnlyReason().contains("不属于任何表"), r.readOnlyReason());
        assertNull(r.source());
    }

    @Test
    @DisplayName("联了两张表：不知道该写回哪张")
    void twoTablesBlockEditing() {
        QueryResult r = of(
                col("id", "shop", "orders", true),
                col("uid", "shop", "users", false));

        assertFalse(r.isEditable());
        assertTrue(r.readOnlyReason().contains("多张表"), r.readOnlyReason());
    }

    @Test
    @DisplayName("整行都取不到主键：定位不到要改哪一行")
    void withoutKeyBlocksEditing() {
        QueryResult r = of(
                col("name", "shop", "orders", false),
                col("note", "shop", "orders", false));

        assertFalse(r.isEditable());
        assertTrue(r.readOnlyReason().contains("没有主键"), r.readOnlyReason());
    }

    /**
     * 这一条是真正的陷阱。
     *
     * <p>{@code SELECT * FROM s1.t JOIN s2.t} 两边表名<b>一模一样</b>，
     * 只比表名的话会把它当成单表放行，然后把两张表的列一起写进其中一张。
     * 光比表名不够，模式名也得比。
     */
    @Test
    @DisplayName("两个模式里的同名表：同样不能改")
    void sameTableNameInTwoSchemasBlocksEditing() {
        QueryResult r = of(
                col("id", "s1", "t", true),
                col("id", "s2", "t", false));

        assertFalse(r.isEditable(), "同名不同模式不是单表来源");
        assertTrue(r.readOnlyReason().contains("同名表"), r.readOnlyReason());
    }

    @Test
    @DisplayName("驱动不报模式名时照样能改，写回目标的模式留空")
    void blankSchemaStillEditable() {
        QueryResult r = of(
                col("id", "", "orders", true),
                col("name", "", "orders", false));

        assertTrue(r.isEditable(), "模式名报不出来不影响定位——表名和主键都在");
        assertNotNull(r.source());
        assertEquals("", r.source().schema());
        assertEquals("orders", r.source().table());
    }

    @Test
    @DisplayName("不是查询结果（比如 UPDATE 的影响行数）：没有可改的东西")
    void updateCountIsNotEditable() {
        QueryResult r = QueryResult.updated(3, 1, "UPDATE orders SET name = 'x'");
        assertFalse(r.isEditable());
        assertNull(r.source());
    }

    // ------------------------------------------------------------------ 夹具

    private static QueryResult of(ColumnMeta... columns) {
        return QueryResult.of(List.of(columns), List.of(), 1, false, "SELECT ...");
    }

    private static ColumnMeta col(String name, String schema, String table, boolean key) {
        return new ColumnMeta(name, name, "VARCHAR", TypeCategory.STRING, 64, 0,
                true, schema, table, key, false);
    }
}
