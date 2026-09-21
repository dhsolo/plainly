import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import org.fxmisc.richtext.CodeArea;

/**
 * 子查询里的补全，真的开一次弹窗看。
 *
 * <p>{@code SqlScopes} 的单元测试证明的是「解析对不对」；这个探针证明的是
 * 界面那一层有没有把解析结果用上——两者是两回事，中间那段胶水没有单元测试。
 *
 * <p>三个场景，最后一个是关键：在外层引用只存在于子查询里的别名，
 * 弹窗应当<b>明说解析不出来</b>，而不是把另一张表的字段补出来。
 *
 * <p>跑之前先关掉应用：本机配置库是 SQLite，两个进程会撞锁。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SubqueryCompletionPreview.java</pre>
 */
public class SubqueryCompletionPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private CodeArea area;
    private SqlEditorPane pane;
    private Stage stage;
    private double at;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        AppContext context = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-subquery");
        DbConnection conn = JdbcConnections.open(cfg);
        DbSession session = new DbSession(cfg, conn);

        pane = new SqlEditorPane(context, session, "PUBLIC");
        area = pane.editor();

        Scene scene = new Scene(pane, 1000, 640);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setTitle("子查询补全取样");
        stage.setScene(scene);
        stage.show();

        // 一、子查询内部引用外层别名——相关子查询，应当补出 ORDERS 的字段
        shoot("inside-subquery",
                "SELECT * FROM ORDERS o\nWHERE EXISTS (SELECT 1 FROM CUSTOMERS c WHERE c.id = o.",
                null);

        // 二、派生表的别名——字段来自子查询的 SELECT 列表，元数据里查不到
        shoot("derived-table",
                "SELECT s.\nFROM (SELECT ID, AMOUNT AS TOTAL, STATUS FROM ORDERS) s",
                9);

        // 三、外层引用子查询里的别名——必须明说解析不出来
        shoot("out-of-scope",
                "SELECT * FROM ORDERS o\nWHERE o.user_id IN (SELECT c.id FROM CUSTOMERS c)\n  AND c.",
                null);

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    /** 填入 SQL、把光标放到末尾（或指定位置）、唤起补全、拍一张。 */
    private void shoot(String name, String sql, Integer caret) {
        step(() -> {
            area.replaceText(sql);
            area.moveTo(caret == null ? sql.length() : caret);
            fireCtrlSpace();
        });
        step(() -> {
            System.out.println("── " + name);
            System.out.println("   弹窗开着：" + pane.completionVisible());
            Window popup = Window.getWindows().stream()
                    .filter(w -> w != stage && w.isShowing())
                    .findFirst().orElse(null);
            if (popup == null) {
                System.out.println("   没有弹窗");
            } else {
                write(popup.getScene().snapshot(null), name);
            }
        });
    }

    private void fireCtrlSpace() {
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ",
                KeyCode.SPACE, false, true, false, false));
    }

    /** 排一个动作到时间线上。JavaFX 要一帧一帧地走，挤在一起什么都渲染不出来。 */
    private void step(Runnable action) {
        at += 1.4;
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
            System.out.println("   → " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("   写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(SubqueryCompletionPreview.class, args);
        System.exit(0);
    }
}
