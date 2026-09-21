package com.plainly.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行前的影响面识别：这条语句会不会一次改掉整张表。
 *
 * <h2>为什么值得单独拦一道</h2>
 * {@code DELETE FROM orders WHERE id = 1} 少敲后半句，仍然是一条完全合法的 SQL——
 * 数据库不会有任何异议，它会安静地删光整张表并报告「影响 128394 行」。
 * 等用户看到那个数字时，事情已经做完了，而查询编辑器里没有回滚可按。
 *
 * <p>所以这里识别的不是「写错的语句」，而是<b>写对了、但影响面远超本意</b>的语句。
 * 判断依据只有一条：顶层有没有 WHERE。
 *
 * <h2>为什么必须先剥注释和字符串</h2>
 * 直接在原文里找 WHERE 有两种错法，两种都很糟：
 * <ul>
 *   <li>{@code UPDATE t SET note = 'where to go'} ——字符串里的 where 被当成了条件，
 *       于是真正危险的语句被放行；</li>
 *   <li>{@code DELETE FROM t -- where id = 1} ——注释掉的条件被当成有效，同上。</li>
 * </ul>
 * 漏报比误报危险得多：误报只是多一次确认，漏报是数据没了。
 *
 * <h2>为什么只认顶层的 WHERE</h2>
 * {@code UPDATE t SET x = (SELECT max(v) FROM u WHERE u.k = 1)} 里有 WHERE，
 * 但那是子查询的条件，被更新的仍然是 t 的每一行。只在括号深度为 0 处计数才认得出来。
 */
public final class SqlRisk {

    /** 语句的种类。文案按种类走，因为用户要确认的东西不一样。 */
    public enum Kind {
        /** UPDATE，没有 WHERE。 */
        UPDATE_ALL("整表更新", "这条 UPDATE 没有 WHERE，会改掉整张表"),
        /** DELETE，没有 WHERE。 */
        DELETE_ALL("整表删除", "这条 DELETE 没有 WHERE，会删光整张表"),
        /** DROP DATABASE / SCHEMA。 */
        DROP_SCHEMA("删除整个库", "这条语句会删掉整个库，连同里面的所有表");

        private final String label;
        private final String headline;

        Kind(String label, String headline) {
            this.label = label;
            this.headline = headline;
        }

        /** 清单里那一行用的短名。 */
        public String label() {
            return label;
        }

        /**
         * 只有一条时，确认框标题上的整句话。
         *
         * <p>值得为此多一个字段：「有 1 条语句会作用于整张表」是一句正确但没用的话，
         * 用户还得自己去下面看是哪一条、为什么。标题直接说清楚，多数情况下
         * 他看一眼标题就能决定。
         */
        public String headline() {
            return headline;
        }
    }

    /**
     * 一条被拦下的语句。
     *
     * @param index  在这次执行的语句列表里的下标，从 0 起。界面上要说「第几条」
     * @param target 受影响的表或库名；认不出来时为空串
     */
    public record Risk(int index, Kind kind, String target, String sql) {

        /** 界面上的一行说明。 */
        public String describe() {
            return "第 " + (index + 1) + " 条 · " + kind.label()
                    + (target.isBlank() ? "" : "：" + target);
        }
    }

    private SqlRisk() {
    }

