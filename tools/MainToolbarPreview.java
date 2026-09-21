import com.plainly.app.AppContext;
import com.plainly.app.view.MainWindow;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 主窗口顶部工具条，究竟挤成什么样。
 *
 * <p>和 {@code TableToolbarPreview} 一个路子：量「想要多宽」对「实得多宽」，
 * 差值为正就是真的放不下，右边的东西已经被挤掉了——那不是审美问题，是功能没了。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/MainToolbarPreview.java</pre>
 */
public class MainToolbarPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private static final int[] WIDTHS = {1280, 1440, 1920};

    private double at;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        MainWindow window = new MainWindow(context);

        Scene scene = new Scene(window, WIDTHS[0], 620);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("主工具条取样");
        stage.show();

        for (int w : WIDTHS) {
            shoot(stage, window, w);
        }
        openEachMenu(stage, window);
        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private void shoot(Stage stage, MainWindow window, int width) {
        step(() -> {
            stage.setWidth(width + 16);
            window.applyCss();
            window.layout();
        });
        step(() -> {
            // setTop 挂的是 VBox(标题条, 工具条)，工具条是第二个孩子
            VBox top = (VBox) window.getTop();
            Node bar = top.getChildren().get(1);
            double need = bar.prefWidth(-1);
            double got = bar.getLayoutBounds().getWidth();
            System.out.printf("宽度 %d：工具条想要 %.0f，实得 %.0f%s%n",
                    width, need, got, need > got + 1 ? "   ← 放不下" : "");
            write(stage.getScene().snapshot(null), "main-toolbar-" + width);
        });
    }

    /** 逐个把下拉菜单弹开拍一张：样式没挂上的话，这里会一眼看出来。 */
    private void openEachMenu(Stage stage, MainWindow window) {
        step(() -> stage.setWidth(1296));
        java.util.List<String> names = java.util.List.of("数据流转", "工具", "运维");
        for (String name : names) {
            step(() -> {
                for (Node n : window.lookupAll(".menu-button")) {
                    if (n instanceof MenuButton mb) {
                        if (mb.isShowing()) {
                            mb.hide();
                        }
                        if (name.equals(mb.getText())) {
                            mb.show();
                        }
                    }
                }
            });
            step(() -> {
                Window popup = javafx.stage.Window.getWindows().stream()
                        .filter(w -> w != stage && w.isShowing())
                        .findFirst().orElse(null);
                if (popup == null) {
                    System.out.println("菜单「" + name + "」没弹出来");
                } else {
                    write(popup.getScene().snapshot(null), "menu-" + name);
                    System.out.println("菜单「" + name + "」已拍");
                }
            });
        }
    }

    private void step(Runnable action) {
        at += 0.9;
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
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(MainToolbarPreview.class, args);
        System.exit(0);
    }
}
