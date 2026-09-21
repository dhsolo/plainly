import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.TableDesignerPane;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 拿<b>真实的</b>结构设计器跑一遍「填好字段名，直接点添加字段」。
 *
 * <h2>为什么必须用真的那个面板</h2>
 * 上一轮我照着设计器的写法搭了一个等价的表格来验，结论是「通过」，
 * 而用户在界面上仍然丢改动。那说明我搭的那份和真的有实质差别——
 * 而差在哪，只有把真的跑起来才知道。仿制品验不出仿制品没复制到的东西。
 *
 * <p>这里连的是仓库里的 H2 演示库，<b>只读结构、不点「应用」</b>，不会改任何库。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/DesignerEditProbe.java [表名]
 * </pre>
 */
public class DesignerEditProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();

    @Override
    public void start(Stage stage) {
        String table = getParameters().getRaw().isEmpty()
                ? "ORDERS" : getParameters().getRaw().get(0);

        AppContext context = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("designer-probe")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");
        DbSession session = new DbSession(cfg, Connections.open(cfg));

        TableDesignerPane pane = new TableDesignerPane(context, session, "PUBLIC", table);
        stage.setScene(new Scene(new StackPane(pane), 1100, 700));
        stage.show();

        // 结构是异步读的，等它到位再动手
        waitUntilLoaded(pane, 0, () -> {
            try {
                runScenario(pane);
            } catch (RuntimeException e) {
                LOG.append("探针自己出错了：").append(e).append(NL);
            }
            System.out.print(LOG);
            context.close();
            Platform.exit();
        });
    }

    /** 轮询等结构加载完（字段表里出现行）。最多等 5 秒。 */
    private void waitUntilLoaded(Parent pane, int tries, Runnable then) {
        TableView<?> fields = findTable(pane);
        if ((fields != null && !fields.getItems().isEmpty()) || tries > 50) {
            then.run();
            return;
        }
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(100));
        wait.setOnFinished(e -> waitUntilLoaded(pane, tries + 1, then));
        wait.play();
    }

    private void runScenario(Parent pane) {
        TableView<Object> fields = findTable(pane);
        Button addButton = findButton(pane, "添加字段");
        if (fields == null || addButton == null) {
            LOG.append("找不到字段表或「添加字段」按钮，探针作废").append(NL);
            return;
        }
        LOG.append("字段表初始行数：").append(fields.getItems().size()).append(NL);

        // 一、点「添加字段」，加出一行
        addButton.requestFocus();
        addButton.fire();
        int added = fields.getItems().size() - 1;
        Object draft = fields.getItems().get(added);
        LOG.append("加出的新行在第 ").append(added).append(" 行，默认名：")
                .append(nameOf(draft)).append(NL);

        // 二、在名称列上进入编辑，敲一个名字
        TableColumn<Object, ?> nameCol = fields.getColumns().get(0);
        fields.getSelectionModel().select(added);
        fields.scrollTo(added);
        fields.layout();
        fields.edit(added, nameCol);

        TextField editor = findFocusedEditor(fields);
        if (editor == null) {
            LOG.append("× 进不去编辑模式——连编辑框都没找到").append(NL);
            return;
        }
        // 刻意<b>不</b>显式取焦：要看的正是「双击进入编辑之后，编辑框自己有没有焦点」。
        // 上一版在这儿加了一句 requestFocus，等于替产品把条件凑齐了——
        // 那样测出来的通过说明不了真实场景
        editor.setText("我填好的字段名");
        LOG.append("输入框里现在是：[").append(editor.getText()).append("]").append(NL);
        LOG.append("编辑框是否持有焦点：").append(editor.isFocused()).append(NL);
        LOG.append("此刻焦点其实在：").append(describeFocus(fields)).append(NL);

        // 三、直接点「添加字段」——发真实的鼠标按下 / 松开事件，
        // 而不是 requestFocus + fire。上一版用后者，结论是「通过」，
        // 而用户在界面上仍然丢改动——差别只可能在这里
        click(addButton);

        LOG.append("点完之后，焦点在：").append(describeFocus(fields)).append(NL);

        LOG.append("上一个字段现在叫：[").append(nameOf(draft)).append("]").append(NL);
        LOG.append("现在总行数：").append(fields.getItems().size()).append(NL);
        LOG.append("结论：").append("我填好的字段名".equals(nameOf(draft))
                ? "  正确，上一个字段保住了" : "× 上一个字段丢了").append(NL);

        typeScenario(fields, addButton);
    }

    /**
     * 类型列那条路：选完数据类型之后会怎样。
     *
     * <p>用户报了两件事：选完类型后上方「表名」输入框的内容被全选，
     * 以及添加字段时改动仍然会丢。前者是焦点跑掉了的症状，
     * 两件事很可能是同一个机制。
     */
    private void typeScenario(TableView<Object> fields, Button addButton) {
        LOG.append(NL).append("== 类型列").append(NL);
        int row = fields.getItems().size() - 1;
        Object draft = fields.getItems().get(row);
        TableColumn<Object, ?> typeCol = fields.getColumns().get(1);

        fields.getSelectionModel().select(row);
        fields.scrollTo(row);
        fields.layout();
        fields.edit(row, typeCol);

        javafx.scene.control.ComboBox<?> box = null;
        for (Node n : all(fields)) {
            if (n instanceof javafx.scene.control.ComboBox<?> c && c.isVisible()) {
                box = c;
                break;
            }
        }
        if (box == null) {
            LOG.append("× 类型列进不去编辑模式").append(NL);
            return;
        }
        LOG.append("下拉是否持有焦点：").append(box.isFocused())
                .append("   编辑框：").append(box.getEditor() != null
                        && box.getEditor().isFocused()).append(NL);
        LOG.append("此刻焦点在：").append(describeFocus(fields)).append(NL);

        // 模拟从下拉列表里选一个：直接设值，走的是 ComboBox 的选中路径
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<String> typed =
                (javafx.scene.control.ComboBox<String>) box;
        typed.setValue("DECIMAL");
        LOG.append("选了 DECIMAL 之后，焦点在：").append(describeFocus(fields)).append(NL);

        click(addButton);
        LOG.append("点完添加字段，焦点在：").append(describeFocus(fields)).append(NL);
        LOG.append("那一行的类型现在是：[").append(typeOf(draft)).append("]").append(NL);
        LOG.append("结论：").append("DECIMAL".equals(typeOf(draft))
                ? "  正确，类型保住了" : "× 类型丢了").append(NL);
    }

    private static String typeOf(Object draft) {
        try {
            return String.valueOf(draft.getClass().getMethod("nativeType").invoke(draft));
        } catch (Exception e) {
            return "（读不出类型：" + e + "）";
        }
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

    private static String describeFocus(Node any) {
        Node owner = any.getScene() == null ? null : any.getScene().getFocusOwner();
        if (owner == null) {
            return "（没有）";
        }
        String extra = owner instanceof TextField f
                ? "  文本=[" + f.getText() + "]  选中=[" + f.getSelectedText() + "]" : "";
        return owner.getClass().getSimpleName() + extra;
    }

    /** 用反射读 ColumnDraft.name()，免得为了一个探针把契约类拖进来。 */
    private static String nameOf(Object draft) {
        try {
            return String.valueOf(draft.getClass().getMethod("name").invoke(draft));
        } catch (Exception e) {
            return "（读不出名字：" + e + "）";
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> TableView<T> findTable(Parent root) {
        for (Node n : all(root)) {
            if (n instanceof TableView<?> t && !t.getColumns().isEmpty()
                    && "数据类型".equals(t.getColumns().size() > 1
                            ? t.getColumns().get(1).getText() : "")) {
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

    /** 编辑中的单元格里那个输入框。 */
    private static TextField findFocusedEditor(Parent root) {
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
        launch(DesignerEditProbe.class, args);
    }
}
