package com.plainly.core.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 的作用域分析：光标所在的这个位置，哪些别名是看得见的，它们各指什么。
 *
 * <h2>为什么必须做这件事</h2>
 * 之前的补全把整段 SQL 里所有的 {@code FROM}/{@code JOIN} 一把抓成一张平表。
 * 日常单表查询没问题，一旦出现子查询就开始给错答案，而且是<b>安静地</b>给错：
 *
 * <pre>
 * SELECT o.&#124; FROM orders o
 * WHERE o.user_id IN (SELECT c.id FROM customers c)
 * </pre>
 *
 * 在外层敲 {@code c.}，平表实现会把 {@code customers} 的字段补出来——可 {@code c}
 * 在外层根本不存在，这条 SQL 是跑不通的。反过来更糟：两个子查询各自把不同的表
 * 命名为 {@code t}，平表里后一个盖掉前一个，于是<b>补出来的字段属于另一张表</b>，
 * 名字看着都对，跑起来才报错。
 *
 * <h2>做法：扫括号，不上语法树</h2>
 * 完整的 SQL 语法树要引一个 ANTLR 级别的依赖，还要为每种方言各维护一份文法。
 * 而补全需要的信息其实只有一层：<b>括号的嵌套结构</b>，加上每层里的
 * {@code FROM} / {@code JOIN} / {@code WITH}。所以这里做的是一个字符扫描器：
 *
 * <ol>
 *   <li>先把字符串字面量和注释抹成空格（保持长度，下标不变），
 *       免得 {@code '(a)'} 里的括号被当成结构；</li>
 *   <li>扫括号建树，内容以 SELECT / WITH 开头的括号才算一个作用域——
 *       {@code IN (1,2,3)} 和 {@code COUNT(*)} 不是；</li>
 *   <li>每层只认属于自己那一层的表引用：子作用域的内容先抹掉，
 *       这样子查询里的 {@code FROM} 不会漏到外层；</li>
 *   <li>解析时从光标所在的最内层往外找——相关子查询本来就能引用外层的别名。</li>
 * </ol>
 *
 * <p>这不是 SQL 解析器，也不假装是。它认得的是括号和几个关键字，
 * 认不出来的写法（怪异的方言语法、把 FROM 写进函数参数里的花活）会退化成
 * 「解析不出这个限定符」，界面上如实说一句，而不是猜一个看着像的答案出来。
 */
public final class SqlScopes {

