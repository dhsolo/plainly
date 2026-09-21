package com.plainly.core.update;

import com.plainly.core.imports.JsonRows;

import java.util.Map;

/**
 * 读 GitHub 的「最新发布」。
 *
 * <h2>为什么用 releases/latest 而不是 tags</h2>
 * tag 是谁都能推的，草稿和预发布也在里面。{@code releases/latest} 由发布方
 * 明确标出来，且 GitHub 保证它<b>不是</b>草稿、<b>不是</b>预发布——
 * 也就是「作者认为可以给人用的那一个」。更新提示要的正是这个语义。
 *
 * <p>即便如此，下面仍然自己再查一遍 {@code draft} 和 {@code prerelease}：
 * 这两个字段的判断在服务端，而服务端的行为不归我们管。多查一次的成本是两行。
 *
 * <h2>解析为什么不引 JSON 库</h2>
 * 直接用 {@link JsonRows}——本项目已经有的那一份，导入 JSON 文件走的就是它。
 * 它把顶层对象读成扁平的 {@code Map<String,String>}，嵌套的部分（{@code author}、
 * {@code assets}）原样抄成一段文本放着，而这里一个都不需要。
 */
public final class ReleaseFeed {

    private ReleaseFeed() {
    }

    /**
     * 把用户填的东西归一成 {@code owner/repo}。认不出来返回 {@code null}。
     *
     * <p>要容忍的是用户<b>真会</b>填进来的形状：他多半是从浏览器地址栏里
     * 复制粘贴的，那就是一整条 {@code https://github.com/owner/repo}。
     * 因为多了个前缀就去请求一个拼错的地址、然后报「检查失败」，
     * 是把一件用户没做错的事说成他做错了。
     */
    public static String normalizeRepo(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replaceFirst("^[a-zA-Z]+://", "");
        s = s.replaceFirst("^(www\\.)?github\\.com/", "");
        s = s.replaceFirst("\\.git$", "");
        s = s.replaceAll("/+$", "");

        String[] parts = s.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        // 只放行 GitHub 允许的字符。这串会被拼进 URL，不做限制的话
        // 用户填错一个字符就可能拼出一个指向别处的地址
        if (!parts[0].matches("[A-Za-z0-9._-]+") || !parts[1].matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

    /** 最新发布的接口地址。{@code repo} 必须是 {@link #normalizeRepo} 出来的。 */
    public static String latestUrl(String repo) {
        return "https://api.github.com/repos/" + repo + "/releases/latest";
    }

    /** 发布页地址。发布方没给 {@code html_url} 时用它兜底。 */
    public static String releasesPage(String repo) {
        return "https://github.com/" + repo + "/releases/latest";
    }

    /**
     * 解析一次 {@code releases/latest} 的响应。
     *
     * @param repo 用来在字段缺失时兜底拼地址
     * @return 认得出的发布；是草稿、是预发布、或版本号认不出来时返回 {@code null}
     */
    public static Release parse(String json, String repo) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, String>[] first = new Map[1];
        JsonRows.forEachObject(json, obj -> {
            if (first[0] == null) {
                first[0] = obj;
            }
        });
        Map<String, String> o = first[0];
        if (o == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(o.get("draft"))
                || "true".equalsIgnoreCase(o.get("prerelease"))) {
            return null;
        }

        Version version = Version.tryParse(o.get("tag_name"));
        if (version == null) {
            return null;
        }

        String title = blankToNull(o.get("name"));
        if (title == null) {
            title = o.get("tag_name");
        }
        String page = blankToNull(o.get("html_url"));
        if (page == null) {
            page = releasesPage(repo);
        }
        String notes = o.get("body");
        return new Release(version, title, notes == null ? "" : notes, page);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
