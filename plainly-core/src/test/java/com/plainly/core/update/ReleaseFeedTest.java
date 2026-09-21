package com.plainly.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发布地址的归一，与 GitHub 响应的解析。
 *
 * <p>样例 JSON 照 {@code releases/latest} 的真实形状写，包含用不上的嵌套字段
 * （{@code author}、{@code assets}）——它们正是解析容易栽的地方，
 * 去掉之后这组用例就测不到真实情况了。
 */
@DisplayName("更新 · 发布清单")
class ReleaseFeedTest {

    @Test
    @DisplayName("owner/repo 的几种写法都认，因为用户就是从地址栏粘过来的")
    void normalizesRepo() {
        assertEquals("acme/plainly", ReleaseFeed.normalizeRepo("acme/plainly"));
        assertEquals("acme/plainly", ReleaseFeed.normalizeRepo("  acme/plainly  "));
        assertEquals("acme/plainly",
                ReleaseFeed.normalizeRepo("https://github.com/acme/plainly"));
        assertEquals("acme/plainly",
                ReleaseFeed.normalizeRepo("https://www.github.com/acme/plainly/"));
        assertEquals("acme/plainly", ReleaseFeed.normalizeRepo("github.com/acme/plainly"));
        assertEquals("acme/plainly",
                ReleaseFeed.normalizeRepo("https://github.com/acme/plainly.git"));
    }

    @Test
    @DisplayName("认不出来就是 null，不去请求一个拼出来的地址")
    void refusesJunkRepo() {
        assertNull(ReleaseFeed.normalizeRepo(null));
        assertNull(ReleaseFeed.normalizeRepo(""));
        assertNull(ReleaseFeed.normalizeRepo("plainly"), "少了 owner");
        assertNull(ReleaseFeed.normalizeRepo("a/b/c"));
        assertNull(ReleaseFeed.normalizeRepo("acme/"));
        assertNull(ReleaseFeed.normalizeRepo("acme/pla inly"), "空格");
        assertNull(ReleaseFeed.normalizeRepo("acme/../../etc"),
                "会被拼进 URL 的东西，不能放行路径片段");
    }

    private static final String LATEST = """
            {
              "url": "https://api.github.com/repos/acme/plainly/releases/1",
              "html_url": "https://github.com/acme/plainly/releases/tag/v0.2.0",
              "id": 1,
              "author": { "login": "someone", "id": 7 },
              "tag_name": "v0.2.0",
              "name": "0.2.0 精度与触发器",
              "draft": false,
              "prerelease": false,
              "assets": [
                { "name": "Plainly-0.2.0.msi", "size": 95891323 }
              ],
              "body": "修了达梦的触发器列名\\n新增 openGauss 精度自检"
            }""";

    @Test
    @DisplayName("取出版本、标题、说明与发布页")
    void parsesLatest() {
        Release r = ReleaseFeed.parse(LATEST, "acme/plainly");
        assertNotNull(r);
        assertEquals("0.2.0", r.version().normalized());
        assertEquals("v0.2.0", r.version().text(), "显示给用户的是发布方写的原文");
        assertEquals("0.2.0 精度与触发器", r.title());
        assertEquals("https://github.com/acme/plainly/releases/tag/v0.2.0", r.pageUrl());
        assertTrue(r.notes().contains("达梦"));
        assertTrue(r.notes().contains(System.lineSeparator())
                        || r.notes().contains("\n"),
                "说明里的换行要真的是换行，不是两个字符 backslash-n");
    }

    @Test
    @DisplayName("草稿和预发布一律不提示")
    void skipsDraftAndPrerelease() {
        assertNull(ReleaseFeed.parse(LATEST.replace("\"draft\": false", "\"draft\": true"),
                "acme/plainly"));
        assertNull(ReleaseFeed.parse(
                LATEST.replace("\"prerelease\": false", "\"prerelease\": true"),
                "acme/plainly"));
    }

    @Test
    @DisplayName("tag 不是版本号就当没读到，不猜")
    void refusesNonVersionTag() {
        assertNull(ReleaseFeed.parse(LATEST.replace("\"v0.2.0\"", "\"nightly\""),
                "acme/plainly"));
    }

    @Test
    @DisplayName("字段缺了也要给得出发布页——否则按钮点下去没地方可去")
    void fallsBackWhenFieldsMissing() {
        String noUrl = LATEST.replace(
                "\"html_url\": \"https://github.com/acme/plainly/releases/tag/v0.2.0\",", "");
        Release r = ReleaseFeed.parse(noUrl, "acme/plainly");
        assertNotNull(r);
        assertEquals("https://github.com/acme/plainly/releases/latest", r.pageUrl());

        String noName = LATEST.replace("\"name\": \"0.2.0 精度与触发器\",", "");
        assertEquals("v0.2.0", ReleaseFeed.parse(noName, "acme/plainly").title(),
                "没写标题就用 tag，不要显示一个空白");
    }

    @Test
    @DisplayName("空的、不是 JSON 的，返回 null 而不是炸在网络线程上")
    void handlesGarbage() {
        assertNull(ReleaseFeed.parse(null, "acme/plainly"));
        assertNull(ReleaseFeed.parse("", "acme/plainly"));
    }
}
