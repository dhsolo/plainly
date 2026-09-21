import com.plainly.app.ui.UiUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 长报错在错误框里显示得全不全。
 *
 * <h2>要验的是「框子够不够高」</h2>
 * 这个 bug 的形态很具体：文本框开了自动折行，而高度是按<b>换行符个数</b>算的。
 * 一条几百字符的单行报错，换行符数是 1，于是框子只有三行高——
 * 用户看到开头半句加一条滚动条，而最要紧的那半句
 * （到底是连不上、还是密码不对）往往在后半段。
 *
 * <p>openGauss、Oracle 的驱动报错动辄几百字符，这一条在它们身上最明显。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/ErrorDisplayProbe.java
 * </pre>
 */
public class ErrorDisplayProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    /** 照着真实驱动的样子造一条长报错：单行、几百字符。 */
    private static final String LONG_MESSAGE =
            "连接失败：The connection attempt failed. org.opengauss.util.PSQLException: "
            + "Connection to 192.168.237.153:5432 refused. Check that the hostname and port "
            + "are correct and that the postmaster is accepting TCP/IP connections. "
            + "Also make sure the client IP is allowed in pg_hba.conf and that the database "
            + "accepts the requested authentication method (sha256 / md5).";

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();

        // 折行估算这一层可以直接算，不必开窗口
        int rowsLong = UiUtils.wrappedRows(LONG_MESSAGE, 64);
        int rowsShort = UiUtils.wrappedRows("连接被拒绝", 64);
        LOG.append("长报错（").append(LONG_MESSAGE.length()).append(" 字符）估算行数：")
                .append(rowsLong).append(NL);
        LOG.append("短报错估算行数：").append(rowsShort).append(NL);

        check("长报错的框子会撑开，不是压在最小高度上", rowsLong >= 6, "算出来 " + rowsLong + " 行");
        check("短报错不会被撑得老高", rowsShort <= 4, "算出来 " + rowsShort + " 行");
        check("再长也有上限，不会把屏幕顶穿",
                UiUtils.wrappedRows(LONG_MESSAGE.repeat(20), 64) <= 24,
                String.valueOf(UiUtils.wrappedRows(LONG_MESSAGE.repeat(20), 64)));
        // 样本要足够长，否则两边都撞到 3 行的下限（那个下限是为了短消息不至于太挤），
        // 比出来一样高，看着像没算宽——上一版就是这么误报的
        int cn = UiUtils.wrappedRows("中".repeat(200), 64);
        int en = UiUtils.wrappedRows("a".repeat(200), 64);
        check("中文按两个字符宽算（同样长度占更多行）", cn > en,
                "中文 " + cn + " 行、英文 " + en + " 行");

        // 真把框打开，确认内容完整、可复制、窗口可拉大
        Platform.runLater(() -> {
            delay(500, () -> {
                inspect();
                System.out.print(LOG);
                System.out.println();
                System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
                Platform.exit();
            });
            UiUtils.showError(stage, "连接 openGauss 失败",
                    new RuntimeException(LONG_MESSAGE));
        });
    }

    private void inspect() {
        Stage dialog = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.isShowing() && s.getScene() != null
                    && find(s.getScene().getRoot(), TextArea.class) != null) {
                dialog = s;
            }
        }
        check("错误框打开了", dialog != null, "没找到");
        if (dialog == null) {
            return;
        }
        Parent root = dialog.getScene().getRoot();

        TextArea area = find(root, TextArea.class);
        check("正文是完整的原文，没有被截断",
                area != null && area.getText().contains("pg_hba.conf")
                        && area.getText().contains("sha256"),
                area == null ? "没有文本区" : area.getText());
        check("文本框高度按折行撑开了",
                area != null && area.getPrefRowCount() >= 6,
                area == null ? "?" : "prefRowCount=" + area.getPrefRowCount());
        check("可以选中复制（不是只读 Label）", area != null && !area.isDisabled(), "?");

        check("有「复制全部」按钮", findButton(root, "复制全部") != null, "没找到按钮");
        check("窗口可以拉大", dialog.isResizable(), "不可调整大小");

        dialog.close();
    }

    // ------------------------------------------------------------------ 辅助

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Parent root, Class<T> type) {
        for (Node n : all(root)) {
            if (type.isInstance(n)) {
                return (T) n;
            }
        }
        return null;
    }

    private static Button findButton(Parent root, String text) {
        for (Node n : all(root)) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private static List<Node> all(Parent root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            out.add(child);
            if (child instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            LOG.append("  ").append(what).append(NL);
        } else {
            fail++;
            LOG.append("× ").append(what).append("   实际：").append(detail).append(NL);
        }
    }

    public static void main(String[] args) {
        launch(ErrorDisplayProbe.class, args);
    }
}
