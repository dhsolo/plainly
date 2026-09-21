import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.FilterDialog;
import com.plainly.app.view.FormViewPane;
import com.plainly.core.edit.EditBuffer;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.query.FilterSpec;
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
 * 筛选对话框与表单视图的取样。
 *
 * <p>用 demo 库里那张有超长 DECIMAL 的表——表单上「精确数值 · 全程按原文传递」这条标记
 * 只有在真值旁边才看得出对不对。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/FilterFormPreview.java</pre>
 */
public class FilterFormPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private Stage main;
    private AppContext ctx;

    @Override
    public void start(Stage stage) {
        main = stage;
        ctx = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-filterform");
        DbSession session = ctx.openSession(cfg);
        QueryResult result = session.connection().execute("SELECT * FROM ORDERS", 20);

        FormViewPane form = new FormViewPane();
        form.setResult(result, new EditBuffer(result));
        Scene scene = new Scene(form, 1080, 420);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("表单视图");
        stage.show();

        after(1.2, () -> {
            write(scene.snapshot(null), "pane-form");
            shootFilter(session);
        });
    }

    private void shootFilter(DbSession session) {
        after(1.2, () -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    write(w.getScene().snapshot(null), "dialog-filter");
                    w.hide();
                }
            }
            after(0.6, () -> {
                ctx.close();
                Platform.exit();
            });
        });
        new FilterDialog(session, "PUBLIC", "ORDERS",
                session.structure("PUBLIC", "ORDERS").columns())
                .showAndWait(main, FilterSpec.empty());
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
        launch(FilterFormPreview.class, args);
        System.exit(0);
    }
}
