import com.plainly.app.AppContext;
import com.plainly.app.view.MainWindow;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 在 Redis 的键空间上把导出对话框打开，看它到底改成什么样了。
 *
 * <p>要对的是四处：标题写的是不是「键空间」、范围那一栏说的是不是「全部键」、
 * 格式里还有没有 SQL INSERT、「同时导出建表语句」是不是禁掉了并说了原因。
 * 这些都是<b>点开才看得见</b>的东西，只能真打开一次。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ExportDialogDiag.java</pre>
 */
public class ExportDialogDiag extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private AppContext context;
    private MainWindow window;
    private TreeView<?> tree;
    private String connectionName;

    @Override
    public void start(Stage stage) {
        context = new AppContext();
        for (ConnectionConfig c : context.registry().listAll()) {
            if (c.type() == DbType.REDIS) {
                connectionName = c.name();
            }
        }
        if (connectionName == null) {
            System.out.println("没有 Redis 连接");
            Platform.exit();
            return;
        }

        window = new MainWindow(context);
        Scene scene = new Scene(window, 1280, 760);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("导出对话框诊断");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
            expandContaining(connectionName);
        });
        step(() -> expandContaining("db0"));
        step(() -> expandStartingWith("GroupNode"));
        step(() -> openFirst("TableNode"));
        step(() -> { });
        step(() -> {
            System.out.println("点「导出」");
            for (Node n : window.lookupAll(".tool-button")) {
                if (n instanceof javafx.scene.control.Button b && "导出".equals(b.getText())) {
                    Platform.runLater(b::fire);
                    return;
                }
            }
            System.out.println("！没找到导出按钮");
        });
        step(() -> {
            Stage dialog = findDialog();
            if (dialog == null) {
                System.out.println("！导出对话框没打开");
                return;
            }
            System.out.println("\n对话框上的文字：");
            texts(dialog.getScene().getRoot()).forEach(t -> System.out.println("   " + t));
            System.out.println("\n禁用的控件：");
            disabled(dialog.getScene().getRoot()).forEach(t -> System.out.println("   " + t));
            write(dialog.getScene().snapshot(null), "export-redis");
            dialog.close();
        });
        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private static Stage findDialog() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null
                    && s.getScene().getRoot().lookup(".dialog-title") != null
                    && !"导出对话框诊断".equals(s.getTitle())) {
                return s;
            }
        }
        return null;
    }

    private static List<String> texts(Node node) {
        List<String> out = new ArrayList<>();
        walk(node, out, false);
        return out;
    }

    private static List<String> disabled(Node node) {
        List<String> out = new ArrayList<>();
        walk(node, out, true);
        return out;
    }

    private static void walk(Node node, List<String> out, boolean onlyDisabled) {
        if (node instanceof javafx.scene.control.Labeled l
                && l.getText() != null && !l.getText().isBlank()) {
            boolean off = l.isDisabled();
            if (onlyDisabled == off) {
                String tip = l.getTooltip() == null ? "" : "   ← " + l.getTooltip().getText();
                out.add(l.getText() + tip);
            }
        }
        if (node instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out, onlyDisabled));
        }
    }

    private void expandContaining(String needle) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).contains(needle)) {
                item.setExpanded(true);
                return;
            }
        }
        System.out.println("树上没有含「" + needle + "」的行");
    }

    private void expandStartingWith(String prefix) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).startsWith(prefix)) {
                item.setExpanded(true);
                return;
            }
        }
    }

    private void openFirst(String prefix) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).startsWith(prefix)) {
                tree.getSelectionModel().select(i);
                javafx.event.Event.fireEvent(tree, new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, true, false, false, null));
                return;
            }
        }
    }

    private void step(Runnable action) {
        at += 1.6;
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
        launch(ExportDialogDiag.class, args);
        System.exit(0);
    }
}
