package com.plainly.app;

import com.plainly.app.ui.Splash;
import com.plainly.app.ui.UiUtils;
import com.plainly.app.ui.WindowState;
import com.plainly.app.view.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX 应用入口。真正的 main 在 {@link Launcher}。 */
public class PlainlyApp extends Application {

    private AppContext context;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // 先把启动画面摆出来，再去做那几件慢的事。
        // 打开配置库放到后台：在界面线程上做的话，启动画面上的进度条是停的
        Splash splash = Splash.show();
        splash.status("正在打开本机配置库…");
        java.util.concurrent.CompletableFuture.supplyAsync(AppContext::new)
                .whenComplete((ctx, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        // 先弹错误框再关启动画面：反过来的话，关掉的那一刻屏幕上一个窗口都没有，
                        // JavaFX 会当成「最后一个窗口关了」直接退出，错误框根本出不来
                        UiUtils.showError(null, "无法初始化本机配置库",
                                error instanceof java.util.concurrent.CompletionException
                                        && error.getCause() != null ? error.getCause() : error);
                        splash.close();
                        Platform.exit();
                        return;
                    }
                    context = ctx;
                    splash.status("正在准备界面…");
                    // 让「正在准备界面」先画出来一帧，再去搭主窗口——搭窗口会占住界面线程
                    Platform.runLater(() -> open(stage, splash));
                }));
    }

    private void open(Stage stage, Splash splash) {
        // 「去下载」要靠它打开浏览器。HostServices 只能从 Application 实例上取，
        // 所以在这儿接进去——AppContext 自己不依赖 JavaFX，理由见那边的注释
        context.setUrlOpener(getHostServices()::showDocument);

        MainWindow root = new MainWindow(context);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());

        stage.setTitle("Plainly");
        // 主题要在挂图标之前定好：brand() 会顺手把当前主题挂到窗口上，
        // 而图标的配色也跟着主题走
        com.plainly.app.ui.Theme.restore(context.uiState());
        UiUtils.brand(stage);
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(640);

        // 尺寸在 show() 之前定好：show() 之后再改，窗口会先按默认大小闪一下再跳过去。
        // 最小宽高要先设，restore 会拿它们当下限
        WindowState windowState = new WindowState(context.uiState());
        windowState.restore(stage);
        // 先透明着摆出来，排版在这一步做完；启动画面收起时再淡入，不会露出半成品
        stage.setOpacity(0);
        stage.show();

        root.setStatus("就绪 · 凭据存储：" + context.credentialStore().describe());

        // 关窗时把标签页现场记下来，供下次启动时恢复。
        // 挂 onCloseRequest 而不是 stop()：stop() 之后 UI 已经在拆了，
        // 这时候再去问标签页里有什么，拿到的东西不可靠
        stage.setOnCloseRequest(e -> {
            // 未提交的事务会随着连接关闭被服务端回滚。用户按过「保存」、
            // 网格也刷新了，看上去一切都存进去了——只有这里能拦住他
            java.util.List<String> pending = context.connectionsWithPendingTransaction();
            if (!pending.isEmpty()
                    && !UiUtils.confirm(stage, "还有没提交的事务",
                            "这些连接上有未提交的改动：" + String.join("、", pending)
                                    + System.lineSeparator()
                                    + "现在退出，它们会被回滚掉，找不回来。确定退出？")) {
                e.consume();
                return;
            }
            root.snapshotSession();
            root.saveLayout();
            windowState.save(stage);
        });

        splash.handOver(stage, () -> {
            // 等淡入做完再摆标签页：页是一页页加进去的，
            // 淡入途中加会看到它们一个个蹦出来
            root.restoreLastSession();

            // 版本检查放在最后，而且整个过程在后台：它是这里唯一一件
            // 「用户没要求、但可能占用网络」的事，不该排在恢复现场前面。
            // 没打开检查开关时这一句立刻返回，不发任何请求
            root.checkForUpdates();
        });
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }
}
