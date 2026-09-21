package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.update.Release;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 「有新版本」的提示条，贴在工具栏下面。
 *
 * <h2>为什么是一条，不是弹窗</h2>
 * 用户启动这个工具是为了去查一张表，不是为了升级。启动时糊上来一个模态框，
 * 得先处理掉它才能干正事——而这件事既不紧急，也不是他此刻想做的。
 * 一条可以视而不见的横幅，把「要不要理」的决定权留在他手上。
 *
 * <h2>为什么不浮在右下角</h2>
 * 浮层会盖住网格的最后一行。这个工具里最贵的屏幕空间就是网格，
 * 而被盖住的那一行不会有任何提示——用户以为数据就那么多。
 * 占一条高度换来的是「它绝不会挡住任何数据」。
 *
 * <h2>三个按钮各自的意思</h2>
 * <ul>
 *   <li><b>去下载</b>：打开发布页。到此为止——不下载，也不执行任何东西；</li>
 *   <li><b>跳过此版本</b>：这一版别再提了，下一版还要提；</li>
 *   <li><b>关掉</b>：这次先不看，下次启动还会出现。</li>
 * </ul>
 * 「跳过」和「关掉」必须分开：只给一个叉的话，用户每次启动都要再关一遍，
 * 而只给「跳过」则会让一次误点变成永久静音。
 */
public class UpdateBar extends HBox {

    private final Label headline = UiUtils.label("", "update-headline");
    private final Label detail = UiUtils.label("", "hint");
    private final Button download = UiUtils.toolButton("去下载", null, "primary");
    private final Button skip = UiUtils.toolButton("跳过此版本", null);
    private final Button dismiss = UiUtils.toolButton("关掉", null);

    public UpdateBar() {
        super(8);
        getStyleClass().add("update-bar");
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        detail.setWrapText(false);
        VBox text = UiUtils.column(1, headline, detail);

        getChildren().addAll(Icons.refresh(Icons.ACCENT, 13), text,
                UiUtils.hSpacer(), download, skip, dismiss);

        // 收起来时不占位置。只设 visible 的话会留下一条空白横条，
        // 看上去像是界面画坏了
        setVisible(false);
        setManaged(false);
    }

    /**
     * 显示一条。
     *
     * @param release        要提示的版本
     * @param currentVersion 本机版本原文，摆在旁边好让用户自己判断该不该升
     * @param onDownload     点「去下载」时做什么
     * @param onSkip         点「跳过此版本」时做什么
     */
    public void show(Release release, String currentVersion,
                     Runnable onDownload, Runnable onSkip) {
        headline.setText("有新版本 " + release.version().text()
                + "　·　本机 " + currentVersion);
        detail.setText(firstLine(release.notes(), release.title()));

        download.setOnAction(e -> onDownload.run());
        skip.setOnAction(e -> {
            onSkip.run();
            hideBar();
        });
        dismiss.setOnAction(e -> hideBar());

        setVisible(true);
        setManaged(true);
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
    }

    /**
     * 更新说明的头一行。
     *
     * <p>只取一行，是因为这里横向就这么宽——塞进整段 Markdown 会挤成一团，
     * 而挤成一团的说明没有人会读。想看全的人点「去下载」，发布页上写得比这儿全。
     *
     * <p>发布方没写说明时退回标题，而不是留空：一条只说「有新版本 0.2.0」
     * 的提示，用户没有任何依据判断要不要现在升。
     */
    private static String firstLine(String notes, String fallback) {
        if (notes == null || notes.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        String line = notes.strip().split("\\R", 2)[0].strip();
        // 说明常常以 Markdown 的标题或列表打头，那几个符号在这里没有意义
        line = line.replaceFirst("^#+\\s*", "").replaceFirst("^[-*]\\s*", "");
        if (line.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return line.length() > 90 ? line.substring(0, 90) + "…" : line;
    }
}
