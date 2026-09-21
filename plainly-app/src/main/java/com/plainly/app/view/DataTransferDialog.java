package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.transfer.TransferService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
 * 数据传输：跨连接、跨数据库类型搬运表结构与数据。
 *
 * <p>界面上最要紧的是「类型映射」那一栏。跨库映射错了不会报错——
 * {@code BIGINT UNSIGNED} 映射成 {@code BIGINT} 语法完全合法，只在某一行的值超过
 * {@code 9223372036854775807} 时才溢出。所以每一列的目标类型和换类型的原因都摆出来，
 * 让人在按下开始之前有机会核对。
 */
public class DataTransferDialog {

    /** 表列表里的一行。 */
    public static class TableRow {
        private final String name;
        private final long rows;
        private boolean picked;

        TableRow(String name, long rows) {
            this.name = name;
            this.rows = rows;
        }

        public String name() {
            return name;
        }

        public long rows() {
            return rows;
        }
    }

    private final AppContext context;

    private final ComboBox<ConnectionConfig> sourceConn = new ComboBox<>();
    private final ComboBox<String> sourceSchema = new ComboBox<>();
    private final ComboBox<ConnectionConfig> targetConn = new ComboBox<>();
    private final ComboBox<String> targetSchema = new ComboBox<>();

    private final TableView<TableRow> tables = new TableView<>();
    private final TableView<TransferService.ColumnPlan> mappings = new TableView<>();
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final TextField batchField = new TextField("2000");
    private final CheckBox verifyBox = new CheckBox("传输后逐表比对行数");
    private final Label summary = UiUtils.label("", "hint");
    private final Label mappingHint = UiUtils.label("选中一张表查看它的类型映射", "hint");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = UiUtils.label("", "hint");

    private final Button startButton = UiUtils.toolButton("开始传输", null, "primary");
    private Stage stage;

    public DataTransferDialog(AppContext context) {
        this.context = context;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("数据传输");

        VBox root = new VBox(buildHead(), buildEndpoints(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1120, 780);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        List<ConnectionConfig> all = context.registry().listAll();
        sourceConn.setItems(FXCollections.observableArrayList(all));
        targetConn.setItems(FXCollections.observableArrayList(all));
        refreshSummary();
        stage.show();
    }

    // ------------------------------------------------------------------ 顶部

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.export(Icons.ACCENT, 14),
                UiUtils.label("跨连接、跨数据库类型搬运表结构与数据", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildEndpoints() {
        configure(sourceConn, sourceSchema, true);
        configure(targetConn, targetSchema, false);

        VBox source = UiUtils.column(4, UiUtils.label("源", "section-label"),
                UiUtils.row(6, grow(sourceConn), fixed(sourceSchema, 180)));
        VBox target = UiUtils.column(4, UiUtils.label("目标 · 将被写入", "section-label"),
                UiUtils.row(6, grow(targetConn), fixed(targetSchema, 180)));
        targetConn.setStyle("-fx-border-color:-sx-red-border;");

        HBox.setHgrow(source, Priority.ALWAYS);
        HBox.setHgrow(target, Priority.ALWAYS);
        HBox bar = UiUtils.row(12, source, Icons.export(Icons.ACCENT, 18), target);
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setPadding(new Insets(12, 14, 12, 14));
        bar.setStyle("-fx-border-color: transparent transparent -sx-border-light transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return bar;
    }

    private void configure(ComboBox<ConnectionConfig> conn, ComboBox<String> schema,
                           boolean isSource) {
        conn.setCellFactory(v -> new ConnectionCell());
        conn.setButtonCell(new ConnectionCell());
        conn.setMaxWidth(Double.MAX_VALUE);
        schema.setMaxWidth(Double.MAX_VALUE);
        conn.valueProperty().addListener((o, was, is) -> loadSchemas(is, schema, isSource));
        schema.valueProperty().addListener((o, was, is) -> {
            if (isSource) {
                loadTables();
            } else {
                refreshSummary();
            }
        });
    }

    private void loadSchemas(ConnectionConfig config, ComboBox<String> box, boolean isSource) {
        box.getItems().clear();
        if (config == null) {
            return;
        }
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return session.schemas();
        }).whenComplete((schemas, error) -> Platform.runLater(() -> {
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

    // ------------------------------------------------------------------ 主体

    private SplitPane buildBody() {
        buildTableList();
        buildMappingTable();

        Button all = UiUtils.toolButton("全选", null);
        all.setOnAction(e -> {
            tables.getItems().forEach(r -> r.picked = true);
            tables.refresh();
            refreshSummary();
        });
        Button none = UiUtils.toolButton("全不选", null);
        none.setOnAction(e -> {
            tables.getItems().forEach(r -> r.picked = false);
            tables.refresh();
            refreshSummary();
        });

        VBox left = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("传输对象", "section-label"), all, none),
                tables);
        left.setPadding(new Insets(10));
        VBox.setVgrow(tables, Priority.ALWAYS);

        VBox right = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("类型映射", "section-label"), mappingHint),
                mappings, buildOptions());
        right.setPadding(new Insets(10));
        VBox.setVgrow(mappings, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void buildTableList() {
        tables.getStyleClass().add("data-grid");
        tables.setPlaceholder(UiUtils.label("先选源连接与库", "hint"));
        tables.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<TableRow, Boolean> pick = new TableColumn<>("");
        pick.setMaxWidth(46);
        pick.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().picked));
        pick.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    TableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        row.picked = box.isSelected();
                        refreshSummary();
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

        TableColumn<TableRow, String> name = new TableColumn<>("表");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));

