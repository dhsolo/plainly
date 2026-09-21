package com.plainly.core.update;

import com.plainly.core.store.UiState;

/**
 * 更新检查的三项设置，存在本机配置库的键值表里。
 *
 * <h2>出厂就带着官方地址，并且默认开启</h2>
 * 发布地址写死成 {@link #DEFAULT_REPO}，用户装完不用先去设置里填什么，
 * 新版本出来自然会被告知——这正是「检查更新」该有的样子。
 *
 * <p>代价要说在明处：<b>它是一次出站请求</b>，发布方因此能看到这台机器的 IP
 * 和「在用 Plainly」这件事。README 的「安全」一节原来写着「唯一的网络行为
 * 就是连你自己配的数据库」，这个功能上线后那句话已经跟着改了——
 * 承诺可以变，但不能变了还挂在那儿。
 *
 * <p>开关随时可以关掉，关掉之后一个请求都不发（见 {@code MainWindow.checkForUpdates}，
 * 它是在走到网络之前就返回，而不是发出去再把结果丢掉）。
 *
 * <h2>地址留空是「用默认的」，填错不是</h2>
 * 输入框清空 = 回到官方地址。但填了一串认不出来的东西时<b>不</b>退回默认——
 * 用户特地填了别的（多半是自己的 fork），这时候偷偷去查官方仓库，
 * 他会以为自己在跟踪 fork 的版本，而实际比的是另一个仓库。
 *
 * <h2>为什么用 UiState 而不是新开一张表</h2>
 * 这三项都符合 {@link UiState} 的契约：丢了只是回到默认值，不是用户的数据。
 * 为三个键建一张带迁移的新表，维护成本比它们本身还大。
 */
public class UpdateSettings {

    /**
     * 出厂的发布地址。
     *
     * <p>写死在这里，而不是打包时注入：注入意味着从源码构建出来的版本
     * 没有这个地址，于是「从源码跑」和「装的正式版」在这件事上行为不一样——
     * 而那种差异最难查，因为两边看起来跑的是同一份代码。
     */
    public static final String DEFAULT_REPO = "dhsolo/plainly";

    /** 启动时是否检查。缺省 {@code true}——见类注释。 */
    public static final String ENABLED = "update.enabled";

    /** 去哪儿检查，{@code owner/repo}。留空表示用 {@link #DEFAULT_REPO}。 */
    public static final String REPO = "update.repo";

    /** 用户按过「跳过此版本」的那一个。只影响被动提示，不影响手动检查。 */
    public static final String SKIPPED = "update.skipped";

    private final UiState state;

    public UpdateSettings(UiState state) {
        this.state = state;
    }

    public boolean enabled() {
        return !"false".equals(state.get(ENABLED, "true"));
    }

    public void setEnabled(boolean on) {
        state.put(ENABLED, String.valueOf(on));
    }

    /**
     * 归一过的 {@code owner/repo}。
     *
     * <p>没填过 → 官方地址；填了但认不出来 → {@code null}（理由见类注释：
     * 那时候退回官方地址会让用户以为自己在跟踪别的仓库）。
     */
    public String repo() {
        String stored = state.get(REPO, null);
        if (stored == null || stored.isBlank()) {
            return DEFAULT_REPO;
        }
        return ReleaseFeed.normalizeRepo(stored);
    }

    /**
     * 用户填进输入框的原文，没填过就是空串。
     *
     * <p>回显给用户的是这个，不是 {@link #repo()}：把默认地址填进输入框的话，
     * 用户分不出「这是出厂值」还是「我自己填过」——而这两者在他想改回默认时
     * 是两回事（前者清空即可，后者他会以为必须手动敲对）。
     */
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
