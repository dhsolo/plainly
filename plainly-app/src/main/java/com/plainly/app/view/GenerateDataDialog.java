package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.generate.DataGenerator;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.converter.DefaultStringConverter;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成测试数据。
 *
 * <p>数值按列的 precision / scale 造满位数，不走浮点——用 {@code nextDouble()} 造出来的
 * {@code DECIMAL(38,10)} 只有十五位有效数字，拿这种数据测精度什么都测不出来。
 */
public class GenerateDataDialog {

    /** 一列的规则，界面上可改。 */
    public static class RuleRow {
        private final ColumnInfo column;
        private DataGenerator.Strategy strategy = DataGenerator.Strategy.AUTO;
        private String fixedValue = "";

        RuleRow(ColumnInfo column) {
            this.column = column;
        }

        public String name() {
            return column.name();
        }

        public String type() {
            return column.nativeType()
                    + (column.precision() > 0 && column.scale() > 0
                    ? "(" + column.precision() + "," + column.scale() + ")" : "");
        }
    }

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;
    private final List<ColumnInfo> columns;

    private final TableView<RuleRow> ruleTable = new TableView<>();
    private final TableView<List<String>> previewTable = new TableView<>();
    private final TextField rowsField = new TextField("1000");
    private final TextField seedField = new TextField("1");
    private final Label summary = UiUtils.label("", "hint");

    private Stage stage;
    private Runnable onFinished = () -> { };

    public GenerateDataDialog(AppContext context, DbSession session, String schema,
                              String table, List<ColumnInfo> columns) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        this.columns = columns;
    }

    public void setOnFinished(Runnable handler) {
        this.onFinished = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("生成测试数据");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 940, 660);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        List<RuleRow> rows = new ArrayList<>();
        columns.forEach(c -> rows.add(new RuleRow(c)));
        ruleTable.setItems(FXCollections.observableArrayList(rows));
        refreshPreview();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.plus(Icons.ACCENT, 14),
                UiUtils.label(schema + "." + table, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        buildRuleTable();
        buildPreviewTable();

        rowsField.setPrefWidth(90);
        seedField.setPrefWidth(70);
        Button refresh = UiUtils.toolButton("刷新预览", Icons.refresh(Icons.NEUTRAL, 11));
        refresh.setOnAction(e -> refreshPreview());

        HBox controls = UiUtils.row(10,
                UiUtils.label("生成", "form-label"), rowsField,
                UiUtils.label("行", "form-label"), UiUtils.vSeparator(),
                UiUtils.label("随机种子", "form-label"), seedField,
                UiUtils.label("同一个种子造出同一批数据，便于复现", "hint"),
                UiUtils.hSpacer(), refresh);

        VBox box = UiUtils.column(8,
                UiUtils.label("每列怎么造", "section-label"), ruleTable,
                controls,
                UiUtils.label("前几行预览", "section-label"), previewTable);
        box.setPadding(new Insets(12));
        VBox.setVgrow(ruleTable, Priority.ALWAYS);
        previewTable.setPrefHeight(180);
        return box;
    }

    private void buildRuleTable() {
        ruleTable.setEditable(true);
        ruleTable.getStyleClass().add("data-grid");
        ruleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<RuleRow, String> name = new TableColumn<>("字段");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));

        TableColumn<RuleRow, String> type = new TableColumn<>("类型");
        type.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type()));

        TableColumn<RuleRow, String> strategy = new TableColumn<>("生成方式");
        strategy.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().strategy.label()));
        strategy.setCellFactory(c -> new ComboBoxTableCell<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(DataGenerator.Strategy.values())
                        .map(DataGenerator.Strategy::label).toList())));
        strategy.setOnEditCommit(e -> {
            for (DataGenerator.Strategy s : DataGenerator.Strategy.values()) {
                if (s.label().equals(e.getNewValue())) {
                    e.getRowValue().strategy = s;
                    break;
                }
            }
            refreshPreview();
        });

        TableColumn<RuleRow, String> fixed = new TableColumn<>("固定值");
        fixed.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fixedValue));
        fixed.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()));
        fixed.setOnEditCommit(e -> {
            e.getRowValue().fixedValue = e.getNewValue();
            refreshPreview();
        });

        ruleTable.getColumns().addAll(name, type, strategy, fixed);
    }

    private void buildPreviewTable() {
        previewTable.getStyleClass().add("data-grid");
        previewTable.setPlaceholder(UiUtils.label("点「刷新预览」看看会造出什么", "hint"));
        for (int i = 0; i < columns.size(); i++) {
            int index = i;
            TableColumn<List<String>, String> col = new TableColumn<>(columns.get(i).name());
            col.setCellValueFactory(c -> new SimpleStringProperty(
                    index < c.getValue().size() ? c.getValue().get(index) : ""));
            previewTable.getColumns().add(col);
        }
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        Button run = UiUtils.toolButton("开始生成", null, "primary");
        run.setOnAction(e -> generate());

        HBox foot = UiUtils.row(8, summary, UiUtils.hSpacer(), cancel, run);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private List<DataGenerator.Rule> rules() {
        List<DataGenerator.Rule> out = new ArrayList<>();
        for (RuleRow row : ruleTable.getItems()) {
            out.add(new DataGenerator.Rule(row.name(), row.strategy, row.fixedValue));
        }
        return out;
    }

    private void refreshPreview() {
        previewTable.setItems(FXCollections.observableArrayList(
                DataGenerator.preview(columns, rules(), 5, seed())));
        ruleTable.refresh();
    }

    private void generate() {
        int rows = parse(rowsField.getText(), 1000);
        if (!UiUtils.confirm(stage, "生成数据",
                "将往 " + schema + "." + table + " 写入 " + UiUtils.groupDigits(rows) + " 行。")) {
            return;
        }
        summary.setText("生成中…");
        context.queryService().submit(() -> DataGenerator.generate(
                        session.connection(), schema, table, columns, rules(), rows, 500,
                        seed(), n -> Platform.runLater(() ->
                                summary.setText("已写入 " + UiUtils.groupDigits(n) + " 行"))))
                .whenComplete((written, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        summary.setText("生成失败");
                        UiUtils.showError(stage, "生成失败", error);
                        return;
                    }
                    onFinished.run();
                    UiUtils.showInfo(stage, "生成完成",
                            "写入 " + UiUtils.groupDigits(written) + " 行。");
                    stage.close();
                }));
    }

    private long seed() {
        return parse(seedField.getText(), 1);
    }

    private static int parse(String text, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(text.trim()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
