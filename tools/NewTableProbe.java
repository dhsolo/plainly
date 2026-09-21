import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.NewTableDialog;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 拿<b>真实的</b>「新建表」对话框跑一遍「填好字段，直接点添加字段」。
 *
 * <h2>为什么要有这一个</h2>
 * 结构设计器上的失焦提交修好之后，同样的操作在新建表对话框里仍然丢改动——
 * 那边用的还是 JavaFX 自带的单元格。两个界面看着像两张表，用户眼里是同一件事。
 * 修完必须两边各跑一遍，不然下次还是只有被报上来的那一半是对的。
 *
 * <p>连的是仓库里的 H2 演示库，<b>只读类型目录、不点「创建」</b>，不会改任何库。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/NewTableProbe.java
 * </pre>
 */
public class NewTableProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();

    private AppContext context;

    @Override
    public void start(Stage stage) {
        context = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("newtable-probe")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");
        DbSession session = new DbSession(cfg, Connections.open(cfg));

        // 宿主窗口：对话框要一个 owner
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();

        NewTableDialog dialog = new NewTableDialog(context, session, "PUBLIC");
        // showAndWait 会开一个嵌套事件循环并阻塞在这里，但 runLater 排的活照样跑得到，
        // 所以先把剧本排上，再让对话框弹出来
        delay(600, this::runScenario);
        dialog.showAndWait(stage);

        System.out.print(LOG);
        context.close();
        Platform.exit();
    }

    private void runScenario() {
        Stage dialog = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && "新建表".equals(s.getTitle())) {
                dialog = s;
            }
        }
        if (dialog == null) {
            LOG.append("没找到「新建表」窗口，探针作废").append(NL);
            return;
        }
        Parent root = dialog.getScene().getRoot();
        TableView<Object> fields = findTable(root);
        Button addButton = findButton(root, "添加字段");
        TextField nameField = findPrompt(root, "表名");
        if (fields == null || addButton == null || nameField == null) {
            LOG.append("字段表 / 添加字段按钮 / 表名框，有没找到的，探针作废").append(NL);
            dialog.close();
            return;
        }

        nameField.setText("probe_table");
        LOG.append("字段表初始行数：").append(fields.getItems().size()).append(NL);

        nameScenario(fields, addButton, nameField);
        typeScenario(fields, addButton, nameField);

        dialog.close();
    }

    /** 一、填好字段名之后直接点「添加字段」。 */
    private void nameScenario(TableView<Object> fields, Button addButton, TextField nameField) {
        LOG.append(NL).append("== 字段名列").append(NL);
        addButton.fire();
        int row = fields.getItems().size() - 1;
        Object draft = fields.getItems().get(row);

        TableColumn<Object, ?> nameCol = fields.getColumns().get(0);
        fields.getSelectionModel().select(row);
        fields.scrollTo(row);
        fields.layout();
        fields.edit(row, nameCol);

        TextField editor = findEditor(fields);
        if (editor == null) {
            LOG.append("× 进不去编辑模式").append(NL);
            return;
        }
        // 刻意不显式取焦：要看的正是「双击进入编辑之后，编辑框自己有没有焦点」
        editor.setText("我填好的字段名");
        LOG.append("输入框里现在是：[").append(editor.getText()).append("]").append(NL);

        click(addButton);
        LOG.append("上一个字段现在叫：[").append(nameOf(draft)).append("]").append(NL);
        LOG.append("结论：").append("我填好的字段名".equals(nameOf(draft))
                ? "  正确，上一个字段保住了" : "× 上一个字段丢了").append(NL);
        LOG.append("表名框：文本=[").append(nameField.getText())
                .append("]  选中=[").append(nameField.getSelectedText()).append("]").append(NL);
    }

    /** 二、选完数据类型之后点「添加字段」，顺便看表名框有没有被全选。 */
    private void typeScenario(TableView<Object> fields, Button addButton, TextField nameField) {
        LOG.append(NL).append("== 类型列").append(NL);
        int row = fields.getItems().size() - 1;
        Object draft = fields.getItems().get(row);
        TableColumn<Object, ?> typeCol = fields.getColumns().get(1);

        fields.getSelectionModel().select(row);
        fields.scrollTo(row);
        fields.layout();
        fields.edit(row, typeCol);

        ComboBox<?> box = null;
        for (Node n : all(fields)) {
            if (n instanceof ComboBox<?> c && c.isVisible()) {
                box = c;
                break;
            }
        }
        if (box == null) {
            LOG.append("× 类型列进不去编辑模式").append(NL);
            return;
        }
        @SuppressWarnings("unchecked")
        ComboBox<String> typed = (ComboBox<String>) box;
        typed.setValue("DECIMAL");

        click(addButton);
        LOG.append("那一行的类型现在是：[").append(typeOf(draft)).append("]").append(NL);
        LOG.append("结论：").append("DECIMAL".equals(typeOf(draft))
                ? "  正确，类型保住了" : "× 类型丢了").append(NL);
        LOG.append("表名框：文本=[").append(nameField.getText())
                .append("]  选中=[").append(nameField.getSelectedText()).append("]").append(NL);
        LOG.append("表名结论：").append(nameField.getSelectedText().isEmpty()
                ? "  正确，表名没被全选" : "× 表名被全选了，下一个键就把它替换掉").append(NL);
    }

    // ------------------------------------------------------------------ 工具

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    /** 发一对真实的鼠标事件，走 JavaFX 自己的按钮行为（取焦发生在按下那一刻）。 */
    private static void click(Button button) {
        javafx.geometry.Bounds b = button.localToScreen(button.getBoundsInLocal());
        double x = b == null ? 0 : b.getMinX() + b.getWidth() / 2;
        double y = b == null ? 0 : b.getMinY() + b.getHeight() / 2;
        for (javafx.event.EventType<javafx.scene.input.MouseEvent> type : List.of(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                javafx.scene.input.MouseEvent.MOUSE_CLICKED)) {
            javafx.event.Event.fireEvent(button, new javafx.scene.input.MouseEvent(
                    type, 5, 5, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, true, false, true, null));
        }
    }

    private static String nameOf(Object draft) {
        return read(draft, "name");
    }

    private static String typeOf(Object draft) {
        return read(draft, "nativeType");
    }

    /** 用反射读 ColumnDraft，免得为了一个探针把契约类拖进来。 */
    private static String read(Object draft, String method) {
        try {
            return String.valueOf(draft.getClass().getMethod(method).invoke(draft));
        } catch (Exception e) {
            return "（读不出 " + method + "：" + e + "）";
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> TableView<T> findTable(Parent root) {
        for (Node n : all(root)) {
            if (n instanceof TableView<?> t && t.getColumns().size() > 1
                    && "数据类型".equals(t.getColumns().get(1).getText())) {
                return (TableView<T>) t;
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

    private static TextField findPrompt(Parent root, String prompt) {
        for (Node n : all(root)) {
            if (n instanceof TextField f && prompt.equals(f.getPromptText())) {
                return f;
            }
        }
        return null;
    }

    /** 编辑中的单元格里那个输入框。 */
    private static TextField findEditor(Parent root) {
        for (Node n : all(root)) {
            if (n instanceof TextField f && f.getScene() != null && f.isVisible()) {
                return f;
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
        launch(NewTableProbe.class, args);
    }
}
