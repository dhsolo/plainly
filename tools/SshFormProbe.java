import com.plainly.app.AppContext;
import com.plainly.app.view.ConnectionDialog;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 勾上「经 SSH 跳板机连接」之后，那一屏还装得下吗。
 *
 * <p>要看两件事：<b>底部有没有被挤出窗口</b>（VBox 不裁剪，被挤出去的部分
 * 不会报错，只是看不见），以及那几个输入框有没有跟上面的「主机」一样撑满。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SshFormProbe.java</pre>
 */
public class SshFormProbe extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private Stage dialogStage;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        // showAndWait 会开一个嵌套事件循环，下面这些 PauseTransition 照样跑
        Platform.runLater(() -> new ConnectionDialog(context, null).showAndWait(null));

        step(() -> {
            dialogStage = findDialog();
            if (dialogStage == null) {
                System.out.println("没找到对话框");
                return;
            }
            report("勾之前");
        });

        step(() -> {
            CheckBox ssh = (CheckBox) find(dialogStage.getScene().getRoot(),
                    n -> n instanceof CheckBox c && c.getText().contains("SSH"));
            ssh.setSelected(true);
            ssh.fireEvent(new javafx.event.ActionEvent());
            System.out.println("\n== 勾上「" + ssh.getText() + "」==");
        });

        step(() -> {
            report("勾之后");
            write(dialogStage.getScene().snapshot(null), "ssh-form");
        });

        step(() -> {
            if (dialogStage != null) {
                dialogStage.close();
            }
            context.close();
            Platform.exit();
        });
    }

    private void report(String tag) {
        Scene scene = dialogStage.getScene();
        Parent root = scene.getRoot();
        System.out.println("[" + tag + "] 窗口高 " + (int) scene.getHeight()
                + "，内容需要 " + (int) root.prefHeight(scene.getWidth()));
        int i = 0;
        for (Node child : ((javafx.scene.layout.VBox) root).getChildrenUnmodifiable()) {
            if (!child.isManaged()) {
                continue;
            }
            double bottom = child.getBoundsInParent().getMaxY();
            System.out.printf("   第%d块 %-12s 底边 %.0f%s%n", i++,
                    child.getClass().getSimpleName(), bottom,
                    bottom > scene.getHeight() + 0.5 ? "   ← 已经掉出窗口" : "");
        }
        java.util.List<Node> fields = new java.util.ArrayList<>();
        collect(root, x -> x instanceof TextField, fields);
        int k = 0;
        for (Node n : fields) {
            TextField t = (TextField) n;
            System.out.printf("   field[%d] w=%.0f  prompt=%s%n", k++,
                    n.getBoundsInLocal().getWidth(),
                    t.getPromptText() == null ? "-" : t.getPromptText());
        }
    }

    private static void collect(Node node, java.util.function.Predicate<Node> match,
                                java.util.List<Node> into) {
        if (match.test(node)) {
            into.add(node);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, match, into);
            }
        }
    }

    private static Stage findDialog() {
        for (Window w : Window.getWindows()) {
            System.out.println("窗口：" + w.getClass().getSimpleName() + " / "
                    + (w instanceof Stage s ? s.getTitle() : "-") + " / showing=" + w.isShowing());
        }
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null
                    && s.getScene().getRoot().lookup(".dialog-title") != null) {
                return s;
            }
        }
        return null;
    }

    private static Node find(Node node, java.util.function.Predicate<Node> match) {
        if (match.test(node)) {
            return node;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node hit = find(child, match);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private void step(Runnable action) {
        at += 1.4;
        PauseTransition p = new PauseTransition(Duration.seconds(at));
        p.setOnFinished(e -> action.run());
        p.play();
    }

    private static void write(Image img, String name) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", new File(OUT + name + ".png"));
            System.out.println("→ " + name + ".png");
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(SshFormProbe.class, args);
        System.exit(0);
    }
}
