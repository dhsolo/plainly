package com.plainly.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.TypeCategory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 复制到剪贴板。
 *
 * <p>重点只有两件事：NULL 和空字符串不能混成一样，长数值不能在任何一种格式里变形。
 */
@DisplayName("复制 · NULL 和空串分得开，长数值一位不差")
class ClipboardFormatsTest {

    private static final String HUGE = "12345678901234567890.1234567890";

    private static final ColumnMeta ID =
            new ColumnMeta("id", "id", "INTEGER", TypeCategory.INTEGER, 10, 0, false,
                    "shop", "orders", true, true);
    /** BIGINT 能到 19 位，超出 double 的 15 位——它和 INT 不是一回事。 */
    private static final ColumnMeta BIG_ID =
            new ColumnMeta("id", "id", "BIGINT", TypeCategory.INTEGER, 19, 0, false,
                    "shop", "orders", true, true);
    private static final ColumnMeta NOTE =
            new ColumnMeta("note", "note", "VARCHAR", TypeCategory.STRING, 200, 0, true,
                    "shop", "orders", false, false);
    private static final ColumnMeta AMOUNT =
            new ColumnMeta("amount", "amount", "DECIMAL", TypeCategory.EXACT_NUMERIC, 38, 10,
                    true, "shop", "orders", false, false);

    private static final List<ColumnMeta> COLUMNS = List.of(ID, NOTE, AMOUNT);

    /** 第二行故意让 note 是空字符串，第三行让它是 NULL。 */
    private static final List<String[]> ROWS = List.of(
            new String[]{"1", "普通备注", HUGE},
            new String[]{"2", "", "1.50"},
            new String[]{"3", null, null});

    @Test
    @DisplayName("CSV 里 NULL 写成空、空字符串写成一对引号")
    void nullDiffersFromEmptyString() {
        String csv = ClipboardFormats.render(ClipboardFormats.Format.CSV,
                COLUMNS, ROWS, false, "orders");
        String[] lines = csv.split("\n");

        assertEquals("2,\"\",1.50", lines[1], "空字符串要留下痕迹");
        assertEquals("3,,", lines[2], "NULL 什么都不写");
    }

    @Test
    @DisplayName("值里有分隔符或换行时按 RFC4180 加引号，粘出去不错位")
    void quotesWhenNeeded() {
        List<String[]> rows = List.<String[]>of(new String[]{"1", "a,b\n第二行 \"引号\"", "1"});
        String csv = ClipboardFormats.render(ClipboardFormats.Format.CSV,
                COLUMNS, rows, false, "orders");
        assertTrue(csv.contains("\"a,b\n第二行 \"\"引号\"\"\""), "实得：" + csv);
    }

    @Test
    @DisplayName("制表符分隔同样加引号——值里本来就可能有制表符")
    void tsvQuotesToo() {
        List<String[]> rows = List.<String[]>of(new String[]{"1", "左\t右", "1"});
        String tsv = ClipboardFormats.render(ClipboardFormats.Format.TSV,
                COLUMNS, rows, false, "orders");
        assertTrue(tsv.contains("\"左\t右\""), "实得：" + tsv);
    }

    @Test
    @DisplayName("长数值在四种无损格式里都一位不差")
    void keepsPrecisionEverywhere() {
        for (ClipboardFormats.Format f : List.of(
                ClipboardFormats.Format.TSV, ClipboardFormats.Format.CSV,
                ClipboardFormats.Format.JSON, ClipboardFormats.Format.SQL_INSERT)) {
            String text = ClipboardFormats.render(f, COLUMNS, ROWS, true, "orders");
            assertTrue(text.contains(HUGE), f + " 把长数值改掉了：" + text);
            assertFalse(text.contains("1.2345678901234568E"),
                    f + " 出现了科学计数法，说明中间过了一次 double");
        }
    }

    @Test
    @DisplayName("JSON 里超出 double 的数值写成字符串，短整数才写成裸数字")
    void jsonQuotesRiskyNumbers() {
        String json = ClipboardFormats.render(ClipboardFormats.Format.JSON,
                COLUMNS, ROWS, false, "orders");
        assertTrue(json.contains("\"amount\": \"" + HUGE + "\""),
                "38 位的 DECIMAL 必须带引号，否则读的人一 parse 就成了 double：" + json);
        assertTrue(json.contains("\"id\": 1"), "INT 的 1 可以是裸数字：" + json);
        assertTrue(json.contains("\"note\": null"), "NULL 就是 JSON null：" + json);

        // BIGINT 到 19 位就越过 double 的 15 位了，它也得带引号——
        // 「整数一律安全」是个很常见也很贵的误解
        String bigJson = ClipboardFormats.render(ClipboardFormats.Format.JSON,
                List.of(BIG_ID), List.<String[]>of(new String[]{"9007199254740993"}),
                false, "orders");
        assertTrue(bigJson.contains("\"id\": \"9007199254740993\""), bigJson);
    }

    @Test
    @DisplayName("INSERT 里数值不带引号、字符串里的单引号双写")
    void insertLiterals() {
        List<String[]> rows = List.<String[]>of(new String[]{"1", "他说 'hi'", HUGE});
        String sql = ClipboardFormats.render(ClipboardFormats.Format.SQL_INSERT,
                COLUMNS, rows, false, "orders");
        assertTrue(sql.startsWith("INSERT INTO orders (id, note, amount) VALUES ("), sql);
        assertTrue(sql.contains("'他说 ''hi'''"), sql);
        assertTrue(sql.contains(", " + HUGE + ");"), sql);
    }

    @Test
    @DisplayName("INSERT 里的 NULL 是关键字，不是字符串 'NULL'")
    void insertNull() {
        String sql = ClipboardFormats.render(ClipboardFormats.Format.SQL_INSERT,
                COLUMNS, List.<String[]>of(ROWS.get(2)), false, "orders");
        assertTrue(sql.contains("(3, NULL, NULL)"), sql);
    }

    @Test
    @DisplayName("Markdown 转义竖线、把换行压成一行——这是它唯一有损的地方")
    void markdownEscapes() {
        List<String[]> rows = List.<String[]>of(new String[]{"1", "a|b\nc", "1"});
        String md = ClipboardFormats.render(ClipboardFormats.Format.MARKDOWN,
                COLUMNS, rows, true, "orders");
        assertTrue(md.contains("a\\|b c"), md);
        assertTrue(md.contains("*(NULL)*") || !md.contains("(NULL)"), md);
        assertTrue(md.contains(" ---: |"), "数值列右对齐：" + md);
    }
}
