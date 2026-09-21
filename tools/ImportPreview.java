import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.ImportDialog;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * 导入向导取样。
 *
 * <p>造一份含 30 位小数的 CSV：那条「有几列超过 15 位有效数字」的警告只有在真有风险时才出现，
 * 拿一份规规矩矩的文件是看不出它工作与否的。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ImportPreview.java</pre>
 */
public class ImportPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private Stage main;
    private AppContext ctx;

    @Override
    public void start(Stage stage) throws Exception {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 300, 160));
        stage.setTitle("导入取样");
        stage.show();

        Path csv = Paths.get(OUT + "sample-import.csv");
        Files.writeString(csv,
                "ID,AMOUNT,NOTE\n"
                        + "9001,12345678901234567890.1234567890,超长小数\n"
                        + "9002,1.5,普通值\n"
                        + "9003,2.25,\"含逗号, 的备注\"\n",
                StandardCharsets.UTF_8);

        ctx = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-import");
        DbSession session = ctx.openSession(cfg);

        after(1.6, () -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    write(w.getScene().snapshot(null), "dialog-import");
                    w.hide();
                }
            }
            after(0.6, () -> {
                ctx.close();
                Platform.exit();
            });
        });

        ImportDialog dialog = new ImportDialog(ctx, session, "PUBLIC", "ORDERS",
                session.structure("PUBLIC", "ORDERS").columns());
        // 直接把文件塞进去，省得走文件选择器
        Platform.runLater(() -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    w.getScene().getRoot().lookupAll(".text-field").stream().findFirst()
                            .ifPresent(n -> {
                                if (n instanceof javafx.scene.control.TextField f) {
                                    f.setText(csv.toString());
                                    f.fireEvent(new javafx.event.ActionEvent());
                                }
                            });
                }
            }
        });
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
        launch(ImportPreview.class, args);
        System.exit(0);
    }
}
