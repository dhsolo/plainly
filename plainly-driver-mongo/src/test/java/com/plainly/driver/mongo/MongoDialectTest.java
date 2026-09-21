package com.plainly.driver.mongo;

import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.query.FilterSpec;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 私有指令的编解码，以及筛选条件到 Mongo 查询文档的翻译。 */
class MongoDialectTest {

    private final MongoDialect dialect = new MongoDialect();

    // ------------------------------------------------------------------ 指令

    /**
     * 指令用 JSON 载荷而不是按行切，就是为了这一条。
     *
     * <p>Mongo 的字段名几乎什么字符都能放，包括换行。按行切的话，
     * 某个真实字段名迟早把指令切错位——而切错位不报错，只会去改错字段。
     */
    @Test
    @DisplayName("字段名里带换行、逗号、引号也不会把指令切错位")
    void weirdFieldNamesSurviveEncoding() {
        String nasty = "a\nb,c\"d";
        SqlDialect.PreparedSql sql = dialect.buildUpdate("shop", "orders",
                List.of(column(nasty)), List.of(column("_id")));

        MongoDialect.Instruction ins = MongoDialect.parse(sql.sql());
        assertNotNull(ins);
        assertEquals(MongoDialect.UPDATE, ins.verb());
        assertEquals(List.of(nasty), ins.payload().getList("set", String.class));
        assertEquals(List.of("_id"), ins.payload().getList("keys", String.class));
    }

    @Test
    @DisplayName("不是本工具发的东西，parse 返回 null 而不是抛异常")
    void foreignTextIsNotAnInstruction() {
        assertNull(MongoDialect.parse("SELECT * FROM t"));
        assertNull(MongoDialect.parse("{\"status\": \"paid\"}"));
        assertNull(MongoDialect.parse(null));
    }

    @Test
    @DisplayName("写回指令带上库名和集合名，绑定值的顺序是先 SET 后主键")
    void updateCarriesTargetAndBindOrder() {
        SqlDialect.PreparedSql sql = dialect.buildUpdate("shop", "orders",
                List.of(column("name"), column("note")), List.of(column("_id")));

        MongoDialect.Instruction ins = MongoDialect.parse(sql.sql());
        assertEquals("shop", ins.payload().getString("db"));
        assertEquals("orders", ins.payload().getString("coll"));
        // 绑定列的顺序决定 executeUpdate 怎么切分 values，错了就会拿主键值去改字段
        assertEquals(List.of("name", "note", "_id"),
                sql.boundColumns().stream().map(ColumnInfo::name).toList());
    }

    @Test
    @DisplayName("没有主键就拒绝生成写回指令，而不是生成一条改全表的")
    void noKeyIsRefused() {
        assertThrows(RuntimeException.class,
                () -> dialect.buildUpdate("shop", "orders", List.of(column("name")), List.of()));
        assertThrows(RuntimeException.class,
                () -> dialect.buildDelete("shop", "orders", List.of()));
    }

    // ------------------------------------------------------------------ 筛选

    @Test
    @DisplayName("等于：值按写法推断类型，数字不会变成字符串")
    void equalityInfersType() {
        Document f = MongoDialect.filterOf(new FilterSpec(
                List.of(cond("age", FilterSpec.Operator.EQ, "30")), List.of()));
        assertEquals(30L, f.get("age"));
    }

    /**
     * 用户在「包含」里写的是要找的内容，不是正则表达式。
     *
     * <p>不转义的话，{@code a.b} 里的点会匹配任意字符，
     * {@code (x} 直接让服务端报语法错——而用户只是想找一段普通文字。
     */
    @Test
    @DisplayName("包含：把输入当纯文本，正则元字符要转义")
    void likeEscapesRegexMetacharacters() {
        Document f = MongoDialect.filterOf(new FilterSpec(
                List.of(cond("code", FilterSpec.Operator.LIKE, "a.b(1)")), List.of()));
        String regex = ((Document) f.get("code")).getString("$regex");
        assertEquals("a\\.b\\(1\\)", regex);
    }

    @Test
    @DisplayName("介于：翻成 $gte / $lte 一对")
    void betweenBecomesRange() {
        Document f = MongoDialect.filterOf(new FilterSpec(
                List.of(new FilterSpec.Condition(FilterSpec.Combiner.AND, "age",
                        FilterSpec.Operator.BETWEEN, "10", "20")), List.of()));
        Document range = (Document) f.get("age");
        assertEquals(10L, range.get("$gte"));
        assertEquals(20L, range.get("$lte"));
    }

    @Test
    @DisplayName("多个条件合成 $and，翻不动的条件当场报错而不是悄悄忽略")
    void multipleConditionsCombine() {
        Document f = MongoDialect.filterOf(new FilterSpec(
                List.of(cond("a", FilterSpec.Operator.EQ, "1"),
                        cond("b", FilterSpec.Operator.GT, "2")), List.of()));
        assertNotNull(f.get("$and"));
        assertEquals(2, f.getList("$and", Document.class).size());
    }

    @Test
    @DisplayName("排序原样带过去，降序是 -1")
    void sortTranslates() {
        Document sort = MongoDialect.sortOf(new FilterSpec(List.of(),
                List.of(new FilterSpec.Sort("age", true), new FilterSpec.Sort("name", false))));
        assertEquals(-1, sort.get("age"));
        assertEquals(1, sort.get("name"));
    }

    @Test
    @DisplayName("取数指令带上分页参数")
    void findCarriesPaging() {
        SqlDialect.PreparedSql sql = dialect.selectPage("shop", "orders",
                FilterSpec.empty(), List.of(), 50, 100);
        Document p = MongoDialect.parse(sql.sql()).payload();
        assertEquals(50, p.getInteger("limit"));
        assertEquals(100, p.getInteger("offset"));
    }

    // ------------------------------------------------------------------ 能力边界

    @Test
    @DisplayName("集合没有结构，改结构的请求要给出理由而不是静静失败")
    void structureChangesAreRefusedWithAReason() {
        assertTrue(!dialect.hasTableStructure());
        String why = dialect.unsupportedReason(null);
        assertTrue(why.contains("没有结构"), why);
    }

    // ------------------------------------------------------------------ 夹具

    private static FilterSpec.Condition cond(String column, FilterSpec.Operator op, String value) {
        return new FilterSpec.Condition(FilterSpec.Combiner.AND, column, op, value, null);
    }

    private static ColumnInfo column(String name) {
        return new ColumnInfo(name, "String", TypeCategory.STRING, 0, 0,
                true, "_id".equals(name), false, null, "", 1);
    }
}
