import com.plainly.app.ui.UiUtils;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 提示弹窗的探针。
 *
 * <p>要确认的是那个图标到底换掉没有。modena 用 CSS 的 {@code -fx-graphic} 指着一张 PNG，
 * 样式表优先级高于代码里设的值——所以「setGraphic 了」不等于「界面上换了」，
 * 必须把弹窗真的画出来看像素。
 *
 * <p>快照的是弹窗自己的场景，只有我们自己的内容，不碰屏幕上的其它东西。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/AlertProbe.java</pre>
 */
public class AlertProbe extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private Stage main;

    public static void main(String[] args) {
        launch(AlertProbe.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setTitle("弹窗探针");
        stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 320, 200));
        stage.show();

        at(0.6, () -> capture("alert-error"));
        at(0.8, () -> UiUtils.showError(main, "无法应用变更",
                new RuntimeException("Table \"ORDERS\" not found; SQL statement:\nALTER TABLE ORDERS ...")));

        at(2.4, () -> capture("alert-confirm"));
        at(2.6, () -> UiUtils.confirm(main, "确认删除连接？", "删除后需要重新填写主机、账号与密码。"));

        at(4.2, () -> capture("alert-info"));
        at(4.4, () -> UiUtils.showInfo(main, "变更已应用", "共 3 项结构变更已提交。"));

        at(6.0, () -> Platform.exit());
    }

    /** 弹窗是用 showAndWait 开的，这里在它开着的时候进来拍一张，然后关掉放行。 */
    private void capture(String name) {
        at(0.9, () -> {
            Window dialog = null;
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    dialog = w;
                }
            }
            if (dialog == null) {
                System.out.println(name + "：没找到弹窗");
                return;
            }
            Stage stage = dialog instanceof Stage ? (Stage) dialog : null;
            List<Image> icons = stage == null ? List.of() : stage.getIcons();
            System.out.println(name + "：窗口图标 " + icons.size() + " 个"
                    + (icons.isEmpty() ? "（还是系统默认）" : "，最小 "
                    + (int) icons.get(0).getWidth() + "px"));

            Image shot = dialog.getScene().snapshot(null);
            write(shot, name);
            dialog.hide();
        });
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
            File f = new File(OUT + name + ".png");
            ImageIO.write(out, "png", f);
            System.out.println("        → " + f.getAbsolutePath() + "  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("        写图失败：" + e);
        }
    }

    /**
     * 动画回调里不能直接 showAndWait（"not allowed during animation or layout processing"），
     * 所以再 runLater 一跳，跳出这一轮 pulse 再动手。
     */
    private void at(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }
}
