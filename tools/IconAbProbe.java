import com.plainly.app.ui.Icons;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.ptr.IntByReference;
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
import javafx.util.Duration;
import javafx.util.Pair;
import javax.imageio.ImageIO;
import java.util.List;

/**
 * 窗口图标为什么没生效——三种设法摆在一起对照。
 *
 * <p>「{@code getIcons()} 里有 6 张」只证明我们设过，证明不了系统收到了。
 * 所以三个窗口各用一种设法，全部 show 出来之后统一用 {@code WM_GETICON} 问系统：
 *
 * <ul>
 *   <li>A：快照出来的 {@code WritableImage}，show 之前设；</li>
 *   <li>B：从磁盘 PNG 读的 {@code Image}，show 之前设；</li>
 *   <li>C：快照图，show 之后再设。</li>
 * </ul>
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/IconAbProbe.java</pre>
 */
public class IconAbProbe extends Application {

    private static final int WM_GETICON = 0x007F;
    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    @Override
    public void start(Stage primary) throws Exception {
        // A：快照图，show 前
        Stage a = window("探针A-快照图");
        a.getIcons().setAll(Icons.appIcons());
        a.show();

        // B：磁盘 PNG，show 前
        File png = new File(OUT + "mark32.png");
        writePng(Icons.appIcons().stream()
                .filter(i -> (int) i.getWidth() == 32).findFirst().orElseThrow(), png);
        Stage b = window("探针B-磁盘图");
        b.getIcons().setAll(new Image(png.toURI().toString()));
        b.show();

        // C：快照图，show 后
        Stage c = window("探针C-事后设");
        c.show();
        c.getIcons().setAll(Icons.appIcons());

        System.out.println("A 图标数=" + a.getIcons().size()
                + "  B=" + b.getIcons().size() + "  C=" + c.getIcons().size());

        PauseTransition p = new PauseTransition(Duration.seconds(1.5));
        p.setOnFinished(e -> {
            report();
            Platform.exit();
        });
        p.play();
    }

    private Stage window(String title) {
        Stage s = new Stage();
        s.setTitle(title);
        s.setScene(new Scene(new StackPane(), 260, 120));
        return s;
    }

    private static void writePng(Image img, File f) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        ImageIO.write(out, "png", f);
    }

    private static void report() {
        long pid = ProcessHandle.current().pid();
        User32 u = User32.INSTANCE;
        u.EnumWindows((hwnd, data) -> {
            IntByReference owner = new IntByReference();
            u.GetWindowThreadProcessId(hwnd, owner);
            if (owner.getValue() != pid) {
                return true;
            }
            char[] buf = new char[256];
            u.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf);
            if (!title.startsWith("探针")) {
                return true;
            }
            List<Pair<String, Integer>> which =
                    List.of(new Pair<>("small", 0), new Pair<>("big", 1));
            StringBuilder sb = new StringBuilder(title).append("  ");
            for (Pair<String, Integer> k : which) {
                long v = u.SendMessage(hwnd, WM_GETICON,
                        new WPARAM(k.getValue()), new LPARAM(0)).longValue();
                sb.append(k.getKey()).append('=').append(v == 0 ? "0(无)" : "有").append("  ");
            }
            System.out.println(sb);
            return true;
        }, null);
    }

    public static void main(String[] args) {
        launch(IconAbProbe.class, args);
        System.exit(0);
    }
}
