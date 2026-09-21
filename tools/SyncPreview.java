import com.plainly.app.AppContext;
import com.plainly.app.view.StructureSyncDialog;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 把结构同步对话框挂起来看效果。
 *
 * <p>它通过真实控件驱动（选下拉、点按钮），走的路径和用户操作完全一致，
 * 所以截图里看到的就是用户会看到的。
 *
 * <p>用法：
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SyncPreview.java</pre>
 */
public class SyncPreview extends Application {

    public static void main(String[] args) {
        launch(SyncPreview.class, args);
    }

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        // 确保两条演示连接在注册表里
        register(context, "demo-test", "shop-test（源）", System.getProperty("plainly.home", ".") + "/demo/plainly-demo-test");
        register(context, "demo-prod", "shop-prod（目标）", System.getProperty("plainly.home", ".") + "/demo/plainly-demo");

        StructureSyncDialog dialog = new StructureSyncDialog(context);
        dialog.show(null);

        Window w = Stage.getWindows().stream()
                .filter(x -> x instanceof Stage s && "结构同步".equals(s.getTitle()))
                .findFirst().orElseThrow();
        Node root = w.getScene().getRoot();

        // 依次：选源连接 → 选目标连接 → 等 schema 载入 → 点比对
        PauseTransition step1 = new PauseTransition(Duration.seconds(1.2));
        step1.setOnFinished(e -> {
            // 用循环收集而不是 stream().map()：通配符捕获会让 toList() 推不出 List<ComboBox<?>>
            List<ComboBox<?>> combos = new ArrayList<>();
            for (Node n : root.lookupAll(".combo-box")) {
                if (n instanceof ComboBox<?> cb) {
                    combos.add(cb);
                }
            }
            System.out.println("找到 " + combos.size() + " 个下拉框");
            selectByText(combos.get(0), "shop-test（源）");
            selectByText(combos.get(2), "shop-prod（目标）");
            System.out.println("已选定源与目标");

            PauseTransition step2 = new PauseTransition(Duration.seconds(2.0));
            step2.setOnFinished(e2 -> {
                click(root, "开始比对");
                System.out.println("已触发比对");
            });
            step2.play();
        });
        step1.play();
    }

    private static void register(AppContext context, String id, String name, String path) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName(name)
                .setType(DbType.H2)
                .setFilePath(path)
                .setUser("sa")
                .setPassword("")
                .setSavePassword(false);
        cfg.setId(id);
        context.registry().save(cfg);
    }

    @SuppressWarnings("unchecked")
    private static void selectByText(ComboBox<?> combo, String text) {
        for (Object item : combo.getItems()) {
            if (item instanceof ConnectionConfig c && text.equals(c.name())) {
                ((ComboBox<Object>) combo).setValue(item);
                return;
            }
        }
        System.out.println("下拉框里没找到：" + text);
    }

    private static void click(Node root, String text) {
        for (Node node : root.lookupAll(".tool-button")) {
            if (node instanceof Button b && text.equals(b.getText())) {
                b.fire();
                return;
            }
        }
        System.out.println("没找到按钮：" + text);
    }
}
