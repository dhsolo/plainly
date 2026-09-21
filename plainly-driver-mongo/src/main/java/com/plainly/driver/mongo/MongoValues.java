package com.plainly.driver.mongo;

import org.bson.BsonType;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * BSON 值和界面上那段文本之间的来回转换。
 *
 * <h2>这个类是这个驱动里最要紧的地方</h2>
 * 整个工具的核心规则是「值一律以文本承载，不经过 double」。在 JDBC 那边
 * 这条规则落在 {@code CellReader} 上；在 MongoDB 这边落在这里。
 *
 * <p>Mongo 的类型比 SQL 还容易出事，因为它有两个长得很像、精度天差地别的类型：
 * <ul>
 *   <li>{@code Double} —— IEEE 754，和 SQL 的 DOUBLE 一样；</li>
 *   <li>{@code Decimal128} —— 34 位十进制有效数字，钱都存在这个类型里。</li>
 * </ul>
 * 把 Decimal128 读成 double 再打印，{@code 9.99} 会变成 {@code 9.9900000000000002}，
 * 而且是<b>安静地</b>变。所以这里对它只做一件事：{@code toString()}，一个字符不动。
 *
 * <h2>写回时的规则：类型跟着原来的走</h2>
 * 网格里所有值都是字符串，写回时必须变回 BSON。要命的地方在于文本 {@code "123"}
 * 既可能该写成整数，也可能该写成字符串——猜错就<b>悄悄改变了文档的结构</b>，
 * 一个本来是字符串的字段变成了数字，之后所有按字符串查它的代码全都查不到。
 *
 * <p>所以改已有字段时不猜：<b>照它原来的类型转</b>（见 {@link #toBson}）。
 * 只有插入新文档、没有原类型可参照时才按写法推断，规则写在 {@link #inferBson} 上。
 */
public final class MongoValues {

    private MongoValues() {
    }

    /** 文档主键的字段名。Mongo 里这个名字是固定的，不是约定。 */
    public static final String ID = "_id";

    /*
     * 什么样的写法才算数字。
     *
     * 这三条正则里有两个刻意的排除，都是真机探针撞出来的：
     *
     * 一、<b>前导零不算数字</b>。"0123" 存成 123 之后前导零就永远找不回来了，
     *     而带前导零的数字串几乎从来不是数字——是邮编、工号、订单号、银行卡号。
     *     单独一个 "0" 例外，"0.5" 这种小数点前的零也例外。
     *
     * 二、<b>带正号的不算数字</b>。用户写数字不会写 "+123"，
     *     但电话号会写成 "+8613800138000"——那是 14 位，正好落进 Int64 的范围，
     *     悄悄变成一个数字之后，前面那个加号也没了。
     *
     * 猜错的代价不对称：把一个数字存成字符串，用户看得见（排序不对劲），
     * 改回来也容易；把一个标识串存成数字，丢掉的字符找不回来，
     * 而且所有按字符串查它的地方从此都查不到。
     */
    private static final java.util.regex.Pattern INTEGER =
            java.util.regex.Pattern.compile("-?(0|[1-9]\\d{0,17})");
    private static final java.util.regex.Pattern HUGE_INTEGER =
            java.util.regex.Pattern.compile("-?[1-9]\\d{18,}");
    private static final java.util.regex.Pattern DECIMAL =
            java.util.regex.Pattern.compile("-?(0|[1-9]\\d*)?\\.\\d+([eE][+-]?\\d+)?");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    // ------------------------------------------------------------------ 读

    /**
     * 一个 BSON 值在网格里显示成什么。
     *
     * <p>返回 {@code null} 表示这个字段在这篇文档里<b>不存在或为 null</b>——
     * 网格会把它显示成 (NULL)。Mongo 里「没有这个字段」和「字段是 null」
     * 是两回事，但在一个按列排布的网格里没法只靠一格文字区分，
     * 真要分辨得看完整文档（双击那一格）。
     */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Decimal128 d) {
            // 钱在这里。绝不能经过 double
            return d.bigDecimalValue().toPlainString();
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            return value.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            // 近似数值本来就是 IEEE 754，读成 double 不丢东西。仍然按文本传递，
            // 一是规则统一，二是 new BigDecimal(double) 会把 0.1 摊成
            // 0.1000000000000000055511151231257827——那是真值，但没人想在格子里看见它
            return value.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (value instanceof Date date) {
            return STAMP.format(date.toInstant()) + " UTC";
        }
        if (value instanceof Instant instant) {
            return STAMP.format(instant) + " UTC";
        }
        if (value instanceof Binary bin) {
            return "Binary(" + bin.getData().length + " 字节) "
                    + Base64.getEncoder().encodeToString(bin.getData());
        }
        if (value instanceof byte[] bytes) {
            return "Binary(" + bytes.length + " 字节) " + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Document doc) {
            return doc.toJson();
        }
        if (value instanceof Map<?, ?> map) {
            return new Document((Map<String, Object>) map).toJson();
        }
        if (value instanceof List<?> list) {
            return listJson(list);
        }
        return value.toString();
    }

    /**
     * 数组转 JSON。
     *
     * <p>自己拼而不用驱动的 {@code toJson}：那个方法只认 {@code Document}，
     * 顶层是数组时用不上。数组在 Mongo 文档里太常见，不能没有显示。
     */
    private static String listJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object item = list.get(i);
            if (item instanceof Document d) {
                sb.append(d.toJson());
            } else if (item instanceof List<?> nested) {
                sb.append(listJson(nested));
            } else if (item instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else {
                sb.append(text(item));
            }
        }
        return sb.append(']').toString();
    }

    /** 这个值在网格里算哪一类，决定对齐方式和导出策略。 */
    public static com.plainly.driver.TypeCategory categoryOf(Object value) {
        if (value instanceof Decimal128 || value instanceof BigDecimal) {
            return com.plainly.driver.TypeCategory.EXACT_NUMERIC;
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return com.plainly.driver.TypeCategory.INTEGER;
        }
        if (value instanceof Double || value instanceof Float) {
            return com.plainly.driver.TypeCategory.APPROX_NUMERIC;
        }
        if (value instanceof Boolean) {
            return com.plainly.driver.TypeCategory.BOOLEAN;
        }
        if (value instanceof Date || value instanceof Instant) {
            return com.plainly.driver.TypeCategory.TEMPORAL;
        }
        if (value instanceof Binary || value instanceof byte[]) {
            return com.plainly.driver.TypeCategory.BINARY;
        }
        if (value instanceof Document || value instanceof Map || value instanceof List) {
            return com.plainly.driver.TypeCategory.JSON;
        }
        return com.plainly.driver.TypeCategory.STRING;
    }

    /** 类型在表头上显示成什么。Mongo 的类型挂在值上，不挂在集合上，所以是按抽样得出的。 */
    public static String typeName(Object value) {
        if (value == null) {
            return "混合";
        }
        if (value instanceof Decimal128) {
            return "Decimal128";
        }
        if (value instanceof ObjectId) {
            return "ObjectId";
        }
        if (value instanceof Long) {
            return "Int64";
        }
        if (value instanceof Integer) {
            return "Int32";
        }
        if (value instanceof Double || value instanceof Float) {
            return "Double";
        }
        if (value instanceof Boolean) {
            return "Boolean";
        }
        if (value instanceof Date || value instanceof Instant) {
            return "Date";
        }
        if (value instanceof Binary || value instanceof byte[]) {
            return "Binary";
        }
        if (value instanceof Document || value instanceof Map) {
            return "Object";
        }
        if (value instanceof List) {
            return "Array";
        }
        return "String";
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把网格里那段文本变回 BSON，<b>照着原来的类型</b>。
     *
     * <h2>为什么必须给原类型</h2>
     * 文本 {@code "123"} 单看是没有类型的。一个原本存字符串 {@code "123"} 的字段，
     * 用户在格子里把它改成 {@code "124"}，如果按「看着像数字就存成数字」来写，
     * 这个字段的类型就<b>被这次编辑悄悄改掉了</b>。之后所有
     * {@code {code: "124"}} 这样的查询再也匹配不上，而没有任何地方报过错。
     *
     * <p>所以这里只做「同类型的值替换」：原来是什么类型，就还写成什么类型。
     * 用户想改类型的话，那是改文档结构，应当去编辑整篇文档的 JSON，
     * 而不是在一个格子里靠写法暗示。
     *
     * @param sample 这个字段原来的值，用来定类型；为 {@code null} 时退回
     *               {@link #inferBson}（新增文档时没有原值可参照）
     */
    public static Object toBson(String text, Object sample) {
        if (text == null) {
            return null;
        }
        if (sample == null) {
            return inferBson(text);
        }
        String trimmed = text.trim();
        try {
            if (sample instanceof Decimal128 || sample instanceof BigDecimal) {
                return new Decimal128(new BigDecimal(trimmed));
            }
            if (sample instanceof Long) {
                return Long.parseLong(trimmed);
            }
            if (sample instanceof Integer) {
                return Integer.parseInt(trimmed);
            }
            if (sample instanceof Double) {
                return Double.parseDouble(trimmed);
            }
            if (sample instanceof Boolean) {
                return Boolean.parseBoolean(trimmed);
            }
            if (sample instanceof ObjectId) {
                return new ObjectId(trimmed);
            }
            if (sample instanceof Document || sample instanceof Map || sample instanceof List) {
                return Document.parse(trimmed);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "「" + text + "」写不成 " + typeName(sample) + "：" + e.getMessage()
                            + "。这一格原来是 " + typeName(sample)
                            + " 类型，改值不会顺带改类型——要换类型请编辑整篇文档", e);
        }
        // 日期、二进制这些不做原地文本编辑：它们的显示形式是给人看的，
        // 不是可逆的写法。真要改就改整篇文档
        if (sample instanceof Date || sample instanceof Instant
                || sample instanceof Binary || sample instanceof byte[]) {
            throw new IllegalArgumentException(
                    typeName(sample) + " 字段不支持在格子里直接改——"
                            + "格子里显示的是给人看的形式，不是能原样写回去的写法。"
                            + "请编辑整篇文档");
        }
        return text;
    }

    /**
     * 没有原值可参照时，按写法推断类型。只用于<b>新增</b>文档。
     *
     * <h2>规则，以及为什么是这一套</h2>
     * <ul>
     *   <li>{@code true} / {@code false} → 布尔；</li>
     *   <li>{@code null} → 空值；</li>
     *   <li>{@code {...}} / {@code [...]} → 按 JSON 解析成子文档或数组；</li>
     *   <li>整数形状的 → Int64（不是 Int32：位数一多就溢出，
     *       而 Mongo 这两种都叫「整数」，选窄的那个只会在某个值上突然失败）。
     *       <b>但带前导零或正号的不算数字</b>，理由见下面那几条正则上的注释；</li>
     *   <li>带小数点的 → <b>Decimal128</b>，不是 Double。
     *       猜错方向的代价不对称：把 9.99 存成 Decimal128 最多是类型比预期精确，
     *       存成 Double 则是精度已经丢了、找不回来；</li>
     *   <li>其余一律字符串。</li>
     * </ul>
     *
     * <p>想要别的类型，写完整篇 JSON 文档去插——那条路是明确的，这条是猜的。
     */
    public static Object inferBson(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return text;
        }
        if (t.equals("true") || t.equals("false")) {
            return Boolean.parseBoolean(t);
        }
        if (t.equals("null")) {
            return null;
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return t.startsWith("{")
                        ? Document.parse(t)
                        : Document.parse("{\"v\":" + t + "}").get("v");
            } catch (RuntimeException e) {
                return text;   // 不是合法 JSON，那就当它是一段普通文字
            }
        }
        if (INTEGER.matcher(t).matches()) {
            return Long.parseLong(t);
        }
        if (DECIMAL.matcher(t).matches() || HUGE_INTEGER.matcher(t).matches()) {
            try {
                return new Decimal128(new BigDecimal(t));
            } catch (RuntimeException e) {
                return text;
            }
        }
        return text;
    }

    /**
     * 把 {@code _id} 那一格的文本变回真正的主键值。
     *
     * <h2>为什么单独一个方法</h2>
     * {@code _id} 决定「改的是哪一篇文档」。它<b>绝大多数</b>是 ObjectId，
     * 但完全可以是字符串、整数，甚至一个子文档——由插入方决定。
     * 猜错的后果不是报错，是<b>改不到任何文档</b>（条件匹配不上），
     * 而 {@code updateOne} 匹配 0 篇不算失败，界面上会显示「已保存」。
     *
     * <p>所以这里要么有原值可照着转，要么按 ObjectId 的形状严格判断：
     * 24 位十六进制才当 ObjectId，其余保持文本。
     */
    public static Object toId(String text, Object sample) {
        if (sample != null) {
            return toBson(text, sample);
        }
        String t = text == null ? "" : text.trim();
        if (t.matches("[0-9a-fA-F]{24}")) {
            return new ObjectId(t);
        }
        return inferBson(t);
    }

    /** BSON 类型枚举转成给人看的名字，用于报错信息。 */
    static String describe(BsonType type) {
        return type == null ? "未知" : type.name();
    }
}
