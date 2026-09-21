package com.plainly.core.util;

/**
 * JSON 的重新缩进。
 *
 * <h2>为什么不用 JSON 库</h2>
 * 「解析成对象再序列化回去」是最省事的写法，也是这里绝对不能用的写法：
 * 任何一个 JSON 库读到 {@code 12345678901234567890.123} 都会把它变成 double，
 * 打印出来就成了 {@code 1.2345678901234568E19}。用户点开这个面板，
 * 恰恰是想看清那一串数字到底是什么——把它改掉，这个功能就是负价值。
 *
 * <p>所以这里只做一件事：<b>扫描 + 原样搬运</b>。字符串里的内容一个字节不动，
 * 数字连同它的写法（前导零、指数形式、尾随的 .0）一起照抄，只在结构符号之间
 * 插入换行和缩进。认不出来的输入原样返回，不猜、不修。
 */
public final class JsonText {

    private static final String INDENT = "  ";

    private JsonText() {
    }

    /** 看起来像不像 JSON。只看第一个非空白字符，不做完整校验。 */
    public static boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[';
            }
        }
        return false;
    }

    /**
     * 重新缩进。
     *
     * <p>输入不是合法 JSON（引号没闭合之类）时原样返回：这是个查看器，
     * 遇到坏数据要如实显示坏数据，不是替它补全。
     */
    public static String pretty(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() + json.length() / 4);
        int depth = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"') {
                int end = endOfString(json, i);
                if (end < 0) {
                    return json; // 引号没闭合：原样交还，不猜它想写什么
                }
                out.append(json, i, end + 1);
                i = end;
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue; // 原有的排版一律丢弃，由这里统一重排
            }

            switch (c) {
                case '{':
                case '[':
                    out.append(c);
                    depth++;
                    // 空容器不换行：{} 和 [] 摊成三行只会让人多滚几屏
                    if (nextMeaningful(json, i + 1) == closerFor(c)) {
                        depth--;
                        out.append(closerFor(c));
                        i = indexOfNextMeaningful(json, i + 1);
                        break;
                    }
                    newline(out, depth);
                    break;
                case '}':
                case ']':
                    depth = Math.max(0, depth - 1);
                    newline(out, depth);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    newline(out, depth);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static char closerFor(char opener) {
        return opener == '{' ? '}' : ']';
    }

    private static char nextMeaningful(String s, int from) {
        int i = indexOfNextMeaningful(s, from);
        return i < 0 ? '\0' : s.charAt(i);
    }

    private static int indexOfNextMeaningful(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 字符串字面量的收尾引号位置；没闭合时返回 -1。转义按 JSON 规则处理。 */
    private static int endOfString(String s, int openQuote) {
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++; // 跳过被转义的那个字符，它不结束字符串
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n');
        out.append(INDENT.repeat(depth));
    }

    /**
     * 十六进制转储。
     *
     * <p>用途很具体：一个「看起来一样」的字符串死活匹配不上时，
     * 差别往往是尾随空格、不间断空格、BOM，或者 CRLF 与 LF。
     * 这些东西在任何文本控件里都长得一模一样，只有摊成字节才看得见。
     *
     * @param bytes  要转储的字节
     * @param maxLen 最多转储多少字节，超出部分只报总量，不往界面里灌几兆的十六进制
     */
    public static String hexDump(byte[] bytes, int maxLen) {
        if (bytes == null) {
            return "";
        }
        int n = Math.min(bytes.length, maxLen);
        StringBuilder out = new StringBuilder(n * 4 + 64);
        for (int off = 0; off < n; off += 16) {
            out.append(String.format("%08X  ", off));
            StringBuilder ascii = new StringBuilder(16);
            for (int i = 0; i < 16; i++) {
                if (off + i < n) {
                    int b = bytes[off + i] & 0xFF;
                    out.append(String.format("%02X ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    out.append("   ");
                }
                if (i == 7) {
                    out.append(' ');
                }
            }
            out.append(' ').append(ascii).append('\n');
        }
        if (bytes.length > n) {
            out.append("… 共 ").append(bytes.length).append(" 字节，以上只显示前 ")
                    .append(n).append(" 字节\n");
        }
        return out.toString();
    }
}
