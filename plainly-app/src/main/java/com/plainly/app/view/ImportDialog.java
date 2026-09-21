package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.imports.CsvParser;
import com.plainly.core.imports.JsonRows;
import com.plainly.core.imports.ImportOptions;
import com.plainly.core.imports.Importer;
import com.plainly.driver.SqlScript;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.query.ConflictPolicy;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入向导。
 *
 * <p>界面上最要紧的是「数值读入方式」那一块，和导出那边的长数值处理是同一件事的两头：
 * 走文本就一位不差，走 double 就在解析阶段被截断。所以不替用户默默决定，
 * 而是先扫一遍文件，把「这份文件里有几列超过 15 位有效数字」算出来摆在他面前。
 */
public class ImportDialog {

    /** 一行映射。目标为空表示这一列不导入。 */
    public static class MappingRow {
        private final int index;
        private final String source;
        private final String sample;
        private String target;

        MappingRow(int index, String source, String sample, String target) {
            this.index = index;
            this.source = source;
            this.sample = sample;
            this.target = target;
        }

        public String source() {
            return source;
        }

        public String sample() {
            return sample;
        }

        public String target() {
            return target;
        }
    }

    private static final String NOT_IMPORTED = "（不导入）";

    private final AppContext context;
    private final DbSession session;
    private final String schema;

    /** 从库节点进来时目标表还没定，由对话框里的下拉选；从表标签页进来则一开始就定死。 */
    private final boolean pickTable;
    private String table;
    private List<ColumnInfo> targetColumns;

    private final Label targetLabel = UiUtils.label("", "hint");
    private final ComboBox<String> tableBox = new ComboBox<>();
    private final Label tableHint = UiUtils.label("", "hint");
    private final VBox tablePickerBox = new VBox(6);
    /*
     * 映射列的下拉选项要跟着目标表换，所以拿一份活的列表交给单元格，
     * 换表时改内容而不是重建 cellFactory——重建会把正在编辑的那一格连同选中状态一起扔掉。
     */
    private final ObservableList<String> targetChoices = FXCollections.observableArrayList();

    private final TextField pathField = new TextField();
    private final ToggleGroup formatGroup = new ToggleGroup();
    private final CheckBox headerBox = new CheckBox("首行为表头");
    private final ComboBox<String> delimiterBox = new ComboBox<>();
    private final ComboBox<String> charsetBox = new ComboBox<>();
    private final Label parsedLabel = UiUtils.label("", "hint");
    private final TableView<MappingRow> mappingTable = new TableView<>();
    private final ToggleGroup numberGroup = new ToggleGroup();
    private final Label numberWarning = UiUtils.label("", "hint");
    private final ToggleGroup conflictGroup = new ToggleGroup();
    private final CheckBox errorFileBox = new CheckBox("把失败行写入同目录的 .err.csv");
    private final CheckBox continueOnErrorBox =
            new CheckBox("出错时继续，末尾汇总失败的语句");
    private final VBox sqlOptionsBox = new VBox(4);
    private final TextField batchField = new TextField("2000");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = UiUtils.label("", "hint");
    private final Label summaryLabel = UiUtils.label("", "hint");
    private final VBox mappingBox = new VBox(6);

    private Stage stage;
    private Button startButton;
    private Button dryRunButton;
    private Runnable onFinished = () -> { };
    private long parsedRows;

    /** 目标表已经定了（从表标签页的「导入」进来）。 */
    public ImportDialog(AppContext context, DbSession session, String schema, String table,
                        List<ColumnInfo> targetColumns) {
        this(context, session, schema, table, targetColumns, false);
    }

    /**
     * 目标表还没定（从库的右键菜单进来）。
     *
     * <p>这里只准备列表名，不预读结构：几千张表的库，挨个 describe 会把对话框卡在打开的那一刻。
     * 等用户选中哪一张，才去读那一张的列。
     */
    public ImportDialog(AppContext context, DbSession session, String schema) {
        this(context, session, schema, null, List.of(), true);
    }

