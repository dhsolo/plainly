import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;

/**
 * SQL 编辑器的 Ctrl+D：把光标所在行复制到下一行。
 *
 * <h2>为什么要真按一遍</h2>
 * 这个键位是用 {@code Nodes.addInputMap} 挂在 CodeArea 上的，而 RichTextFX 自己
 * 也在同一个通道上注册了一堆键。「有没有被别人吃掉」「和补全弹窗的那几个
 * consumeWhen 会不会打架」，光读代码说不准——得真发一个 Ctrl+D 下去看文本变没变。
 *
 * <p>光标落点同样要考：连按几下应该是连着往下复制几行。如果光标留在原地，
 * 第二下复制的还是同一行，而这件事只有连按两次才暴露得出来。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/DuplicateLineProbe.java
 * </pre>
 */
public class DuplicateLineProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final String LF = String.valueOf((char) 10);
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    @Override
    public void start(Stage stage) {
        /*
         * 照 CompletionProbe 的路子搭：真的 AppContext，连 demo 目录下那个 H2。
         *
         * 传 null 进去是不行的——SqlEditorPane 的构造函数里就会去捂补全缓存
         * （warmMetadata）和读代码片段，两处都要 context。
         */
        AppContext context = new AppContext();
        ConnectionConfig config = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        config.setId("probe-duplicate-line");
        DbConnection conn = JdbcConnections.open(config);
        DbSession session = new DbSession(config, conn);

        SqlEditorPane pane = new SqlEditorPane(context, session, "PUBLIC");
        Scene scene = new Scene(pane, 700, 420);
        scene.getStylesheets().add(
                SqlEditorPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("DuplicateLineProbe");
        stage.show();

        delay(800, () -> {
            try {
                run(pane);
            } catch (RuntimeException e) {
                fail++;
                log("探针自己出错了：" + e);
            }
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            Platform.exit();
        });
    }

    private void run(SqlEditorPane pane) {
        CodeArea editor = pane.editor();

        // 一、光标在中间那一行，没有选区
        setText(editor, "SELECT a" + LF + "FROM t" + LF + "WHERE x = 1");
        editor.moveTo(1, 2);            // FROM t 这一行，第 2 列
        ctrlD(editor);
        check("复制光标那一行到下一行",
                text(editor).equals("SELECT a" + LF + "FROM t" + LF + "FROM t" + LF
                        + "WHERE x = 1"));
        check("光标跟到复制出来的那一行（实际第 " + editor.getCurrentParagraph() + " 行）",
                editor.getCurrentParagraph() == 2);
        check("列没变（实际第 " + editor.getCaretColumn() + " 列）",
                editor.getCaretColumn() == 2);

        // 二、连按第二下：应该又多一行，而不是原地打转
        ctrlD(editor);
        check("连按两下就是连着复制两行",
                text(editor).equals("SELECT a" + LF + "FROM t" + LF + "FROM t" + LF
                        + "FROM t" + LF + "WHERE x = 1"));

        // 三、光标在最后一行——那里没有「下一行」可用来插入
        setText(editor, "SELECT a" + LF + "FROM t");
        editor.moveTo(1, 0);
        ctrlD(editor);
        check("最后一行也能复制（末尾没有换行符可借）",
                text(editor).equals("SELECT a" + LF + "FROM t" + LF + "FROM t"));

        // 四、圈中两行：整段复制这两行，不是只复制光标那一行
        setText(editor, "a" + LF + "b" + LF + "c");
        editor.selectRange(0, 3);       // 盖住 a 和 b
        ctrlD(editor);
        check("圈中两行就整段复制两行（实际 " + show(text(editor)) + "）",
                text(editor).equals("a" + LF + "b" + LF + "a" + LF + "b" + LF + "c"));

        // 五、整行往下刷出来的选区，末尾正好落在下一行行首——那一行一个字符都没选中
        setText(editor, "a" + LF + "b" + LF + "c");
        editor.selectRange(0, 2);       // "a" 加上它后面那个换行符
        ctrlD(editor);
        check("选区末尾落在下一行行首时，那一行不算（实际 " + show(text(editor)) + "）",
                text(editor).equals("a" + LF + "a" + LF + "b" + LF + "c"));

        // 六、空行也能复制
        setText(editor, "a" + LF + LF + "c");
        editor.moveTo(1, 0);
        ctrlD(editor);
        check("空行复制出来还是空行（实际 " + show(text(editor)) + "）",
                text(editor).equals("a" + LF + LF + LF + "c"));

        // 七、一次 insertText，所以撤销也是一步
        setText(editor, "only");
        editor.moveTo(0, 0);
        ctrlD(editor);
        editor.undo();
        check("撤销一步就回到原样（实际 " + show(text(editor)) + "）",
                text(editor).equals("only"));
    }

    // ------------------------------------------------------------------ 动作

    /** 发一个真的 Ctrl+D 下去，而不是直接调那个方法——要考的正是「这个键到不到得了」。 */
    private static void ctrlD(CodeArea editor) {
        editor.requestFocus();
        Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D,
                false, true, false, false));
    }

    /**
     * 铺一段初始文本。
     *
     * <p>用 replaceText 而不是 appendText：每一项都要从一个确定的起点开始，
     * 上一项留下的内容会让后面的断言全部错位。顺手把撤销历史也清掉，
     * 否则第七项的 undo 可能撤到上一项去。
     */
    private static void setText(CodeArea editor, String text) {
        editor.replaceText(text);
        editor.getUndoManager().forgetHistory();
    }

    private static String text(CodeArea editor) {
        return editor.getText();
    }

    /** 把换行显示成「⏎」，断言失败时那一行日志才看得懂。 */
    private static String show(String text) {
        return text.replace(LF, "⏎");
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
        launch(DuplicateLineProbe.class, args);
    }
}
