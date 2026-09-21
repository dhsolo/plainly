package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.sync.DataSyncService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据同步：逐行比对，生成把目标改成源的 INSERT / UPDATE / DELETE。
 *
 * <p>两处刻意的设计。其一，DELETE 默认不勾选——同步方向是「以源为准」，
 * 但源里没有不等于该删，很可能只是源库还没同步过来；删错的代价远大于漏删。
 * 其二，数值比较方式是个选项而不是定死的规则：{@code 0.150000} 与 {@code 0.15}
 * 文本不同、数值相同，想把存储形式也对齐就用严格模式，只关心业务值就用数值模式。
 */
public class DataSyncDialog {

    /** 差异表里的一行，带上勾选状态。 */
    public static class DiffRow {
        private final DataSyncService.RowDiff diff;
        private boolean picked;

        DiffRow(DataSyncService.RowDiff diff, boolean picked) {
            this.diff = diff;
            this.picked = picked;
        }

        public DataSyncService.RowDiff diff() {
            return diff;
        }
    }

    private final AppContext context;

    private final ComboBox<ConnectionConfig> sourceConn = new ComboBox<>();
    private final ComboBox<String> sourceSchema = new ComboBox<>();
    private final ComboBox<String> sourceTable = new ComboBox<>();
    private final ComboBox<ConnectionConfig> targetConn = new ComboBox<>();
    private final ComboBox<String> targetSchema = new ComboBox<>();
    private final ComboBox<String> targetTable = new ComboBox<>();
    private final ComboBox<String> keyBox = new ComboBox<>();

    private final ToggleGroup numericGroup = new ToggleGroup();
    private final Label numericNote = UiUtils.label("", "hint");
    private final TableView<DiffRow> diffTable = new TableView<>();
    private final TextArea scriptArea = new TextArea();
    private final Label counts = UiUtils.label("尚未比对", "hint");
    private final Label deleteNote = UiUtils.label(
            "DELETE 默认不勾选：源里没有不等于该删，很可能只是源库还没同步过来", "hint");

    private final Button compareButton = UiUtils.toolButton("开始比对", null, "primary");
    private final Button applyButton = UiUtils.toolButton("应用到目标", null, "primary");
    private final Button exportButton = UiUtils.toolButton("仅导出脚本", null);

    private DataSyncService.DiffResult result;

    /*
     * 生成预览脚本要用的两样东西，在比对那一步一并取好。
     *
     * volatile：写在后台线程（比对任务里），读在界面线程（每次勾选复选框）。
     * 不加的话，界面线程可能读到 null 或者半初始化的引用——而这类问题
     * 不会稳定复现，只会偶尔冒一个说不清来路的空指针。
     *
     * 写入发生在 result 被赋值之前（比对任务返回之前），所以只要 result 不为 null，
     * 这两个就一定已经就位。refreshScript 也正是靠 result == null 提前返回的。
     */
    private volatile List<ColumnInfo> syncColumns;
    private volatile com.plainly.driver.SqlDialect syncDialect;
    private Stage stage;

