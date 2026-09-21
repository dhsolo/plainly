import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.MainWindow;
import com.plainly.driver.ConnectionConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 左侧搜索改版之后的取样。
 *
 * <p>三个之前实测失败的输入各来一次：
 * 「创建时间」（按中文注释，之前零结果）、{@code orderitem}（少打下划线，之前零结果）、
 * {@code order}（正常情况，看排序）。顺带量一次耗时——之前每敲一字 13 次往返约 150ms，
 * 现在应当是首次建索引之后就归零。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SidebarSearchPreview.java</pre>
 */
public class SidebarSearchPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private TextField field;
    private long typedAt;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        // 搜索只覆盖已连接的连接，先都连上（正常使用时这一步是用户展开树完成的）
        int live = 0;
        for (ConnectionConfig c : context.registry().listAll()) {
            try {
                DbSession s = context.openSession(c);
                s.schemas();
                live++;
            } catch (RuntimeException e) {
                System.out.println("连不上 " + c.name() + "：" + e.getMessage());
            }
        }
        System.out.println("已连接 " + live + " 条");

        MainWindow window = new MainWindow(context);
        Scene scene = new Scene(window, 1280, 720);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("左侧搜索取样");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".sidebar-search-field")) {
                if (n instanceof TextField tf) {
                    field = tf;
                }
            }
            System.out.println(field == null ? "没找到搜索框" : "搜索框就位");
        });

        type("创建时间", "search-comment");
        type("orderitem", "search-loose");
        type("order", "search-normal");

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    /** 敲进去、等一拍（防抖 180ms + 后台建索引）、拍一张。 */
    private void type(String needle, String shot) {
        step(() -> {
            if (field == null) {
                return;
            }
            field.setText(needle);
            typedAt = System.nanoTime();
        });
        // 第一次要建索引，给足时间
        step(() -> { });
        step(() -> {
            long ms = (System.nanoTime() - typedAt) / 1_000_000L;
            System.out.printf("「%s」→ %s（含防抖与等待共 %d ms）%n", needle, shot, ms);
            write(field.getScene().snapshot(null), shot);
        });
    }

    private void step(Runnable action) {
        at += 1.8;
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
            System.out.println("   → " + name + ".png");
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(SidebarSearchPreview.class, args);
        System.exit(0);
    }
}
