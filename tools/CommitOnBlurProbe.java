import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.converter.DefaultStringConverter;

/**
 * 「编辑中的单元格失去焦点时，改动到底提交没提交」——真跑一遍。
 *
 * <h2>为什么要有这个探针</h2>
 * 这件事我已经判断错过两次：第一次挂在输入框的焦点监听上，不生效
 * （TableView 先取消编辑、再转移焦点）；改到 {@code cancelEdit} 之后，
 * 光靠读代码仍然说不准。JavaFX 的编辑生命周期上有好几处顺序陷阱，
 * 而它们都不会报错，只会让改动安静地消失。
 *
 * <p>所以这里把结构设计器里那套单元格<b>原样搭一遍</b>，然后用代码模拟
 * 「点到别处」（{@code table.edit(-1, null)}，正是 TableView 自己在做的事），
 * 看提交事件有没有真的发出来、值有没有真的落到数据对象上。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/CommitOnBlurProbe.java
 * </pre>
 */
public class CommitOnBlurProbe extends Application {

    /** 被编辑的数据对象，模拟 ColumnDraft。 */
    public static class Draft {
        String name;
        String type = "VARCHAR";

        Draft(String name) {
            this.name = name;
        }
    }

    private static final StringBuilder LOG = new StringBuilder();
    private static final String NL = System.lineSeparator();

