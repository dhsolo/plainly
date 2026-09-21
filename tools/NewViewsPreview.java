import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.NewTableDialog;
import com.plainly.app.view.RelationPane;
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
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 新增的两个界面取样：触发器页和新建表对话框。
 *
 * <p>触发器页用真库的数据——demo 库里没有触发器，空着的页看不出排布对不对。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/NewViewsPreview.java</pre>
 */
public class NewViewsPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private AppContext ctx;
    private DbSession session;
    private Stage main;

    @Override
    public void start(Stage stage) {
        main = stage;
        ctx = new AppContext();
        ConnectionConfig cfg = ctx.registry().listAll().stream()
                .filter(c -> c.type() == DbType.MYSQL).findFirst().orElseThrow();
        session = ctx.openSession(cfg);
        session.connection().useSchema("nj");

        RelationPane triggers = new RelationPane(ctx, session, "nj", "ele_dev", false);
        triggers.loadIfNeeded();
        Scene scene = new Scene(triggers, 1000, 520);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("触发器页");
        stage.show();

        after(2.0, () -> {
            write(scene.snapshot(null), "pane-triggers");

            RelationPane keys = new RelationPane(ctx, session, "nj", "ele_dev", true);
            keys.loadIfNeeded();
            Scene s2 = new Scene(keys, 1000, 460);
            s2.getStylesheets().addAll(scene.getStylesheets());
            stage.setScene(s2);
            after(1.5, () -> {
                write(s2.snapshot(null), "pane-keys");
                shootNewTable();
            });
        });
    }

    /** 对话框是 showAndWait 开的，趁它开着进来拍一张再关掉。 */
    private void shootNewTable() {
        after(1.2, () -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    write(w.getScene().snapshot(null), "dialog-newtable");
                    w.hide();
                }
            }
            after(0.6, () -> {
                ctx.close();
                Platform.exit();
            });
        });
        NewTableDialog dialog = new NewTableDialog(ctx, session, "nj");
        dialog.showAndWait(main);
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
        launch(NewViewsPreview.class, args);
        System.exit(0);
    }
}
