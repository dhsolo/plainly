package com.plainly.driver;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一页脚本切成一条条语句。
 *
 * <p>不是简单 {@code split(";")}——分号可以合法地出现在字符串、标识符和注释里，
 * 按字面切会把 {@code WHERE note = 'a;b'} 拦腰截断，而且报错信息还完全看不出原因。
 * 这里逐字符扫一遍，跳过下面这些「分号不算分隔符」的区域：
 *
 * <ul>
 *   <li>单引号字符串，{@code ''} 转义；MySQL 另有反斜杠转义，由
 *       {@code mysql} 决定——PostgreSQL 默认反斜杠是普通字符，
 *       当成转义会把 {@code 'C:\'} 这种以反斜杠结尾的串读穿；</li>
 *   <li>双引号 / 反引号 / 方括号包起来的标识符；</li>
 *   <li>{@code --} 与 {@code #} 行注释、{@code /* *}{@code /} 块注释；</li>
 *   <li>PostgreSQL 的美元引用 {@code $$ ... $$}、{@code $tag$ ... $tag$}——
 *       函数体里正常带分号，这是脚本切分最经典的翻车点。</li>
 * </ul>
 *
 * <p>认 {@code DELIMITER} 改分隔符——mysqldump 一带触发器或存储过程就会写它，
 * 而它是 mysql 客户端的指令、不是服务端语句，原样发过去只会换来一句语法错误。
 * 不识别 {@code BEGIN ... END} 块结构：靠的是脚本自己用 DELIMITER 把块围起来，
 * 手写脚本里没围的存储过程仍建议单独选中执行。
 */
public final class SqlScript {

    private SqlScript() {
    }

    public static List<String> split(String script) {
        return split(script, false);
    }

    /**
     * @param mysql 按 MySQL 方言切：字符串里反斜杠转义，{@code /*!...} 版本注释算可执行语句。
     *              别的库上这两条都不成立——PostgreSQL 的 {@code 'C:'} 会被读穿，
     *              而 {@code /*!...} 在那边就是一句普通注释，发过去只会白挨一个错
     */
    public static List<String> split(String script, boolean mysql) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return out;
        }

        StringBuilder cur = new StringBuilder();
        String delimiter = ";";
        int i = 0;
        int n = script.length();

        while (i < n) {
            char c = script.charAt(i);

            // DELIMITER 换分隔符。只认行首、且当前语句还没攒下正文的那一处——
            // 别处出现的 delimiter 是列名或字符串，动了它整份脚本就切错了
            if ((c == 'd' || c == 'D') && atLineStart(script, i)
                    && !hasCode(cur.toString(), mysql)) {
                String word = delimiterWordAt(script, i);
                if (word != null) {
                    delimiter = word;
                    flush(out, cur, mysql);
                    cur.setLength(0);
                    i = endOfLine(script, i);
                    continue;
                }
            }

            // 行注释
            if ((c == '-' && i + 1 < n && script.charAt(i + 1) == '-') || c == '#') {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                } else {
                    end++;
                }
                cur.append(script, i, end);
                i = end;
                continue;
            }

            // 块注释
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                cur.append(script, i, end);
                i = end;
                continue;
            }

            // 单引号字符串
            if (c == '\'') {
                int end = endOfQuoted(script, i, '\'', mysql);
                cur.append(script, i, end);
                i = end;
                continue;
            }

            // 带引号的标识符
            if (c == '"' || c == '`') {
                int end = endOfQuoted(script, i, c, false);
                cur.append(script, i, end);
                i = end;
                continue;
            }
            if (c == '[') {
                int end = script.indexOf(']', i + 1);
                end = end < 0 ? n : end + 1;
                cur.append(script, i, end);
                i = end;
                continue;
            }

            // 分隔符可能不止一个字符（mysqldump 常写 $$ 或 ;;）。
            // 这一步要排在美元引用之前：DELIMITER $$ 之后的 END$$ 是句末，
            // 不是 PostgreSQL 的美元引用开头
            if (script.startsWith(delimiter, i)) {
                flush(out, cur, mysql);
                cur.setLength(0);
                i += delimiter.length();
                continue;
            }

            // 美元引用
            if (c == '$') {
                String tag = dollarTagAt(script, i);
                if (tag != null) {
                    int close = script.indexOf(tag, i + tag.length());
                    int end = close < 0 ? n : close + tag.length();
                    cur.append(script, i, end);
                    i = end;
                    continue;
                }
            }

            cur.append(c);
            i++;
        }

        flush(out, cur, mysql);
        return out;
    }

    /** 从 {@code i} 往回到上一个换行之间是不是只有空白。 */
    private static boolean atLineStart(String s, int i) {
        for (int k = i - 1; k >= 0; k--) {
            char c = s.charAt(k);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static int endOfLine(String s, int i) {
        int end = s.indexOf('\n', i);
        return end < 0 ? s.length() : end + 1;
    }

    /**
     * 光标停在行首的 {@code DELIMITER} 上时，返回它指定的新分隔符；不是这条指令则返回 null。
     *
     * <p>要求关键字后面跟空白：{@code DELIMITERS} 这样的表名不该被当成指令。
     */
    private static String delimiterWordAt(String s, int i) {
        final String word = "DELIMITER";
        int after = i + word.length();
        if (!s.regionMatches(true, i, word, 0, word.length())
                || after >= s.length()
                || !isSpaceOrTab(s.charAt(after))) {
            return null;
        }
        int end = s.indexOf('\n', after);
        if (end < 0) {
            end = s.length();
        }
        String rest = s.substring(after, end).trim();
        // 行尾可能带回车（CRLF）或注释，取第一个词就够
        int space = 0;
        while (space < rest.length() && !Character.isWhitespace(rest.charAt(space))) {
            space++;
        }
        String value = rest.substring(0, space);
        return value.isEmpty() ? null : value;
    }

    private static boolean isSpaceOrTab(char c) {
        return c == ' ' || c == '\t';
    }

    /** 光标停在开引号上，返回闭引号之后的位置；没闭合就到文本末尾。 */
    private static int endOfQuoted(String s, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                // 连着两个是转义后的引号本身，不是结束
                if (i + 1 < n && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** {@code $$} 或 {@code $tag$}，返回整个标记；不是美元引用则返回 null。 */
    private static String dollarTagAt(String s, int start) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '$') {
                return s.substring(start, i + 1);
            }
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return null;
            }
            i++;
        }
        return null;
    }

    /**
     * 只丢掉「空白 + 注释」的片段。
     *
     * <p>末尾那个分号后面通常只剩一个换行，切出来是空的，不该算一条语句；
     * 整段都是注释同理——发给驱动只会换来一句难懂的语法错误。
     *
     * <p>{@code /*!...} 在 MySQL 上例外：那种版本注释是要执行的。mysqldump 把整个触发器
     * 都包在 {@code /*!50003 CREATE ... *}{@code /} 里，当成注释丢掉的话，
     * 导一份 dump 会安安静静地少掉所有触发器和存储过程——比报错还难发现。
     */
    private static void flush(List<String> out, StringBuilder cur, boolean mysql) {
        String piece = cur.toString().trim();
        if (!piece.isEmpty() && hasCode(piece, mysql)) {
            out.add(piece);
        }
    }

    /** 去掉注释之后还剩不剩东西。 */
    private static boolean hasCode(String piece, boolean mysql) {
        int i = 0;
        int n = piece.length();
        while (i < n) {
            char c = piece.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if ((c == '-' && i + 1 < n && piece.charAt(i + 1) == '-') || c == '#') {
                int end = piece.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && piece.charAt(i + 1) == '*') {
                if (mysql && i + 2 < n && piece.charAt(i + 2) == '!') {
                    return true;
                }
                int end = piece.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                return true;
            }
        }
        return false;
    }
}