    @Override
    public void start(Stage stage) {
        ObservableList<Draft> drafts = FXCollections.observableArrayList(
                new Draft("column_1"), new Draft("column_2"));

        TableView<Draft> table = new TableView<>(drafts);
        table.setEditable(true);

        TableColumn<Draft, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type));
        typeCol.setCellFactory(c -> new ProbeTypeCell());
        typeCol.setOnEditCommit(e -> {
            e.getRowValue().type = e.getNewValue();
            LOG.append("  类型提交事件到达：[").append(e.getNewValue()).append("]").append(NL);
        });

        TableColumn<Draft, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        nameCol.setCellFactory(c -> new ProbeCell());
        nameCol.setOnEditCommit(e -> {
            e.getRowValue().name = e.getNewValue();
            LOG.append("  提交事件到达：第 ").append(e.getTablePosition().getRow())
                    .append(" 行 -> [").append(e.getNewValue()).append("]\n");
        });
        table.getColumns().add(nameCol);
        table.getColumns().add(typeCol);

        Button outside = new Button("添加字段（表格外面的按钮）");
        stage.setScene(new Scene(new VBox(table, outside), 400, 240));
        stage.show();

        // 等布局跑完，单元格才真的存在
        Platform.runLater(() -> Platform.runLater(() -> {
            runCase(table, drafts, nameCol, "点表格内的另一个格子", false);
            runCase(table, drafts, nameCol, "按 Esc 放弃", true);
            runFocusCase(table, drafts, nameCol, outside);
            runAddFieldCase(table, drafts, nameCol, outside);
            runTypeCase(table, drafts, typeCol, outside);
            runTypeIdleFocusCase(table, drafts, typeCol, outside);

            System.out.print(LOG);
            Platform.exit();
        }));
    }

    /**
     * 跑一个用例：进入编辑 → 敲字 → 结束编辑。
     *
     * @param escape true 表示模拟按 Esc，false 表示模拟点到别处
     */
    private void runCase(TableView<Draft> table, ObservableList<Draft> drafts,
                         TableColumn<Draft, String> col, String title, boolean escape) {
        drafts.get(0).name = "column_1";
        table.refresh();

        LOG.append("== ").append(title).append('\n');
        table.edit(0, col);

        ProbeCell cell = ProbeCell.editing;
        if (cell == null || !(cell.getGraphic() instanceof TextField field)) {
            LOG.append("  进不去编辑模式，这个用例作废\n");
            return;
        }
        field.setText("改过的值");
        LOG.append("  输入框里现在是：[").append(field.getText()).append("]\n");

        if (escape) {
            // 直接把 Esc 的按键事件发给输入框，走的是真实那条路
            Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ESCAPE, false, false, false, false));
        }
        // 这一句就是 TableView 在用户点向别处时自己做的事
        table.edit(-1, null);

        LOG.append("  数据对象里现在是：[").append(drafts.get(0).name).append("]\n");
        boolean committed = "改过的值".equals(drafts.get(0).name);
        LOG.append("  结论：").append(escape
                ? (committed ? "× 不该提交却提交了" : "  正确，Esc 放弃了改动")
                : (committed ? "  正确，失焦提交了" : "× 改动丢了")).append("\n\n");
    }

    /**
     * 焦点移到<b>表格外面</b>的控件上——比如点「添加字段」按钮。
     *
     * <p>这条路和「点另一个格子」不是一回事：TableView 不会因此调用
     * {@code cancelEdit}，输入框只是失去焦点。挂在 cancelEdit 上的提交
     * 在这里一次都不会触发。
     */
    private void runFocusCase(TableView<Draft> table, ObservableList<Draft> drafts,
                              TableColumn<Draft, String> col, Button outside) {
        drafts.get(0).name = "column_1";
        table.refresh();

        LOG.append("== 点表格外面的按钮（比如「添加字段」）" + NL);
        table.edit(0, col);
        ProbeCell cell = ProbeCell.editing;
        if (cell == null || !(cell.getGraphic() instanceof TextField field)) {
            LOG.append("  进不去编辑模式，这个用例作废" + NL);
            return;
        }
        field.requestFocus();
        field.setText("改过的值");
        outside.requestFocus();     // 焦点交给表格外面的按钮

        LOG.append("  数据对象里现在是：[").append(drafts.get(0).name).append("]" + NL);
        LOG.append("  结论：").append("改过的值".equals(drafts.get(0).name)
                ? "  正确，失焦提交了" : "× 改动丢了").append(NL).append(NL);
    }

    /**
     * 「填好一个字段，直接又点添加字段」——最容易丢东西的那条路。
     *
     * <p>和上一个用例的区别在于按钮<b>还会改动表格的数据</b>：加一行、换选中行、
     * 重算预览。这些动作会让正在编辑的那个单元格被回收，如果提交没有<b>抢在</b>
     * 它们前面发生，改动就跟着那个格子一起没了。
     *
     * <p>这里按真实的点击顺序来：先获焦（鼠标按下），再触发动作（鼠标松开）。
     */
    private void runAddFieldCase(TableView<Draft> table, ObservableList<Draft> drafts,
                                 TableColumn<Draft, String> col, Button addButton) {
        drafts.setAll(new Draft("column_1"), new Draft("column_2"));
        table.refresh();

        // 按钮干的事和 addField 一样：加一行、选中它
        addButton.setOnAction(e -> {
            Draft added = new Draft("column_" + (drafts.size() + 1));
            drafts.add(added);
            table.getSelectionModel().select(added);
        });

        LOG.append("== 填好一个字段，直接又点「添加字段」" + NL);
        table.edit(0, col);
        ProbeCell cell = ProbeCell.editing;
        if (cell == null || !(cell.getGraphic() instanceof TextField field)) {
            LOG.append("  进不去编辑模式，这个用例作废" + NL);
            return;
        }
        field.requestFocus();
        field.setText("我填好的字段");

        // 真实点击的两步：按下取焦点，松开触发动作
        addButton.requestFocus();
        addButton.fire();

        LOG.append("  第一个字段现在是：[").append(drafts.get(0).name).append("]" + NL);
        LOG.append("  行数：").append(drafts.size()).append("（应当是 3）" + NL);
        LOG.append("  结论：").append("我填好的字段".equals(drafts.get(0).name)
                ? "  正确，上一个字段保住了" : "× 上一个字段丢了").append(NL).append(NL);
    }

    /**
     * 类型那一列用的是<b>可编辑下拉</b>，焦点结构和文本框不一样：
     * 真正拿到焦点的是 ComboBox 内部那个编辑框，而监听挂在 ComboBox 本身上。
     * 这两者是不是同步变化，光看代码说不准——所以单独验一条。
     */
    private void runTypeCase(TableView<Draft> table, ObservableList<Draft> drafts,
                             TableColumn<Draft, String> col, Button addButton) {
        drafts.setAll(new Draft("column_1"), new Draft("column_2"));
        table.refresh();
        addButton.setOnAction(e -> drafts.add(new Draft("column_" + (drafts.size() + 1))));

        LOG.append("== 手敲一个类型名，直接点表格外面的按钮" + NL);
        table.edit(0, col);
        ProbeTypeCell cell = ProbeTypeCell.editing;
        if (cell == null || !(cell.getGraphic() instanceof ComboBox<?> box)
                || box.getEditor() == null) {
            LOG.append("  进不去编辑模式，这个用例作废" + NL);
            return;
        }
        box.getEditor().requestFocus();
        box.getEditor().setText("NUMBER(19)");
        LOG.append("  ComboBox 自己是否持有焦点：").append(box.isFocused()).append(NL);

        addButton.requestFocus();
        addButton.fire();

        LOG.append("  第一行的类型现在是：[").append(drafts.get(0).type).append("]" + NL);
        LOG.append("  结论：").append("NUMBER(19)".equals(drafts.get(0).type)
                ? "  正确，手敲的类型保住了" : "× 手敲的类型丢了").append(NL).append(NL);
    }

    /**
     * 手敲类型名之后，点一个<b>什么都不改</b>的外部控件。
     *
     * <p>这条最能暴露问题：按钮不动数据，单元格就不会被回收，
     * {@code cancelEdit} 一次都不会调。此时只剩焦点这一条路——
     * 而 ComboBox 自己压根不持有焦点（持有它的是内部的编辑框），
     * 挂在 ComboBox 上的监听于是永远不触发。
     */
    private void runTypeIdleFocusCase(TableView<Draft> table, ObservableList<Draft> drafts,
                                      TableColumn<Draft, String> col, Button idle) {
        drafts.setAll(new Draft("column_1"), new Draft("column_2"));
        table.refresh();
        idle.setOnAction(e -> { });     // 什么都不做

        LOG.append("== 手敲类型名后，点一个什么都不改的控件" + NL);
        table.edit(0, col);
        ProbeTypeCell cell = ProbeTypeCell.editing;
        if (cell == null || !(cell.getGraphic() instanceof ComboBox<?> box)
                || box.getEditor() == null) {
            LOG.append("  进不去编辑模式，这个用例作废" + NL);
            return;
        }
        // 先让 ComboBox 拿焦点：可编辑下拉会把焦点转交给内部编辑框，
        // 直接对编辑框 requestFocus 在这个时机是不生效的
        box.requestFocus();
        box.getEditor().requestFocus();
        box.getEditor().setText("NUMBER(19)");
        LOG.append("  取焦后：editor.focused=").append(box.getEditor().isFocused())
                .append("  box.focused=").append(box.isFocused())
                .append("  cell.editing=").append(cell.isEditing()).append(NL);
        idle.requestFocus();
        LOG.append("  移焦后：editor.focused=").append(box.getEditor().isFocused())
                .append("  cell.editing=").append(cell.isEditing()).append(NL);

        LOG.append("  第一行的类型现在是：[").append(drafts.get(0).type).append("]" + NL);
        LOG.append("  结论：").append("NUMBER(19)".equals(drafts.get(0).type)
                ? "  正确，手敲的类型保住了" : "× 手敲的类型丢了").append(NL).append(NL);
    }

    /** 和 TableDesignerPane.TypeCell 同一套写法。 */
    private static class ProbeTypeCell extends ComboBoxTableCell<Draft, String> {

        static ProbeTypeCell editing;

        private boolean escapeHookInstalled;
        private boolean escapePressed;
        private TablePosition<Draft, String> editingPosition;

        ProbeTypeCell() {
            super(FXCollections.observableArrayList("VARCHAR", "BIGINT", "DECIMAL"));
            setComboBoxEditable(true);
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing() || !(getGraphic() instanceof ComboBox<?> box)) {
                return;
            }
            editing = this;
            escapePressed = false;
            if (getTableRow() != null && getTableColumn() != null) {
                editingPosition = new TablePosition<>(
                        getTableView(), getTableRow().getIndex(), getTableColumn());
            }
            if (!escapeHookInstalled && box.getEditor() != null) {
                escapeHookInstalled = true;
                box.getEditor().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        escapePressed = true;
                    }
                });
                // 挂在 ComboBox 本身上：实测持有焦点的是它，不是内部的编辑框
                // （editor.focused 始终为 false）。这一点和纯 TextField 相反，
                // 挂错地方监听一次都不会触发
                box.focusedProperty().addListener((o, was, focused) -> {
                    if (!focused && !box.isShowing() && isEditing()) {
                        commitEdit(box.getEditor().getText());
                    }
                });
            }
        }

        @Override
        public void cancelEdit() {
            String typed = getGraphic() instanceof ComboBox<?> b && b.getEditor() != null
                    ? b.getEditor().getText() : null;
            boolean shouldCommit = !escapePressed
                    && typed != null
                    && !typed.isBlank()
                    && !typed.equals(getItem())
                    && editingPosition != null
                    && getTableColumn() != null
                    && getTableView() != null;
            TablePosition<Draft, String> pos = editingPosition;

            super.cancelEdit();

            if (shouldCommit) {
                Event.fireEvent(getTableColumn(),
                        new TableColumn.CellEditEvent<>(getTableView(), pos,
                                TableColumn.editCommitEvent(), typed));
            }
        }
    }

    /** 和 TableDesignerPane.DraftCell 同一套写法。 */
    private static class ProbeCell extends TextFieldTableCell<Draft, String> {

        /** 当前正在编辑的那个格子，给探针取输入框用。 */
        static ProbeCell editing;

        private boolean escapeHookInstalled;
        private boolean escapePressed;
        private TablePosition<Draft, String> editingPosition;

        ProbeCell() {
            super(new DefaultStringConverter());
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing() || !(getGraphic() instanceof TextField field)) {
                return;
            }
            editing = this;
            escapePressed = false;
            if (getTableRow() != null && getTableColumn() != null) {
                editingPosition = new TablePosition<>(
                        getTableView(), getTableRow().getIndex(), getTableColumn());
            }
            if (escapeHookInstalled) {
                return;
            }
            escapeHookInstalled = true;
            field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    escapePressed = true;
                }
            });
            // 第二个挂钩：焦点移到表格外面时 TableView 不会调 cancelEdit，
            // 输入框只是失焦。这条路得自己接
            field.focusedProperty().addListener((o, was, focused) -> {
                if (!focused && isEditing()) {
                    commitEdit(field.getText());
                }
            });
        }

        @Override
        public void cancelEdit() {
            String typed = getGraphic() instanceof TextField f ? f.getText() : null;
            boolean shouldCommit = !escapePressed
                    && typed != null
                    && !typed.equals(getItem())
                    && editingPosition != null
                    && getTableColumn() != null
                    && getTableView() != null;
            TablePosition<Draft, String> pos = editingPosition;

            super.cancelEdit();

            if (shouldCommit) {
                Event.fireEvent(getTableColumn(),
                        new TableColumn.CellEditEvent<>(getTableView(), pos,
                                TableColumn.editCommitEvent(), typed));
            }
        }
    }

    public static void main(String[] args) {
        // 传 class 而不是只传 args：单文件源码模式下类不在 classpath 上，
        // Application.launch(args) 会用 Class.forName 去找主类，必然 ClassNotFound
        launch(CommitOnBlurProbe.class, args);
    }
}
