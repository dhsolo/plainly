import com.plainly.app.ui.Icons;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * 把窗口图标按像素放大，看清 16px 下究竟画成了什么。
 *
 * <p>标题栏里就那么 16×16，肉眼只能看出「一块绿」；放大到能数格子，
 * 才知道是描边糊了、还是条纹压根没占满一个像素。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/IconPreview.java</pre>
 */
public class IconPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private static final int ZOOM = 12;

    public static void main(String[] args) {
        launch(IconPreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        int[] sizes = {16, 24, 32};
        int gap = 8;
        int width = 0;
        int height = 0;
        for (int s : sizes) {
            width += s * ZOOM + gap;
            height = Math.max(height, s * ZOOM);
        }

        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int x0 = 0;
        for (int s : sizes) {
            Image img = Icons.appIcons().stream()
                    .filter(i -> (int) i.getWidth() == s).findFirst().orElseThrow();
            PixelReader px = img.getPixelReader();
            for (int y = 0; y < s * ZOOM; y++) {
                for (int x = 0; x < s * ZOOM; x++) {
                    sheet.setRGB(x0 + x, y, px.getArgb(x / ZOOM, y / ZOOM));
                }
            }
            x0 += s * ZOOM + gap;
        }

        try {
            File f = new File(OUT + "icon-zoom.png");
            ImageIO.write(sheet, "png", f);
            System.out.println("→ " + f.getAbsolutePath() + "  " + width + "×" + height);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
        Platform.exit();
    }
}
