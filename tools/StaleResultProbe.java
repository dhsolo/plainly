import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.DataGridPane;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 查询页下方显示的，永远得是<b>刚跑的这一条</b>的结果。
 *
 * <h2>要盯的是「上一条的结果还留在屏幕上」</h2>
 * 这类问题的共同形状是：新的一次执行没有结果集可显示（报错了、或者跑的是 UPDATE），
 * 于是代码<b>什么都没对网格做</b>——网格里还摆着上一条 SELECT 的那几行。
 * 用户读到的是「我刚执行的语句返回了这些行」，而那是几分钟前另一条语句的结果。
 *
 * <p>这件事读代码不容易发现：每一条路径单看都说得通（报错就显示报错面板、
 * 影响行数就写在标题上），漏的是「顺手把旧结果清掉」这一步。
 * 所以这里按真实顺序跑几轮，每轮之后去问网格里到底还剩什么。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/StaleResultProbe.java
 * </pre>
 */
public class StaleResultProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final String LF = String.valueOf((char) 10);
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    private SqlEditorPane pane;
    private CodeArea editor;
    private DataGridPane grid;

    /** 待跑的步骤。每一步之间要等一次真正的异步执行，所以排成队列一步一步来。 */
    private final Deque<Runnable> steps = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        ConnectionConfig config = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        config.setId("probe-stale-result");
        DbConnection conn = JdbcConnections.open(config);
        DbSession session = new DbSession(config, conn);

        pane = new SqlEditorPane(context, session, "PUBLIC");
        editor = pane.editor();

        Scene scene = new Scene(pane, 900, 560);
        scene.getStylesheets().add(
                SqlEditorPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("StaleResultProbe");
        stage.show();

        grid = (DataGridPane) pane.lookup(".data-grid-pane");

        plan();
        delay(900, this::next);
    }

    // ------------------------------------------------------------------ 步骤

    private void plan() {
        // 一、先跑一条正常的 SELECT，让网格里有东西
        steps.add(() -> run("SELECT 1 AS a, 2 AS b"));
        steps.add(() -> {
            check("正常 SELECT 之后网格里有结果", rows() == 1);
            check("正常 SELECT 之后网格是可见的", grid.isVisible());
        });

        // 二、紧接着跑一条语法错误的：红面板该盖住网格
        steps.add(() -> run("SELECT * FROM 这张表不存在"));
        steps.add(() -> {
            check("报错之后红面板出现", errorPanelVisible());
            check("报错之后网格被盖住", !grid.isVisible());
            check("报错之后网格里不该还留着上一条的结果（实际 " + describe() + "）",
                    grid.result() == null);
        });

        // 三、错误之后再跑一条正常的：网格要回来
        steps.add(() -> run("SELECT 7 AS x"));
        steps.add(() -> {
            check("再执行成功后红面板收起", !errorPanelVisible());
            check("再执行成功后网格回来了", grid.isVisible());
            check("网格里是新结果", rows() == 1);
        });

        // 四、跑一条没有结果集的语句（DDL/DML）：标题会写「影响 N 行」，
        //     但下面那片网格如果还摆着上一条 SELECT 的行，用户会当成这条的结果
        steps.add(() -> run("CREATE TABLE IF NOT EXISTS probe_stale (id INT)"));
        steps.add(() -> {
            log("标题：" + resultLabelText());
            check("没有结果集的语句执行后，网格里不该还留着上一条 SELECT 的结果"
                            + "（实际 " + describe() + "）",
                    grid.result() == null);
        });

        // 五、多条语句里后面那条报错：同样不能留着前面成功那条的结果
        steps.add(() -> run("SELECT 1 AS a;" + LF + "SELECT * FROM 还是不存在;"));
        steps.add(() -> {
            check("多语句中途报错后网格被盖住", !grid.isVisible());
            check("多语句中途报错后网格里不该留着结果（实际 " + describe() + "）",
                    grid.result() == null);
        });

        steps.add(() -> {
            cleanup();
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            Platform.exit();
        });
    }

    private void cleanup() {
        try {
            editor.replaceText("DROP TABLE IF EXISTS probe_stale");
            f5();
        } catch (RuntimeException ignored) {
            // 清理失败不该把结论盖掉
        }
    }

    /** 跑完一步等一会儿再跑下一步：执行是异步的，结果回到界面线程还要一跳。 */
    private void next() {
        Runnable step = steps.poll();
        if (step == null) {
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            fail++;
            log("探针自己出错了：" + e);
        }
        delay(700, this::next);
    }

    private void run(String sql) {
        editor.replaceText(sql);
        editor.moveTo(editor.getLength());
        f5();
    }

    /** 发一个真的 F5，走的就是用户按下去那条路。 */
    private void f5() {
        editor.requestFocus();
        Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5,
                false, false, false, false));
    }

    // ------------------------------------------------------------------ 观察

    private int rows() {
        return grid.result() == null ? -1 : grid.result().rows().size();
    }

    private String describe() {
        return grid.result() == null ? "已清空" : "还剩 " + rows() + " 行";
    }

    private boolean errorPanelVisible() {
        Node panel = pane.lookup(".sql-error-panel");
        return panel != null && panel.isVisible();
    }

    private String resultLabelText() {
        for (Node node : pane.lookupAll(".hint")) {
            if (node instanceof Label label && label.getText() != null
                    && (label.getText().contains("影响") || label.getText().contains("返回")
                            || label.getText().contains("失败"))) {
                return label.getText();
            }
        }
        return "(没找到)";
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        log((ok ? "[通过] " : "[失败] ") + what);
    }

    private static void log(String line) {
        LOG.append(line).append(NL);
    }

    private static void delay(int millis, Runnable action) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> action.run());
        wait.play();
    }

    public static void main(String[] args) {
        launch(StaleResultProbe.class, args);
    }
}
