import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.TableDesignerPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * 把表结构设计器单独挂起来看效果，供开发期视觉核对用。
 *
 * <p>它通过真实按钮触发改动（{@code button.fire()}），而不是去动内部状态——
 * 走的路径和用户点击完全一致，所以截图里看到的就是用户会看到的。
 *
 * <p>用法：
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/DesignerPreview.java</pre>
 */
public class DesignerPreview extends Application {

    public static void main(String[] args) {
        // 单文件源码模式下类不在系统类加载器里，必须用带 Class 参数的重载
        launch(DesignerPreview.class, args);
    }

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("demo")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");
        cfg.setId("preview-demo");

        DbConnection conn = JdbcConnections.open(cfg);
        DbSession session = new DbSession(cfg, conn);

        TableDesignerPane designer = new TableDesignerPane(context, session, "PUBLIC", "ORDERS");
        designer.setStatusSink(msg -> System.out.println("[status] " + msg));

        Scene scene = new Scene(designer, 1180, 780);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setTitle("表结构设计器");
        stage.setScene(scene);
        stage.show();

        // 结构是异步加载的，等它到位后再通过真实按钮制造一次改动，
        // 这样变更预览区就有内容可看
        PauseTransition wait = new PauseTransition(Duration.seconds(2.5));
        wait.setOnFinished(e -> {
            click(designer, "添加字段");
            System.out.println("已触发「添加字段」");
        });
        wait.play();
    }

    /** 按文字找到按钮并触发，等同于用户点击。 */
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
