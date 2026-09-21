package com.plainly.driver.redis;

import com.plainly.driver.DbException;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link RedisJson} 写出的那几种 JSON 读回来。
 *
 * <h2>为什么自己写，而不是引一个 JSON 库</h2>
 * 要处理的形状只有三种（字符串数组、字符串对象、二元组数组），而引一个库要连带
 * 它的对象模型、类型系统和一整套依赖。更要紧的是<b>数字</b>：通用 JSON 库会把
 * {@code 9223372036854775807} 解析成一个数值类型，再转回文本时未必还是原样——
 * 本项目最不能接受的就是这条路。这里<b>不解析数字</b>，遇到数字字面量就把它的
 * 原文照抄下来当字符串，一位不动。
 *
 * <h2>宽进严出</h2>
 * 输入是人手打进去的，所以宽一点：{@code {"a":1}} 里的 1 当成字符串 "1" 收下——
 * Redis 里本来也只有字符串，用户写不写引号表达的是同一件事。
 * 但语法错误一律<b>报错并指出位置</b>，绝不猜：猜错了会静默存进去一个不对的值。
 */
final class RedisJsonReader {

    private final String text;
    private int at;

    private RedisJsonReader(String text) {
        this.text = text;
    }

    /** {@code ["a","b"]} → [a, b]。 */
    static List<String> array(String json) {
        RedisJsonReader r = new RedisJsonReader(json);
        List<String> out = r.readArray();
        r.expectEnd();
        return out;
    }

    /** {@code {"k":"v"}} → [k, v, ...]，拉平成一串，和 Redis 的 HGETALL 一致。 */
    static List<String> flatObject(String json) {
        RedisJsonReader r = new RedisJsonReader(json);
        List<String> out = r.readObject();
        r.expectEnd();
        return out;
    }

    /**
     * {@code [["成员","分数"],...]} → [成员, 分数, ...]。
     *
     * <p>顺序是「成员在前、分数在后」，和导出时写出来的一致；
     * 而 Redis 的 {@code ZADD} 要的是<b>分数在前</b>，那一步的对调由调用方做。
     */
    static List<String> scored(String json) {
        RedisJsonReader r = new RedisJsonReader(json);
        List<String> out = new ArrayList<>();
        r.skipSpace();
        r.expect('[');
        r.skipSpace();
        if (r.peek() == ']') {
            r.at++;
            r.expectEnd();
            return out;
        }
        while (true) {
            r.skipSpace();
            List<String> pair = r.readArray();
            if (pair.size() != 2) {
                throw r.fail("有序集合的每一项要写成 [\"成员\", \"分数\"] 两个元素，"
                        + "实得 " + pair.size() + " 个");
            }
            out.add(pair.get(0));
            out.add(pair.get(1));
            r.skipSpace();
            if (r.peek() == ',') {
                r.at++;
                continue;
            }
            r.expect(']');
            break;
        }
        r.expectEnd();
        return out;
    }

    // ------------------------------------------------------------------ 解析

    private List<String> readArray() {
        skipSpace();
        expect('[');
        List<String> out = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            out.add(readScalar());
            skipSpace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return out;
        }
    }

    private List<String> readObject() {
        skipSpace();
        expect('{');
        List<String> out = new ArrayList<>();
        skipSpace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw fail("对象的字段名要用双引号括起来");
            }
            out.add(readString());
            skipSpace();
            expect(':');
            out.add(readScalar());
            skipSpace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    /**
     * 一个标量：字符串、数字、true / false / null。
     *
     * <p>数字<b>照抄原文</b>，不经过任何数值类型。嵌套的对象和数组这里不收——
     * Redis 的哈希值就是一串字节，装不下嵌套结构；真要存嵌套的 JSON，
     * 那是把整段 JSON 作为一个字符串存进去，写法是把它当字符串写（带引号并转义）。
     */
    private String readScalar() {
        skipSpace();
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '{' || c == '[') {
            throw fail("这里只能放一个值（字符串或数字），不能再嵌套对象或数组。"
                    + "要存一整段 JSON，请把它作为字符串写：整体加引号，内部的引号用 \\\" 转义");
        }
        int start = at;
        while (at < text.length() && ",]}\r\n\t ".indexOf(text.charAt(at)) < 0) {
            at++;
        }
        if (start == at) {
            throw fail("这里缺一个值");
        }
        String raw = text.substring(start, at);
        if (raw.equals("null")) {
            return "";
        }
        return raw;
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw fail("字符串没有收尾的双引号");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw fail("反斜杠后面没有内容");
            }
            char esc = text.charAt(at++);
            switch (esc) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'u':
                    if (at + 4 > text.length()) {
                        throw fail("\\u 后面要跟四位十六进制");
                    }
                    sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    throw fail("认不得的转义 \\" + esc);
            }
        }
    }

    // ------------------------------------------------------------------ 辅助

    private char peek() {
        if (at >= text.length()) {
            throw fail("内容到这儿就没了");
        }
        return text.charAt(at);
    }

    private void expect(char c) {
        skipSpace();
        if (at >= text.length() || text.charAt(at) != c) {
            throw fail("这里应当是 " + c);
        }
        at++;
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private void expectEnd() {
        skipSpace();
        if (at < text.length()) {
            throw fail("后面还有多余的内容");
        }
    }

    /**
     * 报错时带上位置和那一段原文。
     *
     * <p>只说「JSON 格式错误」，用户面对一段几百字符的文本无从下手。
     */
    private DbException fail(String message) {
        int from = Math.max(0, at - 12);
        int to = Math.min(text.length(), at + 12);
        String around = text.substring(from, to).replace("\n", " ");
        return new DbException("值的格式不对（第 " + (at + 1) + " 个字符附近）：" + message
                + "\n…" + around + "…");
    }
}
