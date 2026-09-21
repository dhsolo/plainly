package com.plainly.core.imports;

import com.plainly.driver.DbException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 把 JSON 文件读成一行行的值，接到和 CSV 同一条导入管线上。
 *
 * <h2>接受哪几种形状</h2>
 * <ul>
 *   <li>对象数组：{@code [{"id":1,"name":"a"}, {...}]}——本工具导出的就是这一种；</li>
 *   <li>每行一个对象（NDJSON / JSON Lines）：日志和流式导出常见；</li>
 *   <li>单个对象：当成只有一行。</li>
 * </ul>
 * 其它形状（数组套数组、顶层是数字）直接报错。猜一个「大概是这个意思」的解释，
 * 比说不认识更糟——用户会拿到一张内容莫名其妙的表。
 *
 * <h2>为什么要先扫一遍收集字段名</h2>
 * CSV 的列是位置对齐的，第一行就定死了有几列。JSON 不是：第二个对象完全可能
 * 比第一个多一个字段。只看第一个对象定表头，那个多出来的字段会被<b>安静地丢掉</b>——
 * 每一行都导进去了，就是少了一列，而没有任何提示。
 *
 * <p>所以先整份扫一遍取字段名的并集，再第二遍逐行推送。代价是读两次文件，
 * 换来的是「文件里有的字段，映射界面上一定看得见」。
 *
 * <h2>数字为什么按原文取</h2>
 * JSON 的 number 在多数解析器里就是 double，一个 20 位的订单号读进来就变了。
 * 这里不解析数字，直接把它在文件里的那串字符原样取出来——
 * 和整个工具「值全程走文本」的做法是同一条路。
 */
public final class JsonRows {

    /** 解析出来的一个标量值在行里的表示：{@code null} 表示 JSON 的 null。 */
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    private JsonRows() {
    }

    /**
     * 字段名，按第一次出现的顺序。
     *
     * <p>这就是接给导入管线的「表头行」。
     */
    public static List<String> fieldNames(Path file, Charset charset) {
        Set<String> names = new LinkedHashSet<>();
        forEachObject(file, charset, obj -> names.addAll(obj.keySet()));
        return new ArrayList<>(names);
    }

    /**
     * 逐行推送，第一行是表头。
     *
     * <p>形状刻意做得和 {@link CsvParser#forEach} 一样，导入管线那边就不必分两套走。
     */
    public static void forEach(Path file, Charset charset, Consumer<List<String>> consumer) {
        List<String> header = fieldNames(file, charset);
        if (header.isEmpty()) {
            // 一个对象也没有（空文件、空数组）。这时连表头都不该发：
            // 发一个零列的表头，界面会显示成「已解析 0 行」而不是「文件是空的」，
            // 用户会以为文件读到了、只是没数据
            return;
        }
        consumer.accept(header);
        forEachObject(file, charset, obj -> {
            List<String> row = new ArrayList<>(header.size());
            for (String name : header) {
                // 这个对象没有这个字段，和它的值是 JSON null，在这里都记成「没有值」。
                // 两者的区别到了 Importer.convert 那一步反正也保不住（见那边的注释）
                row.add(obj.get(name));
            }
            consumer.accept(row);
        });
    }

