package com.plainly.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 排版。
 *
 * <h2>为什么不能用正则</h2>
 * 原来这件事是一条正则做的：把 {@code \s+(FROM|WHERE|AND|...)\s+} 换成换行加缩进。
 * 它有一个不会报错、但会<b>改掉查询语义</b>的毛病——正则不认字符串字面量：
 *
 * <pre>WHERE note = 'a AND b'   →   WHERE note = 'a
 *   AND b'</pre>
 *
 * 那个常量里凭空多了个换行和两个空格。用户点了一下「格式化」，查询条件就变了，
 * 而界面上什么提示都没有。所以这里改成按词扫：<b>字符串、引号标识符、注释一律原样搬运</b>，
 * 只在真正的代码部分动手。
 *
 * <h2>排版规则</h2>
 * <ul>
 *   <li>查询字段<b>一行一个</b>——列多了之后，挤在一行里根本看不出少写了哪个；</li>
 *   <li>{@code FROM / WHERE / GROUP BY / ORDER BY / JOIN} 这些子句各起一行；</li>
 *   <li>{@code AND / OR} 起一行并缩进，让条件的层次看得见；</li>
 *   <li><b>只在括号外拆</b>：{@code COALESCE(a, b)} 里的逗号、子查询里的 WHERE 都不动，
 *       否则拆出来的东西比不拆更难读。</li>
 * </ul>
 *
 * <h2>必须幂等</h2>
 * 格式化两次和格式化一次的结果必须一模一样。做不到的话，用户多点一次就会看到
 * 缩进越来越深——而这正是原来那条正则的另一个毛病。
 */
public final class SqlFormatter {

    /** 各自起一行、顶格的子句关键字。 */
    private static final Set<String> CLAUSE = Set.of(
            "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "FETCH", "WINDOW",
            "VALUES", "SET", "RETURNING");

    /** 起一行并缩进的连接词。 */
    private static final Set<String> CONJUNCTION = Set.of("AND", "OR");

    /** 两个词合起来才是一个关键字。 */
    private static final Set<String> TWO_WORD = Set.of(
            "GROUP BY", "ORDER BY", "UNION ALL", "PARTITION BY",
            "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "CROSS JOIN", "FULL JOIN",
            "INSERT INTO", "DELETE FROM");

    /** 三个词才是一个关键字。 */
    private static final Set<String> THREE_WORD = Set.of(
            "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "FULL OUTER JOIN");

    private static final String INDENT = "  ";

    private SqlFormatter() {
    }

    public static String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        List<String> tokens = tokenize(sql);

        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inSelectList = false;
        boolean atLineStart = true;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String upper = token.toUpperCase(Locale.ROOT);

            if (token.equals("(")) {
                depth++;
                append(out, token, false, atLineStart);
                atLineStart = false;
                continue;
            }
            if (token.equals(")")) {
                depth = Math.max(0, depth - 1);
                append(out, token, false, atLineStart);
                atLineStart = false;
                continue;
            }
            if (token.equals(";")) {
                // 语句之间空一行：一屏里跑好几条时，边界得看得出来
                out.append(";").append('\n').append('\n');
                depth = 0;
                inSelectList = false;
                atLineStart = true;
                continue;
            }
            if (token.equals(",")) {
                out.append(',');
                if (depth == 0 && inSelectList) {
                    // 查询字段一行一个。括号里的逗号不拆——那是函数参数
                    out.append('\n').append(INDENT);
                    atLineStart = true;
                } else {
                    atLineStart = false;
                }
                continue;
            }

            // 多词关键字要整体识别，否则 GROUP 和 BY 会被拆到两行上
            String phrase = phraseAt(tokens, i);
            if (phrase != null && depth == 0) {
                i += phrase.split(" ").length - 1;
                newLine(out);
                out.append(phrase);
                atLineStart = false;
                inSelectList = false;
                continue;
            }

            if (depth == 0 && CLAUSE.contains(upper)) {
                newLine(out);
                out.append(upper);
                atLineStart = false;
                inSelectList = false;
                continue;
            }
            if (depth == 0 && CONJUNCTION.contains(upper)) {
                newLine(out);
                out.append(INDENT).append(upper);
                atLineStart = false;
                continue;
            }
            if (depth == 0 && upper.equals("SELECT")) {
                newLine(out);
                out.append("SELECT").append('\n').append(INDENT);
                inSelectList = true;
                atLineStart = true;
                continue;
            }
            if (depth == 0 && upper.equals("UNION")) {
                newLine(out);
                out.append("UNION");
                atLineStart = false;
                inSelectList = false;
                continue;
            }

            append(out, token, true, atLineStart);
            atLineStart = false;
        }
        return out.toString().strip();
    }

    /** 追加一个词，必要时在前面补一个空格。 */
    private static void append(StringBuilder out, String token, boolean spaceBefore,
                               boolean atLineStart) {
        boolean needSpace = spaceBefore && !atLineStart && out.length() > 0
                && !endsWithOpenParen(out);
        if (needSpace) {
            out.append(' ');
        }
        out.append(token);
    }

    private static boolean endsWithOpenParen(StringBuilder out) {
        return out.length() > 0 && out.charAt(out.length() - 1) == '(';
    }

    /** 起新的一行；已经在行首就不再多加换行。 */
    private static void newLine(StringBuilder out) {
        while (out.length() > 0 && (out.charAt(out.length() - 1) == ' ')) {
            out.setLength(out.length() - 1);
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    /** 从 {@code i} 开始能不能凑出一个多词关键字；凑不出返回 null。 */
    private static String phraseAt(List<String> tokens, int i) {
        for (int words = 3; words >= 2; words--) {
            if (i + words > tokens.size()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < words; k++) {
                if (k > 0) {
                    sb.append(' ');
                }
                sb.append(tokens.get(i + k).toUpperCase(Locale.ROOT));
            }
            String phrase = sb.toString();
            if ((words == 3 && THREE_WORD.contains(phrase))
                    || (words == 2 && TWO_WORD.contains(phrase))) {
                return phrase;
            }
        }
        return null;
    }

    /**
     * 切成词。
     *
     * <p>字符串字面量、引号标识符、注释各自作为<b>一整个</b>词返回，内容一个字符都不动——
     * 这是整个类存在的理由：只要它们被当成普通文本参与排版，
     * 一次「格式化」就可能改掉一个字符串常量的内容。
     */
    private static List<String> tokenize(String sql) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 行注释：连同行尾一起原样保留
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.add(sql.substring(i, end));
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.add(sql.substring(i, end));
                i = end;
                continue;
            }
            if (c == '\'') {
                i = readQuoted(sql, i, '\'', out);
                continue;
            }
            if (c == '"' || c == '`') {
                i = readQuoted(sql, i, c, out);
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == ';') {
                out.add(String.valueOf(c));
                i++;
                continue;
            }
            int start = i;
            while (i < n) {
                char d = sql.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ',' || d == ';'
                        || d == '\'' || d == '"' || d == '`') {
                    break;
                }
                i++;
            }
            if (i == start) {
                i++;   // 兜底，避免死循环
            }
            out.add(sql.substring(start, i));
        }
        return out;
    }

    /**
     * 读一段带引号的内容，连引号一起作为一个词返回。
     *
     * <p>认 SQL 的转义写法：引号里连着两个引号表示一个引号本身
     * （{@code 'it''s'}），不是字符串结束。
     */
    private static int readQuoted(String sql, int from, char quote, List<String> out) {
        int n = sql.length();
        int i = from + 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            i++;
        }
        out.add(sql.substring(from, Math.min(i, n)));
        return i;
    }
}
