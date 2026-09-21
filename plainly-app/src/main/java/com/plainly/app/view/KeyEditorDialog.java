package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引与外键的编辑。
 *
 * <p>和字段设计器分开：字段改动要生成 ALTER COLUMN 并考虑数据兼容性，
 * 而索引和外键是独立对象，建了删了不动表里的数据。混在一起只会让那边的差异计算更难说清。
 *
 * <p>建索引和加外键都可能失败在数据上——唯一索引撞上既有重复值、外键撞上孤儿行。
 * 所以这里只生成语句并如实报错，不去猜「要不要先帮你清理一下数据」。
 */
public class KeyEditorDialog {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;

    private final TableView<IndexInfo> indexTable = new TableView<>();
    private final TableView<ForeignKeyInfo> keyTable = new TableView<>();
    private final TextArea preview = new TextArea();

    // 索引表单
    private final TextField indexName = new TextField();
    private final ColumnPicker indexColumns = new ColumnPicker("索引包含的列", "加一列");
    private final CheckBox indexUnique = new CheckBox("唯一");
    private final Button indexSubmit =
            UiUtils.toolButton("新建索引", Icons.plus(Icons.NEUTRAL, 11));
    /** 正在改的那个索引；null 表示在新建。 */
    private IndexInfo editingIndex;

    // 外键表单
    private final TextField keyName = new TextField();
    private final ColumnPicker keyColumns = new ColumnPicker("本表的列", "加一列");
    private final ComboBox<String> refTable = new ComboBox<>();
    private final ColumnPicker refColumns = new ColumnPicker("目标表的列", "加一列");
    private final ComboBox<String> onDelete = new ComboBox<>();
    private final ComboBox<String> onUpdate = new ComboBox<>();
    private final Button keySubmit =
            UiUtils.toolButton("新建外键", Icons.plus(Icons.NEUTRAL, 11));
    /** 正在改的那个外键；null 表示在新建。 */
    private ForeignKeyInfo editingKey;

    private List<ColumnInfo> columns = List.of();
    private final List<String> pending = new ArrayList<>();
    private Stage stage;
    private Runnable onApplied = () -> { };

