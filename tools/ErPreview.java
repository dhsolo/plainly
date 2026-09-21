import com.plainly.app.AppContext;
import com.plainly.app.view.ErDiagramDialog;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * ER 图取样：真逆向一次 demo 库，看盒子、关系线和精确数值列的标记画成什么样。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ErPreview.java</pre>
 */
public class ErPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private Stage main;
    private AppContext ctx;

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 280, 140));
        stage.setTitle("ER 取样");
        stage.show();

        ctx = new AppContext();
        new ErDiagramDialog(ctx).show(main);

        // 挑一个 H2 连接（demo 库），再点「重新逆向」
        after(1.0, () -> withDialog(w -> {
            var combos = w.getScene().getRoot().lookupAll(".combo-box").toArray();
            if (combos.length > 0 && combos[0] instanceof ComboBox<?> box) {
                for (Object item : box.getItems()) {
                    if (String.valueOf(item).contains("H2")
                            || item.toString().contains("shop")) {
                        @SuppressWarnings("unchecked")
                        ComboBox<Object> typed = (ComboBox<Object>) box;
                        typed.setValue(item);
                        break;
                    }
                }
            }
        }));

        after(2.6, () -> withDialog(w -> w.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b
                        && "重新逆向".equals(b.getText()))
                .findFirst()
                .ifPresent(n -> ((javafx.scene.control.Button) n).fire())));

        after(5.0, () -> withDialog(w -> {
            write(w.getScene().snapshot(null), "dialog-er");
            w.hide();
        }));

        after(6.0, () -> {
            ctx.close();
            Platform.exit();
        });
    }

    private void withDialog(java.util.function.Consumer<Window> action) {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w != main) {
                action.accept(w);
                return;
            }
        }
        System.out.println("没找到对话框");
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
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }

    public static void main(String[] args) {
        launch(ErPreview.class, args);
        System.exit(0);
    }
}
