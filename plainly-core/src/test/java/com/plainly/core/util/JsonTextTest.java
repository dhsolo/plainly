package com.plainly.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JSON 重新缩进。
 *
 * <p>这里最要紧的一条：<b>数字不许被改写</b>。用 JSON 库解析再序列化就会栽在这一条上，
 * 所以专门有一条测试盯着它。
 */
@DisplayName("JSON 排版 · 只改缩进，不改内容")
class JsonTextTest {

    @Test
    @DisplayName("超长数字原样保留，不变成科学计数法")
    void keepsHugeNumbers() {
        String json = "{\"amount\":12345678901234567890.1234567890,\"n\":1.50}";
        String pretty = JsonText.pretty(json);
        assertTrue(pretty.contains("12345678901234567890.1234567890"), pretty);
        assertTrue(pretty.contains("1.50"), "尾随的零也是原文的一部分：" + pretty);
        assertFalse(pretty.contains("E"), pretty);
    }

    @Test
    @DisplayName("字符串里的内容一个字都不动")
    void doesNotTouchStringContents() {
        String json = "{\"a\":\"里面有 { 和 : 和 , 还有 \\\" 引号\"}";
        String pretty = JsonText.pretty(json);
        assertTrue(pretty.contains("\"里面有 { 和 : 和 , 还有 \\\" 引号\""), pretty);
    }

    @Test
    @DisplayName("嵌套按层缩进")
    void indentsNested() {
        String pretty = JsonText.pretty("{\"a\":{\"b\":[1,2]}}");
        assertEquals(String.join("\n",
                "{",
                "  \"a\": {",
                "    \"b\": [",
                "      1,",
                "      2",
                "    ]",
                "  }",
                "}"), pretty);
    }

    @Test
    @DisplayName("空对象和空数组不摊成三行")
    void keepsEmptyContainersInline() {
        assertEquals(String.join("\n",
                "{",
                "  \"a\": {},",
                "  \"b\": []",
                "}"), JsonText.pretty("{\"a\":{},\"b\":[]}"));
    }

    @Test
    @DisplayName("坏数据原样返回，不替它补全")
    void returnsBrokenInputAsIs() {
        String broken = "{\"a\": \"没闭合的引号";
        assertEquals(broken, JsonText.pretty(broken));
    }

    @Test
    @DisplayName("只有以 { 或 [ 开头才当成 JSON")
    void detectsJson() {
        assertTrue(JsonText.looksLikeJson("  {\"a\":1}"));
        assertTrue(JsonText.looksLikeJson("[1,2]"));
        assertFalse(JsonText.looksLikeJson("SO-2024-001"));
        assertFalse(JsonText.looksLikeJson(null));
    }

    @Test
    @DisplayName("十六进制转储能把尾随空格和 BOM 显出来")
    void hexDumpShowsInvisibleCharacters() {
        String dump = JsonText.hexDump("ab ".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                1024);
        // 尾随空格的证据在左边的字节栏里：20 是明摆着的，
        // 右边的 ASCII 栏按惯例仍把空格画成空格（那正是它在文本框里看不出来的原因）
        assertTrue(dump.startsWith("00000000  61 62 20"), dump);
        assertTrue(dump.contains("ab "), dump);
    }

    @Test
    @DisplayName("超出上限时只转储前面一段，并说明总量")
    void hexDumpRespectsLimit() {
        byte[] bytes = new byte[100];
        String dump = JsonText.hexDump(bytes, 16);
        assertTrue(dump.contains("共 100 字节"), dump);
    }
}
