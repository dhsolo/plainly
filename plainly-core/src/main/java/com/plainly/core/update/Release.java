package com.plainly.core.update;

/**
 * 发布方公布的一次发布。
 *
 * <p>只留界面上真正要用的四样。刻意<b>不</b>带下载地址和安装包的校验和——
 * 这一版的更新功能到「告诉用户有新版、把发布页打开」为止，不下载也不执行。
 * 把下载地址取回来放着，下一步就会有人顺手去下，而没有代码签名证书时
 * 那正是 README「没有自动更新」那节判断为「比手动下载更糟」的做法。
 *
 * @param version 解析过的版本号，已经按 MSI 规则规范化
 * @param title   发布标题，发布方没写时退回 tag
 * @param notes   更新说明原文（Markdown），可能很长，界面自己截断
 * @param pageUrl 发布页地址，点「去下载」打开的就是它
 */
public record Release(Version version, String title, String notes, String pageUrl) {
}