    /** 一个别名指向什么。 */
    public enum Kind {
        /** 实实在在的一张表或视图，字段能从元数据里查到。 */
        TABLE("表"),
        /** {@code FROM (SELECT ...) x} 里的 x，字段由子查询的 SELECT 列表决定。 */
        DERIVED("子查询"),
        /** {@code WITH x AS (...)} 里的 x。 */
        CTE("公用表表达式");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一个在某个作用域里看得见的关系。
     *
     * @param alias   用来限定的名字。没写别名时就是表名本身
     * @param table   指向的表名；{@link Kind#TABLE} 之外为 null
     * @param columns 子查询 / CTE 投影出的列名。{@link Kind#TABLE} 时为空
     * @param star    子查询的 SELECT 列表里有 {@code *}，说明列名不止 {@code columns} 里这些
     */
    public record Relation(String alias, String table, Kind kind,
                           List<String> columns, boolean star) {

        static Relation table(String alias, String table) {
            return new Relation(alias, table, Kind.TABLE, List.of(), false);
        }

        /**
         * 去掉库名限定的表名。
         *
         * <p>查元数据要用这个：{@code describeTable(schema, table)} 的第二个参数
         * 收的是光秃秃的表名，把 {@code shop.orders} 整个塞进去查不到东西。
         * 带库名的那份仍然留在 {@link #table()} 里，界面要显示时用得上。
         */
        public String simpleTable() {
            return table == null ? null : SqlScopes.simpleName(table);
        }

        /** 界面上跟在名字后面的那句说明。 */
        public String describe() {
            if (kind == Kind.TABLE) {
                return table;
            }
            if (star) {
                return kind.label() + " · SELECT * ，列名取自它引用的表";
            }
            return kind.label() + " · " + columns.size() + " 列";
        }
    }

    /** 一个作用域：根作用域，或者一对括号里的子查询。 */
    public static final class Scope {

        private final int start;
        private final int end;
        private final Scope parent;
        private final List<Scope> children = new ArrayList<>();
        private final Map<String, Relation> relations = new LinkedHashMap<>();
        private List<String> projection = List.of();
        private boolean projectsStar;

        /**
         * 这个作用域是在给谁下定义。
         *
         * <p>{@code FROM (SELECT ...) s} 里，括号内那个作用域「提供」了 s。
         * 而 s 这个名字在它自己的定义内部是<b>还不存在</b>的——
         * 往外找别名时必须把它挡掉，否则会在子查询里补出一个引用自己的名字，
         * 写出来的 SQL 跑不通。
         */
        private String providesAlias;

        /** 递归 CTE 例外：{@code WITH RECURSIVE t AS (... FROM t)} 里引用自己是合法的。 */
        private boolean selfReferenceAllowed;

        Scope(int start, int end, Scope parent) {
            this.start = start;
            this.end = end;
            this.parent = parent;
            if (parent != null) {
                parent.children.add(this);
            }
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public Scope parent() {
            return parent;
        }

        public List<Scope> children() {
            return children;
        }

        /** 本层自己声明的关系，不含外层的。 */
        public List<Relation> ownRelations() {
            return List.copyOf(relations.values());
        }

        /** 这一层 SELECT 出来的列名。 */
        public List<String> projection() {
            return projection;
        }

        public boolean projectsStar() {
            return projectsStar;
        }

        boolean contains(int pos) {
            return pos >= start && pos <= end;
        }
    }

    // ------------------------------------------------------------------ 正则

    /**
     * {@code FROM t} / {@code JOIN db.t AS x}。别名不能是这些关键字。
     *
     * <p>分隔符允许是一个左括号，为的是 {@code FROM (a JOIN b ON ...)} 这种
     * 加了括号的连接。{@code FROM (SELECT ...)} 不会误入：那对括号是一个子作用域，
     * 内容已经被抹空，紧跟在左括号后面的是右括号，不是表名。
     */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)(?:\\s+|\\s*\\(\\s*)"
                    + "(?!SELECT\\b|WITH\\b|VALUES\\b|LATERAL\\b)"
                    + "(?<table>[`\"\\[]?[\\w$]+[`\"\\]]?(?:\\s*\\.\\s*[`\"\\[]?[\\w$]+[`\"\\]]?)?)"
                    + "(?:\\s+(?:AS\\s+)?(?<alias>(?!" + Keywords.NOT_AN_ALIAS + ")[\\w$]+))?",
            Pattern.CASE_INSENSITIVE);

    /** {@code FROM ( ) x}——子作用域的内容已经被抹空，只剩一对括号。 */
    private static final Pattern DERIVED_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s*\\((?<body>\\s*)\\)\\s*"
                    + "(?:AS\\s+)?(?<alias>(?!" + Keywords.NOT_AN_ALIAS + ")[\\w$]+)?",
            Pattern.CASE_INSENSITIVE);

    /** {@code WITH RECURSIVE}。 */
    private static final Pattern RECURSIVE = Pattern.compile(
            "\\bWITH\\s+RECURSIVE\\b", Pattern.CASE_INSENSITIVE);

    /** {@code WITH x AS ( )} / {@code , y AS ( )}，含可选的列名表。 */
    private static final Pattern CTE_REF = Pattern.compile(
            "(?<name>[\\w$]+)\\s*(?:\\([^()]*\\))?\\s+AS\\s*(?:MATERIALIZED\\s*)?\\((?<body>\\s*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** 不能当别名用的词。写在一处，两个正则共用。 */
    private static final class Keywords {
        private static final String NOT_AN_ALIAS =
                "ON\\b|USING\\b|WHERE\\b|GROUP\\b|ORDER\\b|LEFT\\b|RIGHT\\b|INNER\\b|OUTER\\b|"
                        + "FULL\\b|CROSS\\b|NATURAL\\b|JOIN\\b|LIMIT\\b|OFFSET\\b|FETCH\\b|"
                        + "HAVING\\b|UNION\\b|EXCEPT\\b|INTERSECT\\b|WINDOW\\b|FOR\\b|"
                        + "SET\\b|VALUES\\b|AS\\b|WITH\\b|SELECT\\b|STRAIGHT_JOIN\\b";

        private Keywords() {
        }
    }

    private SqlScopes() {
    }

    // ------------------------------------------------------------------ 入口

    /** 分析整段 SQL，返回根作用域。 */
    public static Scope analyze(String sql) {
        String text = sql == null ? "" : sql;
        char[] masked = maskLiteralsAndComments(text);

        Scope root = new Scope(0, text.length(), null);
        buildScopes(masked, root, 0, text.length());
        fill(text, masked, root);
        return root;
    }

    /** 光标落在哪个作用域里。取最内层的那个。 */
    public static Scope scopeAt(Scope root, int caret) {
        Scope current = root;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Scope child : current.children) {
                if (child.contains(caret)) {
                    current = child;
                    moved = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * 从这个作用域往外找一个别名。
     *
     * <p>由内向外是关键：相关子查询能引用外层的别名，反过来不行。
     * 找不到就返回 null——那说明用户敲的这个限定符在这个位置确实不存在，
     * 界面该说「解析不出来」，而不是从别处捡一个同名的塞给他。
     */
    public static Relation resolve(Scope scope, String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String key = alias.toLowerCase(Locale.ROOT);
        boolean blocked = false;
        for (Scope s = scope; s != null; s = s.parent) {
            Relation r = s.relations.get(key);
            if (r != null && !blocked) {
                return r;
            }
            // 跨出一层之前先看看：这一层是不是正在定义这个名字
            if (key.equals(s.providesAlias) && !s.selfReferenceAllowed) {
                blocked = true;
            }
        }
        return null;
    }

    /**
     * 这个位置能看见的全部关系，由内向外。
     *
     * <p>内层同名的遮住外层的——这就是 SQL 自己的规则。
     */
    public static List<Relation> visible(Scope scope) {
        Map<String, Relation> out = new LinkedHashMap<>();
        java.util.Set<String> blocked = new java.util.LinkedHashSet<>();
        for (Scope s = scope; s != null; s = s.parent) {
            for (Map.Entry<String, Relation> e : s.relations.entrySet()) {
                if (!blocked.contains(e.getKey())) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            if (s.providesAlias != null && !s.selfReferenceAllowed) {
                blocked.add(s.providesAlias);
            }
        }
        return List.copyOf(out.values());
    }

    // ------------------------------------------------------------------ 遮蔽

    /**
     * 把字符串字面量和注释抹成空格，长度和下标都不变。
     *
     * <p>不抹的话，{@code WHERE note = '括号 ( 在这里'} 里的那个括号会被当成结构，
     * 后面整段的嵌套层级就全错了。
     *
     * <p>反引号、双引号、方括号包起来的<b>标识符</b>要留着：表名可能就写成
     * {@code `order`}，抹掉它就没法认出这是哪张表了。这里只跳过它们的内容，不清空。
     */
    static char[] maskLiteralsAndComments(String text) {
        char[] out = text.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];

            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (out[j] == '\'') {
                        if (j + 1 < n && out[j + 1] == '\'') {
                            j += 2; // '' 是一个转义的单引号，字符串还没结束
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                blank(out, i, Math.min(j + 1, n));
                i = Math.min(j + 1, n);
                continue;
            }
            if (c == '`' || c == '[' || c == '"') {
                char close = c == '[' ? ']' : c;
                int j = i + 1;
                while (j < n && out[j] != close) {
                    j++;
                }
                i = Math.min(j + 1, n); // 标识符原样留着，只是跳过去
                continue;
            }
            if (c == '-' && i + 1 < n && out[i + 1] == '-') {
                int j = i;
                while (j < n && out[j] != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }
            if (c == '#') {
                int j = i;
                while (j < n && out[j] != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                int j = text.indexOf("*/", i + 2);
                int end = j < 0 ? n : j + 2;
                blank(out, i, end);
                i = end;
                continue;
            }
            i++;
        }
        return out;
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') {
                out[k] = ' '; // 换行留着，注释才不会把后面的行并上来
            }
        }
    }

    // ------------------------------------------------------------------ 建树

    /** 在 {@code [from,to)} 里找出属于本层的子查询括号，各自建一个作用域并递归。 */
    private static void buildScopes(char[] masked, Scope parent, int from, int to) {
        int i = from;
        while (i < to) {
            char c = masked[i];
            if (c == '(') {
                int close = matchingParen(masked, i, to);
                int contentStart = i + 1;
                int contentEnd = close < 0 ? to : close;
                if (startsQuery(masked, contentStart, contentEnd)) {
                    Scope child = new Scope(contentStart, contentEnd, parent);
                    buildScopes(masked, child, contentStart, contentEnd);
                } else {
                    // 不是子查询（函数调用、IN 列表、列名表）：里面的东西仍属于本层
                    buildScopes(masked, parent, contentStart, contentEnd);
                }
                i = close < 0 ? to : close + 1;
                continue;
            }
            i++;
        }
    }

    private static int matchingParen(char[] masked, int open, int limit) {
        int depth = 0;
        for (int i = open; i < limit; i++) {
            if (masked[i] == '(') {
                depth++;
            } else if (masked[i] == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 括号里第一个词是不是 SELECT / WITH——只有这样才算一个作用域。 */
    private static boolean startsQuery(char[] masked, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(masked[i])) {
            i++;
        }
        // 允许再套一层括号：((SELECT ...))
        while (i < to && masked[i] == '(') {
            i++;
            while (i < to && Character.isWhitespace(masked[i])) {
                i++;
            }
        }
        int j = i;
        while (j < to && (Character.isLetter(masked[j]) || masked[j] == '_')) {
            j++;
        }
        String word = new String(masked, i, Math.max(0, j - i)).toUpperCase(Locale.ROOT);
        return word.equals("SELECT") || word.equals("WITH");
    }

    // ------------------------------------------------------------------ 填内容

    /** 自底向上填：子作用域的投影列要先算出来，外层的派生表才能引用它。 */
    private static void fill(String text, char[] masked, Scope scope) {
        for (Scope child : scope.children) {
            fill(text, masked, child);
        }

        char[] own = ownText(masked, scope);
        parseProjection(own, scope);
        parseRelations(own, scope);
    }

    /**
     * 本层自己的文本：范围外抹空，子作用域的内容也抹空（<b>但保留那对括号</b>）。
     *
     * <p>保留括号是为了还能认出 {@code FROM ( ) x} 这种形状——派生表的别名
     * 就写在收尾括号的后面，括号一起抹掉就找不着它了。
     */
    private static char[] ownText(char[] masked, Scope scope) {
        char[] own = new char[masked.length];
        java.util.Arrays.fill(own, ' ');
        for (int i = scope.start; i < scope.end && i < masked.length; i++) {
            own[i] = masked[i];
        }
        for (Scope child : scope.children) {
            for (int i = child.start; i < child.end && i < own.length; i++) {
                own[i] = own[i] == '\n' ? '\n' : ' ';
            }
        }
        return own;
    }

    private static void parseRelations(char[] own, Scope scope) {
        String s = new String(own);
        // WITH RECURSIVE 里，CTE 引用自己是合法的，不能按「自己还没定义好」挡掉
        boolean recursive = RECURSIVE.matcher(s).find();
        boolean[] inCall = inFunctionCall(own);

        // CTE 先认：WITH x AS ( ) —— 它们对本层可见
        Matcher cte = CTE_REF.matcher(s);
        while (cte.find()) {
            if (inCall[cte.start()]) {
                continue;
            }
            Scope body = childStartingAt(scope, cte.end("body") >= 0 ? cte.start("body") : -1);
            String name = cte.group("name");
            if (isKeyword(name)) {
                continue;
            }
            scope.relations.put(name.toLowerCase(Locale.ROOT), derived(name, Kind.CTE, body));
            if (body != null) {
                body.providesAlias = name.toLowerCase(Locale.ROOT);
                body.selfReferenceAllowed = recursive;
            }
        }

        // 派生表：FROM ( ) x
        Matcher derived = DERIVED_REF.matcher(s);
        while (derived.find()) {
            if (inCall[derived.start()]) {
                continue;
            }
            String alias = derived.group("alias");
            if (alias == null || alias.isBlank()) {
                // 没起别名的派生表在多数库里是语法错误，认不出别名就不硬造一个
                continue;
            }
            Scope body = childStartingAt(scope, derived.start("body"));
            scope.relations.put(alias.toLowerCase(Locale.ROOT),
                    derived(alias, Kind.DERIVED, body));
            if (body != null) {
                body.providesAlias = alias.toLowerCase(Locale.ROOT);
            }
        }

        // 普通表引用
        Matcher m = TABLE_REF.matcher(s);
        while (m.find()) {
            if (inCall[m.start()]) {
                continue; // EXTRACT(YEAR FROM d) 里那个 FROM 是函数语法，不是子句
            }
            String table = stripQuotes(m.group("table"));
            if (table.isEmpty()) {
                continue;
            }
            String alias = m.group("alias");
            if (alias != null && !alias.isBlank()) {
                scope.relations.put(alias.toLowerCase(Locale.ROOT),
                        Relation.table(alias, table));
            }
            // 表名自身也能当限定符：SELECT orders.id FROM orders
            String simple = simpleName(table);
            scope.relations.putIfAbsent(simple.toLowerCase(Locale.ROOT),
                    Relation.table(simple, table));
        }
    }

    /**
     * 哪些位置落在<b>函数调用</b>的括号里。
     *
     * <p>为的是 {@code EXTRACT(YEAR FROM o.created_at)} 这一类：里面那个 FROM 是
     * 函数自己的语法，把它当成子句会凭空多出一张叫 {@code created_at} 的「表」。
     * {@code TRIM(BOTH ' ' FROM s)}、{@code SUBSTRING(s FROM 1 FOR 2)} 同理。
     *
     * <p>判据是括号前面那个词：是个普通标识符就当函数名，是 SQL 关键字就当分组括号。
     * 光看「前一个字符是不是字母」不够——{@code FROM (a JOIN b)} 里左括号前面
     * 也是字母（FROM 的 M），那样就会把加了括号的连接一起误伤掉。
     */
    private static boolean[] inFunctionCall(char[] own) {
        boolean[] flags = new boolean[own.length];
        java.util.Deque<Boolean> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < own.length; i++) {
            char c = own[i];
            if (c == '(') {
                // 函数调用里再套括号，里面仍然算在函数调用里
                boolean callSite = !stack.isEmpty() && stack.peek();
                if (!callSite) {
                    callSite = looksLikeFunctionName(wordBefore(own, i));
                }
                stack.push(callSite);
                continue;
            }
            if (c == ')') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                continue;
            }
            flags[i] = !stack.isEmpty() && stack.peek();
        }
        return flags;
    }

    /** 左括号前面紧挨着的那个词。中间隔着空白也算：{@code COUNT (x)} 是合法写法。 */
    private static String wordBefore(char[] own, int parenIndex) {
        int j = parenIndex - 1;
        while (j >= 0 && Character.isWhitespace(own[j])) {
            j--;
        }
        int end = j + 1;
        while (j >= 0 && (Character.isLetterOrDigit(own[j]) || own[j] == '_' || own[j] == '$')) {
            j--;
        }
        return end <= j + 1 ? "" : new String(own, j + 1, end - (j + 1));
    }

    /**
     * 括号前面这个词是函数名，还是一个后面本来就能跟括号的关键字。
     *
     * <p>凡是不在这张关键字表里的标识符，都按函数名处理——自定义函数没法穷举，
     * 而认错方向的代价不对称：把函数当成分组，会凭空多出一张不存在的「表」，
     * 一路补出一堆错的字段；把分组当成函数，最多是少认出一张表。
     */
    private static boolean looksLikeFunctionName(String word) {
        if (word.isEmpty()) {
            return false;
        }
        return !PAREN_KEYWORD.matcher(word).matches();
    }

    /** 这些词后面跟的括号是分组，不是函数调用。 */
    private static final Pattern PAREN_KEYWORD = Pattern.compile(
            "FROM|JOIN|IN|NOT|AND|OR|ON|WHERE|HAVING|VALUES|SET|BY|SELECT|UNION|EXCEPT|"
                    + "INTERSECT|ALL|ANY|SOME|EXISTS|USING|INTO|RETURNING|WHEN|THEN|ELSE|"
                    + "CASE|LIKE|OVER|PARTITION|AS|IS|BETWEEN|WITH|RECURSIVE|LATERAL|"
                    + "TABLE|UPDATE|DELETE|INSERT|KEY|PRIMARY|UNIQUE|INDEX|CHECK|REFERENCES",
            Pattern.CASE_INSENSITIVE);

    private static Relation derived(String alias, Kind kind, Scope body) {
        if (body == null) {
            return new Relation(alias, null, kind, List.of(), true);
        }
        return new Relation(alias, null, kind, body.projection, body.projectsStar);
    }

    /** 找出内容正好从 {@code pos} 开始的那个子作用域。 */
    private static Scope childStartingAt(Scope scope, int pos) {
        if (pos < 0) {
            return null;
        }
        for (Scope child : scope.children) {
            if (child.start == pos) {
                return child;
            }
        }
        return null;
    }

    /**
     * 本层 SELECT 出来的列名。
     *
     * <p>只在本层的括号深度 0 上找 FROM：{@code EXTRACT(YEAR FROM d)} 里那个 FROM
     * 是函数语法的一部分，把它当成子句边界会把整个投影列表截断。
     */
    private static void parseProjection(char[] own, Scope scope) {
        String s = new String(own);
        int select = indexOfKeyword(s, "SELECT", scope.start, scope.end, 0);
        if (select < 0) {
            return;
        }
        int listStart = select + "SELECT".length();
        int from = indexOfKeyword(s, "FROM", listStart, scope.end, 0);
        int listEnd = from < 0 ? scope.end : from;

        List<String> names = new ArrayList<>();
        boolean star = false;
        for (String item : splitTopLevel(s, listStart, listEnd)) {
            String piece = item.trim();
            if (piece.isEmpty()) {
                continue;
            }
            String head = piece.toUpperCase(Locale.ROOT);
            if (head.startsWith("DISTINCT ") || head.startsWith("ALL ")) {
                piece = piece.substring(piece.indexOf(' ') + 1).trim();
            }
            if (piece.equals("*") || piece.endsWith(".*")) {
                star = true;
                continue;
            }
            String name = projectedName(piece);
            if (name != null) {
                names.add(name);
            }
        }
        scope.projection = List.copyOf(names);
        scope.projectsStar = star;
    }

    /** {@code ... AS name} 结尾。 */
    private static final Pattern EXPLICIT_ALIAS = Pattern.compile(
            "\\bAS\\s+[`\"\\[]?([A-Za-z_][\\w$]*)[`\"\\]]?\\s*$", Pattern.CASE_INSENSITIVE);

    /** {@code expr name} 结尾——省掉 AS 的写法。名字必须以字母或下划线开头。 */
    private static final Pattern IMPLICIT_ALIAS = Pattern.compile(
            "^(.*\\S)\\s+[`\"\\[]?([A-Za-z_][\\w$]*)[`\"\\]]?\\s*$");

    /** {@code t.col}。 */
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile(
            "^[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*[`\"\\[]?([\\w$]+)[`\"\\]]?$");

    /** 光秃秃一个列名。 */
    private static final Pattern BARE_COLUMN = Pattern.compile("[`\"\\[]?[\\w$]+[`\"\\]]?");

    /**
     * 一个投影项对外叫什么名字。
     *
     * <p>认不出来就返回 null。{@code COUNT(*)} 这种表达式各家给的默认列名都不一样
     * （MySQL 原样、PostgreSQL 叫 count、H2 又是另一套），编一个出来补给用户，
     * 换个库就是错的。
     */
    private static String projectedName(String item) {
        Matcher as = EXPLICIT_ALIAS.matcher(item);
        if (as.find()) {
            return as.group(1);
        }
        Matcher implicit = IMPLICIT_ALIAS.matcher(item);
        if (implicit.find()) {
            String candidate = implicit.group(2);
            if (!isKeyword(candidate)) {
                return candidate;
            }
        }
        Matcher qualified = QUALIFIED_COLUMN.matcher(item);
        if (qualified.find()) {
            return qualified.group(1);
        }
        if (BARE_COLUMN.matcher(item).matches()) {
            return stripQuotes(item);
        }
        return null;
    }

    /** 按顶层逗号切分，括号里的逗号不算。 */
    private static List<String> splitTopLevel(String s, int from, int to) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < Math.min(to, s.length())) {
            out.add(s.substring(start, Math.min(to, s.length())));
        }
        return out;
    }

    /** 在指定括号深度上找一个关键字。 */
    private static int indexOfKeyword(String s, String keyword, int from, int to, int wantDepth) {
        int depth = 0;
        int limit = Math.min(to, s.length());
        for (int i = Math.max(0, from); i < limit; i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != wantDepth) {
                continue;
            }
            if (!s.regionMatches(true, i, keyword, 0, keyword.length())) {
                continue;
            }
            boolean leftOk = i == 0 || !isWordChar(s.charAt(i - 1));
            int after = i + keyword.length();
            boolean rightOk = after >= s.length() || !isWordChar(s.charAt(after));
            if (leftOk && rightOk) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static final Pattern IS_KEYWORD = Pattern.compile(
            "^(?:" + Keywords.NOT_AN_ALIAS + "|FROM|BY|AND|OR|NOT|NULL|IS|IN|LIKE|BETWEEN"
                    + "|CASE|WHEN|THEN|ELSE|END|DESC|ASC)$",
            Pattern.CASE_INSENSITIVE);

    private static boolean isKeyword(String word) {
        return word != null && IS_KEYWORD.matcher(word).matches();
    }

    static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    static String stripQuotes(String s) {
        return s.replaceAll("[`\"\\[\\]]", "").replaceAll("\\s+", "");
    }
}
