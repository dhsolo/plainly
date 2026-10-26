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
 *   <li>函数参数、{@code IN (1, 2, 3)} 这类<b>普通括号里不拆</b>：
 *       {@code COALESCE(a, b)} 拆成三行比不拆更难读；</li>
 *   <li><b>子查询</b>（括号里以 SELECT / WITH 开头）按同样的规则排，整体缩进一级，
 *       {@code )} 单独起一行和开头对齐。</li>
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

    /**
     * 一层查询：最外层的语句，或者括号里的一个子查询。
     *
     * <p>子查询和外层用同一套规则排版，只是整体往里缩一级。原来括号里一律不动，
     * {@code FROM (SELECT a, b, c FROM t WHERE ...) p} 整个挤在一行里——
     * 偏偏子查询往往是整条语句里最长、最需要看清的那一段。
     *
     * @param base   这一层子句关键字的缩进
     * @param parens 这一层里还没合上的<b>普通</b>括号（函数参数、IN 列表），
     *               大于 0 时不拆行
     */
    private static final class Level {
        final String base;
        int parens;
        boolean inSelectList;

        Level(String base) {
            this.base = base;
        }
    }

    public static String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        List<String> tokens = tokenize(sql);

        StringBuilder out = new StringBuilder();
        java.util.Deque<Level> levels = new java.util.ArrayDeque<>();
        levels.push(new Level(""));
        boolean atLineStart = true;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String upper = token.toUpperCase(Locale.ROOT);
            Level level = levels.peek();
            boolean splittable = level.parens == 0;

            if (token.equals("(")) {
                boolean subquery = i + 1 < tokens.size()
                        && SUBQUERY_START.contains(tokens.get(i + 1).toUpperCase(Locale.ROOT));
                boolean space = subquery || spaceBeforeParen(tokens, i);
                append(out, token, space, atLineStart);
                atLineStart = false;
                if (subquery) {
                    // 里面的 SELECT 会自己起新行，缩进比 ( 所在的那一行深一级
                    levels.push(new Level(lineIndent(out) + INDENT));
                } else {
                    level.parens++;
                }
                continue;
            }
            if (token.equals(")")) {
                if (level.parens == 0 && levels.size() > 1) {
                    // 子查询收尾：) 单独起一行，和开头那个 ( 所在的行对齐
                    levels.pop();
                    newLine(out);
                    out.append(level.base, 0, level.base.length() - INDENT.length()).append(')');
                } else {
                    level.parens = Math.max(0, level.parens - 1);
                    append(out, token, false, atLineStart);
                }
                atLineStart = false;
                continue;
            }
            if (token.equals(";")) {
                // 语句之间空一行：一屏里跑好几条时，边界得看得出来
                out.append(";").append('\n').append('\n');
                levels.clear();
                levels.push(new Level(""));
                atLineStart = true;
                continue;
            }
            if (token.equals(",")) {
                out.append(',');
                if (splittable && level.inSelectList) {
                    // 查询字段一行一个。括号里的逗号不拆——那是函数参数
                    out.append('\n').append(level.base).append(INDENT);
                    atLineStart = true;
                } else {
                    atLineStart = false;
                }
                continue;
            }
            if (token.startsWith("--")) {
                // 行注释一直管到行尾。后面的词要是接在同一行，就被它吞进注释里了
                append(out, token, true, atLineStart);
                out.append('\n').append(level.base).append(level.inSelectList ? INDENT : "");
                atLineStart = true;
                continue;
            }

            // 多词关键字要整体识别，否则 GROUP 和 BY 会被拆到两行上
            String phrase = phraseAt(tokens, i);
            if (phrase != null && splittable) {
                i += phrase.split(" ").length - 1;
                newLine(out);
                out.append(level.base).append(phrase);
                atLineStart = false;
                level.inSelectList = false;
                continue;
            }

            if (splittable && CLAUSE.contains(upper)) {
                newLine(out);
                out.append(level.base).append(upper);
                atLineStart = false;
                level.inSelectList = false;
                continue;
            }
            if (splittable && CONJUNCTION.contains(upper)) {
                newLine(out);
                out.append(level.base).append(INDENT).append(upper);
                atLineStart = false;
                continue;
            }
            if (splittable && upper.equals("SELECT")) {
                newLine(out);
                out.append(level.base).append("SELECT").append('\n')
                        .append(level.base).append(INDENT);
                level.inSelectList = true;
                atLineStart = true;
                continue;
            }
            if (splittable && upper.equals("UNION")) {
                newLine(out);
                out.append(level.base).append("UNION");
                atLineStart = false;
                level.inSelectList = false;
                continue;
            }

            append(out, token, true, atLineStart);
            atLineStart = false;
        }
        return out.toString().strip();
    }

    /** 括号后面跟着这些词，括号里就是一个子查询。 */
    private static final Set<String> SUBQUERY_START = Set.of("SELECT", "WITH");

    /**
     * 跟在这些词后面的 ( 前面留空格。
     *
     * <p>别的词后面不留：那是函数名，{@code count(1)} 才是通常的写法，
     * 写成 {@code count (1)} 的也顺手收紧。
     */
    private static final Set<String> SPACED_BEFORE_PAREN = Set.of(
            "IN", "EXISTS", "FROM", "JOIN", "ON", "AND", "OR", "NOT", "AS", "WHERE",
            "VALUES", "USING", "ALL", "ANY", "SOME", "SELECT", "HAVING", "WHEN", "THEN",
            "ELSE", "CASE", "SET", "INTO");

    private static boolean spaceBeforeParen(List<String> tokens, int i) {
        if (i == 0) {
            return false;
        }
        String prev = tokens.get(i - 1);
        if (prev.equals("(") || prev.equals(",")) {
            return prev.equals(",");
        }
        String upper = prev.toUpperCase(Locale.ROOT);
        if (SPACED_BEFORE_PAREN.contains(upper)) {
            return true;
        }
        // 运算符后面也留：a = (b + c)、x * (y - 1)
        return !Character.isLetterOrDigit(prev.charAt(prev.length() - 1))
                && prev.charAt(prev.length() - 1) != '_'
                && prev.charAt(prev.length() - 1) != ')'
                && prev.charAt(0) != '"' && prev.charAt(0) != '`';
    }

    /** 当前这一行开头的空格。 */
    private static String lineIndent(StringBuilder out) {
        int start = out.lastIndexOf("\n") + 1;
        int end = start;
        while (end < out.length() && out.charAt(end) == ' ') {
            end++;
        }
        return out.substring(start, end);
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
