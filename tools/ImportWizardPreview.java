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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 从库节点进入导入向导的取样。
 *
 * <p>要验的是那条新路：目标表一开始是空的，表清单异步来，选中之后列才读得到，
 * 映射按新表的字段名重配。所以脚本不直接塞列，只塞文件和一个表名，剩下的让界面自己走。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ImportWizardPreview.java</pre>
 */
public class ImportWizardPreview extends Application {

    /** 预览产物的落脚点。写成系统临时目录，不写死某台机器上的路径。 */
    private static final String OUT = System.getProperty("java.io.tmpdir") + "/plainly-preview/";

    private Stage main;
    private AppContext ctx;

    @Override
    public void start(Stage stage) throws Exception {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 300, 160));
        stage.setTitle("导入向导取样");
        stage.show();

        Path csv = Paths.get(OUT + "wizard-import.csv");
        Files.writeString(csv,
                "ID,CUSTOMER_ID,AMOUNT,NOTE\n"
                        + "9001,7,12345678901234567890.1234567890,超长小数\n"
                        + "9002,8,1.5,普通值\n",
                StandardCharsets.UTF_8);

        ctx = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-import-wizard");
        DbSession session = ctx.openSession(cfg);

        // 1.0s：表清单已经回来了，这时候还没选表——先拍一张「没选目标表」的样子
        after(1.0, () -> {
            Window w = dialogWindow();
            if (w == null) {
                System.out.println("找不到对话框窗口");
                return;
            }
            ComboBox<String> box = findCombo(w);
            System.out.println("表清单：" + (box == null ? "(没找到下拉)" : box.getItems()));
            write(w.getScene().snapshot(null), "wizard-no-table");

            // 先把文件填上，再选表——顺序反着来，正好验「换表之后映射会重配」
            TextField path = (TextField) w.getScene().getRoot().lookupAll(".text-field")
                    .stream().findFirst().orElse(null);
            if (path != null) {
                path.setText(csv.toString());
            }
            after(0.8, () -> {
                if (box != null && box.getItems().contains("ORDERS")) {
                    box.getSelectionModel().select("ORDERS");
                }
                after(1.2, () -> {
                    Window w2 = dialogWindow();
                    if (w2 != null) {
                        write(w2.getScene().snapshot(null), "wizard-picked");
                        w2.hide();
                    }
                    after(0.5, () -> {
                        ctx.close();
                        Platform.exit();
                    });
                });
            });
        });

        new ImportDialog(ctx, session, "PUBLIC").showAndWait(main);
    }

    private Window dialogWindow() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w != main) {
                return w;
            }
        }
        return null;
    }

    /** 目标表那个下拉：认它的内容——只有它装着表名。 */
    @SuppressWarnings("unchecked")
    private static ComboBox<String> findCombo(Window w) {
        for (Node n : w.getScene().getRoot().lookupAll(".combo-box")) {
            if (n instanceof ComboBox<?> c && c.getItems().contains("ORDERS")) {
                return (ComboBox<String>) c;
            }
        }
        return null;
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
        launch(ImportWizardPreview.class, args);
        System.exit(0);
    }
}
