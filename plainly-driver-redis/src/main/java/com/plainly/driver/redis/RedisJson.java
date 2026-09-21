package com.plainly.driver.redis;

import java.util.List;

/**
 * 把集合类的值写成 JSON。
 *
 * <h2>为什么导出时要换一种写法</h2>
 * 网格里哈希显示成 {@code name = 张三, city = 杭州}——那是<b>给眼睛看的</b>，
 * 逗号和等号既是分隔符也可能是数据本身，机器读不回去。
 * 导出的文件是要被别的程序读的，得用一种有转义规则的写法。
 *
 * <p>只做「写」这一半，而且只写字符串、数组、对象三种：Redis 的值全是字节串，
 * <b>不存在数字类型</b>。所以这里也绝不把任何东西写成 JSON 数字——
 * 一个存着 {@code 9223372036854775807} 的键，写成数字就丢精度了，
 * 而它本来只是一串字符。
 */
final class RedisJson {

    private RedisJson() {
    }

    /** 一个 JSON 字符串字面量，含首尾引号。 */
    static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append('\\').append('"');
                    break;
                case '\\':
                    sb.append('\\').append('\\');
                    break;
                case '\n':
                    sb.append('\\').append('n');
                    break;
                case '\r':
                    sb.append('\\').append('r');
                    break;
                case '\t':
                    sb.append('\\').append('t');
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** {@code ["a","b"]}。 */
    static String array(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(string(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /**
     * {@code {"k":"v"}}，参数是<b>拉平</b>的键值序列。
     *
     * <p>拉平是因为 Redis 的 {@code HGETALL} 本来就是这么回的：一个数组，
     * 奇数位是字段名、偶数位是值。中间再转成 Map 一趟，反而会丢掉重复字段
     * 这种异常情况（正常不该有，但真出现时静静吃掉一个更糟）。
     */
    static String object(List<String> flatPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < flatPairs.size(); i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(string(flatPairs.get(i))).append(':').append(string(flatPairs.get(i + 1)));
        }
        return sb.append('}').toString();
    }

    /**
     * 有序集合：{@code [["成员","分数"],...]}。
     *
     * <p>分数也写成字符串。Redis 的分数是双精度浮点，服务端回的是它的文本形式；
     * 再解析成数字写回 JSON，等于让它多过一道浮点转换——本项目不做这种事。
     */
    static String scored(List<String> flatPairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < flatPairs.size(); i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(string(flatPairs.get(i))).append(',')
                    .append(string(flatPairs.get(i + 1))).append(']');
        }
        return sb.append(']').toString();
    }
}
