package com.plainly.driver.mongo;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BSON 与文本之间的来回转换。
 *
 * <p>这一组测试守的是整个工具的那条核心规则：值不经过 double。
 * MongoDB 上它比别处更容易破，因为 Decimal128 和 Double 在界面上长得一模一样。
 */
class MongoValuesTest {

    // ------------------------------------------------------------------ 读

    @Test
    @DisplayName("Decimal128 原样保留，一位不改")
    void decimal128KeepsEveryDigit() {
        String raw = "12345678901234567890.1234567890123";
        assertEquals(raw, MongoValues.text(Decimal128.parse(raw)));
    }

    @Test
    @DisplayName("钱那种两位小数不会变成 9.9900000000000002")
    void moneyStaysExact() {
        assertEquals("9.99", MongoValues.text(Decimal128.parse("9.99")));
    }

    @Test
    @DisplayName("Int64 的 19 位数不丢——double 只扛得住 15 位")
    void int64KeepsAllDigits() {
        long big = 9223372036854775807L;
        assertEquals("9223372036854775807", MongoValues.text(big));
    }

    @Test
    @DisplayName("ObjectId 显示成十六进制，能原样转回去")
    void objectIdRoundTrips() {
        ObjectId oid = new ObjectId();
        String text = MongoValues.text(oid);
        assertEquals(oid.toHexString(), text);
        assertEquals(oid, MongoValues.toId(text, null));
    }

    @Test
    @DisplayName("字段不存在或为 null 时返回 null，网格显示 (NULL)")
    void nullBecomesNull() {
        assertNull(MongoValues.text(null));
    }

