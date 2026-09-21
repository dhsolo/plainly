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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 从搜索结果点过去，树有没有真的展开到那一行。
 *
 * <p>这条最容易安静地失败：树是懒加载的，每展开一层都要一次数据库往返。
 * 写成「展开三层然后选中」的话，第二层执行时第一层的孩子还没长出来，
 * 一路扑空——<b>而且不会报错</b>，只是什么都没发生。所以必须真的走一遍，
 * 看最后选中的是不是那个节点。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RevealProbe.java [表名]</pre>
 */
public class RevealProbe extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private TextField field;
    private TreeView<?> tree;

    @Override
    public void start(Stage stage) {
        String needle = getParameters().getRaw().isEmpty()
                ? "dish_step" : getParameters().getRaw().get(0);

        AppContext context = new AppContext();
        for (ConnectionConfig c : context.registry().listAll()) {
            try {
                DbSession s = context.openSession(c);
                s.schemas();
            } catch (RuntimeException ignored) {
                // 连不上的跳过
            }
        }

        MainWindow window = new MainWindow(context);
        Scene scene = new Scene(window, 1280, 760);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("定位取样");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".sidebar-search-field")) {
                if (n instanceof TextField tf) {
                    field = tf;
                }
            }
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
        });

        step(() -> {
            field.setText(needle);
            System.out.println("敲入「" + needle + "」");
        });
        step(() -> { });
        step(() -> {
            // 结果列表里选第一条并「点」它
            for (Node n : window.lookupAll(".search-results")) {
                if (n instanceof javafx.scene.control.ListView<?> lv && !lv.getItems().isEmpty()) {
                    lv.getSelectionModel().selectFirst();
                    System.out.println("结果 " + lv.getItems().size() + " 条，选中第一条");
                    // 直接派发一次单击：走的是真实的处理器
                    javafx.event.Event.fireEvent(lv, new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                            0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false,
                            true, false, false, null));
                }
            }
        });

        // 定位要等树一层层加载，给足时间
        for (int i = 0; i < 4; i++) {
            step(() -> { });
        }

        step(() -> {
            TreeItem<?> selected = tree.getSelectionModel().getSelectedItem();
            System.out.println("树上选中：" + (selected == null ? "【什么都没选中】"
                    : String.valueOf(selected.getValue())));
            System.out.println("树可见行数：" + tree.getExpandedItemCount());
            write(scene.snapshot(null), "reveal");
        });

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private void step(Runnable action) {
        at += 1.6;
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
            System.out.println("→ " + name + ".png");
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(RevealProbe.class, args);
        System.exit(0);
    }
}
