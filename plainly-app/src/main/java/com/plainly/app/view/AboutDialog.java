package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.Launcher;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.LocalStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 关于。
 *
 * <h2>这一页的用处不是署名，是「出问题时能问清楚」</h2>
 * 用户报一个现象，第一件要确认的永远是<b>他跑的是哪一版</b>。
 * 没有这一页，这个问题只能让他去「程序和功能」里翻，而从源码跑起来的那些人
 * 连那里都没有。
 *
 * <p>所以除了版本号，还把运行环境和本机数据的位置一起摆出来——
 * 那几样正是排查时要问的：Java 版本、操作系统、配置库在哪。
 * 摆出来之后，用户可以直接复制整段贴过来。
 */
public class AboutDialog {

    private final AppContext context;

    public AboutDialog(AppContext context) {
        this.context = context;
    }

    /**
     * 版本号取自 jar 的 {@code Implementation-Version}。
     *
     * <p>从源码或 IDE 里跑时没有 manifest，取不到——这时候如实说「开发版」，
     * 不要编一个像模像样的号。一个假版本号比没有版本号更糟：
     * 用户会拿它来对报告，而它对不上任何一次发布。
     */
    public static String version() {
        String v = Launcher.class.getPackage() == null
                ? null : Launcher.class.getPackage().getImplementationVersion();
        if (v == null || v.isBlank()) {
            return "开发版（从源码运行，没有版本号）";
        }
        return v.endsWith("-SNAPSHOT") ? v + "（未发布）" : v;
    }

    public void show(Window owner) {
        Stage stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("关于 Plainly");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot(stage));
        Scene scene = new Scene(root, 560, 440);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();
    }

    private HBox buildHead() {
        VBox title = UiUtils.column(2,
                UiUtils.label("Plainly", "dialog-title"),
                UiUtils.label("跨数据库桌面客户端 · " + version(), "hint"));
        HBox head = UiUtils.row(10, Icons.appMark(34), title);
        head.getStyleClass().add("dialog-head");
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    private VBox buildBody() {
        Label pitch = UiUtils.label(
                "数值全程以文本传递，绝不经过 double —— DECIMAL(38,10)、Int64、"
                + "Decimal128 一位不差。", "hint");
        pitch.setWrapText(true);

        TextArea details = new TextArea(environment());
        details.setEditable(false);
        details.setWrapText(false);
        details.getStyleClass().add("mono");
        VBox.setVgrow(details, Priority.ALWAYS);

        VBox box = UiUtils.column(8, pitch,
                UiUtils.label("运行环境", "section-label"), details);
        box.setPadding(new Insets(12, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** 排查时要问的那几样，一次摆齐，方便整段复制。 */
    private String environment() {
        StringBuilder sb = new StringBuilder();
        line(sb, "版本", version());
        line(sb, "Java", System.getProperty("java.version")
                + "（" + System.getProperty("java.vendor") + "）");
        line(sb, "JavaFX", System.getProperty("javafx.runtime.version", "未知"));
        line(sb, "操作系统", System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " / " + System.getProperty("os.arch"));
        line(sb, "默认编码", System.getProperty("file.encoding"));
        line(sb, "本机配置库", localStorePath());
        line(sb, "已保存连接", String.valueOf(connectionCount()));
        line(sb, "许可证", "Apache License 2.0");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, String value) {
        sb.append(String.format("%-12s%s%n", name, value));
    }

    /**
     * 配置库的位置。
     *
     * <p>这一条在升级和换机器时最常被问到：连接和口令都在那个文件里，
     * 而它<b>不在</b>安装目录下——卸载不会删它，也不会跟着安装包走。
     */
    private String localStorePath() {
        try {
            return LocalStore.defaultLocation().toString();
        } catch (RuntimeException e) {
            return "读不到（" + e.getMessage() + "）";
        }
    }

    private int connectionCount() {
        try {
            return context.registry().listAll().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private HBox buildFoot(Stage stage) {
        Button copy = UiUtils.toolButton("复制运行环境", null);
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(environment());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });

        /*
         * 检查更新先留着不接。
         *
         * 仓库现在一个 Release 都没有，GitHub 的 latest 端点返回 404——
         * 现在把它接上，它唯一能做的就是静默失败。而这种「永远走不到成功分支」
         * 的代码最容易腐坏：等真发了第一个版本，谁也不确定它还对不对。
         *
         * 按钮摆在这儿并标明原因，比藏起来好：用户看得见这件事有人想过。
         */
        Button update = UiUtils.toolButton("检查更新", null);
        update.setDisable(true);
        Label why = UiUtils.label("（还没有发布过版本，等第一个 Release 出来再接）", "hint");

        Button close = UiUtils.toolButton("关闭", null, "primary");
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, copy, update, why, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        foot.setAlignment(Pos.CENTER_LEFT);
        return foot;
    }
}
