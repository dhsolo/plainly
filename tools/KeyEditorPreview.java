import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.KeyEditorDialog;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 索引 / 外键编辑器改版后的取样。
 *
 * <p>要看的是三件之前做不到的事有没有真的摆在界面上：多列（而且看得出顺序）、
 * ON UPDATE、以及「改」。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/KeyEditorPreview.java</pre>
 */
public class KeyEditorPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-keys");
        DbConnection conn = JdbcConnections.open(cfg);
        DbSession session = new DbSession(cfg, conn);

        stage.setWidth(200);
        stage.setHeight(120);
        stage.show();

        KeyEditorDialog dialog = new KeyEditorDialog(context, session, "PUBLIC", "ORDERS");
        dialog.show(stage);

        shoot(stage, "keyeditor-index", 0);
        shoot(stage, "keyeditor-fk", 1);

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private void shoot(Stage owner, String name, int tabIndex) {
        step(() -> {
            Window dlg = pick(owner);
            if (dlg == null) {
                return;
            }
            for (Node n : dlg.getScene().getRoot().lookupAll(".tab-pane")) {
                if (n instanceof TabPane tabs && tabs.getTabs().size() > tabIndex) {
                    tabs.getSelectionModel().select(tabIndex);
                    Tab t = tabs.getTabs().get(tabIndex);
                    System.out.println("切到标签：" + t.getText());
                }
            }
        });
        step(() -> {
            Window dlg = pick(owner);
            if (dlg == null) {
                System.out.println("对话框没出来");
                return;
            }
            write(dlg.getScene().snapshot(null), name);
        });
    }

    private static Window pick(Stage owner) {
        return Window.getWindows().stream()
                .filter(w -> w != owner && w.isShowing())
                .findFirst().orElse(null);
    }

    private void step(Runnable action) {
        at += 1.1;
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
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(KeyEditorPreview.class, args);
        System.exit(0);
    }
}