        TableColumn<TableRow, String> rows = new TableColumn<>("行数");
        rows.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().rows() < 0 ? "—" : UiUtils.groupDigits(c.getValue().rows())));

        tables.getColumns().addAll(pick, name, rows);
        tables.getSelectionModel().selectedItemProperty().addListener(
                (o, was, is) -> loadMapping(is));
    }

    private void buildMappingTable() {
        mappings.getStyleClass().add("data-grid");
        mappings.setPlaceholder(UiUtils.label("选中一张表", "hint"));
        mappings.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<TransferService.ColumnPlan, String> column = new TableColumn<>("列");
        column.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source().name()));

        TableColumn<TransferService.ColumnPlan, String> from = new TableColumn<>("源类型");
        from.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().source().nativeType()));

        TableColumn<TransferService.ColumnPlan, String> to = new TableColumn<>("目标类型");
        to.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().targetType()));

        TableColumn<TransferService.ColumnPlan, String> note = new TableColumn<>("说明");
        note.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().exact() ? "精确对应" : "已避损 · " + c.getValue().reason()));

        mappings.getColumns().addAll(column, from, to, note);
    }

    private VBox buildOptions() {
        VBox modes = new VBox(4);
        modes.getChildren().add(UiUtils.label("目标已存在时", "section-label"));
        for (TransferService.TargetMode mode : TransferService.TargetMode.values()) {
            RadioButton b = new RadioButton(mode.label());
            b.setToggleGroup(modeGroup);
            b.setUserData(mode);
            if (mode == TransferService.TargetMode.CREATE) {
                b.setSelected(true);
            }
            modes.getChildren().add(b);
        }

        batchField.setPrefWidth(80);
        verifyBox.setSelected(true);
        HBox exec = UiUtils.row(10,
                UiUtils.label("批量提交，每", "form-label"), batchField,
                UiUtils.label("行一次", "form-label"),
                UiUtils.vSeparator(), verifyBox);

        return UiUtils.column(10, modes,
                UiUtils.column(4, UiUtils.label("执行方式", "section-label"), exec));
    }

    private HBox buildFoot() {
        progress.setVisible(false);
        progress.setPrefWidth(160);

        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        startButton.setOnAction(e -> start());

        HBox foot = UiUtils.row(8, summary, progress, progressLabel,
                UiUtils.hSpacer(), cancel, startButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 装载

    private void loadTables() {
        ConnectionConfig config = sourceConn.getValue();
        String schema = sourceSchema.getValue();
        if (config == null || schema == null) {
            return;
        }
        tables.setPlaceholder(UiUtils.label("正在读取…", "hint"));
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return session.tables(schema);
        }).whenComplete((list, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "读取表清单失败", error);
                return;
            }
            List<TableRow> rows = new ArrayList<>();
            for (TableInfo t : list) {
                rows.add(new TableRow(t.name(), t.rowEstimate()));
            }
            tables.setItems(FXCollections.observableArrayList(rows));
            refreshSummary();
        }));
    }

    private void loadMapping(TableRow row) {
        if (row == null || targetConn.getValue() == null || targetSchema.getValue() == null) {
            mappings.getItems().clear();
            mappingHint.setText(row == null ? "选中一张表查看它的类型映射" : "先选好目标连接与库");
            return;
        }
        ConnectionConfig sourceCfg = sourceConn.getValue();
        String sourceDb = sourceSchema.getValue();
        ConnectionConfig targetCfg = targetConn.getValue();
        String targetDb = targetSchema.getValue();

        context.queryService().submit(() -> {
            DbConnection src = context.openSession(sourceCfg).connection();
            DbConnection dst = context.openSession(targetCfg).connection();
            return TransferService.plan(src, sourceDb, row.name(), dst, targetDb);
        }).whenComplete((plan, error) -> Platform.runLater(() -> {
            if (error != null) {
                mappingHint.setText(UiUtils.rootMessage(error));
                return;
            }
            mappings.setItems(FXCollections.observableArrayList(plan.columns()));
            long widened = plan.columns().stream().filter(c -> !c.exact()).count();
            mappingHint.setText(widened == 0
                    ? row.name() + " · 全部精确对应"
                    : row.name() + " · " + widened + " 列换了类型以免损失，见下方说明");
        }));
    }

    private void refreshSummary() {
        long picked = tables.getItems().stream().filter(r -> r.picked).count();
        long rows = tables.getItems().stream().filter(r -> r.picked)
                .mapToLong(r -> Math.max(0, r.rows())).sum();
        summary.setText(picked == 0 ? "还没选要传的表"
                : "预计 " + UiUtils.groupDigits(rows) + " 行 · 游标流式读取，内存占用与行数无关");
        startButton.setDisable(picked == 0 || targetConn.getValue() == null
                || targetSchema.getValue() == null);
    }

    // ------------------------------------------------------------------ 执行

    private void start() {
        Set<String> picked = new LinkedHashSet<>();
        tables.getItems().stream().filter(r -> r.picked).forEach(r -> picked.add(r.name()));
        if (picked.isEmpty()) {
            return;
        }

        ConnectionConfig sourceCfg = sourceConn.getValue();
        String sourceDb = sourceSchema.getValue();
        ConnectionConfig targetCfg = targetConn.getValue();
        String targetDb = targetSchema.getValue();
        TransferService.TargetMode mode =
                (TransferService.TargetMode) modeGroup.getSelectedToggle().getUserData();
        int batch = batchSize();
        boolean verify = verifyBox.isSelected();

        String prompt = "将把 " + picked.size() + " 张表写入 "
                + targetCfg.name() + " / " + targetDb + "。\n\n"
                + (mode == TransferService.TargetMode.TRUNCATE
                ? "所选模式会先清空目标表里已有的数据。" : "目标表不存在时会自动建表。");
        if (!UiUtils.confirm(stage, "开始传输", prompt)) {
            return;
        }

        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        startButton.setDisable(true);

        context.queryService().submit(() -> {
            DbConnection src = context.openSession(sourceCfg).connection();
            DbConnection dst = context.openSession(targetCfg).connection();
            List<String> report = new ArrayList<>();
            for (String table : picked) {
                Platform.runLater(() -> progressLabel.setText("正在传输 " + table + " …"));
                TransferService.TablePlan plan =
                        TransferService.plan(src, sourceDb, table, dst, targetDb);
                TransferService.Outcome outcome = TransferService.transfer(
                        src, sourceDb, plan, dst, targetDb, mode, batch,
                        n -> Platform.runLater(() -> progressLabel.setText(
                                table + " · 已搬 " + UiUtils.groupDigits(n) + " 行")));
                if (!outcome.ok()) {
                    report.add(table + " 失败：" + outcome.problem());
                    // 一张表失败就停：继续搬只会让「哪些搬成了」变得更难说清
                    break;
                }
                String line = table + " · " + UiUtils.groupDigits(outcome.read()) + " 行";
                if (verify) {
                    line += " · 比对 " + TransferService.verify(src, sourceDb, dst, targetDb, table);
                }
                report.add(line);
            }
            return report;
        }).whenComplete((report, error) -> Platform.runLater(() -> {
            progress.setVisible(false);
            progressLabel.setText("");
            startButton.setDisable(false);
            if (error != null) {
                UiUtils.showError(stage, "传输失败", error);
                return;
            }
            UiUtils.showInfo(stage, "传输完成", String.join("\n", report));
        }));
    }

    private int batchSize() {
        try {
            return Math.max(1, Integer.parseInt(batchField.getText().trim()));
        } catch (NumberFormatException e) {
            return 2000;
        }
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

    /** 下拉里显示连接名与类型，光有名字分不清是哪一种库。 */
    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null
                    : item.name() + "  ·  " + item.type().displayName());
        }
    }
}
