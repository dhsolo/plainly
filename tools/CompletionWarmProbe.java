import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

/**
 * 补全改成「只读缓存」之后，还给不给得出候选。
 *
 * <h2>改了什么，风险在哪</h2>
 * 补全原来直接调 {@code session.tableNamesForCompletion} / {@code columnsForCompletion}，
 * 未命中缓存时它们会真的去查库——等于把一次网络往返放进了击键路径上。
 * 现在改成只读缓存，缓存由后台预热。
 *
 * <p>这么改<b>换来的风险是「静默失效」</b>：万一预热没跑、或者跑得不是同一个库，
 * 补全会安安静静地一个候选都不给——不报错，只是「这个功能好像没用」。
 * 那比原来的卡顿糟糕得多，所以必须验。
 *
 * <h2>验三件事</h2>
 * <ol>
 *   <li>开页之后<b>不做任何操作</b>，预热能把表清单捂热；</li>
 *   <li>Ctrl+空格 给得出表名候选；</li>
 *   <li>敲 {@code 表名.} 之后，那张表的结构会被后台补上，
 *       下一次击键就有字段候选（第一次没有是预期的）。</li>
 * </ol>
 *
 * <p>连的是 demo 目录里的 H2 演示库，只读。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/CompletionWarmProbe.java</pre>
 */
public class CompletionWarmProbe extends Application {

    private static int failures;

    private SqlEditorPane pane;
    private CodeArea area;
    private DbSession session;

    public static void main(String[] args) {
        launch(CompletionWarmProbe.class, args);
        System.exit(failures == 0 ? 0 : 1);
    }

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("demo").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("completion-warm-probe");
        DbConnection conn = Connections.open(cfg);
        session = new DbSession(cfg, conn);

        pane = new SqlEditorPane(context, session, "PUBLIC");
        area = pane.editor();
        stage.setScene(new Scene(pane, 900, 600));
        stage.setTitle("补全预热探针");
        stage.show();

        // 一、开页之后什么都不做，看预热有没有把表清单捂热
        at(1.5, () -> {
            var cached = session.cachedTables("PUBLIC");
            check("开页后表清单已被后台捂热", cached != null && !cached.isEmpty());
            System.out.println("    缓存里的表：" + (cached == null ? "(无)"
                    : cached.stream().map(t -> t.name()).toList()));
        });

        /*
         * 二、限定符补全放在最前面做，而且全程只按 Ctrl+空格、不发 ESC。
         *
         * 上一版在每段之间发 ESC 想把弹窗关掉，结果之后的 Ctrl+空格 再也没能
         * 触发 showCompletion——而 completionItems() 还留着上一段的内容，
         * 于是看起来像「限定符分支不工作」。文本、光标、SqlContext 解析
         * 全都是对的，错的是探针驱动界面的方式。
         *
         * 这一版靠 showCompletion 自己的行为来排队：候选为空时它会把弹窗隐藏，
         * 所以下一次 Ctrl+空格 又是从「弹窗没开」的状态开始，不需要 ESC。
         */
        at(2.2, () -> {
            area.replaceText("select * from ORDERS where ORDERS.");
            area.moveTo(area.getLength());
            fire(KeyCode.SPACE, true);
            System.out.println("    第一次敲「ORDERS.」，候选 "
                    + names(pane.completionItems())
                    + " —— 结构还没读，这时没有字段是预期的");
        });
        at(3.6, () -> check("敲「ORDERS.」触发了后台去读结构",
                session.cachedStructure("PUBLIC", "ORDERS") != null));

        // 三、结构热了之后，同样一下 Ctrl+空格 应该给出字段
        at(4.2, () -> {
            area.moveTo(area.getLength());
            fire(KeyCode.SPACE, true);
        });
        at(4.8, () -> {
            var items = pane.completionItems();
            System.out.println("    预热之后再敲一次，候选 " + names(items));
            // 断言要具体：非空不够，得真的是字段。
            // 上一版就因为拿到一个表名 [ORDERS(TABLE)] 而误判为通过
            check("字段补全在预热之后给得出字段候选", items.stream()
                    .anyMatch(i -> i.kind() == SqlEditorPane.Kind.COLUMN));
        });

        // 四、表名补全（换一段文本，此时弹窗是开着的，用 refreshCompletion 那条路）
        at(5.4, () -> {
            area.replaceText("select * from ord");
            area.moveTo(area.getLength());
            fire(KeyCode.SPACE, true);
        });
        at(6.0, () -> {
            var items = pane.completionItems();
            System.out.println("    表名候选 " + names(items));
            check("表名补全给得出候选", items.stream()
                    .anyMatch(i -> i.name().equalsIgnoreCase("ORDERS")
                            && i.kind() == SqlEditorPane.Kind.TABLE));
        });

        at(6.8, () -> {
            System.out.println();
            System.out.println(failures == 0
                    ? "全部通过：只读缓存 + 后台预热，补全照常给候选"
                    : failures + " 项失败：补全静默失效了，这比原来的卡顿更糟");
            context.close();
            Platform.exit();
        });
    }

    private void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "[对] " : "[错] ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static String names(java.util.List<SqlEditorPane.Suggestion> items) {
        return items.size() + " 项 " + items.stream()
                .map(i -> i.name() + "(" + i.kind() + ")").limit(8).toList();
    }

    /** 往编辑器上派发一次按键，走的是真实的事件通道。 */
    private void fire(KeyCode code, boolean control) {
        area.requestFocus();
        area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, false, false));
    }

    private void at(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }
}
