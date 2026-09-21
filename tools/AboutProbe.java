import com.plainly.app.AppContext;
import com.plainly.app.view.AboutDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 「关于」这一页真的打得开，而且里面的东西是真的。
 *
 * <h2>要验的是「别编」</h2>
 * 这一页存在的意义是出问题时能问清楚跑的是哪一版。所以最该验的不是布局，
 * 而是<b>版本号的来源</b>：从 jar 跑时要读到 manifest 里的号，
 * 从源码跑时要如实说「开发版」——而不是编一个像模像样的号。
 * 一个假版本号比没有版本号更糟：用户会拿它来对报告，而它对不上任何一次发布。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/AboutProbe.java
 * </pre>
 */
public class AboutProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();

        LOG.append("version() 返回：").append(AboutDialog.version()).append(NL);
        check("版本号不是空的", !AboutDialog.version().isBlank(), "空的");
        check("从 jar 跑时读到了真实版本号（不是「开发版」）",
                !AboutDialog.version().startsWith("开发版"),
                "读到的是：" + AboutDialog.version());

        // showAndWait 会阻塞，所以先把剧本排上
        delay(600, () -> {
            inspect();
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            context.close();
            Platform.exit();
        });
        new AboutDialog(context).show(stage);
    }

    private void inspect() {
        Stage about = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && "关于 Plainly".equals(s.getTitle())) {
                about = s;
            }
        }
        check("窗口打开了", about != null, "没找到「关于 Plainly」窗口");
        if (about == null) {
            return;
        }
        Parent root = about.getScene().getRoot();

        TextArea details = find(root, TextArea.class);
        check("有运行环境那一块", details != null, "没找到文本区");
        if (details != null) {
            String text = details.getText();
            LOG.append("--- 运行环境 ---").append(NL).append(text);
            for (String field : List.of("版本", "Java", "操作系统", "本机配置库", "许可证")) {
                check("列出了「" + field + "」", text.contains(field), text);
            }
            check("配置库路径指向 Plainly 而不是老名字",
                    text.contains("Plainly") && !text.contains("Strata"), text);
        }

        Button update = findButton(root, "检查更新");
        check("「检查更新」按钮存在但是禁用的（还没有 Release 可查）",
                update != null && update.isDisabled(),
                update == null ? "没找到按钮" : "它没被禁用");

        about.close();
    }

    // ------------------------------------------------------------------ 辅助

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            LOG.append("  ").append(what).append(NL);
        } else {
            fail++;
            LOG.append("× ").append(what).append("   实际：").append(detail).append(NL);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Parent root, Class<T> type) {
        for (Node n : all(root)) {
            if (type.isInstance(n)) {
                return (T) n;
            }
        }
        return null;
    }

    private static Button findButton(Parent root, String text) {
        for (Node n : all(root)) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private static List<Node> all(Parent root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            out.add(child);
            if (child instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    public static void main(String[] args) {
        launch(AboutProbe.class, args);
    }
}
