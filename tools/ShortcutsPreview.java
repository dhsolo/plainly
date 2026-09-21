import com.plainly.app.view.ShortcutsDialog;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 快捷键速查页画出来看一眼。
 *
 * <p>要看的是两列对不对得齐、长说明会不会把第二列挤出窗口、以及新加的
 * 样式类（shortcut-keys / shortcut-what）有没有生效——改过 CSS 就得看像素。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ShortcutsPreview.java &lt;输出目录&gt;</pre>
 */
public class ShortcutsPreview extends Application {

    private static String out = ".";
    private Stage main;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(ShortcutsPreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.setTitle("快捷键预览");
        stage.show();

        at(0.6, () -> new ShortcutsDialog().show(main));
        at(1.6, this::shoot);
        at(2.4, Platform::exit);
    }

    private void shoot() {
        Window dialog = null;
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w != main) {
                dialog = w;
            }
        }
        if (dialog == null) {
            System.out.println("没找到对话框");
            return;
        }
        write(dialog.getScene().snapshot(null), "shortcuts");
        dialog.hide();
    }

    private static void write(Image img, String name) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            File f = new File(out, name + ".png");
            ImageIO.write(buf, "png", f);
            System.out.println("→ " + f.getAbsolutePath() + "  " + w + "x" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    private void at(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }
}
