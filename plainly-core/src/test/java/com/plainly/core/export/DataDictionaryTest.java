package com.plainly.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.Row;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据字典的渲染。
 *
 * <p>字典是要发出去的文件，所以这里守的都是「别把文档弄坏」：
 * 注释里出现 {@code <} 会不会把 HTML 从那里开始整段吃掉、
 * 出现 {@code |} 会不会把 Markdown 表格撑散。这两种字符在真实的库里
 * 一点也不罕见（{@code price < 100}、{@code 状态：A|B|C}）。
 */
@DisplayName("数据字典 · 渲染")
class DataDictionaryTest {

    private static DataDictionary.TableDoc doc() {
        List<ColumnInfo> columns = new ArrayList<>();
        columns.add(new ColumnInfo("id", "BIGINT", TypeCategory.INTEGER, 19, 0,
                false, true, true, null, "主键", 1));
        columns.add(new ColumnInfo("price", "DECIMAL", TypeCategory.EXACT_NUMERIC, 38, 10,
                true, false, false, "0.00", "限价 price < 100 & 折扣", 2));
        columns.add(new ColumnInfo("state", "VARCHAR", TypeCategory.STRING, 8, 0,
                false, false, false, "'A'", "状态：A|B|C", 3));

        TableStructure structure = new TableStructure(
                new TableInfo("PUBLIC", "orders", ObjectKind.TABLE, "订单表 <主表>", 5006),
                columns,
                List.of(new IndexInfo("PK_ORDERS", List.of("id"), true, true)));
        return new DataDictionary.TableDoc(structure, List.of());
    }

    @Test
    @DisplayName("HTML：注释里的尖括号和 & 要转义，不能把文档吃掉")
    void htmlEscapes() {
        String html = DataDictionary.renderHtml("字典", "PUBLIC", List.of(doc()));

        assertTrue(html.contains("订单表 &lt;主表&gt;"), "表注释里的尖括号要转义");
        assertTrue(html.contains("price &lt; 100 &amp; 折扣"), "字段注释里的也要");
        // 转义漏了的话，这一段会原样出现在文档里，从这里开始的排版就全乱了
        assertFalse(html.contains("<主表>"), html.substring(0, Math.min(400, html.length())));
    }

    @Test
    @DisplayName("Markdown：注释里的竖线要转义，否则整行表格散架")
    void markdownEscapesPipes() {
        String md = DataDictionary.renderMarkdown("字典", "PUBLIC", List.of(doc()));

        assertTrue(md.contains("状态：A\\|B\\|C"), "竖线要转义：" + md);
        assertTrue(md.contains("| `id` |"), "字段行要在");
        assertTrue(md.contains("`PK_ORDERS`"), "索引要列出来");
    }

    @Test
    @DisplayName("Markdown：目录里的锚点和小标题对得上")
    void markdownAnchorsMatch() {
        String md = DataDictionary.renderMarkdown("字典", "PUBLIC", List.of(doc()));
        assertTrue(md.contains("- [orders](#orders)"), md);
        assertTrue(md.contains("## orders"), md);
    }

    @Test
    @DisplayName("扁平表一行一个字段，序号从 1 开始")
    void flatRowsAreOneRowPerColumn() {
        RowSource source = DataDictionary.asRows("PUBLIC", List.of(doc()));
        List<Row> rows = new ArrayList<>();
        source.forEach(rows::add);

        assertEquals(3, rows.size(), "三个字段就是三行");
        assertEquals(11, source.columns().size());
        assertEquals("PUBLIC", rows.get(0).get(0));
        assertEquals("orders", rows.get(0).get(1));
        assertEquals("1", rows.get(0).get(3));
        assertEquals("id", rows.get(0).get(4));
        assertEquals("是", rows.get(0).get(7), "id 是主键");
        assertEquals("3", rows.get(2).get(3), "序号要连着数");
    }

    @Test
    @DisplayName("扁平表的列一律是字符串——序号不该在 xlsx 里变成能求和的数字")
    void flatColumnsAreAllText() {
        RowSource source = DataDictionary.asRows("PUBLIC", List.of(doc()));
        source.columns().forEach(c ->
                assertEquals(TypeCategory.STRING, c.category(), c.name() + " 应当声明为字符串"));
    }
}
