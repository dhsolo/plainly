package com.plainly.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 版本号的解析与比较。
 *
 * <p>这里每一条盯的都是「不报错的错」：版本比错了，界面上只是多一条或少一条提示，
 * 没有任何异常。而多出来的那条会把用户送去下载一个装上去毫无变化的包。
 */
@DisplayName("更新 · 版本号")
class VersionTest {

    @Test
    @DisplayName("常见写法都认得：v 前缀、SNAPSHOT、缺段")
    void parsesCommonForms() {
        assertEquals("0.2.0", Version.tryParse("0.2.0").normalized());
        assertEquals("0.2.0", Version.tryParse("v0.2.0").normalized());
        assertEquals("0.2.0", Version.tryParse("V0.2.0").normalized());
        assertEquals("0.2.0", Version.tryParse("0.2.0-SNAPSHOT").normalized());
        assertEquals("0.2.0", Version.tryParse("0.2.0-rc1").normalized());
        assertEquals("0.2.0", Version.tryParse("0.2.0+build3").normalized());
        assertEquals("0.2.0", Version.tryParse("  0.2.0  ").normalized());
        assertEquals("0.2.0", Version.tryParse("0.2").normalized(), "缺的段按 0 补");
        assertEquals("1.0.0", Version.tryParse("1").normalized());
    }

    @Test
    @DisplayName("原文照留，不把用户看到的版本号改写掉")
    void keepsOriginalText() {
        assertEquals("v0.2.0", Version.tryParse("v0.2.0").text());
        assertEquals("0.2.0-SNAPSHOT", Version.tryParse("0.2.0-SNAPSHOT").text());
    }

    @Test
    @DisplayName("认不出来就是 null，不硬凑一个版本号")
    void refusesNonVersions() {
        assertNull(Version.tryParse(null));
        assertNull(Version.tryParse(""));
        assertNull(Version.tryParse("   "));
        assertNull(Version.tryParse("开发版（从源码运行，没有版本号）"),
                "AboutDialog 在没有 manifest 时返回的就是这一句");
        assertNull(Version.tryParse("latest"));
        assertNull(Version.tryParse(".2.0"));
    }

    @Test
    @DisplayName("超出 MSI 上限的版本不认——那样的 MSI 根本打不出来")
    void refusesOutOfMsiRange() {
        assertNotNull(Version.tryParse("255.255.65535"), "正好在上限上是合法的");
        assertNull(Version.tryParse("256.0.0"));
        assertNull(Version.tryParse("0.256.0"));
        assertNull(Version.tryParse("0.0.65536"));
    }

    @Test
    @DisplayName("按段比大小")
    void comparesBySegment() {
        assertTrue(Version.tryParse("0.2.0").isNewerThan(Version.tryParse("0.1.9")));
        assertTrue(Version.tryParse("1.0.0").isNewerThan(Version.tryParse("0.99.99")));
        assertTrue(Version.tryParse("0.1.10").isNewerThan(Version.tryParse("0.1.9")),
                "按数字比，不是按字符串——字符串比的话 10 < 9");
        assertFalse(Version.tryParse("0.1.0").isNewerThan(Version.tryParse("0.2.0")));
        assertFalse(Version.tryParse("0.2.0").isNewerThan(Version.tryParse("0.2.0")));
    }

    /**
     * 这一条是整个更新功能里最要紧的。
     *
     * <p>Windows 只比前三段。按四段比的话，0.1.0.5 会被当成比 0.1.0.4 新，
     * 于是提示用户去升级——而他装完之后 Windows 认为版本没变，什么都不会发生。
     * 用户看到的是：进度条、「完成」、打开还是旧的，全程不报错。
     */
    @Test
    @DisplayName("第四段被无视：Windows 认为是同一版，就不该提示")
    void ignoresFourthSegmentLikeWindowsDoes() {
        Version older = Version.tryParse("0.1.0.4");
        Version newer = Version.tryParse("0.1.0.5");
        assertEquals("0.1.0", older.normalized());
        assertEquals("0.1.0", newer.normalized());
        assertFalse(newer.isNewerThan(older),
                "只有第四段不同 —— 装上去不会有任何变化，所以不算新版本");
        assertEquals(older, newer);
    }

    @Test
    @DisplayName("本机版本未知时，任何版本都算新的——由调用方决定要不要提示")
    void nullCurrentIsAlwaysOlder() {
        assertTrue(Version.tryParse("0.1.0").isNewerThan(null));
    }
}
