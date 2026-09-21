package com.plainly.app;

import com.plainly.app.ui.UiUtils;
import com.plainly.app.ui.WindowState;
import com.plainly.app.view.MainWindow;
import javafx.application.Application;
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
        try {
            context = new AppContext();
        } catch (RuntimeException e) {
            UiUtils.showError(null, "无法初始化本机配置库", e);
            javafx.application.Platform.exit();
            return;
        }

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

        // 恢复的询问要等窗口出来——弹窗得有个 owner
        root.offerSessionRestore();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }
}
