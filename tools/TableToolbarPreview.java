import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.TableTabPane;
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
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 打开表那一页的工具条，究竟挤成什么样。
 *
 * <p>「挤不挤」不能靠数按钮个数拍脑袋——要看在真实窗口宽度下有没有溢出、
 * 有没有把右边的说明文字顶没。所以按几个常见宽度各拍一张，并把工具条
 * 自己要求的最小宽度打出来：那个数超过窗口宽度，就是真的放不下。
 *
 * <p>跑之前先关掉应用：本机配置库是 SQLite，两个进程会撞锁。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/TableToolbarPreview.java</pre>
 */
public class TableToolbarPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    /** 左侧还占着树，所以表格页拿到的宽度比整个窗口小一截。 */
    private static final int[] WIDTHS = {1000, 1180, 1420};

    private double at;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-toolbar");
        DbConnection conn = JdbcConnections.open(cfg);
        DbSession session = new DbSession(cfg, conn);

        TableTabPane pane = new TableTabPane(context, session, "PUBLIC", "ORDERS");
        Scene scene = new Scene(pane, WIDTHS[0], 520);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("表页工具条取样");
        stage.show();

        for (int w : WIDTHS) {
            shoot(stage, pane, w);
        }
        eachView(stage, pane);
        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private void shoot(Stage stage, TableTabPane pane, int width) {
        step(() -> {
            stage.setWidth(width + 16);
            pane.applyCss();
            pane.layout();
        });
        step(() -> {
            Node bar = pane.getTop();
            double need = bar.prefWidth(-1);
            double got = bar.getLayoutBounds().getWidth();
            System.out.printf("宽度 %d：工具条想要 %.0f，实得 %.0f%s%n",
                    width, need, got, need > got + 1 ? "   ← 放不下，右边的东西被挤掉了" : "");
            write(stage.getScene().snapshot(null), "toolbar-" + width);
        });
    }

    /**
     * 把六个视图逐个点一遍。
     *
     * <p>操作区现在跟着视图重建，所以每个视图都得看一眼：既确认该出现的出现了
     * （索引外键视图上才有「编辑索引与外键」），也确认没有哪个视图把工具条撑爆。
     */
    private void eachView(Stage stage, TableTabPane pane) {
        step(() -> stage.setWidth(1016));
        String[] names = {"数据", "表单", "结构", "DDL", "索引", "外键", "触发器"};
        for (String name : names) {
            step(() -> {
                for (Node n : pane.lookupAll(".toggle-button")) {
                    if (n instanceof javafx.scene.control.ToggleButton t
                            && name.equals(t.getText())) {
                        t.fire();
                    }
                }
            });
            step(() -> {
                // 顶部现在是两行：上面视图条，下面操作条。两行分别量
                javafx.scene.layout.VBox top = (javafx.scene.layout.VBox) pane.getTop();
                Node views = top.getChildren().get(0);
                Node acts = top.getChildren().get(1);
                double got = top.getLayoutBounds().getWidth();
                System.out.printf("视图 %-8s 视图条 %.0f  操作条 %.0f  可用 %.0f%s%n",
                        name, views.prefWidth(-1), acts.prefWidth(-1), got,
                        Math.max(views.prefWidth(-1), acts.prefWidth(-1)) > got + 1
                                ? "   ← 放不下" : "");
                write(stage.getScene().snapshot(null), "view-" + name.replace('/', '-'));
            });
        }
    }

    /** 排一个动作到时间线上。JavaFX 要一帧一帧地走，挤在一起量不到布局结果。 */
    private void step(Runnable action) {
        at += 0.9;
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
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(TableToolbarPreview.class, args);
        System.exit(0);
    }
}