    private ImportDialog(AppContext context, DbSession session, String schema, String table,
                         List<ColumnInfo> targetColumns, boolean pickTable) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        this.targetColumns = targetColumns;
        this.pickTable = pickTable;
    }

    public void setOnFinished(Runnable handler) {
        this.onFinished = handler;
    }

    public void showAndWait(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("导入数据");

        // 正文套在滚动区里。加上「目标表」那一栏之后，整份表单比 760 高了将近两百像素，
        // 顶出去的正是最底下的「开始导入」——不报错，就是按不到。
        // 交给滚动条，窗口再矮也够得着页脚
        ScrollPane scroller = new ScrollPane(buildBody());
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("dialog-scroll");

        VBox root = new VBox(buildHead(), scroller, buildFoot());
        VBox.setVgrow(scroller, Priority.ALWAYS);
        // 放得下就一次铺开，放不下才滚——上限收在屏幕高度里，笔记本上不至于比屏幕还高
        double height = Math.min(pickTable ? 900 : 830,
                javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() - 80);
        Scene scene = new Scene(root, 980, height);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        refreshSummary();
        if (pickTable) {
            loadTables();
        }
        stage.showAndWait();
    }

    private HBox buildHead() {
        targetLabel.setText(targetText());
        HBox head = UiUtils.row(8,
                Icons.imports(Icons.ACCENT, 14),
                targetLabel);
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private String targetText() {
        return table == null ? schema + " · 还没选目标表" : schema + "." + table;
    }

    private VBox buildBody() {
        VBox body = UiUtils.column(16,
                section("源文件", buildSource()),
                section("文件格式", buildFormat()),
                mappingBox,
                section("数值读入方式", buildNumberMode()),
                section("其他选项", buildOtherOptions()));
        body.setPadding(new Insets(14, 14, 8, 14));
        VBox.setVgrow(body, Priority.ALWAYS);
        if (pickTable) {
            // 摆在最前面：先有目标表，字段映射才有东西可对
            tablePickerBox.getChildren().addAll(
                    UiUtils.label("目标表", "section-label"), buildTablePicker());
            body.getChildren().add(0, tablePickerBox);
        }

        mappingBox.getChildren().addAll(
                UiUtils.row(10, UiUtils.label("字段映射", "section-label"),
                        UiUtils.label("按名字自动匹配，可手工改", "hint"),
                        UiUtils.hSpacer(), parsedLabel),
                mappingTable);
        buildMappingTable();
        VBox.setVgrow(mappingTable, Priority.ALWAYS);
        return body;
    }

    private HBox buildTablePicker() {
        tableBox.setPrefWidth(260);
        tableBox.setPromptText("正在读取表清单…");
        tableBox.setDisable(true);
        tableBox.setOnAction(e -> selectTable(tableBox.getValue()));
        return UiUtils.row(8, tableBox, tableHint);
    }

    /**
     * 列出这个库里可以导入的表。
     *
     * <p>视图不列：往视图里写多数库直接拒绝，摆出来只是让人选中之后再挨一个错误弹窗。
     */
    private void loadTables() {
        context.queryService().submit(() -> session.tables(schema))
                .whenComplete((tables, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        tableBox.setPromptText("读不到表清单");
                        hint(tableHint, UiUtils.rootMessage(error), true);
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    tables.stream()
                            .filter(t -> t.kind() == ObjectKind.TABLE)
                            .forEach(t -> names.add(t.name()));
                    tableBox.setItems(FXCollections.observableArrayList(names));
                    tableBox.setDisable(names.isEmpty());
                    tableBox.setPromptText(names.isEmpty() ? "这个库里还没有表" : "选择目标表");
                    hint(tableHint, names.isEmpty() ? "" : names.size() + " 张表可选", false);
                }));
    }

    /** 选中一张表：读它的列，映射按新的字段名重配一遍。 */
    private void selectTable(String name) {
        if (name == null || name.equals(table)) {
            return;
        }
        hint(tableHint, "正在读取表结构…", false);
        context.queryService().submit(() -> session.structure(schema, name).columns())
                .whenComplete((columns, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        hint(tableHint, UiUtils.rootMessage(error), true);
                        return;
                    }
                    table = name;
                    targetColumns = columns;
                    targetLabel.setText(targetText());
                    refreshTargetChoices();
                    hint(tableHint, columns.size() + " 个字段", false);
                    // 换了目标表，上一张表配好的映射就不作数了，按新字段名重来一遍
                    reparse();
                }));
    }

    private void refreshTargetChoices() {
        targetChoices.setAll(NOT_IMPORTED);
        targetColumns.forEach(c -> targetChoices.add(c.name()));
    }

    private static void hint(Label label, String text, boolean error) {
        label.setText(text);
        label.getStyleClass().setAll(error ? "status-error" : "hint");
    }

    private HBox buildSource() {
        pathField.setPromptText("选择要导入的文件");
        HBox.setHgrow(pathField, Priority.ALWAYS);
        // 手敲或粘贴路径同样要解析——不是每个人都会去点「浏览」
        pathField.textProperty().addListener((o, was, is) -> reparse());
        Button browse = UiUtils.toolButton("浏览", null);
        browse.setOnAction(e -> chooseFile());
        return UiUtils.row(8, pathField, browse);
    }

    private GridPane buildFormat() {
        GridPane grid = new GridPane();
        grid.setHgap(9);
        ImportOptions.Format[] formats = ImportOptions.Format.values();
        for (int i = 0; i < formats.length; i++) {
            ImportOptions.Format f = formats[i];
            RadioButton b = new RadioButton(f.label());
            b.setToggleGroup(formatGroup);
            b.setUserData(f);
            if (f == ImportOptions.Format.CSV) {
                b.setSelected(true);
            }
            if (f == ImportOptions.Format.JSON) {
                b.setTooltip(new Tooltip("对象数组，或每行一个对象（NDJSON）。"
                        + "字段名就是表头，数字按原文读入不丢精度"));
            }
            VBox card = UiUtils.column(3, b, UiUtils.label(f.note(), "hint"));
            card.setStyle("-fx-border-color:-sx-border;-fx-border-radius:4;-fx-padding:9 11 9 11;");
            card.setMinWidth(180);
            grid.add(card, i, 0);
        }
        formatGroup.selectedToggleProperty().addListener((o, was, is) -> {
            // 分界线在「走不走字段映射」，不在「是不是 CSV」：
            // JSON 和 CSV 都是一行一条记录、映射到某张表的列上，只有 SQL 脚本不是
            boolean mapped = currentFormat() != ImportOptions.Format.SQL;
            mappingBox.setVisible(mapped);
            mappingBox.setManaged(mapped);
            // SQL 脚本整份按库执行，不落到某一张表上，目标表这一栏也就没有意义
            tablePickerBox.setVisible(mapped);
            tablePickerBox.setManaged(mapped);
            sqlOptionsBox.setVisible(!mapped);
            sqlOptionsBox.setManaged(!mapped);
            syncFormatControls();
            reparse();
        });
        return grid;
    }

    private void buildMappingTable() {
        mappingTable.setEditable(true);
        mappingTable.getStyleClass().add("data-grid");
        mappingTable.setPlaceholder(UiUtils.label(
                pickTable ? "先选目标表，再选文件" : "先选一个文件", "hint"));
        mappingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        mappingTable.setPrefHeight(220);
        // 上下都是内容，不给最小高度就会被挤成只剩一行
        mappingTable.setMinHeight(170);

        TableColumn<MappingRow, String> source = new TableColumn<>("文件列");
        source.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source()));

        TableColumn<MappingRow, String> sample = new TableColumn<>("首行样例");
        sample.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sample()));

        TableColumn<MappingRow, String> target = new TableColumn<>("目标字段");
        refreshTargetChoices();
        target.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().target() == null ? NOT_IMPORTED : c.getValue().target()));
        target.setCellFactory(c -> new ComboBoxTableCell<>(targetChoices));
        target.setOnEditCommit(e -> {
            e.getRowValue().target = NOT_IMPORTED.equals(e.getNewValue()) ? null : e.getNewValue();
            mappingTable.refresh();
            refreshSummary();
        });

        TableColumn<MappingRow, String> type = new TableColumn<>("目标类型 · 读入方式");
        type.setCellValueFactory(c -> new SimpleStringProperty(describeTarget(c.getValue())));

        mappingTable.getColumns().addAll(source, sample, target, type);
    }

    private String describeTarget(MappingRow row) {
        if (row.target() == null) {
            return "不导入 · 目标表无对应字段";
        }
        ColumnInfo info = lookup(row.target());
        if (info == null) {
            return "";
        }
        return info.nativeType() + " · " + (info.category().isNumeric() ? "BigDecimal" : "String");
    }

    private VBox buildNumberMode() {
        VBox box = new VBox(6);
        for (ImportOptions.NumberMode mode : ImportOptions.NumberMode.values()) {
            RadioButton b = new RadioButton(mode.label()
                    + (mode == ImportOptions.NumberMode.TEXT ? "（推荐）" : ""));
            b.setToggleGroup(numberGroup);
            b.setUserData(mode);
            if (mode == ImportOptions.NumberMode.TEXT) {
                b.setSelected(true);
            }
            VBox item = UiUtils.column(2, b, UiUtils.label(mode.note(), "hint"));
            box.getChildren().add(item);
        }
        box.getChildren().add(numberWarning);
        return box;
    }

    private VBox buildOtherOptions() {
        headerBox.setSelected(true);
        headerBox.setOnAction(e -> reparse());

        delimiterBox.getItems().addAll("逗号 ,", "分号 ;", "制表符 \\t", "竖线 |");
        delimiterBox.setValue("逗号 ,");
        delimiterBox.setOnAction(e -> reparse());

        charsetBox.getItems().addAll("UTF-8", "GBK", "UTF-16", "ISO-8859-1");
        charsetBox.setValue("UTF-8");
        charsetBox.setOnAction(e -> reparse());

        errorFileBox.setSelected(true);
        batchField.setPrefWidth(80);

        VBox conflicts = new VBox(4);
        conflicts.getChildren().add(UiUtils.label("主键冲突时", "form-label"));
        HBox row = new HBox(16);
        for (ConflictPolicy policy : ConflictPolicy.values()) {
            RadioButton b = new RadioButton(policy.label());
            b.setToggleGroup(conflictGroup);
            b.setUserData(policy);
            if (policy == ConflictPolicy.SKIP) {
                b.setSelected(true);
            }
            row.getChildren().add(b);
        }
        conflicts.getChildren().add(row);

        HBox first = UiUtils.row(16, headerBox,
                UiUtils.label("分隔符", "form-label"), delimiterBox,
                UiUtils.label("编码", "form-label"), charsetBox);
        HBox second = UiUtils.row(10, errorFileBox, UiUtils.hSpacer(),
                UiUtils.label("批量提交，每", "form-label"), batchField,
                UiUtils.label("行一次", "form-label"));

        // 只对 SQL 脚本有意义：一份 dump 的收尾常是 SET SQL_MODE=@OLD_SQL_MODE 这类
        // 会话记账语句，它失败并不代表数据没进去，不该把整趟导入判成失败
        sqlOptionsBox.getChildren().addAll(continueOnErrorBox,
                UiUtils.label("dump 的收尾多是 SET 会话变量，跟数据无关；"
                        + "关掉则遇到第一个错就停，已执行的不回滚", "hint"));
        sqlOptionsBox.setVisible(false);
        sqlOptionsBox.setManaged(false);
        return UiUtils.column(10, first, conflicts, second, sqlOptionsBox);
    }

    private HBox buildFoot() {
        progress.setVisible(false);
        progress.setPrefWidth(160);

        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        dryRunButton = UiUtils.toolButton("试运行", null);
        dryRunButton.setOnAction(e -> run(true));
        startButton = UiUtils.toolButton("开始导入", null, "primary");
        startButton.setOnAction(e -> run(false));

        HBox foot = UiUtils.row(8, summaryLabel, progress, progressLabel,
                UiUtils.hSpacer(), cancel, dryRunButton, startButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private VBox section(String title, javafx.scene.Node body) {
        return UiUtils.column(6, UiUtils.label(title, "section-label"), body);
    }

    // ------------------------------------------------------------------ 解析

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要导入的文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV / JSON / SQL",
                        "*.csv", "*.txt", "*.json", "*.ndjson", "*.sql"));
        File file = FileDialogs.open(stage, context.uiState(), chooser);
        if (file != null) {
            pathField.setText(file.getAbsolutePath());
            reparse();
        }
    }

    /**
     * 读文件的前几行，据此建立映射并估一估数值风险。
     *
     * <p>只读前 200 行：几百兆的文件不能为了预览整份读进来。
     */
    private void reparse() {
        String text = pathField.getText() == null ? "" : pathField.getText().trim();
        if (text.isEmpty() || currentFormat() == ImportOptions.Format.SQL) {
            mappingTable.getItems().clear();
            parsedLabel.setText("");
            refreshSummary();
            return;
        }
        Path file = Paths.get(text);
        if (!Files.isReadable(file)) {
            parsedLabel.setText("读不到这个文件");
            return;
        }

        List<List<String>> head;
        try {
            head = readHead(file);
        } catch (RuntimeException e) {
            parsedLabel.setText(UiUtils.rootMessage(e));
            return;
        }
        if (head.isEmpty()) {
            parsedLabel.setText("文件是空的");
            mappingTable.getItems().clear();
            return;
        }

        boolean hasHeader = headerRowPresent();
        List<String> names = hasHeader ? head.get(0) : null;
        List<String> sample = head.size() > (hasHeader ? 1 : 0)
                ? head.get(hasHeader ? 1 : 0) : List.of();

        List<MappingRow> rows = new ArrayList<>();
        int width = head.get(0).size();
        for (int i = 0; i < width; i++) {
            String name = names != null && i < names.size() ? names.get(i) : "第 " + (i + 1) + " 列";
            String value = i < sample.size() ? sample.get(i) : "";
            ColumnInfo matched = matchByName(name);
            rows.add(new MappingRow(i, name, value, matched == null ? null : matched.name()));
        }
        mappingTable.setItems(FXCollections.observableArrayList(rows));

        parsedRows = countRows(file);
        parsedLabel.setText("已解析 " + UiUtils.groupDigits(parsedRows) + " 行");
        warnAboutLongNumbers(head);
        refreshSummary();
    }

    private long countRows(Path file) {
        long[] count = {0};
        if (currentFormat() == ImportOptions.Format.JSON) {
            JsonRows.forEach(file, charset(), row -> count[0]++);
        } else {
            CsvParser.forEach(file, delimiter(), charset(), row -> count[0]++);
        }
        return Math.max(0, count[0] - (headerRowPresent() ? 1 : 0));
    }

    /** 前若干行，用来做映射预览。JSON 的第一行是字段名，和勾了表头的 CSV 同形。 */
    private List<List<String>> readHead(Path file) {
        return currentFormat() == ImportOptions.Format.JSON
                ? JsonRows.head(file, charset(), 200)
                : CsvParser.head(file, delimiter(), charset(), 200);
    }

    /**
     * 首行是不是字段名。
     *
     * <p>JSON 上不由用户决定：字段名写在每个对象里，读出来的第一行必然是它们。
     * 与 {@code ImportOptions.headerRowPresent()} 是同一条规则，
     * 界面这边也得按它算，否则预览会把表头当成第一条数据显示出来。
     */
    private boolean headerRowPresent() {
        return currentFormat() == ImportOptions.Format.JSON || headerBox.isSelected();
    }

    /**
     * 分隔符和「首行为表头」只对 CSV 有意义。
     *
     * <p>灰掉而不是藏起来：位置不跳，用户也看得见这两项确实存在、只是这次用不上。
     */
    private void syncFormatControls() {
        boolean csv = currentFormat() == ImportOptions.Format.CSV;
        delimiterBox.setDisable(!csv);
        headerBox.setDisable(!csv);
        if (!csv) {
            // JSON 的字段名就是表头，勾选框要如实反映这一点
            headerBox.setSelected(currentFormat() == ImportOptions.Format.JSON);
        }
    }

    /** 先算清楚这份文件里有多少列真的会被 double 毁掉，再让用户选。 */
    private void warnAboutLongNumbers(List<List<String>> head) {
        int start = headerRowPresent() ? 1 : 0;
        int width = head.get(0).size();
        int risky = 0;
        for (int col = 0; col < width; col++) {
            for (int r = start; r < head.size(); r++) {
                List<String> row = head.get(r);
                if (col < row.size() && exceedsDouble(row.get(col))) {
                    risky++;
                    break;
                }
            }
        }
        numberWarning.setText(risky == 0 ? ""
                : "这份文件的前 200 行里，有 " + risky + " 列超过 15 位有效数字——选 double 会在解析阶段就丢掉。");
        numberWarning.getStyleClass().setAll(risky == 0 ? "hint" : "status-error");
    }

    private static boolean exceedsDouble(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return new BigDecimal(value.trim()).precision() > 15;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private ColumnInfo matchByName(String name) {
        for (ColumnInfo c : targetColumns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private ColumnInfo lookup(String name) {
        return matchByName(name);
    }

    private void refreshSummary() {
        long mapped = mappingTable.getItems().stream().filter(r -> r.target() != null).count();
        boolean sql = currentFormat() == ImportOptions.Format.SQL;
        if (sql) {
            summaryLabel.setText("SQL 脚本按语句逐条执行，不走字段映射");
        } else if (table == null) {
            summaryLabel.setText("先选一个目标表");
        } else {
            summaryLabel.setText("将导入 " + UiUtils.groupDigits(parsedRows)
                    + " 行 · " + mapped + " 个字段");
        }
        if (startButton != null) {
            // SQL 脚本只认库不认表，所以它不等目标表
            boolean ready = !pathField.getText().isBlank()
                    && (sql || (table != null && mapped > 0));
            startButton.setDisable(!ready);
            dryRunButton.setDisable(!ready);
        }
    }

    // ------------------------------------------------------------------ 执行

    private void run(boolean dryRun) {
        Path file = Paths.get(pathField.getText().trim());
        if (currentFormat() == ImportOptions.Format.SQL) {
            runSqlScript(file, dryRun);
            return;
        }

        List<ImportOptions.ColumnMapping> mappings = new ArrayList<>();
        for (MappingRow row : mappingTable.getItems()) {
            mappings.add(new ImportOptions.ColumnMapping(row.index, row.source(), row.target()));
        }

        ImportOptions options = new ImportOptions()
                .setSource(file)
                .setFormat(currentFormat())
                .setHasHeader(headerRowPresent())
                .setDelimiter(delimiter())
                .setCharset(charset())
                .setNumberMode((ImportOptions.NumberMode) numberGroup.getSelectedToggle().getUserData())
                .setConflictPolicy((ConflictPolicy) conflictGroup.getSelectedToggle().getUserData())
                .setBatchSize(batchSize())
                .setWriteErrorFile(errorFileBox.isSelected())
                .setDryRun(dryRun)
                .setMappings(mappings);

        busy(true, dryRun ? "试运行中…" : "导入中…");
        context.queryService()
                .submit(() -> Importer.run(session.connection(), schema, table, targetColumns,
                        options, n -> Platform.runLater(() ->
                                progressLabel.setText("已处理 " + UiUtils.groupDigits(n) + " 行"))))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    busy(false, "");
                    if (error != null) {
                        UiUtils.showError(stage, dryRun ? "试运行失败" : "导入失败", error);
                        return;
                    }
                    report(result, dryRun);
                }));
    }

    /** 一趟脚本的战果。{@code total} 是切出来的条数，{@code done} 是真正执行成功的。 */
    private record ScriptOutcome(int total, int done, int failed, List<String> problems) {
    }

    /** SQL 脚本：切开逐条执行。这条路不经过字段映射，也没有冲突策略可言。 */
    private void runSqlScript(Path file, boolean dryRun) {
        busy(true, dryRun ? "试运行中…" : "执行中…");
        boolean keepGoing = continueOnErrorBox.isSelected();
        context.queryService().submit(() -> {
            String script = Files.readString(file, charset());
            List<String> statements = SqlScript.split(script,
                    session.config().type() == com.plainly.driver.DbType.MYSQL);
            if (dryRun) {
                return new ScriptOutcome(statements.size(), 0, 0, List.of());
            }
            session.connection().useSchema(schema);
            int done = 0;
            int failed = 0;
            List<String> problems = new ArrayList<>();
            // 一份 dump 能切出几万条语句，每条都往 FX 线程扔一次刷新，
            // 界面会被自己的进度条压住。四分之一秒报一次就够看了
            long lastTick = 0;
            for (int i = 0; i < statements.size(); i++) {
                String one = statements.get(i);
                try {
                    session.connection().execute(one, 0);
                    done++;
                } catch (RuntimeException e) {
                    failed++;
                    if (problems.size() < 5) {
                        problems.add(describeFailure(i + 1, statements.size(), one, e));
                    }
                    if (!keepGoing) {
                        // 报错里带上位置：一份几万条的 dump，光有驱动那句话找不到地方
                        throw new com.plainly.driver.DbException(
                                describeFailure(i + 1, statements.size(), one, e)
                                + "\n\n已执行 " + done + " 条，这些不会回滚。"
                                + "勾上「出错时继续」可以跳过这类语句并在末尾汇总。", e);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastTick >= 250 || i == statements.size() - 1) {
                    lastTick = now;
                    int shown = done;
                    int shownFailed = failed;
                    Platform.runLater(() -> progressLabel.setText("已执行 " + shown + " 条"
                            + (shownFailed > 0 ? " · 失败 " + shownFailed + " 条" : "")));
                }
            }
            return new ScriptOutcome(statements.size(), done, failed, problems);
        }).whenComplete((outcome, error) -> Platform.runLater(() -> {
            busy(false, "");
            if (error != null) {
                UiUtils.showError(stage, "执行脚本失败", error);
                return;
            }
            if (dryRun) {
                UiUtils.showInfo(stage, "试运行完成",
                        "脚本切出 " + outcome.total() + " 条语句，未执行。");
                return;
            }
            StringBuilder sb = new StringBuilder("已执行 ")
                    .append(outcome.done()).append(" / ").append(outcome.total()).append(" 条语句。");
            if (outcome.failed() > 0) {
                sb.append("\n失败 ").append(outcome.failed()).append(" 条")
                        .append("\n\n前几条问题：\n· ")
                        .append(String.join("\n· ", outcome.problems()));
            }
            UiUtils.showInfo(stage, outcome.failed() > 0 ? "执行完成，有失败" : "执行完成",
                    sb.toString());
            onFinished.run();
            stage.close();
        }));
    }

    /** 「第几条 / 共几条 · 语句开头 · 驱动怎么说」——三样齐了才定位得到。 */
    private static String describeFailure(int index, int total, String sql, Throwable e) {
        String head = sql.replace('\n', ' ').replace('\r', ' ').trim();
        if (head.length() > 120) {
            head = head.substring(0, 120) + "…";
        }
        return "第 " + index + " / " + total + " 条：" + head + "\n  " + UiUtils.rootMessage(e);
    }

    private void report(Importer.Result result, boolean dryRun) {
        StringBuilder sb = new StringBuilder();
        sb.append("读取 ").append(UiUtils.groupDigits(result.read())).append(" 行，")
                .append(dryRun ? "可写入 " : "写入 ").append(UiUtils.groupDigits(result.written()))
                .append(" 行");
        if (result.failed() > 0) {
            sb.append("，失败 ").append(UiUtils.groupDigits(result.failed())).append(" 行");
        }
        sb.append('。');
        if (result.errorFile() != null) {
            sb.append("\n失败行已写入 ").append(result.errorFile());
        }
        if (!result.problems().isEmpty()) {
            sb.append("\n\n前几条问题：\n· ").append(String.join("\n· ", result.problems()));
        }
        if (dryRun) {
            sb.append("\n\n试运行没有写库。");
        }
        UiUtils.showInfo(stage, dryRun ? "试运行完成" : "导入完成", sb.toString());
        if (!dryRun) {
            onFinished.run();
            stage.close();
        }
    }

    private void busy(boolean busy, String text) {
        progress.setVisible(busy);
        progress.setProgress(busy ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        progressLabel.setText(text);
        startButton.setDisable(busy);
        dryRunButton.setDisable(busy);
    }

    private ImportOptions.Format currentFormat() {
        if (formatGroup.getSelectedToggle() == null) {
            return ImportOptions.Format.CSV;
        }
        return (ImportOptions.Format) formatGroup.getSelectedToggle().getUserData();
    }

    private char delimiter() {
        String v = delimiterBox.getValue();
        if (v == null || v.startsWith("逗号")) {
            return ',';
        }
        if (v.startsWith("分号")) {
            return ';';
        }
        if (v.startsWith("制表符")) {
            return '\t';
        }
        return '|';
    }

    private Charset charset() {
        try {
            return Charset.forName(charsetBox.getValue());
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private int batchSize() {
        try {
            return Integer.parseInt(batchField.getText().trim());
        } catch (NumberFormatException e) {
            return 2000;
        }
    }
}
