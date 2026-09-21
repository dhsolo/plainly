import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.CopyTableDialog;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * 「复制表」对话框长什么样、拦不拦得住。
 *
 * <p>三件事光看代码看不出来：预览框里的语句排得下吗、撞名时按钮真的灰了吗、
 * 长语句会不会把按钮挤出窗口。所以真开一个对话框，接着屏幕上的像素看。
 *
 * <p>连的是临时 H2 库，<b>不碰</b> demo 目录和任何用户的库。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/CopyDialogPreview.java &lt;输出目录&gt;</pre>
 */
public class CopyDialogPreview extends Application {

    private static String out = ".";
    private Stage main;
    private AppContext context;
    private DbSession session;
    private Path dir;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(CopyDialogPreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) throws Exception {
        main = stage;
        stage.setTitle("复制表预览");
        stage.setScene(new Scene(new StackPane(), 320, 200));
        stage.show();

        dir = Files.createTempDirectory("plainly-copy-preview");
        ConnectionConfig cfg = new ConnectionConfig()
                .setId("preview")
                .setName("临时 H2")
                .setType(DbType.H2)
                .setFilePath(dir.resolve("preview").toString());
        try (DbConnection seed = Connections.open(cfg)) {
            seed.executeDdlBatch(List.of(
                    "CREATE TABLE ORDERS (ID BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + " UID BIGINT NOT NULL, CODE VARCHAR(32), AMOUNT DECIMAL(12,2),"
                            + " CREATED TIMESTAMP)",
                    "CREATE INDEX IDX_ORDERS_UID ON ORDERS (UID)",
                    "CREATE UNIQUE INDEX UK_CODE ON ORDERS (CODE)",
                    // 故意先占住 ORDERS_COPY，用来验证撞名会被拦下
                    "CREATE TABLE ORDERS_COPY (ID BIGINT)",
                    // USERS_copy 没被占，用来验证正常态下按钮是可按的
                    "CREATE TABLE USERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))"));
        }

        context = new AppContext();
        session = new DbSession(cfg, Connections.open(cfg));

        // 第一张：ORDERS_copy 已被占，应当出现红字且「开始复制」灰着
        open(0.6, "ORDERS");
        at(2.4, () -> shoot("copy-dialog-blocked"));
        at(2.8, this::closeDialog);

        // 第二张：USERS_copy 没被占，应当没有红字且「开始复制」可按
        open(3.2, "USERS");
        at(5.0, () -> shoot("copy-dialog-ok"));

        at(6.4, Platform::exit);
        stage.setOnHidden(e -> cleanup());
    }

    private void open(double seconds, String table) {
        at(seconds, () -> {
            CopyTableDialog dialog = new CopyTableDialog(context, session, "PUBLIC",
                    new TableInfo("PUBLIC", table, ObjectKind.TABLE, "订单", 25));
            dialog.show(main);
        });
    }

    private void closeDialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w != main) {
                w.hide();
            }
        }
    }

    /** 拍一张，顺带把「开始复制」按不按得下去打出来——那是这次要验的东西。 */
    private void shoot(String name) {
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
        javafx.scene.Node button = null;
        for (javafx.scene.Node node : dialog.getScene().getRoot().lookupAll(".tool-button")) {
            if (node instanceof javafx.scene.control.Button b
                    && b.getText().startsWith("开始复制")) {
                button = b;
            }
        }
        System.out.println(name + "：开始复制 "
                + (button == null ? "找不到" : (button.isDisabled() ? "灰着（拦下了）" : "可按")));
        write(dialog.getScene().snapshot(null), name);
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
