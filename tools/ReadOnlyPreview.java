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
 * 只读提示条的取样：不能改的时候有没有说清楚，能改的时候会不会白占一条。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ReadOnlyPreview.java</pre>
 */
public class ReadOnlyPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    @Override
    public void start(Stage stage) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-readonly");
        DbConnection conn = JdbcConnections.open(cfg);

        // 带表达式的结果集：有一列不属于任何表，改了没处回写
        QueryResult ro = conn.execute("SELECT ID, AMOUNT * 2 AS DOUBLED FROM ORDERS", 6);
        QueryResult rw = conn.execute("SELECT * FROM ORDERS", 6);
        System.out.println("只读原因 = " + ro.readOnlyReason());
        System.out.println("可写原因 = " + rw.readOnlyReason());

        shoot(stage, ro, "grid-readonly", () ->
                shoot(stage, rw, "grid-editable", () -> {
                    conn.close();
                    Platform.exit();
                }));
    }

    private void shoot(Stage stage, QueryResult result, String name, Runnable next) {
        DataGridPane grid = new DataGridPane();
        grid.setResult(result);
        Scene scene = new Scene(grid, 820, 260);
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        PauseTransition p = new PauseTransition(Duration.seconds(1.0));
        p.setOnFinished(e -> {
            write(scene.snapshot(null), name);
            next.run();
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
            ImageIO.write(out, "png", new File(OUT + name + ".png"));
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(ReadOnlyPreview.class, args);
        System.exit(0);
    }
}