    /** 扫一批语句，返回其中影响面过大的那些，保持原顺序。 */
    public static List<Risk> scan(List<String> statements) {
        List<Risk> risks = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            Risk risk = of(i, statements.get(i));
            if (risk != null) {
                risks.add(risk);
            }
        }
        return risks;
    }

    /** 单条语句的判断；不危险时返回 null。 */
    public static Risk of(int index, String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty()) {
            return null;
        }
        String head = tokens.get(0).upper();
        if (head.equals("DROP") && tokens.size() >= 2) {
            String what = tokens.get(1).upper();
            if (what.equals("DATABASE") || what.equals("SCHEMA")) {
                return new Risk(index, Kind.DROP_SCHEMA, dropTarget(tokens), sql);
            }
            return null;
        }
        boolean update = head.equals("UPDATE");
        boolean delete = head.equals("DELETE");
        if (!update && !delete) {
            return null;
        }
        if (hasTopLevel(tokens, "WHERE")) {
            return null;
        }
        // MySQL 允许 UPDATE/DELETE ... LIMIT n。那是明确限量的写法，
        // 影响的行数是用户自己写下的数字，不该再去问他一遍
        if (hasTopLevel(tokens, "LIMIT")) {
            return null;
        }
        return new Risk(index, update ? Kind.UPDATE_ALL : Kind.DELETE_ALL,
                update ? updateTarget(tokens) : deleteTarget(tokens), sql);
    }

    // -------------------------------------------------------------- 目标名字

    /** {@code UPDATE [LOW_PRIORITY] [IGNORE] t SET ...} 里的 t。 */
    private static String updateTarget(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String u = tokens.get(i).upper();
            if (u.equals("LOW_PRIORITY") || u.equals("IGNORE") || u.equals("ONLY")) {
                continue;
            }
            return tokens.get(i).text();
        }
        return "";
    }

    /**
     * {@code DELETE FROM t} 里的 t。
     *
     * <p>多表删除写作 {@code DELETE t1 FROM t1 JOIN t2 …}，要删的表在 DELETE 之后、
     * FROM 之前——但那些名字往往是别名。取 FROM 后面那个真表名更有用。
     */
    private static String deleteTarget(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            if (tokens.get(i).upper().equals("FROM") && i + 1 < tokens.size()) {
                return tokens.get(i + 1).text();
            }
        }
        return "";
    }

    /** {@code DROP DATABASE [IF EXISTS] name}。 */
    private static String dropTarget(List<Token> tokens) {
        for (int i = 2; i < tokens.size(); i++) {
            String u = tokens.get(i).upper();
            if (u.equals("IF") || u.equals("EXISTS")) {
                continue;
            }
            return tokens.get(i).text();
        }
        return "";
    }

    private static boolean hasTopLevel(List<Token> tokens, String keyword) {
        for (Token t : tokens) {
            if (t.depth() == 0 && t.upper().equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 词法

    /**
     * 一个词。
     *
     * @param depth 出现处的括号深度；顶层为 0
     */
    private record Token(String text, int depth) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * 切成词，顺带丢掉注释和字符串字面量。
     *
     * <p>字面量整个丢掉而不是留个占位：这里只关心关键字和标识符，
     * 而字面量的内容恰恰是最会冒充关键字的东西（见类注释）。
     *
     * <p>转义一律按「反斜杠转义」处理，包括本不支持它的 PostgreSQL。
     * 代价是 PG 里 {@code '\'} 这种合法写法会被误读成未闭合的字符串，
     * 后面整条语句都落在「字符串里」，于是什么也扫不出来——那是漏报。
     * 但反过来（不认反斜杠而库认）会让 MySQL 的 {@code 'a\'} 提前闭合，
     * 把真正的语句正文当成字面量丢掉，同样是漏报，且更常见。两害相权取其轻。
     */
    private static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int depth = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                flush(out, word, depth);
                i = endOfLine(sql, i);
                continue;
            }
            if (c == '#') {
                flush(out, word, depth);
                i = endOfLine(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                flush(out, word, depth);
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                flush(out, word, depth);
                i = endOfQuoted(sql, i);
                continue;
            }
            if (c == '(') {
                flush(out, word, depth);
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                flush(out, word, depth);
                depth = Math.max(0, depth - 1);
                i++;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c > 127) {
                word.append(c);
                i++;
                continue;
            }
            flush(out, word, depth);
            i++;
        }
        flush(out, word, depth);
        return out;
    }

    private static void flush(List<Token> out, StringBuilder word, int depth) {
        if (word.length() > 0) {
            out.add(new Token(word.toString(), depth));
            word.setLength(0);
        }
    }

    private static int endOfLine(String s, int i) {
        while (i < s.length() && s.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** 跳过一段引起来的东西，返回它之后的位置。未闭合时到末尾。 */
    private static int endOfQuoted(String s, int start) {
        char open = s.charAt(start);
        char close = open == '[' ? ']' : open;
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && open != '[' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (c == close) {
                // 双写即转义：一个字面量里的两个连续引号仍属于同一个字面量
                if (i + 1 < s.length() && s.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return s.length();
    }
}
