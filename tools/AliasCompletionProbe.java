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
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 补全里的别名：选中表就起一个，之后补字段带着它。
 *
 * <h2>为什么要真走一遍弹窗</h2>
 * 别名这件事横跨三处：{@code SqlAliases} 拼名字（那有单元测试）、
 * {@code SqlContext.expectsAlias} 判位置、{@code applySelectedSuggestion} 真插进去。
 * 三处各自对，串起来仍然可能错——最常见的是<b>位置判错</b>：
 * {@code INSERT INTO t} 后面也补一个别名，那是语法错误，而且要等执行才报。
 *
 * <p>字段那半边还有一个只有跑起来才看得见的依赖：别名要先被
 * {@code SqlScopes} 解析出来，字段候选才带得上它。所以这里的顺序是
 * 先选表（自动起别名）、再回 SELECT 那儿补字段，和用户真实的写法一致。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/AliasCompletionProbe.java
 * </pre>
 */
public class AliasCompletionProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    private SqlEditorPane pane;
    private CodeArea editor;

    private final Deque<Runnable> steps = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        ConnectionConfig config = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        config.setId("probe-alias-completion");
        DbConnection conn = JdbcConnections.open(config);
        DbSession session = new DbSession(config, conn);

        pane = new SqlEditorPane(context, session, "PUBLIC");
        editor = pane.editor();

        Scene scene = new Scene(pane, 900, 560);
        scene.getStylesheets().add(
                SqlEditorPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("AliasCompletionProbe");
        stage.show();

        // 补全只读缓存，要等 warmMetadata 把表清单捂热
        delay(1500, () -> {
            log("这个库里的表：" + tableNames());
            plan();
            next();
        });
    }

    private void plan() {
        String table = firstTable();
        if (table == null) {
            fail++;
            log("demo 库里一张表都没有，后面没法考");
            steps.add(this::finish);
            return;
        }

        // 一、FROM 之后选一张表：该跟出一个别名
        steps.add(() -> type("SELECT * FROM " + table.substring(0, 2)));
        steps.add(() -> {
            pickExact(table);
            String text = editor.getText();
            check("FROM 之后选表会跟一个别名（实际 " + show(text) + "）",
                    text.startsWith("SELECT * FROM " + table + " ")
                            && text.length() > ("SELECT * FROM " + table + " ").length());
        });

        // 二、同一张表再 JOIN 一次：第二个别名不能和第一个撞
        steps.add(() -> {
            editor.appendText(" JOIN " + table.substring(0, 2));
            editor.moveTo(editor.getLength());
            ctrlSpace();
        });
        steps.add(() -> {
            pickExact(table);
            String text = editor.getText();
            List<String> aliases = aliasesIn(text, table);
            log("两次起出来的别名：" + aliases);
            check("自连接的两个别名不一样（实际 " + aliases + "）",
                    aliases.size() == 2 && !aliases.get(0).equalsIgnoreCase(aliases.get(1)));
        });

        // 三、INSERT INTO 之后不该起别名——那是语法错误
        steps.add(() -> type("INSERT INTO " + table.substring(0, 2)));
        steps.add(() -> {
            pickExact(table);
            check("INSERT INTO 之后不起别名（实际 " + show(editor.getText()) + "）",
                    editor.getText().equals("INSERT INTO " + table));
        });

        // 四、UPDATE 之后也不起：MySQL 允许、SQL Server 不允许，给了反而坑
        steps.add(() -> type("UPDATE " + table.substring(0, 2)));
        steps.add(() -> {
            pickExact(table);
            check("UPDATE 之后不起别名（实际 " + show(editor.getText()) + "）",
                    editor.getText().equals("UPDATE " + table));
        });

        // 五、起过别名之后回 SELECT 那儿补字段：候选要带着别名
        steps.add(() -> type("SELECT  FROM " + table.substring(0, 2)));
        steps.add(() -> pickExact(table));
        steps.add(() -> {
            // 光标回到 SELECT 后面那个空格处
            editor.moveTo("SELECT ".length());
            ctrlSpace();
        });
        /*
         * 唤两次，中间隔一步。
         *
         * 字段补全<b>只读缓存</b>——表结构没读过时它先把读取丢到后台、这一次不给候选
         * （见 SqlEditorPane.warmTable，那是刻意的：同步读会把一次网络往返放进击键路径）。
         * 所以第一次唤起是去捂热，第二次才拿得到字段。用户手上也是这样：
         * 敲字的间隙足够后台读完，不会察觉。
         */
        steps.add(() -> {
            editor.moveTo("SELECT ".length());
            ctrlSpace();
        });
        steps.add(() -> {
            String alias = aliasIn(editor.getText(), table);
            List<SqlEditorPane.Suggestion> columns = pane.completionItems().stream()
                    .filter(s -> s.kind() == SqlEditorPane.Kind.COLUMN)
                    .toList();
            log("别名 " + alias + "，全部字段候选："
                    + columns.stream().map(SqlEditorPane.Suggestion::name).toList());
            check("有字段候选", !columns.isEmpty());
            check("字段候选带着别名前缀（实际 "
                            + (columns.isEmpty() ? "(空)" : columns.get(0).name()) + "）",
                    !columns.isEmpty() && columns.stream()
                            .allMatch(s -> s.name().startsWith(alias + ".")));
            check("插进去的也是带前缀的那份",
                    !columns.isEmpty()
                            && columns.get(0).insertText().equals(columns.get(0).name()));
        });

        // 六、没起别名的表（用户自己删掉了，或者直接手敲的）：
        //     字段就该光秃秃地补，而且<b>不能重复</b>——
        //     SqlScopes 对一张表会登记两条（别名一条、表名一条），照单全收就会补出两份
        steps.add(() -> {
            editor.replaceText("SELECT  FROM " + table);
            editor.moveTo("SELECT ".length());
            ctrlSpace();
        });
        steps.add(() -> {
            editor.moveTo("SELECT ".length());
            ctrlSpace();
        });
        steps.add(() -> {
            List<String> names = pane.completionItems().stream()
                    .filter(s -> s.kind() == SqlEditorPane.Kind.COLUMN)
                    .map(SqlEditorPane.Suggestion::name)
                    .toList();
            log("没起别名时的字段候选：" + names);
            check("没起别名就不加前缀", names.stream().noneMatch(n -> n.contains(".")));
            check("字段没有重复（" + names.size() + " 条，去重后 "
                            + names.stream().distinct().count() + " 条）",
                    names.size() == names.stream().distinct().count());
        });

        /*
         * 七、起过别名的表，在<b>限定符</b>的位置上要补别名，不是补表名。
         *
         * 用户在 ON 后面照着表名敲（他心里想的就是那张表），原来补出来的是光秃秃的
         * 表名——既没有别名，而且一旦这张表起过别名，CUSTOMERS.id 这种写法在
         * MySQL、PostgreSQL、Oracle 上都直接报错。
         */
        steps.add(() -> type("SELECT * FROM " + table + " a JOIN " + table
                + " b ON " + table.substring(0, 3)));
        steps.add(() -> {
            List<SqlEditorPane.Suggestion> tables = pane.completionItems().stream()
                    .filter(s -> s.kind() == SqlEditorPane.Kind.TABLE)
                    .toList();
            List<String> names = tables.stream()
                    .map(SqlEditorPane.Suggestion::name).toList();
            log("ON 后面照表名敲，补出来的是：" + names);
            check("补的是别名而不是表名（实际 " + names + "）",
                    !names.isEmpty() && names.stream()
                            .noneMatch(n -> n.equalsIgnoreCase(table)));
            check("两个别名都给出来了（自连接）（实际 " + names + "）",
                    names.contains("a") && names.contains("b"));
            check("候选里没有重复",
                    names.size() == names.stream().distinct().count());
        });

        // 八、没起别名的表，表名本身就是合法限定符，照旧补表名
        steps.add(() -> type("SELECT * FROM " + table + " WHERE " + table.substring(0, 3)));
        steps.add(() -> {
            List<String> names = pane.completionItems().stream()
                    .filter(s -> s.kind() == SqlEditorPane.Kind.TABLE)
                    .map(SqlEditorPane.Suggestion::name)
                    .toList();
            log("没起别名时，限定符位置补出来的是：" + names);
            check("没起别名就补表名本身（实际 " + names + "）", names.contains(table));
        });

        steps.add(this::finish);
    }

    private void finish() {
        System.out.print(LOG);
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        Platform.exit();
    }

    // ------------------------------------------------------------------ 动作

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
        delay(450, this::next);
    }

    /** 铺一段文本，光标落到末尾，然后唤出补全。 */
    private void type(String sql) {
        editor.replaceText(sql);
        editor.moveTo(editor.getLength());
        ctrlSpace();
    }

    private void ctrlSpace() {
        editor.requestFocus();
        Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, " ", "", KeyCode.SPACE,
                false, true, false, false));
    }

    /** 在候选里挑出名字完全相同的那一条，选中它再按 Tab 采用。 */
    private void pickExact(String name) {
        List<SqlEditorPane.Suggestion> items = pane.completionItems();
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name().equalsIgnoreCase(name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            fail++;
            log("候选里没有 " + name + "，现有 "
                    + items.stream().map(SqlEditorPane.Suggestion::name).limit(8).toList());
            return;
        }
        for (int i = 0; i < index; i++) {
            Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN,
                    false, false, false, false));
        }
        Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                false, false, false, false));
    }

    // ------------------------------------------------------------------ 观察

    private List<String> tableNames() {
        return pane.completionItems().stream()
                .map(SqlEditorPane.Suggestion::name).limit(10).toList();
    }

    /** 拿这个库里第一张名字够长的表来做样本。 */
    private String firstTable() {
        editor.replaceText("SELECT * FROM ");
        editor.moveTo(editor.getLength());
        ctrlSpace();
        for (SqlEditorPane.Suggestion s : pane.completionItems()) {
            if (s.kind() == SqlEditorPane.Kind.TABLE && s.name().length() >= 3) {
                Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                        KeyCode.ESCAPE, false, false, false, false));
                return s.name();
            }
        }
        return null;
    }

    /** 文本里 {@code 表名 别名} 形式的那个别名。 */
    private static String aliasIn(String text, String table) {
        List<String> all = aliasesIn(text, table);
        return all.isEmpty() ? "" : all.get(0);
    }

    private static List<String> aliasesIn(String text, String table) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b" + java.util.regex.Pattern.quote(table) + "\\s+([\\w$]+)")
                .matcher(text);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String show(String text) {
        return text.replace(String.valueOf((char) 10), "⏎");
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
        launch(AliasCompletionProbe.class, args);
    }
}
