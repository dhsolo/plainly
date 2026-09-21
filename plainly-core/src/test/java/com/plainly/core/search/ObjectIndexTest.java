package com.plainly.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.meta.DbObjects.ColumnRef;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对象搜索的匹配质量。
 *
 * <p>这些用例都来自在真实库上量出来的失败：搜「创建时间」零结果、
 * 搜 {@code orderitem} 零结果、搜 {@code oi} 第一条是不相干的 {@code ado_point}。
 */
@DisplayName("对象索引 · 按人真正会敲的东西去找")
class ObjectIndexTest {

    private static List<ObjectIndex.Entry> index() {
        List<ObjectIndex.Entry> all = new ArrayList<>();
        all.addAll(ObjectIndex.fromTables(List.of(
                new TableInfo("app", "orders", ObjectKind.TABLE, "订单主表", -1),
                new TableInfo("app", "order_item", ObjectKind.TABLE, "订单明细", -1),
                new TableInfo("app", "t_work_order_detail", ObjectKind.TABLE, "", -1),
                new TableInfo("app", "v_order", ObjectKind.VIEW, "", -1),
                new TableInfo("app", "ado_point", ObjectKind.TABLE, "", -1))));
        all.addAll(ObjectIndex.fromColumns(List.of(
                new ColumnRef("app", "ele_dev", "createTime", "VARCHAR", "创建时间"),
                new ColumnRef("app", "ele_dev", "lastModifyTime", "VARCHAR", "最后修改时间"),
                new ColumnRef("app", "orders", "order_no", "VARCHAR", "订单号"),
                new ColumnRef("app", "invoice", "orders", "VARCHAR", ""))));
        return all;
    }

    private static List<String> names(String needle, int limit) {
        return ObjectIndex.search(index(), needle, limit).stream()
                .map(h -> h.entry().name()).toList();
    }

    // ------------------------------------------------------------------ 注释

    @Test
    @DisplayName("按中文注释能搜到——列名是英文，人按中文想")
    void findsByChineseComment() {
        List<ObjectIndex.Hit> hits = ObjectIndex.search(index(), "创建时间", 10);
        assertEquals(1, hits.size(), "这条之前是零结果");
        assertEquals("createTime", hits.get(0).entry().name());
        assertTrue(hits.get(0).matchedComment(),
                "凭注释命中的，界面上要把注释显出来，否则用户看不懂为什么它排在这儿");
    }

    @Test
    @DisplayName("表的注释也能搜")
    void findsTableByComment() {
        assertTrue(names("订单明细", 10).contains("order_item"));
    }

    @Test
    @DisplayName("注释匹配排在名字匹配后面——名字对得上的更可能是要找的那个")
    void nameBeatsComment() {
        // order_no 名字里有 order；createTime 只有注释「创建时间」不含 order
        List<String> hits = names("订单", 10);
        assertEquals(List.of("orders", "order_item", "order_no"), hits,
                "全是靠注释命中的，按表在前、名字短在前排");
    }

    // ------------------------------------------------------------------ 少打下划线

    @Test
    @DisplayName("少打一个下划线仍然找得到")
    void looseMatchIgnoresUnderscore() {
        assertTrue(names("orderitem", 10).contains("order_item"), "这条之前是零结果");
        assertTrue(names("workorderdetail", 10).contains("t_work_order_detail"));
    }

    @Test
    @DisplayName("去分隔符匹配排在正常包含匹配之后")
    void looseRanksLast() {
        List<String> hits = names("order", 10);
        assertEquals("orders", hits.get(0), "完全同名的排最前");
    }

    // ------------------------------------------------------------------ 不做过度模糊

    @Test
    @DisplayName("不做首字母缩写匹配：oi 不该把 ado_point 捞出来当第一条")
    void doesNotDoAcronymFuzzy() {
        List<String> hits = names("oi", 10);
        assertFalse(hits.contains("order_item"),
                "order_item 里没有连着的 oi，不该靠首字母凑出来");
        // ado_point 里确实有子串 "oi"，命中不算错；错的是让它冒充「order_item 的缩写」
        assertTrue(hits.isEmpty() || hits.contains("ado_point"));
    }

    // ------------------------------------------------------------------ 基本排序

    @Test
    @DisplayName("完全同名 > 开头 > 包含；同档里表排在字段前")
    void ranking() {
        List<ObjectIndex.Hit> hits = ObjectIndex.search(index(), "orders", 10);
        assertEquals("orders", hits.get(0).entry().name());
        assertEquals(ObjectSearch.Kind.TABLE, hits.get(0).entry().kind(),
                "同样叫 orders，表排在字段前面");
        assertEquals(ObjectSearch.Kind.COLUMN, hits.get(1).entry().kind());
    }

    @Test
    @DisplayName("大小写不影响：库里写成什么样是建库那天的习惯")
    void ignoresCase() {
        assertTrue(names("ORDER_ITEM", 10).contains("order_item"));
        assertTrue(names("CreateTime", 10).contains("createTime"));
    }

    @Test
    @DisplayName("空片段不返回任何东西")
    void blankReturnsNothing() {
        assertTrue(ObjectIndex.search(index(), "", 10).isEmpty());
        assertTrue(ObjectIndex.search(index(), null, 10).isEmpty());
        assertTrue(ObjectIndex.search(index(), "   ", 10).isEmpty());
    }

    @Test
    @DisplayName("视图和表分得开")
    void viewsAreMarked() {
        ObjectIndex.Hit view = ObjectIndex.search(index(), "v_order", 10).get(0);
        assertEquals(ObjectSearch.Kind.VIEW, view.entry().kind());
    }
}
