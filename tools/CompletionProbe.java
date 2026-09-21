import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

/**
 * 补全的按键探针。
 *
 * <p>直接向 CodeArea 派发 KeyEvent：这会走完整的 filter → handler → InputMap 链，
 * 只是绕开操作系统层面的焦点（Robot 的按键会打到当前 OS 焦点窗口，在无人值守下不可靠）。
 *
 * <p>关键观测点：
 * <ul>
 *   <li>补全弹窗是不是真的开了——{@code Window.getWindows()} 里会多出一个 Popup；</li>
 *   <li>WellBehavedFX 的 InputMap 到底装没装到 CodeArea 上；</li>
 *   <li>TAB 之后文本变没变。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/CompletionProbe.java</pre>
 */
public class CompletionProbe extends Application {

    private CodeArea area;
    private SqlEditorPane pane;
    private Scene scene;
    private static int exitCode = 0;

    public static void main(String[] args) {
        launch(CompletionProbe.class, args);
        System.exit(exitCode);
    }

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-demo");
        DbConnection conn = JdbcConnections.open(cfg);
        DbSession session = new DbSession(cfg, conn);

        SqlEditorPane pane = new SqlEditorPane(context, session, "PUBLIC");
        this.pane = pane;
        area = pane.editor();

        scene = new Scene(pane, 1000, 700);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setTitle("补全探针");
        stage.setScene(scene);
        stage.show();

        // 对照实验：同一个 TAB，在弹窗打开前后各发一次
        step(2.0, () -> {
            area.replaceText("sele");
            area.moveTo(4);
            System.out.println();
            System.out.println(">>> 阶段一：弹窗未开，派发 TAB");
            fire(KeyCode.TAB, false);
            System.out.println(">>> 阶段一结束，文本 = [" + area.getText() + "]");
        });

        step(3.5, () -> {
            System.out.println();
            System.out.println(">>> 阶段二：派发 Ctrl+Space 打开弹窗");
            area.replaceText("sele");
            area.moveTo(4);
            fire(KeyCode.SPACE, true);
        });

        step(5.0, () -> {
            System.out.println();
            System.out.println(">>> 阶段三：弹窗已开(" + pane.completionVisible()
                    + ", 选中=" + (pane.completionSelection() == null
                    ? "null" : pane.completionSelection().name()) + ")，派发 TAB");
            fire(KeyCode.TAB, false);
            System.out.println(">>> 阶段三结束");
        });

        step(6.5, () -> {
            System.out.println();
            System.out.println(">>> 阶段四：派发 ENTER");
            fire(KeyCode.ENTER, false);
        });

        step(8.0, () -> {
            report("全部结束");
            String text = area.getText().trim();
            System.out.println();
            boolean ok = text.equalsIgnoreCase("SELECT");
            System.out.println(ok ? "通过：补全生效"
                    : "失败：补全未生效，文本 = [" + text + "]");
            exitCode = ok ? 0 : 1;
            Platform.exit();
        });
    }

    private void fire(KeyCode code, boolean ctrl) {
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, ctrl, false, false);
        Event.fireEvent(area, e);
    }

    private void report(String tag) {
        System.out.println();
        System.out.println("== " + tag);
        System.out.println("   文本     = [" + area.getText().replace("\n", "\\n") + "]");
        System.out.println("   光标     = " + area.getCaretPosition());
        System.out.println("   弹窗可见 = " + pane.completionVisible());
        System.out.println("   候选数量 = " + pane.completionItems().size());
        System.out.println("   候选内容 = " + pane.completionItems().stream()
                .map(SqlEditorPane.Suggestion::name).limit(8).toList());
        System.out.println("   当前选中 = " + (pane.completionSelection() == null
                ? "null" : pane.completionSelection().name()));
        long popups = Window.getWindows().stream()
                .filter(w -> w.getClass().getSimpleName().contains("Popup")).count();
        System.out.println("   弹窗数量 = " + popups + "（窗口总数 "
                + Window.getWindows().size() + "）");
        for (Window w : Window.getWindows()) {
            System.out.println("      " + w.getClass().getSimpleName()
                    + " showing=" + w.isShowing());
        }
    }

    /** 打印 CodeArea 上挂着的属性键，用来确认 WellBehavedFX 的 InputMap 装上没有。 */
    private void dumpInputMap(String tag) {
        // CodeArea 继承自 Region 而不是 Control，没有 getSkin()；
        // 它的按键绑定挂在 properties 里，看属性键就知道装没装
        System.out.println("== " + tag);
        System.out.println("   CodeArea 属性键：");
        area.getProperties().keySet().forEach(k ->
                System.out.println("      " + k));
    }

    private void step(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> action.run());
        p.play();
    }
}