    @Test
    @DisplayName("子文档和数组显示成 JSON")
    void nestedRendersAsJson() {
        assertTrue(MongoValues.text(new Document("a", 1)).contains("\"a\""));
        String list = MongoValues.text(java.util.List.of(1, "x"));
        assertTrue(list.startsWith("[") && list.endsWith("]"), list);
        assertTrue(list.contains("\"x\""), list);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 这一条是整个类存在的理由。
     *
     * <p>一个存字符串 {@code "123"} 的字段，用户把它改成 {@code "124"}。
     * 如果按「看着像数字就存成数字」来写，这个字段的类型就被这次编辑
     * 悄悄改掉了，而没有任何地方报错。
     */
    @Test
    @DisplayName("改值不会顺带改类型：字符串字段改完还是字符串")
    void updateKeepsOriginalType() {
        Object written = MongoValues.toBson("124", "123");
        assertInstanceOf(String.class, written);
        assertEquals("124", written);
    }

    @Test
    @DisplayName("整数字段改完还是整数，不会变成字符串")
    void integerStaysInteger() {
        assertInstanceOf(Long.class, MongoValues.toBson("42", 7L));
        assertInstanceOf(Integer.class, MongoValues.toBson("42", 7));
    }

    @Test
    @DisplayName("Decimal128 字段改完仍是 Decimal128，且值精确")
    void decimalStaysDecimal() {
        Object written = MongoValues.toBson("0.30", Decimal128.parse("0.10"));
        assertInstanceOf(Decimal128.class, written);
        assertEquals(new BigDecimal("0.30"), ((Decimal128) written).bigDecimalValue());
    }

    @Test
    @DisplayName("写不成原来的类型时当场报错，并说清为什么")
    void incompatibleValueIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MongoValues.toBson("不是数字", 7L));
        assertTrue(e.getMessage().contains("Int64"), e.getMessage());
    }

    @Test
    @DisplayName("日期和二进制不给在格子里直接改——显示的形式不是能写回去的写法")
    void dateAndBinaryRefuseInlineEdit() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoValues.toBson("2026-01-01 00:00:00.000 UTC", new java.util.Date()));
        assertThrows(IllegalArgumentException.class,
                () -> MongoValues.toBson("x", new org.bson.types.Binary(new byte[] {1})));
    }

    // ------------------------------------------------------------------ 新增文档时的推断

    @Test
    @DisplayName("新增文档：整数形状的存成 Int64，不是 Int32")
    void inferPrefersInt64() {
        assertInstanceOf(Long.class, MongoValues.inferBson("42"));
    }

    /**
     * 猜错方向的代价不对称。
     *
     * <p>把 9.99 存成 Decimal128，最坏是类型比预期精确；存成 Double，
     * 精度当场就丢了，而且找不回来。
     */
    @Test
    @DisplayName("新增文档：带小数点的存成 Decimal128 而不是 Double")
    void inferPrefersDecimalOverDouble() {
        Object v = MongoValues.inferBson("9.99");
        assertInstanceOf(Decimal128.class, v);
        assertEquals(new BigDecimal("9.99"), ((Decimal128) v).bigDecimalValue());
    }

    @Test
    @DisplayName("新增文档：超过 18 位的整数走 Decimal128，不会溢出成负数")
    void hugeIntegerDoesNotOverflow() {
        Object v = MongoValues.inferBson("123456789012345678901234");
        assertInstanceOf(Decimal128.class, v);
        assertEquals(new BigDecimal("123456789012345678901234"),
                ((Decimal128) v).bigDecimalValue());
    }

    /**
     * 这一条是真机探针撞出来的，离线测试当时全绿。
     *
     * <p>{@code "0123"} 被存成了 {@code 123}——前导零永远找不回来了。
     * 而带前导零的数字串几乎从来不是数字：邮编、工号、订单号、银行卡号。
     */
    @Test
    @DisplayName("新增文档：带前导零的不算数字，原样存成字符串")
    void leadingZeroStaysString() {
        assertEquals("0123", MongoValues.inferBson("0123"));
        assertEquals("00", MongoValues.inferBson("00"));
        assertEquals("-0123", MongoValues.inferBson("-0123"));
        // 单独一个 0 还是数字；小数点前的 0 也正常
        assertInstanceOf(Long.class, MongoValues.inferBson("0"));
        assertInstanceOf(Decimal128.class, MongoValues.inferBson("0.5"));
    }

    /**
     * 电话号写成 {@code +8613800138000} 正好 14 位，落在 Int64 范围里。
     * 悄悄变成数字之后，前面那个加号也没了。
     */
    @Test
    @DisplayName("新增文档：带正号的不算数字（电话号）")
    void leadingPlusStaysString() {
        assertEquals("+8613800138000", MongoValues.inferBson("+8613800138000"));
        assertEquals("+123", MongoValues.inferBson("+123"));
        // 负号是正常的数字写法，仍然当数字
        assertInstanceOf(Long.class, MongoValues.inferBson("-123"));
    }

    @Test
    @DisplayName("新增文档：布尔、null、JSON 各按写法来，其余当字符串")
    void inferHandlesTheRest() {
        assertEquals(Boolean.TRUE, MongoValues.inferBson("true"));
        assertNull(MongoValues.inferBson("null"));
        assertInstanceOf(Document.class, MongoValues.inferBson("{\"a\": 1}"));
        assertInstanceOf(java.util.List.class, MongoValues.inferBson("[1, 2]"));
        assertEquals("abc", MongoValues.inferBson("abc"));
    }

    /**
     * {@code _id} 猜错不会报错，只会<b>匹配不到任何文档</b>——
     * 而 updateOne 匹配 0 篇不算失败。所以这一条必须严格。
     */
    @Test
    @DisplayName("_id：正好 24 位十六进制才当 ObjectId")
    void idOnlyBecomesObjectIdWhenItLooksLikeOne() {
        assertInstanceOf(ObjectId.class, MongoValues.toId("507f1f77bcf86cd799439011", null));
        // 23 位、25 位、含非十六进制字符的，都不是 ObjectId
        assertEquals("507f1f77bcf86cd79943901", MongoValues.toId("507f1f77bcf86cd79943901", null));
        assertEquals("zzzf1f77bcf86cd799439011",
                MongoValues.toId("zzzf1f77bcf86cd799439011", null));
    }

    @Test
    @DisplayName("_id 是字符串或整数的集合，照原类型转")
    void idFollowsExistingType() {
        assertEquals("u-1", MongoValues.toId("u-1", "u-0"));
        assertInstanceOf(Long.class, MongoValues.toId("2", 1L));
    }
}
