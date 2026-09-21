import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.ExportDialog;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 导出查询结果时那一行「导出范围」长什么样。
 *
 * <p>要看的是：「全表」那一项在查询结果上已经收起来了（它对查询没有意义），
 * 换上的「重新执行，导出全部」在不在、提示对不对、以及旁边那句
 * 「屏幕上这份被行数上限截过」有没有随 truncated 变。
 *
 * <p>临时 H2 库，不碰 demo 目录和任何用户的库。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ExportRangePreview.java &lt;输出目录&gt;</pre>
 */
public class ExportRangePreview extends Application {

    private static String out = ".";
    private Stage main;
    private AppContext context;
    private DbSession session;
    private Path dir;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(ExportRangePreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) throws Exception {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 320, 200));
        stage.setTitle("导出范围预览");
        stage.show();

        dir = Files.createTempDirectory("plainly-export-preview");
        ConnectionConfig cfg = new ConnectionConfig()
                .setId("preview").setName("临时 H2").setType(DbType.H2)
                .setFilePath(dir.resolve("preview").toString());
        context = new AppContext();
        session = new DbSession(cfg, Connections.open(cfg));

        // 故意用一个超出行数上限的查询：网格里这份是被截过的
        String sql = "SELECT X AS ID, CONCAT('name-', X) AS NAME FROM SYSTEM_RANGE(1, 50000)";
        QueryResult capped = session.connection().execute(sql, 1000);
        System.out.println("网格里这份 " + capped.rows().size() + " 行，truncated="
                + capped.truncated());

        at(0.6, () -> ExportDialog.forQuery(context, session, "PUBLIC", capped)
                .showAndWait(main));
        at(1.8, this::shoot);
        at(2.8, Platform::exit);
        stage.setOnHidden(e -> cleanup());
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
        for (javafx.scene.Node node : dialog.getScene().getRoot().lookupAll(".radio-button")) {
            if (node instanceof javafx.scene.control.RadioButton b) {
                System.out.println("  单选项「" + b.getText() + "」"
                        + (b.isVisible() ? "可见" : "已收起")
                        + (b.isSelected() ? "，选中" : "")
                        + (b.isDisabled() ? "，灰着" : ""));
            }
        }
        write(dialog.getScene().snapshot(null), "export-range");
        dialog.hide();
    }

    private void cleanup() {
        try {
            session.connection().close();
            context.close();
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 清不掉不影响结论
                    }
                });
            }
        } catch (Exception ignored) {
            // 同上
        }
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