    /** 只读前若干行，用来做映射预览。第一行是表头。 */
    public static List<List<String>> head(Path file, Charset charset, int rows) {
        List<List<String>> out = new ArrayList<>();
        forEach(file, charset, row -> {
            if (out.size() < rows) {
                out.add(row);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 把文件里每一个顶层对象推给 {@code consumer}，字段按文件里的顺序。
     *
     * <p>公开出来是给连接配置的导入用：那边要的是「一个对象一条连接」，
     * 而不是 {@link #forEach} 那种对齐到表头的行——连接的字段各不相同
     * （SQLite 只有文件路径，MySQL 有主机端口），对齐成同一张表反而要处理一堆空列。
     */
    public static void forEachObject(Path file, Charset charset,
                                      Consumer<Map<String, String>> consumer) {
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            new Parser(reader).run(consumer);
        } catch (IOException e) {
            throw new DbException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 一个够用的 JSON 读取器。
     *
     * <p>为什么不引第三方库：这个模块目前零 JSON 依赖，而要做的事只有
     * 「把顶层的对象一个个拿出来，值全部当文本」。为此拉进一个解析库，
     * 换来的是一份新的版本与安全维护负担，以及一层会把数字变成 double 的默认行为。
     *
     * <p>刻意<b>不</b>做的：数字的类型判定（一律原文）、注释、单引号字符串。
     * 遇到不认识的东西就报错并说出位置，不去猜。
     */
    private static final class Parser {

        private final Reader reader;
        private int pushback = -2;
        private long offset;

        Parser(Reader reader) {
            this.reader = reader;
        }

        void run(Consumer<Map<String, String>> consumer) throws IOException {
            int c = skipWhitespace();
            if (c < 0) {
                return; // 空文件：没有行，不是错误
            }
            if (c == '[') {
                readArray(consumer);
                return;
            }
            if (c == '{') {
                // 单个对象，或者每行一个对象（NDJSON）。两种走同一条路：
                // 读完一个对象，跳过空白，还有内容就接着读下一个
                push(c);
                while (true) {
                    int next = skipWhitespace();
                    if (next < 0) {
                        return;
                    }
                    if (next != '{') {
                        throw fail("这里应当是一个对象，读到的是 " + display(next));
                    }
                    push(next);
                    consumer.accept(readObject());
                    // NDJSON 里对象之间可能有逗号，也可能没有；两种都放过
                    int sep = skipWhitespace();
                    if (sep < 0) {
                        return;
                    }
                    if (sep != ',') {
                        push(sep);
                    }
                }
            }
            throw fail("顶层要么是对象数组，要么是每行一个对象；读到的是 " + display(c));
        }

        private void readArray(Consumer<Map<String, String>> consumer) throws IOException {
            int c = skipWhitespace();
            if (c == ']') {
                return; // 空数组
            }
            push(c);
            while (true) {
                int start = skipWhitespace();
                if (start != '{') {
                    throw fail("数组里应当全是对象，读到的是 " + display(start)
                            + "。一行一条记录才导得进表里");
                }
                push(start);
                consumer.accept(readObject());

                int sep = skipWhitespace();
                if (sep == ',') {
                    continue;
                }
                if (sep == ']') {
                    return;
                }
                throw fail("对象之后应当是 , 或 ]，读到的是 " + display(sep));
            }
        }

        /** 读一个对象。返回 LinkedHashMap，保住文件里的字段顺序。 */
        private Map<String, String> readObject() throws IOException {
            expect('{');
            Map<String, String> out = new LinkedHashMap<>();
            int c = skipWhitespace();
            if (c == '}') {
                return out;
            }
            push(c);
            while (true) {
                int quote = skipWhitespace();
                if (quote != '"') {
                    throw fail("字段名要用双引号引起来，读到的是 " + display(quote));
                }
                push(quote);
                String key = readString();
                int colon = skipWhitespace();
                if (colon != ':') {
                    throw fail("字段名后面应当是 :，读到的是 " + display(colon));
                }
                out.put(key, readValue());

                int sep = skipWhitespace();
                if (sep == ',') {
                    continue;
                }
                if (sep == '}') {
                    return out;
                }
                throw fail("字段之后应当是 , 或 }，读到的是 " + display(sep));
            }
        }

        /**
         * 读一个值，全部化成文本。
         *
         * <p>嵌套的对象和数组按原文保留：目标列多半是 JSON 或文本类型，
         * 把它压平或者丢掉都不如原样存进去——那至少是不丢信息的一种做法。
         */
        private String readValue() throws IOException {
            int c = skipWhitespace();
            switch (c) {
                case '"':
                    push(c);
                    return readString();
                case '{':
                case '[':
                    push(c);
                    return readRaw((char) c);
                case 't':
                    expectWord("true");
                    return TRUE;
                case 'f':
                    expectWord("false");
                    return FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        push(c);
                        return readNumber();
                    }
                    throw fail("这里应当是一个值，读到的是 " + display(c));
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = read();
                if (c < 0) {
                    throw fail("字符串没有结束的引号");
                }
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append((char) c);
                    continue;
                }
                int esc = read();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readUnicode());
                    default -> throw fail("不认识的转义 \\" + display(esc));
                }
            }
        }

        private char readUnicode() throws IOException {
            char[] hex = new char[4];
            for (int i = 0; i < 4; i++) {
                int c = read();
                if (c < 0) {
                    throw fail("\\u 后面要跟四位十六进制");
                }
                hex[i] = (char) c;
            }
            try {
                return (char) Integer.parseInt(new String(hex), 16);
            } catch (NumberFormatException e) {
                throw fail("\\u" + new String(hex) + " 不是四位十六进制");
            }
        }

        /** 数字原文照抄，一个字符也不解析——解析就是丢精度的那一步。 */
        private String readNumber() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = read();
                if (c < 0) {
                    break;
                }
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    sb.append((char) c);
                    continue;
                }
                push(c);
                break;
            }
            return sb.toString();
        }

        /** 嵌套结构原样抄出来，含引号与转义。 */
        private String readRaw(char open) throws IOException {
            char close = open == '{' ? '}' : ']';
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            while (true) {
                int c = read();
                if (c < 0) {
                    throw fail("嵌套的 " + open + " 没有闭合");
                }
                if (c == '"') {
                    push(c);
                    // 字符串里的括号不算层级，所以整段读出来再原样写回去
                    sb.append('"').append(escape(readString())).append('"');
                    continue;
                }
                sb.append((char) c);
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return sb.toString();
                    }
                }
            }
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }

        private void expectWord(String word) throws IOException {
            for (int i = 1; i < word.length(); i++) {
                int c = read();
                if (c != word.charAt(i)) {
                    throw fail("这里应当是 " + word);
                }
            }
        }

        private void expect(char expected) throws IOException {
            int c = skipWhitespace();
            if (c != expected) {
                throw fail("这里应当是 " + expected + "，读到的是 " + display(c));
            }
        }

        private int skipWhitespace() throws IOException {
            int c;
            do {
                c = read();
            } while (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿');
            return c;
        }

        private int read() throws IOException {
            if (pushback != -2) {
                int c = pushback;
                pushback = -2;
                return c;
            }
            offset++;
            return reader.read();
        }

        private void push(int c) {
            pushback = c;
            offset--;
        }

        private static String display(int c) {
            return c < 0 ? "文件末尾" : "「" + (char) c + "」";
        }

        /** 报错要带位置：一份几万行的 JSON 里说「格式不对」等于什么都没说。 */
        private DbException fail(String message) {
            return new DbException("JSON 第 " + offset + " 个字符处：" + message);
        }
    }
}
