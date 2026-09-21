package com.plainly.core.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON 导入的读取。
 *
 * <p>这里最要紧的两条都不会报错，只会安静地把数据弄坏：
 * <ul>
 *   <li><b>精度</b>——把 JSON 的 number 解析成 double，一个 20 位的订单号进库就变了。
 *       所以数字必须按<b>原文</b>取，一个字符都不动；</li>
 *   <li><b>丢字段</b>——只看第一个对象定表头，后面对象多出来的字段会被整列丢掉。
 *       每一行都导进去了，就是少一列，而界面上什么也不会说。</li>
 * </ul>
 */
@DisplayName("导入 · JSON")
class JsonRowsTest {

    @TempDir
    Path dir;

    private Path write(String json) {
        try {
            Path file = dir.resolve("in-" + System.nanoTime() + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<List<String>> rows(String json) {
        List<List<String>> out = new ArrayList<>();
        JsonRows.forEach(write(json), StandardCharsets.UTF_8, out::add);
        return out;
    }

    @Test
    @DisplayName("对象数组：第一行是字段名，后面是值")
    void objectArray() {
        List<List<String>> rows = rows("[{\"id\":\"1\",\"name\":\"甲\"},"
                + "{\"id\":\"2\",\"name\":\"乙\"}]");
        assertEquals(3, rows.size());
        assertEquals(List.of("id", "name"), rows.get(0));
        assertEquals(List.of("1", "甲"), rows.get(1));
        assertEquals(List.of("2", "乙"), rows.get(2));
    }

    @Test
    @DisplayName("数字按原文取，不经过 double")
    void numbersKeepTheirText() {
        List<List<String>> rows = rows("[{\"amount\":123456789012345678901234567890.5,"
                + "\"rate\":1.0e-7,\"neg\":-42}]");
        assertEquals("123456789012345678901234567890.5", rows.get(1).get(0),
                "解析成 double 就只剩 15 位有效数字，而且没有任何提示");
        assertEquals("1.0e-7", rows.get(1).get(1));
        assertEquals("-42", rows.get(1).get(2));
    }

    @Test
    @DisplayName("后面的对象多出字段时，表头要把它算进去")
    void headerIsTheUnionOfAllKeys() {
        List<List<String>> rows = rows("[{\"a\":\"1\"},{\"a\":\"2\",\"b\":\"x\"}]");
        assertEquals(List.of("a", "b"), rows.get(0),
                "只看第一个对象定表头，b 这一列会被整个丢掉，而且不报错");
        // 第一个对象没有 b，那一格是「没有值」
        assertNull(rows.get(1).get(1));
        assertEquals("x", rows.get(2).get(1));
    }

    @Test
    @DisplayName("字段顺序按文件里第一次出现的顺序，不排序")
    void fieldOrderFollowsTheFile() {
        List<String> names = JsonRows.fieldNames(
                write("[{\"z\":\"1\",\"a\":\"2\",\"m\":\"3\"}]"), StandardCharsets.UTF_8);
        assertEquals(List.of("z", "a", "m"), names,
                "按字母排会让映射界面上的顺序和用户文件里的对不上");
    }

    @Test
    @DisplayName("每行一个对象（NDJSON）也认")
    void ndjson() {
        List<List<String>> rows = rows("{\"id\":\"1\"}\n{\"id\":\"2\"}\n{\"id\":\"3\"}\n");
        assertEquals(4, rows.size());
        assertEquals(List.of("id"), rows.get(0));
        assertEquals("3", rows.get(3).get(0));
    }

    @Test
    @DisplayName("单个对象当成一行")
    void singleObject() {
        List<List<String>> rows = rows("{\"id\":\"7\"}");
        assertEquals(2, rows.size());
        assertEquals("7", rows.get(1).get(0));
    }

    @Test
    @DisplayName("null 和 true/false 各自有确定的落法")
    void literals() {
        List<List<String>> rows = rows("[{\"a\":null,\"b\":true,\"c\":false}]");
        assertNull(rows.get(1).get(0), "JSON 的 null 就是没有值，不能变成字符串 \"null\"");
        assertEquals("true", rows.get(1).get(1));
        assertEquals("false", rows.get(1).get(2));
    }

    @Test
    @DisplayName("转义字符要还原")
    void escapes() {
        List<List<String>> rows = rows(
                "[{\"s\":\"a\\\"b\\\\c\\nd\\te\\u4e2d\"}]");
        assertEquals("a\"b\\c\nd\te中", rows.get(1).get(0));
    }

    @Test
    @DisplayName("嵌套的对象和数组按原文保留，不压平也不丢")
    void nestedStaysRaw() {
        List<List<String>> rows = rows("[{\"tags\":[\"a\",\"b\"],\"meta\":{\"k\":\"v\"}}]");
        assertEquals("[\"a\",\"b\"]", rows.get(1).get(0));
        assertEquals("{\"k\":\"v\"}", rows.get(1).get(1));
    }

    @Test
    @DisplayName("嵌套结构里的括号在字符串中时不算层级")
    void bracketsInsideStringsDoNotNest() {
        List<List<String>> rows = rows("[{\"meta\":{\"k\":\"}]}\"},\"after\":\"ok\"}]");
        assertEquals("{\"k\":\"}]}\"}", rows.get(1).get(0),
                "字符串里的 } 被当成结构闭合的话，后面整条记录都会读错位");
        assertEquals("ok", rows.get(1).get(1));
    }

    @Test
    @DisplayName("空数组和空文件都不是错误，一行都不发（连表头也不发）")
    void emptyIsNotAnError() {
        // 发一个零列的表头，界面会说「已解析 0 行」——听起来像文件读到了只是没数据；
        // 一行不发才会走到「文件是空的」那条提示上
        assertEquals(0, rows("[]").size());
        assertEquals(0, rows("").size());
    }

    @Test
    @DisplayName("认不出来的形状要报错，并且说清楚位置")
    void badShapesAreRefused() {
        DbException e = assertThrows(DbException.class, () -> rows("[1,2,3]"));
        assertTrue(e.getMessage().contains("对象"), e.getMessage());
        assertTrue(e.getMessage().contains("第"), "几万行的文件里，不说位置等于什么都没说");

        assertThrows(DbException.class, () -> rows("[{\"a\":\"1\"}"), "少了收尾的 ]");
        assertThrows(DbException.class, () -> rows("{\"a\":\"未闭合"));
        assertThrows(DbException.class, () -> rows("42"));
    }

    @Test
    @DisplayName("BOM 开头的文件照样读")
    void bom() {
        List<List<String>> rows = rows("﻿[{\"id\":\"1\"}]");
        assertEquals(List.of("id"), rows.get(0));
    }

    @Test
    @DisplayName("head 只取前几行，表头算在里面")
    void headLimits() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            sb.append(i > 0 ? "," : "").append("{\"i\":\"").append(i).append("\"}");
        }
        List<List<String>> head = JsonRows.head(write(sb.append("]").toString()),
                StandardCharsets.UTF_8, 5);
        assertEquals(5, head.size());
        assertEquals(List.of("i"), head.get(0));
        assertEquals("3", head.get(4).get(0));
    }
}
