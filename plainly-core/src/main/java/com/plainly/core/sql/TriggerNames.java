package com.plainly.core.sql;

import java.util.Locale;

/**
 * 从一条 {@code CREATE TRIGGER} 语句里认出触发器名。
 *
 * <h2>为什么需要它</h2>
 * 有些数据库的触发器是<b>整条语句</b>编辑的（SQLite 的元数据里存的就是整条）。
 * 那种模式下，新建对话框上面那个「名称」输入框只是个说明——真正生效的名字
 * 写在下面那段语句里。两处一旦不一致，用户以为自己在建 A，实际动的是 B；
 * 而在支持 {@code CREATE OR REPLACE} 的库上，"动的是 B" 意味着<b>把 B 覆盖掉</b>。
 *
 * <p>所以提交前要把语句里的名字抠出来和输入框比一遍。这段解析因此不是锦上添花，
 * 它的结论会被用来<b>拦住</b>用户——认错了就是拦住正确的操作，
 * 所以宁可返回 {@code null}（不拦），也不猜。
 */
public final class TriggerNames {

    private TriggerNames() {
    }

    /**
     * 这条建触发器语句会不会<b>静默覆盖</b>同名的那个。
     *
     * <h2>要认的是 {@code CREATE OR REPLACE TRIGGER} 这一整句</h2>
     * 不能只看开头是不是 {@code CREATE OR REPLACE}。PostgreSQL 那段的开头正是
     * {@code CREATE OR REPLACE FUNCTION}——可那个 OR REPLACE 管的是<b>函数</b>，
     * 后面挂触发器用的还是光秃秃的 {@code CREATE TRIGGER}，撞名一定被拒。
     *
     * <p>判错的后果有两个，方向还相反：
     * <ul>
     *   <li>新建时撞名，本该直接拦下说「换个名字」，却弹出「会覆盖掉原来那个，
     *       确定继续？」——用户点了继续，撞回来的是数据库的原始报错。
     *       <b>对话框承诺了一件做不到的事。</b></li>
     *   <li>改已有触发器时以为不必先删，于是不发 DROP，接着
     *       {@code CREATE TRIGGER} 报 already exists——<b>改触发器整个走不通。</b></li>
     * </ul>
     *
     * <p>会发这一整句的只有 Oracle 和达梦，正是真会静默覆盖的那两家。
     */
    public static boolean replacesExisting(String ddl) {
        if (ddl == null) {
            return false;
        }
        String upper = ddl.toUpperCase(Locale.ROOT);
        int at = upper.indexOf("CREATE");
        while (at >= 0) {
            int i = at + "CREATE".length();
            i = skipWord(upper, i, "OR");
            if (i > 0) {
                int j = skipWord(upper, i, "REPLACE");
                if (j > 0 && skipWord(upper, j, "TRIGGER") > 0) {
                    return true;
                }
            }
            at = upper.indexOf("CREATE", at + 1);
        }
        return false;
    }

    /**
     * 跳过一个指定的词；下一个词不是它就返回 -1。
     *
     * <p>按词比对而不是 {@code contains}：{@code contains} 会把
     * {@code CREATE OR REPLACE TRIGGERS_LOG} 这种名字也算进去，
     * 而多出来的空白、换行又不能当成不一样。
     */
    private static int skipWord(String upper, int from, String word) {
        int i = from;
        while (i < upper.length() && Character.isWhitespace(upper.charAt(i))) {
            i++;
        }
        if (!upper.startsWith(word, i)) {
            return -1;
        }
        int end = i + word.length();
        if (end < upper.length() && (Character.isLetterOrDigit(upper.charAt(end))
                || upper.charAt(end) == '_')) {
            return -1;
        }
        return end;
    }

    /**
     * 认出语句里的触发器名；认不出返回 {@code null}。
     *
     * <p>返回的名字<b>不含</b>引号和模式限定：{@code "shenjian"."ss_after_insert"}
     * 得到的是 {@code ss_after_insert}。比较的是「叫什么」，不是「写法一不一样」。
     */
    public static String parse(String ddl) {
        if (ddl == null) {
            return null;
        }
        String upper = ddl.toUpperCase(Locale.ROOT);
        int at = upper.indexOf("TRIGGER");
        if (at < 0) {
            return null;
        }
        int i = skipOptionalWords(ddl, upper, at + "TRIGGER".length());
        if (i >= ddl.length()) {
            return null;
        }
        char open = ddl.charAt(i);
        String name = (open == '"' || open == '`' || open == '[')
                ? readQuoted(ddl, i, open)
                : readBare(ddl, i);
        return name.isEmpty() ? null : name;
    }

    /**
     * 跳过 {@code IF NOT EXISTS}。
     *
     * <p>SQLite 写得出 {@code CREATE TRIGGER IF NOT EXISTS name}。不跳的话，
     * {@code IF} 会被当成触发器名，然后拿它去拦一个本来完全正确的操作。
     */
    private static int skipOptionalWords(String ddl, String upper, int from) {
        int i = from;
        while (true) {
            while (i < ddl.length() && Character.isWhitespace(ddl.charAt(i))) {
                i++;
            }
            String rest = upper.substring(Math.min(i, upper.length()));
            String matched = null;
            for (String word : new String[]{"IF ", "NOT ", "EXISTS "}) {
                if (rest.startsWith(word)) {
                    matched = word;
                    break;
                }
            }
            if (matched == null) {
                return i;
            }
            i += matched.length() - 1;
        }
    }

    /** 带引号的标识符，可能还带着模式限定：{@code "库"."名字"}。 */
    private static String readQuoted(String ddl, int from, char open) {
        char close = open == '[' ? ']' : open;
        StringBuilder token = new StringBuilder();
        int i = from;
        while (i < ddl.length()) {
            char c = ddl.charAt(i++);
            if (c == open || c == close) {
                continue;
            }
            if (c == '.') {
                token.setLength(0);   // 前面那截是模式名，丢掉
                continue;
            }
            if (Character.isWhitespace(c) || c == '(') {
                break;
            }
            token.append(c);
        }
        return token.toString().trim();
    }

    private static String readBare(String ddl, int from) {
        StringBuilder token = new StringBuilder();
        int i = from;
        while (i < ddl.length()) {
            char c = ddl.charAt(i++);
            if (Character.isWhitespace(c) || c == '(') {
                break;
            }
            if (c == '.') {
                token.setLength(0);
                continue;
            }
            token.append(c);
        }
        return token.toString().trim();
    }
}
