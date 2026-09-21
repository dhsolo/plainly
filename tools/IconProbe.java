import com.plainly.app.ui.Icons;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * 窗口图标的探针。
 *
 * <p>{@code Node.snapshot()} 在节点不属于任何 Scene 时能不能出图，是这里唯一想确认的事：
 * 出不了图不会报错，只会得到一张全透明的空图，装到 Stage 上看起来就像「没设图标」。
 * 所以这里直接读像素：中心必须是那块青绿，条纹处必须是白的，四角必须透明（圆角）。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/IconProbe.java</pre>
 */
public class IconProbe extends Application {

    private static int exitCode = 0;

    public static void main(String[] args) {
        launch(IconProbe.class, args);
        System.exit(exitCode);
    }

    @Override
    public void start(Stage stage) {
        boolean ok = true;
        for (Image img : Icons.appIcons()) {
            int w = (int) img.getWidth();
            PixelReader px = img.getPixelReader();
            if (px == null || w == 0) {
                System.out.println("失败：" + w + "px 没有出图");
                ok = false;
                continue;
            }
            Color corner = px.getColor(0, 0);
            Color mid = px.getColor(w / 2, w / 2);
            Color body = px.getColor(w / 2, (int) (w * 0.22));

            boolean cornerClear = corner.getOpacity() < 0.5;
            boolean midWhite = mid.getOpacity() > 0.9 && mid.getBrightness() > 0.85;
            boolean bodyTeal = body.getOpacity() > 0.9 && body.getBrightness() < 0.6;

            System.out.printf("%3dpx  角=%s  中=%s  底=%s  %s%n", w,
                    fmt(corner), fmt(mid), fmt(body),
                    (cornerClear && midWhite && bodyTeal) ? "ok" : "??");
            if (!(cornerClear && midWhite && bodyTeal)) {
                ok = false;
            }
        }
        System.out.println(ok ? "通过：图标已渲染" : "失败：图标不对");
        exitCode = ok ? 0 : 1;
        Platform.exit();
    }

    private static String fmt(Color c) {
        return String.format("%.2f/%02x%02x%02x", c.getOpacity(),
                (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }
}
