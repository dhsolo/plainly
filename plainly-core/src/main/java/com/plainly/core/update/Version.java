package com.plainly.core.update;

/**
 * 一个版本号，按 <b>Windows Installer 的规则</b>比较。
 *
 * <h2>为什么只比前三段</h2>
 * 这不是简化，是照抄 Windows 的实际行为。MSI 比较 {@code ProductVersion} 时
 * 只看前三段纯数字（主 ≤ 255、次 ≤ 255、构建 ≤ 65535），<b>第四段完全无视</b>：
 * 在 Windows 眼里 {@code 0.1.0.4} 和 {@code 0.1.0.5} 是同一个版本。
 *
 * <p>所以如果这里按四段比，更新检查就会造出这样一条路：提示「有新版本」→
 * 用户去下载 → 双击 → 看到进度条 → 看到「完成」→ 打开<b>还是旧的</b>。
 * 全程没有任何一步报错。README 的「一处改版本号」记的就是这个现象
 * （那次的起因是打包脚本里的版本号没跟着 pom 改），更新检查是同一个陷阱的
 * 第二个入口——既然装上去不会有变化，那就<b>不该提示</b>。
 *
 * <h2>认不出来时返回 null，不返回 0.0.0</h2>
 * 版本号有两个来源都可能给出不是版本号的东西：从源码跑时 jar 没有 manifest，
 * {@code AboutDialog.version()} 如实返回「开发版」；远端的 tag 则是任人填的字符串。
 * 把它们当成 {@code 0.0.0} 的话，任何一个正式版本都比它大——于是开发版每次启动
 * 都会被告知「有新版本」，而那台机器上根本没有 MSI 可装。
 */
public final class Version implements Comparable<Version> {

    /** MSI 对三段的上限。超出的版本 jpackage 压根打不出来，所以不可能真有这样一个发布。 */
    private static final int MAX_MAJOR = 255;
    private static final int MAX_MINOR = 255;
    private static final int MAX_BUILD = 65535;

    private final int major;
    private final int minor;
    private final int build;
    private final String text;

    private Version(int major, int minor, int build, String text) {
        this.major = major;
        this.minor = minor;
        this.build = build;
        this.text = text;
    }

    /**
     * 解析。认不出来返回 {@code null}。
     *
     * <p>容忍这几种写法，因为它们都会真的出现：
     * <ul>
     *   <li>{@code v0.2.0}——GitHub 的 tag 习惯带 v；</li>
     *   <li>{@code 0.2.0-SNAPSHOT}——从开发构建里取到的；</li>
     *   <li>{@code 0.2.0.7}、{@code 0.2.0+build3}——多出来的部分按 Windows 的做法丢掉；</li>
     *   <li>{@code 0.2}——缺的段按 0 补。</li>
     * </ul>
     *
     * <p>但<b>不</b>猜：{@code 开发版（从源码运行，没有版本号）} 这种就是 null。
     * 从里面挑出一个数字来当版本号，比说「不知道」糟得多。
     */
    public static Version tryParse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // 数字段到此为止：-SNAPSHOT、-rc1、+build3 都从这里断开
        int cut = 0;
        while (cut < s.length() && (Character.isDigit(s.charAt(cut)) || s.charAt(cut) == '.')) {
            cut++;
        }
        s = s.substring(0, cut);
        if (s.isEmpty()) {
            return null;
        }

        String[] parts = s.split("\\.", -1);
        int[] seg = {0, 0, 0};
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length || parts[i].isEmpty()) {
                // 「0.2」补成 0.2.0。但第一段就空（".2.0"）不算版本号
                if (i == 0) {
                    return null;
                }
                continue;
            }
            try {
                seg[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (seg[0] < 0 || seg[0] > MAX_MAJOR
                || seg[1] < 0 || seg[1] > MAX_MINOR
                || seg[2] < 0 || seg[2] > MAX_BUILD) {
            // 超限的版本 jpackage 会拒绝，也就打不出 MSI。
            // 真收到这样一个 tag，说明发布那边出了问题，不该拿它去提示用户升级
            return null;
        }
        return new Version(seg[0], seg[1], seg[2], raw.trim());
    }

    /** 原文，照发布方写的样子。界面上显示这个，不要把它规范化过再给用户看。 */
    public String text() {
        return text;
    }

    /** 规范化的三段形式，{@code 0.2.0}。用来和 MSI 里的 ProductVersion 对照。 */
    public String normalized() {
        return major + "." + minor + "." + build;
    }

    /**
     * 比较。<b>只比前三段</b>——理由见类注释。
     *
     * <p>返回 0 的含义是「Windows 认为这是同一个版本」，而不是「字符串相同」。
     */
    @Override
    public int compareTo(Version other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(build, other.build);
    }

    /** 装上它是不是真能带来变化。等价于 {@code compareTo(current) > 0}。 */
    public boolean isNewerThan(Version current) {
        return current == null || compareTo(current) > 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return (major * 256 + minor) * 65536 + build;
    }

    @Override
    public String toString() {
        return normalized();
    }
}