    public DataSyncDialog(AppContext context) {
        this.context = context;
        applyButton.setDisable(true);
        exportButton.setDisable(true);
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("数据同步");

        VBox root = new VBox(buildHead(), buildEndpoints(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1180, 820);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        List<ConnectionConfig> all = context.registry().listAll();
        sourceConn.setItems(FXCollections.observableArrayList(all));
        targetConn.setItems(FXCollections.observableArrayList(all));
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.refresh(Icons.ACCENT, 14),
                UiUtils.label("逐行比对，生成把目标改成源的 INSERT / UPDATE / DELETE", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildEndpoints() {
        configure(sourceConn, sourceSchema, sourceTable, true);
        configure(targetConn, targetSchema, targetTable, false);
        targetConn.setStyle("-fx-border-color:-sx-red-border;");

        VBox source = UiUtils.column(4, UiUtils.label("源 · 以它为准", "section-label"),
                UiUtils.row(6, grow(sourceConn), fixed(sourceSchema, 150),
                        fixed(sourceTable, 190)));
        VBox target = UiUtils.column(4, UiUtils.label("目标 · 将被修改", "section-label"),
                UiUtils.row(6, grow(targetConn), fixed(targetSchema, 150),
                        fixed(targetTable, 190)));
        HBox.setHgrow(source, Priority.ALWAYS);
        HBox.setHgrow(target, Priority.ALWAYS);

        keyBox.setPrefWidth(180);
        compareButton.setOnAction(e -> compare());

        HBox keys = UiUtils.row(8, UiUtils.label("比对键", "form-label"), keyBox,
                UiUtils.vSeparator(), buildNumericMode(), UiUtils.hSpacer(), compareButton);
        keys.setAlignment(Pos.CENTER_LEFT);

        VBox box = UiUtils.column(10, UiUtils.row(12, source,
                Icons.export(Icons.ACCENT, 18), target), keys, numericNote);
        box.setPadding(new Insets(12, 14, 12, 14));
        box.setStyle("-fx-border-color: transparent transparent -sx-border-light transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return box;
    }

    private HBox buildNumericMode() {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(UiUtils.label("数值比较", "form-label"));
        for (DataSyncService.NumericMode mode : DataSyncService.NumericMode.values()) {
            RadioButton b = new RadioButton(mode.label());
            b.setToggleGroup(numericGroup);
            b.setUserData(mode);
            if (mode == DataSyncService.NumericMode.TEXT) {
                b.setSelected(true);
                numericNote.setText(mode.note());
            }
            b.setOnAction(e -> numericNote.setText(mode.note()));
            row.getChildren().add(b);
        }
        return row;
    }

    private void configure(ComboBox<ConnectionConfig> conn, ComboBox<String> schema,
                           ComboBox<String> table, boolean isSource) {
        conn.setCellFactory(v -> new ConnectionCell());
        conn.setButtonCell(new ConnectionCell());
        conn.setMaxWidth(Double.MAX_VALUE);
        conn.valueProperty().addListener((o, was, is) -> loadSchemas(is, schema));
        schema.valueProperty().addListener((o, was, is) -> loadTables(conn.getValue(), is, table));
        table.valueProperty().addListener((o, was, is) -> {
            if (isSource) {
                loadKeys();
            }
        });
    }

    private void loadSchemas(ConnectionConfig config, ComboBox<String> box) {
        box.getItems().clear();
        if (config == null) {
            return;
        }
        context.queryService().submit(() -> context.openSession(config).schemas())
                .whenComplete((schemas, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "连接 " + config.name() + " 失败", error);
                        return;
                    }
                    box.setItems(FXCollections.observableArrayList(
                            schemas.stream().map(SchemaInfo::name).toList()));
                    schemas.stream().filter(SchemaInfo::isDefault).findFirst()
                            .ifPresent(s -> box.setValue(s.name()));
                }));
    }

    private void loadTables(ConnectionConfig config, String schema, ComboBox<String> box) {
        box.getItems().clear();
        if (config == null || schema == null) {
            return;
        }
        context.queryService().submit(() -> context.openSession(config).tables(schema))
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "读取表清单失败", error);
                        return;
                    }
                    box.setItems(FXCollections.observableArrayList(
                            list.stream().map(TableInfo::name).toList()));
                }));
    }

    /** 比对键默认取源表的主键——那是最可能唯一定位一行的东西。 */
    private void loadKeys() {
        keyBox.getItems().clear();
        ConnectionConfig config = sourceConn.getValue();
        String schema = sourceSchema.getValue();
        String table = sourceTable.getValue();
        if (config == null || schema == null || table == null) {
            return;
        }
        context.queryService()
                .submit(() -> context.openSession(config).structure(schema, table))
                .whenComplete((structure, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        return;
                    }
                    keyBox.setItems(FXCollections.observableArrayList(
                            structure.columns().stream().map(ColumnInfo::name).toList()));
                    List<String> pk = structure.primaryKeyColumns();
                    if (!pk.isEmpty()) {
                        keyBox.setValue(pk.get(0));
                    }
                }));
    }

    // ------------------------------------------------------------------ 主体

    private SplitPane buildBody() {
        buildDiffTable();
        scriptArea.setEditable(false);
        scriptArea.getStyleClass().add("ddl-area");

        VBox top = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("差异明细", "section-label"), counts,
                        UiUtils.hSpacer(), deleteNote),
                diffTable);
        top.setPadding(new Insets(10));
        VBox.setVgrow(diffTable, Priority.ALWAYS);

        VBox bottom = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("同步脚本", "section-label"),
                        UiUtils.label("全参数化，数值以 BigDecimal 绑定", "hint")),
                scriptArea);
        bottom.setPadding(new Insets(10));
        VBox.setVgrow(scriptArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void buildDiffTable() {
        diffTable.getStyleClass().add("data-grid");
        diffTable.setPlaceholder(UiUtils.label("选好两侧的表，点「开始比对」", "hint"));

        TableColumn<DiffRow, Boolean> pick = new TableColumn<>("");
        pick.setMaxWidth(46);
        pick.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().picked));
        pick.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    DiffRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        row.picked = box.isSelected();
                        refreshScript();
                    }
                });
            }

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(Boolean.TRUE.equals(value));
                setGraphic(box);
            }
        });

        TableColumn<DiffRow, String> action = new TableColumn<>("动作");
        action.setPrefWidth(90);
        action.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().diff().action().name()));

        TableColumn<DiffRow, String> key = new TableColumn<>("键");
        key.setPrefWidth(140);
        key.setCellValueFactory(c ->
                new SimpleStringProperty(String.join(", ", c.getValue().diff().keyValues())));

        TableColumn<DiffRow, String> changed = new TableColumn<>("差异列");
        changed.setPrefWidth(200);
        changed.setCellValueFactory(c -> new SimpleStringProperty(
                String.join(", ", c.getValue().diff().changedColumns())));

        TableColumn<DiffRow, String> from = new TableColumn<>("源值");
        from.setPrefWidth(220);
        from.setCellValueFactory(c -> new SimpleStringProperty(describe(c.getValue(), true)));

        TableColumn<DiffRow, String> to = new TableColumn<>("目标值");
        to.setPrefWidth(220);
        to.setCellValueFactory(c -> new SimpleStringProperty(describe(c.getValue(), false)));

        diffTable.getColumns().addAll(pick, action, key, changed, from, to);
    }

    /** 只显示差异列的值——整行摊开在表格里既看不完也看不出重点。 */
    private String describe(DiffRow row, boolean source) {
        DataSyncService.RowDiff diff = row.diff();
        List<String> values = source ? diff.sourceValues() : diff.targetValues();
        if (values == null) {
            return source ? "源不存在" : "目标不存在";
        }
        if (diff.changedColumns().isEmpty()) {
            return String.join(" · ", values);
        }
        List<String> parts = new ArrayList<>();
        for (String name : diff.changedColumns()) {
            int index = result.columns().indexOf(name);
            if (index >= 0) {
                parts.add(name + "=" + values.get(index));
            }
        }
        return String.join(" · ", parts);
    }

    private HBox buildFoot() {
        exportButton.setOnAction(e -> exportScript());
        applyButton.setOnAction(e -> apply());
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, exportButton, applyButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 比对

    private void compare() {
        ConnectionConfig sc = sourceConn.getValue();
        ConnectionConfig tc = targetConn.getValue();
        String ss = sourceSchema.getValue();
        String st = sourceTable.getValue();
        String ts = targetSchema.getValue();
        String tt = targetTable.getValue();
        String key = keyBox.getValue();
        if (sc == null || tc == null || ss == null || st == null || ts == null || tt == null
                || key == null) {
            UiUtils.showInfo(stage, "还差点东西", "两侧的连接、库、表和比对键都要先选好。");
            return;
        }
        DataSyncService.NumericMode mode =
                (DataSyncService.NumericMode) numericGroup.getSelectedToggle().getUserData();

        compareButton.setDisable(true);
        counts.setText("比对中…");
        context.queryService().submit(() -> {
            DbConnection src = context.openSession(sc).connection();
            DbSession target = context.openSession(tc);
            DbConnection dst = target.connection();
            // 顺手把生成预览脚本要用的两样东西取下来。
            //
            // 为什么非要在这儿取：预览脚本是<b>每勾一下复选框</b>就重新生成一次的，
            // 而那跑在界面线程上。到那时候再去 openSession 拿列定义，等于把一次
            // 网络往返放进了每一次点击——连接正常时只是浪费，连接断了就是每点一下
            // 卡满探活超时，用户会觉得整个对话框坏了。
            syncColumns = target.structure(ts, tt).columns();
            syncDialect = dst.dialect();
            return DataSyncService.compare(src, ss, st, dst, ts, tt, List.of(key), mode);
        }).whenComplete((diff, error) -> Platform.runLater(() -> {
            compareButton.setDisable(false);
            if (error != null) {
                counts.setText("比对失败");
                UiUtils.showError(stage, "比对失败", error);
                return;
            }
            result = diff;
            List<DiffRow> rows = new ArrayList<>();
            for (DataSyncService.RowDiff d : diff.diffs()) {
                // DELETE 默认不勾：删错的代价远大于漏删
                rows.add(new DiffRow(d, d.action() != DataSyncService.Action.DELETE));
            }
            diffTable.setItems(FXCollections.observableArrayList(rows));
            counts.setText(diff.count(DataSyncService.Action.INSERT) + " 条 INSERT · "
                    + diff.count(DataSyncService.Action.UPDATE) + " 条 UPDATE · "
                    + diff.count(DataSyncService.Action.DELETE) + " 条 DELETE · "
                    + diff.same() + " 行一致");
            refreshScript();
        }));
    }

    private List<DataSyncService.RowDiff> picked() {
        List<DataSyncService.RowDiff> out = new ArrayList<>();
        for (DiffRow row : diffTable.getItems()) {
            if (row.picked) {
                out.add(row.diff());
            }
        }
        return out;
    }

    private void refreshScript() {
        if (result == null) {
            return;
        }
        List<DataSyncService.RowDiff> selected = picked();
        applyButton.setDisable(selected.isEmpty());
        exportButton.setDisable(selected.isEmpty());
        if (selected.isEmpty()) {
            scriptArea.setText("-- 没有勾选任何差异");
            return;
        }
        try {
            List<DataSyncService.Statement> script = buildScript(selected);
            StringBuilder sb = new StringBuilder();
            Set<String> shown = new LinkedHashSet<>();
            for (DataSyncService.Statement s : script) {
                if (shown.add(s.sql().sql())) {
                    sb.append(s.sql().sql()).append(";\n");
                }
            }
            sb.append("\n-- 共 ").append(script.size()).append(" 条语句，值以参数绑定");
            scriptArea.setText(sb.toString());
        } catch (RuntimeException e) {
            scriptArea.setText("-- 无法生成：" + UiUtils.rootMessage(e));
            applyButton.setDisable(true);
        }
    }

    /**
     * 生成脚本。<b>不碰连接</b>——列定义和方言在比对那一步就取好了。
     *
     * <p>这个方法两边都会调：{@code refreshScript} 在界面线程上（勾一下复选框
     * 就来一次），{@code apply} 在后台线程上。所以它必须是纯计算，
     * 里面一旦有网络调用，界面线程那条路就会卡。
     */
    private List<DataSyncService.Statement> buildScript(
            List<DataSyncService.RowDiff> selected) {
        return DataSyncService.script(syncDialect,
                targetSchema.getValue(), targetTable.getValue(), result, syncColumns, selected);
    }

    private void exportScript() {
        scriptArea.selectAll();
        scriptArea.copy();
        scriptArea.deselect();
        UiUtils.showInfo(stage, "已复制", "同步脚本已复制到剪贴板。\n"
                + "注意：脚本里的值是占位符，直接拿去手工执行需要自行补齐参数。");
    }

    private void apply() {
        List<DataSyncService.RowDiff> selected = picked();
        long deletes = selected.stream()
                .filter(d -> d.action() == DataSyncService.Action.DELETE).count();
        String prompt = "将对 " + targetConn.getValue().name() + " / "
                + targetSchema.getValue() + "." + targetTable.getValue()
                + " 执行 " + selected.size() + " 条语句。"
                + (deletes > 0 ? "\n\n其中 " + deletes + " 条是 DELETE，会永久删除目标里的行。" : "");
        if (!UiUtils.confirm(stage, "应用到目标", prompt)) {
            return;
        }

        applyButton.setDisable(true);
        context.queryService().submit(() -> {
            List<DataSyncService.Statement> script = buildScript(selected);
            DbConnection dst = context.openSession(targetConn.getValue()).connection();
            return DataSyncService.apply(dst, script);
        }).whenComplete((affected, error) -> Platform.runLater(() -> {
            applyButton.setDisable(false);
            if (error != null) {
                UiUtils.showError(stage, "同步失败", error);
                return;
            }
            UiUtils.showInfo(stage, "同步完成", "影响 " + affected + " 行。可以再比对一次确认。");
            compare();
        }));
    }

    private static javafx.scene.Node grow(javafx.scene.control.Control node) {
        HBox.setHgrow(node, Priority.ALWAYS);
        return node;
    }

    private static javafx.scene.control.Control fixed(javafx.scene.control.Control node,
                                                      double width) {
        node.setPrefWidth(width);
        node.setMinWidth(width);
        return node;
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null
                    : item.name() + "  ·  " + item.type().displayName());
        }
    }
}
