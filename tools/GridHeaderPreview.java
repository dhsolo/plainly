import com.plainly.app.view.DataGridPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 数据网格表头的取样。
 *
 * <p>「列名会不会被类型挤掉」只有画出来才知道，所以真的取一次数据渲染，
 * 并且故意把窗口压窄——列一挤，问题才现形。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/GridHeaderPreview.java</pre>
 */
public class GridHeaderPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    @Override
    public void start(Stage stage) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-demo");
        DbConnection conn = JdbcConnections.open(cfg);
        QueryResult result = conn.execute("SELECT * FROM ORDERS", 12);

        DataGridPane grid = new DataGridPane();
        grid.setResult(result);

        // 故意窄：宽窗口下什么排布都不难看，挤起来才见真章
        Scene scene = new Scene(grid, 760, 300);
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("表头取样");
        stage.show();

        PauseTransition p = new PauseTransition(Duration.seconds(1.2));
        p.setOnFinished(e -> {
            write(scene.snapshot(null), "grid-head");
            conn.close();
            Platform.exit();
        });
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
            File f = new File(OUT + name + ".png");
            ImageIO.write(out, "png", f);
            System.out.println("→ " + f.getAbsolutePath() + "  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(GridHeaderPreview.class, args);
        System.exit(0);
    }
}
