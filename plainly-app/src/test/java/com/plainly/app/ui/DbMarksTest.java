package com.plainly.app.ui;

import com.plainly.driver.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 树上那个种类标记，必须真的能把种类分开。
 *
 * <h2>为什么值得一个测试</h2>
 * 这件事坏掉的时候不会报错，也不会在任何截图上看出问题——只有用户在一棵
 * 装着十条连接的树上找不到自己要的那条。加 MongoDB 时就破过一次：
 * 它拿了和 MySQL 一样的 {@code M}，只剩底色在区分，而
 * {@code DbMarks} 自己的注释写着「颜色是辅助，字母才是判据」。
 *
 * <p>这些检查跑起来只要几毫秒，却能保证下一个加库的人在<b>写代码的时候</b>
 * 就发现字母撞了，而不是等用户来说「这俩图标看着一样」。
 */
class DbMarksTest {

    @Test
    @DisplayName("每一种库的字母都不一样——字母是判据，不能靠颜色兜底")
    void lettersAreUnique() {
        Map<String, DbType> seen = new LinkedHashMap<>();
        for (DbType type : DbType.values()) {
            String letter = DbMarks.letter(type);
            DbType other = seen.put(letter, type);
            assertTrue(other == null,
                    "「" + letter + "」被 " + other + " 和 " + type + " 同时用了。"
                            + "颜色只是辅助（色觉差异、偏色、灰度打印都可能让两个块看着一样），"
                            + "分开它们的必须是字母");
        }
    }

    @Test
    @DisplayName("每一种库的底色也都不一样")
    void colorsAreUnique() {
        Map<String, DbType> seen = new LinkedHashMap<>();
        for (DbType type : DbType.values()) {
            String color = DbMarks.color(type);
            DbType other = seen.put(color, type);
            assertTrue(other == null,
                    color + " 被 " + other + " 和 " + type + " 同时用了");
        }
    }

    /**
     * 两个字符在 15px 的块里每个只剩五像素宽，糊成一团。
     * 这一条约束的是「别想着用缩写解决字母不够用」。
     */
    @Test
    @DisplayName("标记上只有一个字，且不是占位的问号")
    void everyTypeHasARealSingleGlyph() {
        for (DbType type : DbType.values()) {
            String letter = DbMarks.letter(type);
            assertEquals(1, letter.codePointCount(0, letter.length()),
                    type + " 的标记不是一个字：" + letter);
            assertFalse("?".equals(letter), type + " 还没有分配标记");
        }
    }

    @Test
    @DisplayName("颜色是能解析的十六进制，不会到画的时候才炸")
    void colorsParse() {
        for (DbType type : DbType.values()) {
            javafx.scene.paint.Color.web(DbMarks.color(type));
        }
    }
}
