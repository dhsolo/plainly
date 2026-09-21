package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.update.Release;
import com.plainly.core.update.ReleaseFeed;
import com.plainly.core.update.UpdateChecker;
import com.plainly.core.update.UpdateSettings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 检查更新，以及更新检查的设置。
 *
 * <h2>设置和检查放在同一页</h2>
 * 这两件事在时间上是连着的：用户点开「检查更新」，发现还没填发布地址——
 * 如果设置在另一个地方，他得关掉这页、去翻菜单、填完再回来重点一次。
 * 而这一整套他一辈子只会做一遍，为它单开一页设置不值得。
 *
 * <h2>手动检查不受「跳过此版本」影响</h2>
 * 「跳过」说的是「别再主动弹给我」，不是「以后问你你也别说」。
 * 用户特地点开这一页来问，那就如实回答——否则他会看到「已是最新」，
 * 而发布页上明明摆着一个更新的版本，且没有任何线索解释这个矛盾。
 */
public class UpdateDialog {

    private final AppContext context;
    private final UpdateSettings settings;

    private final TextField repoField = new TextField();
    private final CheckBox autoCheck = new CheckBox("启动时检查新版本");
    private final Button checkNow = UiUtils.toolButton("现在检查", null, "primary");
    private final Button openPage = UiUtils.toolButton("去下载", null);
    private final Label result = UiUtils.label("", "hint");
    private final TextArea notes = new TextArea();

    /** 最近一次查出来的新版本。没有就是 null，「去下载」也就点不动。 */
    private Release found;

    public UpdateDialog(AppContext context) {
        this.context = context;
        this.settings = context.updateSettings();
    }

    public void show(Window owner) {
        Stage stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("检查更新");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot(stage));
        Scene scene = new Scene(root, 560, 420);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 头部不重复标题栏那句「检查更新」（{@code DialogTitleTest} 会拦），
     * 改放真正有用的那一条：本机现在是哪一版。
     *
     * <p>这也正好是用户点开这一页想知道的第一件事——「我在用哪一版」比
     * 「这是检查更新页」有信息量得多，后者标题栏已经说过了。
     */
    private HBox buildHead() {
        VBox title = UiUtils.column(2,
                UiUtils.label("本机版本 " + AboutDialog.version(), "dialog-title"),
                UiUtils.label("看看发布方那边有没有更新的一版", "hint"));
        HBox head = UiUtils.row(10, Icons.refresh(Icons.ACCENT, 20), title);
        head.getStyleClass().add("dialog-head");
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    private VBox buildBody() {
        repoField.setText(settings.repoRaw());
        // 提示语里摆出默认地址，这样「留空会去查哪儿」是看得见的，
        // 而不是一个要看文档才知道的隐藏行为
        repoField.setPromptText("留空 = " + UpdateSettings.DEFAULT_REPO
                + "（也可填 owner/repo 或整条 GitHub 地址）");
        HBox.setHgrow(repoField, Priority.ALWAYS);

        autoCheck.setSelected(settings.enabled());
        autoCheck.setOnAction(e -> {
            settings.setEnabled(autoCheck.isSelected());
            if (autoCheck.isSelected()) {
                // 重新打开检查 = 「我想再看到提示」。留着上次的跳过记录的话，
                // 用户打开开关之后什么都不会发生，而他无从知道为什么
                settings.clearSkipped();
            }
        });

        /*
         * 这段话不是免责声明，是这个功能的边界。
         *
         * 这一项出厂是开的，所以用户多半不是"打开它"而是"发现它开着"——
         * 那就更该把已经在发生的事说清楚，而不是等他自己去翻文档。
         * 关掉之后是真的一个包都不发（见 MainWindow.checkForUpdates，
         * 它在走到网络之前就返回），不是发出去再把结果丢掉。
         */
        Label privacy = UiUtils.label(
                "这一项默认开着：每次启动会向 GitHub 请求一次版本信息，"
                + "对方因此能看到这台机器的 IP。关掉之后不发生任何网络请求。", "hint");
        privacy.setWrapText(true);

        Label scope = UiUtils.label(
                "查到新版本只会提示，并帮你打开发布页。本工具不下载、也不运行安装包："
                + "安装包目前没有代码签名，替你无人值守地跑一个 SmartScreen 会拦的程序，"
                + "比你自己去下载更糟。", "hint");
        scope.setWrapText(true);

        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPromptText("更新说明会显示在这里");
        notes.getStyleClass().add("mono");
        VBox.setVgrow(notes, Priority.ALWAYS);

        result.setWrapText(true);

        VBox box = UiUtils.column(8,
                UiUtils.label("发布地址", "section-label"),
                UiUtils.row(8, repoField),
                autoCheck,
                privacy,
                scope,
                UiUtils.label("结果", "section-label"),
                result,
                notes);
        box.setPadding(new Insets(12, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot(Stage stage) {
        checkNow.setOnAction(e -> runCheck());

        openPage.setDisable(true);
        openPage.setOnAction(e -> {
            if (found != null) {
                context.openUrl(found.pageUrl());
            }
        });

        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, checkNow, openPage, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        foot.setAlignment(Pos.CENTER_LEFT);
        return foot;
    }

    /**
     * 查一次。
     *
     * <p>先把输入框里的地址存下来再查：用户改了地址直接点「现在检查」是最自然的动作，
     * 要求他先按一下别处的「保存」，是把工具的实现细节变成他的操作步骤。
     *
     * <p>网络那一下必须离开界面线程。连不上 GitHub 的网络里，那一下会一直等到超时，
     * 放在界面线程上就是整个窗口冻住十秒。
     */
    private void runCheck() {
        settings.setRepo(repoField.getText());

        String repo = settings.repo();
        if (repo == null) {
            String typed = repoField.getText();
            result.setText(typed == null || typed.isBlank()
                    ? "先填一个发布地址。"
                    : "这个地址认不出来：填 owner/repo，或者整条"
                            + " https://github.com/owner/repo。");
            notes.clear();
            setFound(null);
            return;
        }

        checkNow.setDisable(true);
        result.setText("正在查 " + ReleaseFeed.latestUrl(repo) + " …");
        notes.clear();
        setFound(null);

        String current = AboutDialog.version();
        UpdateChecker checker =
                new UpdateChecker(settings, UpdateChecker.httpFetcher(current));

        context.queryService().submit(() -> checker.check(current))
                .whenComplete((r, error) -> Platform.runLater(() -> {
                    checkNow.setDisable(false);
                    if (error != null) {
                        // check() 自己不抛，走到这儿说明是别的地方坏了
                        result.setText("检查出错：" + UiUtils.rootMessage(error));
                        return;
                    }
                    result.setText(r.message());
                    if (r.release() != null) {
                        notes.setText(r.release().notes());
                    }
                    // 已是最新时也让「去下载」可点：发布页上有历史版本和校验信息，
                    // 用户这时候想去看一眼是合理的
                    setFound(r.release());
                }));
    }

    private void setFound(Release release) {
        this.found = release;
        openPage.setDisable(release == null);
    }
}
