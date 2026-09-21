import com.plainly.app.AppContext;
import com.plainly.app.view.StructureSyncDialog;
import com.plainly.driver.ConnectionConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 结构同步弹窗上那两个连接下拉，选中之后到底显示了什么。
 *
 * <p>「只看见一个图标、看不到连接名」这种事，看代码是看不出来的——
 * 单元格里明明把名字塞进去了。所以真的选一次，然后量按钮区里那个标签有多宽：
 * 宽度接近 0 就说明它被压没了，而不是没画。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SyncComboPreview.java</pre>
 */
public class SyncComboPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        List<ConnectionConfig> all = context.registry().listAll();
        System.out.println("已保存的连接 " + all.size() + " 条");

        stage.setWidth(200);
        stage.setHeight(120);
        stage.show();

        new StructureSyncDialog(context).show(stage);

        step(() -> {
            Window dlg = pick(stage);
            if (dlg == null) {
                System.out.println("弹窗没出来");
                return;
            }
            int i = 0;
            for (Node n : dlg.getScene().getRoot().lookupAll(".combo-box")) {
                if (!(n instanceof ComboBox<?> box)) {
                    continue;
                }
                // 只动装连接的那两个：它们的 items 是 ConnectionConfig
                if (box.getItems().isEmpty()
                        || !(box.getItems().get(0) instanceof ConnectionConfig)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                ComboBox<ConnectionConfig> cc = (ComboBox<ConnectionConfig>) box;
                cc.getSelectionModel().select(Math.min(i, cc.getItems().size() - 1));
                System.out.println("下拉 " + i + " 选中 -> " + cc.getValue().name());
                i++;
            }
        });

        step(() -> {
            Window dlg = pick(stage);
            if (dlg == null) {
                return;
            }
            for (Node n : dlg.getScene().getRoot().lookupAll(".combo-box")) {
                if (!(n instanceof ComboBox<?> box) || box.getValue() == null
                        || !(box.getValue() instanceof ConnectionConfig cfg)) {
                    continue;
                }
                // 必须用 getButtonCell()：lookup(".list-cell") 会捞到别的节点，
                // 量出来的数字看着像「被压扁了」，其实根本不是那个东西
                javafx.scene.control.ListCell<?> cell = box.getButtonCell();
                System.out.printf("  「%s」下拉宽 %.0f，按钮单元格宽 %.0f，文字 [%s]%n",
                        cfg.name(), box.getWidth(),
                        cell == null ? -1 : cell.getWidth(),
                        cell == null ? "" : String.valueOf(cell.getText()));
            }
            write(dlg.getScene().snapshot(null), "sync-combo");
        });

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private static Window pick(Stage owner) {
        return Window.getWindows().stream()
                .filter(w -> w != owner && w.isShowing())
                .findFirst().orElse(null);
    }

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
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(SyncComboPreview.class, args);
        System.exit(0);
    }
}
