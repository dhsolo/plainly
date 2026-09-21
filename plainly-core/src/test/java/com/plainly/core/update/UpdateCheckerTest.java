package com.plainly.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.core.store.LocalStore;
import com.plainly.core.store.UiState;
import com.plainly.core.update.UpdateChecker.Status;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 检查流程。
 *
 * <p>整组不碰网络——{@link UpdateChecker.Fetcher} 就是为这件事抽出来的。
 * 顺带把「请求到底发没发出去」记下来：出厂不检查这条承诺，只断言返回值是测不到的，
 * 得看有没有真的去请求。
 */
@DisplayName("更新 · 检查流程")
class UpdateCheckerTest {

    @TempDir
    Path dir;

    private LocalStore local;

    /** 记下每次请求的地址。没被调用过，列表就是空的。 */
    private final List<String> requested = new ArrayList<>();

    private UpdateSettings settings() {
        local = new LocalStore(dir.resolve("plainly.db"));
        return new UpdateSettings(new UiState(local));
    }

    @AfterEach
    void closeStore() {
        if (local != null) {
            local.close();
        }
    }

    private UpdateChecker checker(UpdateSettings settings, String response) {
        return new UpdateChecker(settings, url -> {
            requested.add(url);
            return response;
        });
    }

    private static String latest(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                + "\",\"draft\":false,\"prerelease\":false,"
                + "\"html_url\":\"https://github.com/acme/plainly/releases/tag/" + tag + "\","
                + "\"body\":\"说明\"}";
    }

    /**
     * README 的「安全」一节写着这个工具不做任何遥测。没配地址时必须<b>一个包都不发</b>，
     * 而不是发出去之后把结果丢掉。
     */
    @Test
    @DisplayName("没填地址：不检查，也不发任何请求")
    void notConfiguredSendsNothing() {
        UpdateChecker.Result r = checker(settings(), latest("v0.2.0")).check("0.1.0");
        assertEquals(Status.NOT_CONFIGURED, r.status());
        assertTrue(requested.isEmpty(), "没配地址却发了请求：" + requested);
    }

    @Test
    @DisplayName("从源码跑：版本号取不到，如实说没法比，也不发请求")
    void unknownCurrentVersion() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker.Result r = checker(s, latest("v0.2.0"))
                .check("开发版（从源码运行，没有版本号）");
        assertEquals(Status.UNKNOWN_CURRENT, r.status());
        assertTrue(requested.isEmpty(), "比不了就不必去问");
    }

    @Test
    @DisplayName("有新版本")
    void findsNewer() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker.Result r = checker(s, latest("v0.2.0")).check("0.1.0");
        assertEquals(Status.AVAILABLE, r.status());
        assertTrue(r.isAvailable());
        assertNotNull(r.release());
        assertEquals("0.2.0", r.release().version().normalized());
        assertEquals(List.of("https://api.github.com/repos/acme/plainly/releases/latest"),
                requested);
    }

    @Test
    @DisplayName("已是最新")
    void alreadyLatest() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker.Result r = checker(s, latest("v0.1.0")).check("0.1.0");
        assertEquals(Status.UP_TO_DATE, r.status());
    }

    @Test
    @DisplayName("远端比本机还旧：不提示")
    void remoteOlder() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        assertEquals(Status.UP_TO_DATE, checker(s, latest("v0.1.0")).check("0.2.0").status());
    }

    /**
     * 和 {@code VersionTest} 那条对应，但盯的是整条链路：
     * 只有第四段不同的发布，一路走到结论上必须仍然是「已是最新」。
     */
    @Test
    @DisplayName("只有第四段不同：Windows 装了也不会变，所以不提示")
    void fourthSegmentIsNotAnUpdate() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker.Result r = checker(s, latest("0.1.0.5")).check("0.1.0.4");
        assertEquals(Status.UP_TO_DATE, r.status(),
                "提示了的话，用户会装一个什么都不改变的包，而且全程不报错");
    }

    @Test
    @DisplayName("网络出错：变成一句话，不往上抛")
    void networkFailureBecomesMessage() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker checker = new UpdateChecker(s, url -> {
            throw new java.net.UnknownHostException("api.github.com");
        });
        UpdateChecker.Result r = checker.check("0.1.0");
        assertEquals(Status.FAILED, r.status());
        assertTrue(r.message().contains("api.github.com"));
    }

    @Test
    @DisplayName("对方返回读不懂的东西：说读不出来，并给出可以自己去看的地址")
    void unparseableResponse() {
        UpdateSettings s = settings();
        s.setRepo("acme/plainly");
        UpdateChecker.Result r = checker(s, "{\"message\":\"Not Found\"}").check("0.1.0");
        assertEquals(Status.FAILED, r.status());
        assertTrue(r.message().contains("https://github.com/acme/plainly/releases/latest"));
    }

    @Test
    @DisplayName("跳过按版本记，换个 tag 写法不会重新弹出来")
    void skipIsByNormalizedVersion() {
        UpdateSettings s = settings();
        assertFalse(s.isSkipped(Version.tryParse("0.2.0")));

        s.skip(Version.tryParse("v0.2.0"));
        assertTrue(s.isSkipped(Version.tryParse("0.2.0")), "同一个版本，换了写法");
        assertTrue(s.isSkipped(Version.tryParse("0.2.0.9")), "第四段不改变版本");
        assertFalse(s.isSkipped(Version.tryParse("0.3.0")), "下一版还是要提示");

        s.clearSkipped();
        assertFalse(s.isSkipped(Version.tryParse("0.2.0")));
    }

    @Test
    @DisplayName("地址存的是原文，回显给用户看的也是原文")
    void keepsRepoAsTyped() {
        UpdateSettings s = settings();
        s.setRepo("  https://github.com/acme/plainly  ");
        assertEquals("https://github.com/acme/plainly", s.repoRaw());
        assertEquals("acme/plainly", s.repo(), "拿去拼地址的是归一之后的");
    }
}
