import com.plainly.app.AppContext;
import com.plainly.app.view.ConnectionDialog;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
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
 * 连接表单上新加的「分组」那一行，画出来看一眼。
 *
 * <p>要确认三件事：这一行在不在、下拉里有没有列出已有的分组、
 * 加了一行之后按钮有没有被挤出窗口——最后这条出过事，
 * 勾上 SSH 时表单变长，「保存」曾经整个掉到窗口外面，于是连接根本存不下来。
 *
 * <p>只读：开一个编辑框看看就关掉，不保存任何东西。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ConnectionFormPreview.java &lt;输出目录&gt;</pre>
 */
public class ConnectionFormPreview extends Application {

    private static String out = ".";
    private Stage main;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(ConnectionFormPreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 320, 200));
        stage.setTitle("连接表单预览");
        stage.show();

        at(1.6, this::shoot);
        at(0.6, () -> {
            try (AppContext ctx = new AppContext()) {
                ConnectionConfig demo = new ConnectionConfig()
                        .setId("preview")
                        .setName("订单库 生产")
                        .setType(DbType.MYSQL)
                        .setHost("10.0.0.8")
                        .setPort(3306)
                        .setUser("app")
                        .setDatabase("shop")
                        .setColor("#c0392b")
                        .setGroup("线上");
                new ConnectionDialog(ctx, demo).showAndWait(main);
            }
        });
        at(2.6, Platform::exit);
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
        // 「分组」那一行在表单中段，滚一点才看得见
        for (javafx.scene.Node node : dialog.getScene().getRoot().lookupAll(".dialog-scroll")) {
            if (node instanceof javafx.scene.control.ScrollPane sp) {
                sp.setVvalue(0.62);
            }
        }
        dialog.getScene().getRoot().applyCss();
        dialog.getScene().getRoot().layout();
        write(dialog.getScene().snapshot(null), "connection-form");
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
