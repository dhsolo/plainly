import com.plainly.app.AppContext;
import com.plainly.app.view.ConnectionDialog;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 新加的三种库在连接对话框里长什么样。
 *
 * <p>看三件事：类型下拉里出现了没有、「库」那一格的标签有没有跟着变
 * （Oracle 填的是服务名，跟 MySQL 的库名不是一回事）、说明有没有被截断。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/NewTypesPreview.java</pre>
 */
public class NewTypesPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private Stage dialog;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        Platform.runLater(() -> new ConnectionDialog(context, null).showAndWait(null));

        step(() -> {
            dialog = findDialog();
            ComboBox<?> types = typeBox();
            System.out.println("类型下拉里有 " + types.getItems().size() + " 项：");
            types.getItems().forEach(t -> System.out.println("   · " + t));
        });

        for (String want : new String[] {"Oracle", "达梦", "Redis"}) {
            step(() -> select(want));
            step(() -> {
                System.out.println("\n== " + want + " ==");
                System.out.println("   「库」那一格标签：" + databaseLabel());
                System.out.println("   说明：" + hintText());
                write(dialog.getScene().snapshot(null), "type-" + safeName(want));
            });
        }

        step(() -> {
            if (dialog != null) {
                dialog.close();
            }
            context.close();
            Platform.exit();
        });
    }

    private void select(String contains) {
        @SuppressWarnings("unchecked")
        ComboBox<Object> box = (ComboBox<Object>) typeBox();
        for (Object item : box.getItems()) {
            if (String.valueOf(item).contains(contains)) {
                box.setValue(item);
                return;
            }
        }
        System.out.println("下拉里没有 " + contains);
    }

    private ComboBox<?> typeBox() {
        // 第二个下拉就是类型（第一个是连接名的输入框，不是下拉）
        for (Node n : dialog.getScene().getRoot().lookupAll(".combo-box")) {
            if (n instanceof ComboBox<?> c && !c.getItems().isEmpty()
                    && String.valueOf(c.getItems().get(0)).contains("MySQL")) {
                return c;
            }
        }
        throw new IllegalStateException("找不到类型下拉");
    }

    /** 「库」那一格的标签：找到装着输入框的那一行，取行首的 Label。 */
    private String databaseLabel() {
        for (Node n : dialog.getScene().getRoot().lookupAll(".form-label")) {
            if (n instanceof Label l && (l.getText().contains("库") || l.getText().contains("模式")
                    || l.getText().contains("服务名") || l.getText().contains("编号"))) {
                if (!l.getText().equals("库文件")) {
                    return l.getText();
                }
            }
        }
        return "（没找到）";
    }

    private String hintText() {
        for (Node n : dialog.getScene().getRoot().lookupAll(".hint")) {
            if (n instanceof Label l && (l.getText().startsWith("经 JDBC")
                    || l.getText().startsWith("默认按服务名")
                    || l.getText().startsWith("达梦") || l.getText().startsWith("键值库"))) {
                return l.getText();
            }
        }
        return "（没找到）";
    }

    private static String safeName(String s) {
        return switch (s) {
            case "达梦" -> "dm";
            case "Oracle" -> "oracle";
            default -> "redis";
        };
    }

    private static Stage findDialog() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null
                    && s.getScene().getRoot().lookup(".dialog-title") != null) {
                return s;
            }
        }
        throw new IllegalStateException("没找到对话框");
    }

    private void step(Runnable action) {
        at += 1.2;
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
        launch(NewTypesPreview.class, args);
        System.exit(0);
    }
}
