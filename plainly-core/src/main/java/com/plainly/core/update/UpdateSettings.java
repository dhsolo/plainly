package com.plainly.core.update;

import com.plainly.core.store.UiState;

/**
 * 更新检查的三项设置，存在本机配置库的键值表里。
 *
 * <h2>出厂是关的，而且地址留空</h2>
 * README 的「安全」一节写着：本工具不做任何遥测，不联网上报，唯一的网络行为
 * 就是连你自己配的数据库。一次版本检查也是一次出站请求——它会把这台机器的
 * IP 和「在用 Plainly」这件事告诉发布方。那条承诺不能因为多了个功能就悄悄作废。
 *
 * <p>所以默认不检查、地址为空，<b>不发生任何网络请求</b>；要检查得用户自己打开。
 * 打开这个开关是一次明确的选择，而不是「装完就已经这样了」。
 *
 * <h2>为什么用 UiState 而不是新开一张表</h2>
 * 这三项都符合 {@link UiState} 的契约：丢了只是回到默认值，不是用户的数据。
 * 为三个键建一张带迁移的新表，维护成本比它们本身还大。
 */
public class UpdateSettings {

    /** 启动时是否检查。缺省 {@code false}——见类注释。 */
    public static final String ENABLED = "update.enabled";

    /** 去哪儿检查，{@code owner/repo}。缺省为空。 */
    public static final String REPO = "update.repo";

    /** 用户按过「跳过此版本」的那一个。只影响被动提示，不影响手动检查。 */
    public static final String SKIPPED = "update.skipped";

    private final UiState state;

    public UpdateSettings(UiState state) {
        this.state = state;
    }

    public boolean enabled() {
        return "true".equals(state.get(ENABLED, "false"));
    }

    public void setEnabled(boolean on) {
        state.put(ENABLED, String.valueOf(on));
    }

    /** 归一过的 {@code owner/repo}；没配或填得认不出来时返回 {@code null}。 */
    public String repo() {
        return ReleaseFeed.normalizeRepo(state.get(REPO, null));
    }

    /** 用户填进输入框的原文。要回显给他看的是这个，不是归一之后的。 */
    public String repoRaw() {
        return state.get(REPO, "");
    }

    public void setRepo(String raw) {
        state.put(REPO, raw == null || raw.isBlank() ? null : raw.trim());
    }

    /**
     * 这一版是不是被用户跳过了。
     *
     * <p>比的是<b>规范化后的三段</b>而不是原文：用户跳过的是「这个版本」，
     * 而发布方可能把 tag 从 {@code 0.2.0} 改写成 {@code v0.2.0} 再发一次。
     * 按原文比的话，同一个版本会换个写法重新弹出来。
     */
    public boolean isSkipped(Version version) {
        String skipped = state.get(SKIPPED, null);
        if (skipped == null || version == null) {
            return false;
        }
        return skipped.equals(version.normalized());
    }

    public void skip(Version version) {
        state.put(SKIPPED, version == null ? null : version.normalized());
    }

    /** 忘掉跳过记录。用户在设置里重新打开检查时调一次——那是「我想再看到提示」的意思。 */
    public void clearSkipped() {
        state.put(SKIPPED, null);
    }
}
