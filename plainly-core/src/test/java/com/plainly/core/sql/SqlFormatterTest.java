package com.plainly.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 排版。
 *
 * <p>这里最要紧的一条不是排得好不好看，而是<b>排版不能改变语义</b>。
 * 原来那条正则会把字符串常量里的 {@code AND} 也当成关键字换行，
 * 于是点一下「格式化」，查询条件就变了——不报错，没提示。
 */
@DisplayName("SQL 排版")
class SqlFormatterTest {

    @Test
    @DisplayName("字符串常量一个字符都不能动——这是这个类存在的理由")
    void neverTouchesStringLiterals() {
        String sql = "SELECT id FROM t WHERE note = 'a AND b' AND tag = 'x OR y'";
        String out = SqlFormatter.format(sql);

        assertTrue(out.contains("'a AND b'"), "常量里的 AND 不能被换行：" + out);
        assertTrue(out.contains("'x OR y'"), "常量里的 OR 不能被换行：" + out);
        // 而常量外面那个 AND 该换行
        assertTrue(out.contains("\n  AND tag"), out);
    }

    @Test
    @DisplayName("常量里的引号转义认得出来，不会把后面的内容当成代码")
    void handlesEscapedQuotes() {
        String sql = "SELECT id FROM t WHERE s = 'it''s from here' AND x = 1";
        String out = SqlFormatter.format(sql);
        assertTrue(out.contains("'it''s from here'"), out);
        // 常量里那个 from 不该被当成子句关键字
        assertEquals(1, count(out, "\nFROM"), "只该有一个 FROM 子句：" + out);
    }

    @Test
    @DisplayName("查询字段一行一个")
    void oneFieldPerLine() {
        String out = SqlFormatter.format("select id, name, age from users");
        assertEquals(String.join("\n",
                "SELECT",
                "  id,",
                "  name,",
                "  age",
                "FROM users"), out);
    }

    @Test
    @DisplayName("括号里的逗号不拆——那是函数参数，不是查询字段")
    void doesNotSplitInsideParens() {
        String out = SqlFormatter.format("select id, coalesce(a, b, c) as x from t");
        assertTrue(out.contains("coalesce(a, b, c)"), "函数参数要留在一行：" + out);
        assertTrue(out.contains("\n  coalesce"), "但它自己是一个字段，要另起一行：" + out);
    }

    @Test
    @DisplayName("子查询里的 WHERE 不往外拆")
    void doesNotSplitInsideSubquery() {
        String out = SqlFormatter.format(
                "select id from t where id in (select id from u where a = 1 and b = 2)");
        assertEquals(1, count(out, "\nWHERE"), "只有外层那个 WHERE 起新行：" + out);
        assertTrue(out.contains("(select id from u where a = 1 and b = 2)")
                        || out.contains("(select id from u where a = 1 and b = 2 )"),
                "子查询原样留在括号里：" + out);
    }

    @Test
    @DisplayName("多词关键字不能被拆到两行上")
    void keepsMultiWordKeywordsTogether() {
        String out = SqlFormatter.format(
                "select a from t left join u on t.id = u.id group by a order by a");
        assertTrue(out.contains("\nLEFT JOIN"), out);
        assertTrue(out.contains("\nGROUP BY a"), out);
        assertTrue(out.contains("\nORDER BY a"), out);
    }

    @Test
    @DisplayName("格式化两次和一次的结果一样——否则多点一下缩进就越来越深")
    void isIdempotent() {
        for (String sql : new String[]{
                "select id, name from users where a = 1 and b = 2 order by id",
                "select id from t where note = 'a AND b'",
                "select a from t left join u on t.id = u.id",
                "select id, coalesce(a, b) from t where id in (select x from y)"}) {
            String once = SqlFormatter.format(sql);
            String twice = SqlFormatter.format(once);
            assertEquals(once, twice, "不幂等：" + sql);
        }
    }

    @Test
    @DisplayName("注释原样保留")
    void keepsComments() {
        String out = SqlFormatter.format(
                "select id -- 这里说明一下 from 不是关键字\nfrom t");
        assertTrue(out.contains("-- 这里说明一下 from 不是关键字"), out);
        assertEquals(1, count(out, "\nFROM"), "注释里那个 from 不算子句：" + out);
    }

    @Test
    @DisplayName("多条语句之间空一行")
    void separatesStatements() {
        String out = SqlFormatter.format("select 1; select 2");
        assertTrue(out.contains(";\n\nSELECT"), out);
    }

    @Test
    @DisplayName("空输入原样返回，不炸")
    void handlesEmpty() {
        assertEquals("", SqlFormatter.format(""));
        assertEquals("", SqlFormatter.format(null));
        assertEquals("   ", SqlFormatter.format("   "));
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }
}
