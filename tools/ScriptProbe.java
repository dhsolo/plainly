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
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fxmisc.richtext.CodeArea;

/**
 * 执行范围的探针。
 *
 * <p>盯的是三件事：整页多条能不能逐条跑完、选中一段是不是只跑那一段、
 * 字符串里的分号会不会被当成分隔符。
 * 状态文字从 {@code setStatusSink} 拿——不必为了看结果给界面开新的口子。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ScriptProbe.java</pre>
 */
public class ScriptProbe extends Application {

    private static final String TWO_ROWS = "SELECT 1 AS n UNION ALL SELECT 2";
    private static final String THREE_ROWS = "SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3";

    private CodeArea area;
    private SqlEditorPane pane;
    private volatile String status = "";
    private final List<String> failures = new ArrayList<>();
    private static int exitCode = 0;

    public static void main(String[] args) {
        launch(ScriptProbe.class, args);
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

        pane = new SqlEditorPane(context, session, "PUBLIC");
        pane.setStatusSink(s -> status = s);
        area = pane.editor();

        stage.setScene(new Scene(pane, 900, 620));
        stage.setTitle("执行探针");
        stage.show();

        // 一、整页两条：应当逐条跑完，展示最后一条的结果集
        at(1.5, () -> run(TWO_ROWS + ";\n" + THREE_ROWS + ";\n"));
        at(3.0, () -> {
            expect("整页多条", status.contains("共 2 条语句") && status.contains("返回 3 行"));
        });

        // 二、选中第一条：只跑选中的那一段，不带整页的前缀
        at(3.5, () -> {
            load(TWO_ROWS + ";\n" + THREE_ROWS + ";\n");
            area.selectRange(0, TWO_ROWS.length());
            fire(KeyCode.F5);
        });
        at(5.0, () -> {
            expect("只跑选中片段", status.contains("返回 2 行") && !status.contains("共 "));
        });

        // 三、字符串里的分号不是分隔符
        at(5.5, () -> run("SELECT 'a;b' AS s"));
        at(7.0, () -> {
            expect("字符串里的分号", status.contains("返回 1 行") && !status.contains("共 "));
        });

        // 四、第二条报错：要说清楚停在哪一条，而不是整页静悄悄失败
        at(7.5, () -> run("SELECT 1;\nSELECT * FROM no_such_table_here;\n"));
        at(9.5, () -> {
            expect("中途报错", status.contains("执行失败"));
        });

        at(10.5, () -> {
            System.out.println();
            if (failures.isEmpty()) {
                System.out.println("通过：执行范围符合预期");
            } else {
                failures.forEach(f -> System.out.println("失败：" + f));
                exitCode = 1;
            }
            Platform.exit();
        });
    }

    private void run(String sql) {
        load(sql);
        fire(KeyCode.F5);
    }

    /** 只放文本、不执行——选中之后再按 F5 的用例要这个。 */
    private void load(String sql) {
        area.replaceText(sql);
        area.moveTo(0);
        area.deselect();
        status = "";
    }

    private void fire(KeyCode code) {
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
    }

    private void expect(String name, boolean ok) {
        System.out.println((ok ? "  ok  " : "  ??  ") + name + "  →  " + status);
        if (!ok) {
            failures.add(name + "，实际状态：" + status);
        }
    }

    private void at(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> action.run());
        p.play();
    }
}