    public KeyEditorDialog(AppContext context, DbSession session, String schema, String table) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
    }

    public void setOnApplied(Runnable handler) {
        this.onApplied = handler;
    }

    /** 打开时停在哪个标签：0 索引，1 外键。 */
    private int initialTab;

    /** 从索引页进来就停在索引标签，从外键页进来就停在外键标签，不让人再点一下。 */
    public void show(Window owner, int tab) {
        this.initialTab = tab;
        show(owner);
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("索引与外键");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 900, 680);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        reload();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.key(Icons.ACCENT, 14),
                UiUtils.label(schema + "." + table, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("索引", buildIndexPane()));
        tabs.getTabs().add(new Tab("外键", buildKeyPane()));
        tabs.getSelectionModel().select(Math.max(0, Math.min(initialTab, 1)));

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(6);
        preview.setText("-- 还没有待执行的变更");

        VBox box = UiUtils.column(8, tabs,
                UiUtils.row(10, UiUtils.label("将要执行", "section-label"),
                        UiUtils.label("建索引与加外键都可能因既有数据而失败，失败会如实报出来", "hint")),
                preview);
        box.setPadding(new Insets(10));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return box;
    }

    private VBox buildIndexPane() {
        indexTable.getStyleClass().add("data-grid");
        indexTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        indexTable.setPlaceholder(UiUtils.label("没有索引", "hint"));
        indexTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<IndexInfo, String> name = new TableColumn<>("名称");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<IndexInfo, String> cols = new TableColumn<>("列（按顺序）");
        cols.setCellValueFactory(c ->
                new SimpleStringProperty(String.join(", ", c.getValue().columns())));
        TableColumn<IndexInfo, String> kind = new TableColumn<>("类型");
        kind.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().primary() ? "主键" : (c.getValue().unique() ? "唯一" : "普通")));
        indexTable.getColumns().addAll(name, cols, kind);
        indexTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editIndex();
            }
        });

        indexName.setPromptText("索引名");
        indexName.setPrefWidth(180);

        indexSubmit.setOnAction(e -> submitIndex());

        Button edit = UiUtils.toolButton("修改选中项…", Icons.format(Icons.NEUTRAL, 11));
        edit.setOnAction(e -> editIndex());

        Button drop = UiUtils.toolButton("删除", Icons.minus("#a0402a", 11), "danger");
        drop.setOnAction(e -> dropIndex());

        Button reset = UiUtils.toolButton("清空表单", null);
        reset.setOnAction(e -> resetIndexForm());

        VBox form = UiUtils.column(8,
                UiUtils.row(8, UiUtils.label("名称", "form-label"), indexName, indexUnique),
                indexColumns,
                UiUtils.row(8, indexSubmit, reset));

        // 表单固定宽度：不锁的话左边的表格会把它挤扁，「唯一」两个字都露不全
        form.setPrefWidth(300);
        form.setMinWidth(300);

        VBox listSide = UiUtils.column(8, indexTable, UiUtils.row(8, edit, drop));
        VBox.setVgrow(indexTable, Priority.ALWAYS);

        HBox split = UiUtils.row(14, listSide, form);
        HBox.setHgrow(listSide, Priority.ALWAYS);

        VBox box = UiUtils.column(8, split,
                UiUtils.label("列的顺序有意义：(a, b) 和 (b, a) 是两个不同的索引，"
                        + "只有前者能加速按 a 过滤的查询", "hint"));
        box.setPadding(new Insets(8));
        VBox.setVgrow(split, Priority.ALWAYS);
        return box;
    }

    private VBox buildKeyPane() {
        keyTable.getStyleClass().add("data-grid");
        keyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        keyTable.setPlaceholder(UiUtils.label("没有外键", "hint"));

        TableColumn<ForeignKeyInfo, String> name = new TableColumn<>("名称");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<ForeignKeyInfo, String> cols = new TableColumn<>("本表的列");
        cols.setCellValueFactory(c ->
                new SimpleStringProperty(String.join(", ", c.getValue().columns())));
        TableColumn<ForeignKeyInfo, String> target = new TableColumn<>("指向");
        target.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().refTable() + " ("
                        + String.join(", ", c.getValue().refColumns()) + ")"));
        TableColumn<ForeignKeyInfo, String> rule = new TableColumn<>("删除时 / 更新时");
        rule.setCellValueFactory(c -> new SimpleStringProperty(
                nz(c.getValue().onDelete()) + " / " + nz(c.getValue().onUpdate())));
        keyTable.getColumns().addAll(name, cols, target, rule);
        keyTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editKey();
            }
        });

        keyName.setPromptText("约束名");
        keyName.setPrefWidth(180);
        refTable.setPromptText("目标表");
        refTable.setPrefWidth(170);

        for (ComboBox<String> box : List.of(onDelete, onUpdate)) {
            box.getItems().addAll("NO ACTION", "CASCADE", "SET NULL", "RESTRICT");
            box.setValue("NO ACTION");
            // 120 放不下「NO ACTION」，会显示成「NO AC...」——四个选项里有三个看不全
            box.setPrefWidth(150);
            box.setMinWidth(150);
        }
        refTable.valueProperty().addListener((o, was, is) -> loadRefColumns(is));

        keySubmit.setOnAction(e -> submitKey());

        Button edit = UiUtils.toolButton("修改选中项…", Icons.format(Icons.NEUTRAL, 11));
        edit.setOnAction(e -> editKey());
        Button drop = UiUtils.toolButton("删除", Icons.minus("#a0402a", 11), "danger");
        drop.setOnAction(e -> dropForeignKey());
        Button reset = UiUtils.toolButton("清空表单", null);
        reset.setOnAction(e -> resetKeyForm());

        // 目标表的下拉单独一行，两个选择器才对得齐；
        // 把它塞进右边那一栏会让右侧整体下沉一行，看着像没对准
        VBox form = UiUtils.column(10,
                UiUtils.row(8, UiUtils.label("名称", "form-label"), keyName,
                        UiUtils.label("指向表", "form-label"), refTable),
                UiUtils.row(16, keyColumns, refColumns),
                UiUtils.row(8, UiUtils.label("删除时", "form-label"), onDelete,
                        UiUtils.label("更新时", "form-label"), onUpdate),
                UiUtils.row(8, keySubmit, reset));

        VBox listSide = UiUtils.column(8, keyTable, UiUtils.row(8, edit, drop));
        VBox.setVgrow(keyTable, Priority.ALWAYS);

        VBox box = UiUtils.column(8, listSide, form,
                UiUtils.label("两边的列按位置配对：本表第一列对目标表第一列。"
                        + "顺序错了约束照样建得起来，只是配错了对，而且不会报错", "hint"));
        box.setPadding(new Insets(8));
        VBox.setVgrow(listSide, Priority.ALWAYS);
        return box;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "NO ACTION" : s;
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("关闭", null);
        cancel.setOnAction(e -> stage.close());
        Button apply = UiUtils.toolButton("应用", null, "primary");
        apply.setOnAction(e -> apply());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, apply);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 装载

    private void reload() {
        context.queryService().submit(() -> {
            var structure = session.structure(schema, table);
            var keys = session.connection().listForeignKeys(schema, table);
            var tables = session.tables(schema);
            return new Object[]{structure, keys, tables};
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "读取表结构失败", error);
                return;
            }
            var structure = (com.plainly.driver.meta.DbObjects.TableStructure) result[0];
            @SuppressWarnings("unchecked")
            List<ForeignKeyInfo> keys = (List<ForeignKeyInfo>) result[1];
            @SuppressWarnings("unchecked")
            List<TableInfo> tables = (List<TableInfo>) result[2];

            columns = structure.columns();
            indexTable.setItems(FXCollections.observableArrayList(structure.indexes()));
            keyTable.setItems(FXCollections.observableArrayList(keys));

            List<String> names = columns.stream().map(ColumnInfo::name).toList();
            indexColumns.setAvailable(names);
            keyColumns.setAvailable(names);
            refTable.setItems(FXCollections.observableArrayList(
                    tables.stream().map(TableInfo::name).toList()));
        }));
    }

    private void loadRefColumns(String target) {
        if (target == null) {
            refColumns.setAvailable(List.of());
            return;
        }
        context.queryService().submit(() -> session.structure(schema, target).columns())
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        return;
                    }
                    refColumns.setAvailable(list.stream().map(ColumnInfo::name).toList());
                    // 改一条已有外键时，目标列已经填好了，别用主键把它冲掉
                    if (refColumns.isEmpty()) {
                        // 目标表的主键是最常见的选择，先替他填上
                        List<String> pk = list.stream().filter(ColumnInfo::primaryKey)
                                .map(ColumnInfo::name).toList();
                        refColumns.setSelected(pk);
                    }
                }));
    }

    // ------------------------------------------------------------------ 变更

    /**
     * 新建或修改索引。
     *
     * <p>「修改」在这里就是<b>删了重建</b>，因为绝大多数数据库根本没有
     * {@code ALTER INDEX ... COLUMNS} 这种东西。这件事不藏着——按钮上写的是
     * 「改…（删了重建）」，待执行清单里也会实打实地列出两条语句。
     * 藏起来的代价是：用户以为只是改个名，实际上索引会先消失一段时间，
     * 期间的查询会全表扫。
     */
    private void submitIndex() {
        if (indexName.getText().isBlank() || indexColumns.isEmpty()) {
            UiUtils.showInfo(stage, "还差点东西", "索引名和至少一列都要填。");
            return;
        }
        IndexInfo index = new IndexInfo(indexName.getText().trim(),
                indexColumns.selected(), indexUnique.isSelected(), false);

        if (editingIndex != null) {
            queue(session.connection().dialect()
                    .dropIndexDdl(schema, table, editingIndex.name()));
        }
        queue(session.connection().dialect().createIndexDdl(schema, table, index));
        resetIndexForm();
    }

    private void editIndex() {
        IndexInfo selected = indexTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UiUtils.showInfo(stage, "先选一条", "在左边选中要改的索引。");
            return;
        }
        if (selected.primary()) {
            UiUtils.showInfo(stage, "主键不在这里改",
                    "主键要在结构设计器里改——那会连带影响列定义。");
            return;
        }
        editingIndex = selected;
        indexName.setText(selected.name());
        indexUnique.setSelected(selected.unique());
        indexColumns.setSelected(selected.columns());
        indexSubmit.setText("确认修改（删了重建）");
    }

    private void resetIndexForm() {
        editingIndex = null;
        indexName.clear();
        indexUnique.setSelected(false);
        indexColumns.clear();
        indexSubmit.setText("新建索引");
    }

    private void dropIndex() {
        IndexInfo selected = indexTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (selected.primary()) {
            UiUtils.showInfo(stage, "主键索引不能这样删",
                    "主键要在结构设计器里改——那会连带影响列定义。");
            return;
        }
        queue(session.connection().dialect().dropIndexDdl(schema, table, selected.name()));
    }

    /** 新建或修改外键。修改同样是删了重建：外键约束改不了，只能重加。 */
    private void submitKey() {
        if (keyName.getText().isBlank() || keyColumns.isEmpty()
                || refTable.getValue() == null || refColumns.isEmpty()) {
            UiUtils.showInfo(stage, "还差点东西", "约束名、本表的列、目标表和目标列都要填。");
            return;
        }
        if (keyColumns.selected().size() != refColumns.selected().size()) {
            // 两边数量不等在数据库那边会报一句很难懂的话，不如在这里就说清楚
            UiUtils.showInfo(stage, "两边的列数对不上",
                    "本表选了 " + keyColumns.selected().size() + " 列，目标表选了 "
                            + refColumns.selected().size() + " 列。"
                            + "外键的列是按位置一一配对的，数量必须相等。");
            return;
        }
        if (editingKey != null) {
            queue(session.connection().dialect()
                    .dropForeignKeyDdl(schema, table, editingKey.name()));
        }
        queue(session.connection().dialect().addForeignKeyDdl(schema, table,
                keyName.getText().trim(), keyColumns.selected(),
                schema, refTable.getValue(), refColumns.selected(),
                onUpdate.getValue(), onDelete.getValue()));
        resetKeyForm();
    }

    private void editKey() {
        ForeignKeyInfo selected = keyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UiUtils.showInfo(stage, "先选一条", "在上面选中要改的外键。");
            return;
        }
        editingKey = selected;
        keyName.setText(selected.name());
        keyColumns.setSelected(selected.columns());
        onDelete.setValue(nz(selected.onDelete()));
        onUpdate.setValue(nz(selected.onUpdate()));
        // 先把目标列填上，再设目标表：设表会触发装载，而装载看到已经有选中的列就不再覆盖
        refColumns.setSelected(selected.refColumns());
        refTable.setValue(selected.refTable());
        keySubmit.setText("确认修改（删了重建）");
    }

    private void resetKeyForm() {
        editingKey = null;
        keyName.clear();
        keyColumns.clear();
        refColumns.clear();
        refTable.setValue(null);
        onDelete.setValue("NO ACTION");
        onUpdate.setValue("NO ACTION");
        keySubmit.setText("新建外键");
    }

    private void dropForeignKey() {
        ForeignKeyInfo selected = keyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        queue(session.connection().dialect()
                .dropForeignKeyDdl(schema, table, selected.name()));
    }

    private void queue(String ddl) {
        pending.add(ddl);
        preview.setText(String.join(";\n", pending) + ";");
    }

    private void apply() {
        if (pending.isEmpty()) {
            return;
        }
        if (!UiUtils.confirm(stage, "应用变更",
                "将执行 " + pending.size() + " 条语句。")) {
            return;
        }
        List<String> statements = new ArrayList<>(pending);
        context.queryService()
                .submit(() -> session.executeDdl(schema, statements))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "应用失败", error);
                        return;
                    }
                    pending.clear();
                    preview.setText("-- 还没有待执行的变更");
                    resetIndexForm();
                    resetKeyForm();
                    session.invalidateTable(schema, table);
                    reload();
                    onApplied.run();
                    UiUtils.showInfo(stage, "已应用", "执行了 " + count + " 条语句。");
                }));
    }
}
