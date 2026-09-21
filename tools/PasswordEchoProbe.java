import com.plainly.app.AppContext;
import com.plainly.app.view.ConnectionDialog;
import com.plainly.driver.ConnectionConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 「右键编辑连接，密码框是空的」——改完之后回显了没有。
 *
 * <h2>原来为什么是空的</h2>
 * 注册表交出来的 {@code ConnectionConfig} 口令字段<b>恒为 null</b>，那是有意的设计：
 * 口令不跟着配置对象在界面各处流转，只在建连接那一刻才解密。
 * 但编辑对话框是唯一一个「用户就是来看和改口令的」地方，空着的框说不清是
 * 「没存过口令」还是「存了但不给你看」，而且会诱使人重输一遍。
 *
 * <p><b>本探针只读</b>：打开对话框看一眼就关掉，全程不点保存，不改任何配置。
 * 清空口令那条路（空串 = 清掉）由 {@code ConnectionGroupTest} 在临时库上验，
 * 不拿真配置去试。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/PasswordEchoProbe.java [输出目录]</pre>
 */
public class PasswordEchoProbe extends Application {

    private static String out = ".";
    private static int failures;

    private Stage main;
    private AppContext context;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(PasswordEchoProbe.class, args);
        System.exit(failures == 0 ? 0 : 1);
    }

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setScene(new Scene(new StackPane(), 300, 160));
        stage.setTitle("密码回显探针");
        stage.show();

        context = new AppContext();
        ConnectionConfig target = context.registry().listAll().stream()
                .filter(ConnectionConfig::savePassword)
                .filter(c -> !c.type().isFileBased())
                .findFirst().orElse(null);
        if (target == null) {
            System.out.println("没有勾了「保存密码」的网络连接可测");
            Platform.exit();
            return;
        }
        System.out.println("被编辑的连接：" + target.name());
        System.out.println("注册表交出来的口令字段 = " + target.password()
                + "   ← 恒为 null，这是有意的");

        // showAndWait 会挡住，所以先把检查排上再开对话框
        at(1.4, this::inspect);
        at(0.5, () -> new ConnectionDialog(context, target).showAndWait(main));
        at(2.6, () -> {
            System.out.println();
            System.out.println(failures == 0
                    ? "通过：编辑既有连接时，密码框回显了已保存的口令"
                    : failures + " 项失败");
            context.close();
            Platform.exit();
        });
    }

    private void inspect() {
        Window dialog = null;
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w != main) {
                dialog = w;
            }
        }
        if (dialog == null) {
            check("找到编辑对话框", false);
            return;
        }
        PasswordField field = null;
        for (javafx.scene.Node node : dialog.getScene().getRoot().lookupAll(".password-field")) {
            if (node instanceof PasswordField pf) {
                // 表单里有两个密码框：连接口令在前，SSH 口令在后。取第一个
                field = pf;
                break;
            }
        }
        if (field == null) {
            check("找到密码框", false);
            return;
        }
        int len = field.getText() == null ? 0 : field.getText().length();
        System.out.println("密码框里的字符数 = " + len
                + "（只报长度，不打印口令本身）");
        check("密码框已回显已保存的口令", len > 0);

        write(dialog.getScene().snapshot(null), "password-echo");
        dialog.hide();
    }

    private static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "[对] " : "[错] ") + what);
        if (!ok) {
            failures++;
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
            System.out.println("→ " + f.getAbsolutePath());
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
