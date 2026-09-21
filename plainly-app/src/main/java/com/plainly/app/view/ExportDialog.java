package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.time.LocalDateTime;
import javafx.scene.control.Tooltip;

/**
 * 导出对话框。
 *
 * <p>界面上最要紧的是「长数值处理」那一块：xlsx 的数值单元格底层就是 IEEE 754 双精度，
 * 这是文件格式本身的限制，跟用什么语言写无关。所以不替用户默默决定，
 * 而是把损失量按当前数据实算出来摆在他面前。
 */
public class ExportDialog {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String tableName;
    private final QueryResult current;
    private final long totalRows;

    private final ExportOptions options = new ExportOptions();
    private final ToggleGroup rangeGroup = new ToggleGroup();
    private final ToggleGroup formatGroup = new ToggleGroup();
    private final ToggleGroup precisionGroup = new ToggleGroup();
    private final CheckBox headerBox = new CheckBox("包含表头");
    private final CheckBox nullEmptyBox = new CheckBox("NULL 写为空单元格");
    private final CheckBox ddlBox = new CheckBox("同时导出建表语句");
    private final TextField pathField = new TextField();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = UiUtils.label("", "hint");
    private final VBox precisionPanel = new VBox();

    private RadioButton rangeAll;
    /** 「重新执行，导出全部」。只在导出查询结果时存在，导出表时为 null。 */
    private RadioButton rerunQuery;
    /** 底部那句「这次怎么取数」。跟着导出范围变。 */
    private final Label rangeHint = new Label();
    private Stage stage;

    /**
     * 这条连接有没有「表」这回事。
     *
     * <p>Redis 上没有：那一层是按键名前缀分出来的组。整个对话框里有四处
     * 要跟着变——范围的说法、格式里的 SQL INSERT、建表语句那个勾，
     * 以及值这一列到底是完整的还是预览。
     */
    private final boolean structural;

    /**
     * 导出的是不是一张实际的表。
     *
     * <p>查询结果没有对应的表：整表扫描无从谈起，建表语句也无从生成。
     * 这两件事在界面上要禁掉并说明原因，而不是等点了导出再报错。
     */
    private final boolean fromTable;

    /**
     * 这份结果背后那条语句，用于「重新执行，导出全部」。
     *
     * <p>取自 {@link QueryResult#statement()}——结果自己就带着它，不必另外传。
     * 导出表时用不上（那边走的是分页扫表）。
     */
    private final String querySql;

    /** 表数据导出。 */
    public ExportDialog(AppContext context, DbSession session, String schema,
                        String tableName, QueryResult current, long totalRows) {
        this(context, session, schema, tableName, current, totalRows, true);
    }

    /** 查询结果导出。 */
    public static ExportDialog forQuery(AppContext context, DbSession session,
                                        String schema, QueryResult result) {
        return new ExportDialog(context, session, schema, "query_result",
                result, result.rows().size(), false);
    }

    private ExportDialog(AppContext context, DbSession session, String schema,
                         String tableName, QueryResult current, long totalRows,
                         boolean fromTable) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.tableName = tableName;
        this.current = current;
        this.totalRows = totalRows;
        this.fromTable = fromTable;
        this.querySql = current == null ? null : current.statement();
        this.structural = session.connection().dialect().hasTableStructure();
    }

    public void showAndWait(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("导出数据");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 720, 700);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.export(Icons.ACCENT, 14),
                UiUtils.label(structural
                        ? schema + "." + tableName
                        : schema + " · 键空间 " + tableName, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        VBox body = new VBox(18,
                section("导出范围", buildRange()),
                section("文件格式", buildFormat()),
                buildPrecisionPanel(),
                section("其他选项", buildOtherOptions()),
                section("输出到", buildPathRow()));
        if (!structural) {
            body.getChildren().add(1, keyValueNote());
        }
        body.setPadding(new Insets(18, 20, 18, 20));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    /**
     * 键值库的一句说明。
     *
     * <p>非说不可，因为<b>导出的内容和网格里看到的不一样</b>：网格那一列叫「值预览」，
     * 字符串只取前 200 字节、集合只取前 5 个元素——那是为了让一屏两百行能刷出来。
     * 导出会重新去读完整的值。不说的话，用户对着文件会以为多出来的东西是哪儿来的；
     * 反过来，要是导出真照着预览写，那才是最坏的结果：一份看着完整、其实被截断的文件。
     */
    private javafx.scene.Node keyValueNote() {
        Label text = UiUtils.label("导出会重新读取<b>完整的值</b>，不是网格里那一列预览："
                .replace("<b>", "").replace("</b>", "")
                + "字符串取全文，哈希 / 列表 / 集合写成 JSON。"
                + "单个集合超过 5 万个元素的键会被跳过并在该格注明——"
                + "截一半写进去，文件是看不出来的。", "hint");
        text.setWrapText(true);
        VBox box = new VBox(text);
        box.setStyle("-fx-background-color:-sx-green-bg;-fx-background-radius:4;"
                + "-fx-padding:9 12 9 12;");
        return box;
    }

    private VBox section(String title, javafx.scene.Node content) {
        return UiUtils.column(9, UiUtils.label(title, "section-label"), content);
    }

    private HBox buildRange() {
        rangeAll = radio(structural ? "全表" : "全部键", rangeGroup,
                totalRows >= 0
                        ? UiUtils.groupDigits(totalRows) + (structural ? " 行" : " 个键")
                        : "数量未知");
        RadioButton page = radio(fromTable ? "当前页" : "当前结果",
                rangeGroup, current.rows().size() + " 行");
        page.setSelected(true);

        if (fromTable) {
            // 用 radio() 包好的那个 box，不是 RadioButton 本身——
            // 「N 行 / N 个键」的提示挂在 box 里。原来这里直接放 RadioButton，
            // 提示做出来了却从没进过场景，所以「全表」旁边一直是空的
            return UiUtils.row(22, wrapperOf(rangeAll), wrapperOf(page));
        }

        /*
         * 查询结果背后没有一张可以再扫一遍的表，但有那条语句本身。
         *
         * 网格里这份结果是按「限制行数」截过的。原来这里只能导出已取回的那些行——
         * 用户想导两百万行，拿到一千行，而且多半不会去数。现在多给一条路：
         * 重新执行同一条语句，开游标逐行写出去，内存占用与结果集大小无关。
         *
         * 代价必须说清楚：语句要再跑一遍，而且两次之间数据可能已经变了。
         */
        rangeAll.setDisable(true);
        rangeAll.setVisible(false);
        rangeAll.setManaged(false);
        wrapperOf(rangeAll).setVisible(false);
        wrapperOf(rangeAll).setManaged(false);

        rerunQuery = radio("重新执行，导出全部",
                rangeGroup, current.truncated() ? "屏幕上这份被行数上限截过" : "不受行数上限限制");
        rerunQuery.setDisable(querySql == null || querySql.isBlank());
        rerunQuery.setTooltip(new Tooltip(
                "把这条语句再执行一遍并逐行写出，不受「限制行数」约束。"
                        + System.lineSeparator()
                        + "两次执行之间数据可能已经变了，导出的是此刻的结果。"));
        return UiUtils.row(22, wrapperOf(page), wrapperOf(rerunQuery));
    }

    private static HBox wrapperOf(RadioButton b) {
        return (HBox) b.getUserData();
    }

    private RadioButton radio(String text, ToggleGroup group, String hint) {
        RadioButton b = new RadioButton(text);
        b.setToggleGroup(group);
        HBox box = UiUtils.row(6, b, UiUtils.label(hint, "hint"));
        box.setAlignment(Pos.CENTER_LEFT);
        // 返回 RadioButton 本身（调用方要用它判断选中），外面那个 box 挂在
        // userData 上，由 wrapperOf 取出来放进场景
        b.setUserData(box);
        return b;
    }

    private GridPane buildFormat() {
        GridPane grid = new GridPane();
        grid.setHgap(9);
        // 键值库上不给 SQL INSERT：它会拼出 INSERT INTO db0.user (键, 类型, TTL…)，
        // 那不是任何地方能灌回去的东西——既不是 Redis 的，也不是哪张表的
        ExportOptions.Format[] formats = structural
                ? ExportOptions.Format.values()
                : java.util.Arrays.stream(ExportOptions.Format.values())
                        .filter(f -> f != ExportOptions.Format.SQL_INSERT)
                        .toArray(ExportOptions.Format[]::new);
        for (int i = 0; i < formats.length; i++) {
            ExportOptions.Format f = formats[i];
            RadioButton b = new RadioButton(f.label());
            b.setToggleGroup(formatGroup);
            b.setUserData(f);
            if (f == ExportOptions.Format.CSV) {
                b.setSelected(true);
            }
            VBox card = UiUtils.column(3, b, UiUtils.label(f.note(), "hint"));
            card.setStyle("-fx-border-color:-sx-border;-fx-border-radius:4;-fx-padding:9 11 9 11;");
            card.setMinWidth(160);
            grid.add(card, i, 0);
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(100.0 / formats.length);
            grid.getColumnConstraints().add(cc);
        }
        formatGroup.selectedToggleProperty().addListener((o, was, is) -> {
            updatePrecisionPanel();
            syncPathExtension();
            updateDdlBox();
        });
        return grid;
    }

    private HBox buildOtherOptions() {
        headerBox.setSelected(true);
        nullEmptyBox.setSelected(true);
        updateDdlBox();
        return UiUtils.row(20, headerBox, nullEmptyBox, ddlBox);
    }

    /**
     * 「同时导出建表语句」只在 SQL INSERT 下成立。
     *
     * <p>CSV / xlsx / JSON 里没有放 DDL 的位置，硬塞进去只会让文件既不是数据也不是脚本。
     * 不能用的时候禁掉并说清楚为什么，而不是让它看着能点。
     */
    private void updateDdlBox() {
        String reason = null;
        if (!structural) {
            // 不拦的话，勾上再导会走到 dialect.createTableDdl，
            // 那条路在 Redis 上是直接抛异常的
            reason = "Redis 没有列定义，也就没有建表语句";
        } else if (!fromTable) {
            reason = "查询结果不是一张实际的表，没有建表语句可导";
        } else if (currentFormat() != ExportOptions.Format.SQL_INSERT) {
            reason = "只有 SQL INSERT 格式能容纳建表语句";
        }
        ddlBox.setDisable(reason != null);
        ddlBox.setTooltip(new Tooltip(reason != null ? reason
                : "在 INSERT 之前写入 CREATE TABLE 与索引，整个文件可以直接建库回灌"));
        if (reason != null) {
            ddlBox.setSelected(false);
        }
    }

    /**
     * 建表语句连同索引。
     *
     * <p>只出 CREATE，不出 DROP：这个文件很可能被人直接拿去跑，
     * 建表撞名失败最多是白跑一趟，DROP 撞名可是把人家的数据删了。
     */
    private String buildDdl() {
        TableStructure structure = session.structure(schema, tableName);
        SqlDialect dialect = session.connection().dialect();
        StringBuilder sb = new StringBuilder();
        sb.append("-- Plainly 导出于 ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append('\n');
        sb.append("-- 表：").append(dialect.qualify(schema, tableName)).append('\n');
        sb.append(dialect.createTableDdl(schema, structure)).append(";\n");
        for (IndexInfo index : structure.indexes()) {
            if (index.primary()) {
                continue;
            }
            sb.append(dialect.createIndexDdl(schema, tableName, index)).append(";\n");
        }
        return sb.toString();
    }

    private HBox buildPathRow() {
        pathField.setText(defaultPath().toString());
        HBox.setHgrow(pathField, Priority.ALWAYS);
        Button browse = UiUtils.toolButton("浏览", null);
        browse.setOnAction(e -> chooseTarget());
        return UiUtils.row(8, pathField, browse);
    }

    private Path defaultPath() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String home = System.getProperty("user.home");
        return Paths.get(home, "Documents", tableName + "_" + date + "." + currentFormat().extension());
    }

    private ExportOptions.Format currentFormat() {
        if (formatGroup.getSelectedToggle() == null) {
            return ExportOptions.Format.CSV;
        }
        return (ExportOptions.Format) formatGroup.getSelectedToggle().getUserData();
    }

    /**
     * 换格式时把路径后缀跟着换掉。
     *
     * <p>只动最后一段的后缀：目录和文件名可能已经被用户改过，那部分要原样留着。
     * 不走 {@code Paths.get} —— 输入框里随时可能是一条写到一半的非法路径，
     * 解析失败会直接抛异常，而这里只是想换个后缀。
     */
    private void syncPathExtension() {
        String text = pathField.getText() == null ? "" : pathField.getText().trim();
        if (text.isEmpty()) {
            pathField.setText(defaultPath().toString());
            return;
        }
        int slash = Math.max(text.lastIndexOf('\\'), text.lastIndexOf('/'));
        int dot = text.lastIndexOf('.');
        String stem = dot > slash ? text.substring(0, dot) : text;
        pathField.setText(stem + "." + currentFormat().extension());
    }

    private void chooseTarget() {
        ExportOptions.Format format = currentFormat();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出到");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                format.label(), "*." + format.extension()));

        // 起始目录取输入框里那个，省得每次都从系统默认目录翻起
        String current = pathField.getText() == null ? "" : pathField.getText().trim();
        int slash = Math.max(current.lastIndexOf('\\'), current.lastIndexOf('/'));
        if (slash > 0) {
            File dir = new File(current.substring(0, slash));
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
            chooser.setInitialFileName(current.substring(slash + 1));
        } else if (!current.isEmpty()) {
            chooser.setInitialFileName(current);
        }

        File file = FileDialogs.save(stage, context.uiState(), chooser);
        if (file != null) {
            pathField.setText(file.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------- 长数值处理

    private VBox buildPrecisionPanel() {
        precisionPanel.getStyleClass().add("precision-panel");
        updatePrecisionPanel();
        return precisionPanel;
    }

    private void updatePrecisionPanel() {
        precisionPanel.getChildren().clear();

        List<ColumnMeta> exactColumns = Exporters.exactNumericColumns(current.columns());
        boolean formatIsLossy = currentFormat() == ExportOptions.Format.XLSX;

        if (exactColumns.isEmpty() || !formatIsLossy) {
            precisionPanel.setVisible(false);
            precisionPanel.setManaged(false);
            return;
        }
        precisionPanel.setVisible(true);
        precisionPanel.setManaged(true);

        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < exactColumns.size() && i < 4; i++) {
            if (i > 0) {
                cols.append(" · ");
            }
            cols.append(exactColumns.get(i).name()).append(' ')
                    .append(exactColumns.get(i).displayType());
        }

        HBox head = UiUtils.row(9,
                Icons.warn("#a8781a", 15),
                UiUtils.label("长数值处理", "precision-title"),
                UiUtils.label("检测到 " + exactColumns.size() + " 个精确数值列", "hint"),
                UiUtils.hSpacer(),
                UiUtils.label(cols.toString(), "mono", "hint"));
        head.getStyleClass().add("precision-head");

        RadioButton asText = new RadioButton("写为文本单元格");
        asText.setToggleGroup(precisionGroup);
        asText.setUserData(ExportOptions.LongNumberMode.TEXT);
        asText.setSelected(true);
        RadioButton asNumber = new RadioButton("写为数值单元格");
        asNumber.setToggleGroup(precisionGroup);
        asNumber.setUserData(ExportOptions.LongNumberMode.NUMERIC);

        VBox textOption = UiUtils.column(2,
                UiUtils.row(7, asText, UiUtils.label("推荐", "status-ok")),
                UiUtils.label(ExportOptions.LongNumberMode.TEXT.note(), "hint"));
        VBox numberOption = UiUtils.column(2, asNumber,
                UiUtils.label(ExportOptions.LongNumberMode.NUMERIC.note(), "hint"));

        VBox body = UiUtils.column(10, textOption, numberOption, buildComparison(exactColumns));
        body.getStyleClass().add("precision-body");

        precisionPanel.getChildren().addAll(head, body);
    }

    /** 用结果集里真实的长数值算一遍两种写法的差别，而不是拿个假例子演示。 */
    private VBox buildComparison(List<ColumnMeta> exactColumns) {
        String sample = findLongSample(exactColumns);
        if (sample == null) {
            VBox box = new VBox(UiUtils.label(
                    "当前数据中没有超过 15 位有效数字的值，两种写法结果相同。", "hint"));
            box.setPadding(new Insets(6, 0, 0, 0));
            return box;
        }

        String viaDouble;
        try {
            viaDouble = new BigDecimal(String.valueOf(Double.parseDouble(sample))).toPlainString();
        } catch (NumberFormatException e) {
            viaDouble = sample;
        }
        int common = commonPrefixLength(sample, viaDouble);

        VBox box = new VBox();
        box.getStyleClass().add("compare-box");
        box.getChildren().addAll(
                compareRow("原始值", sample, -1, null),
                compareRow("文本", sample, -1, "compare-row-good"),
                compareRow("数值", viaDouble, common, "compare-row-bad"));
        return box;
    }

    private HBox compareRow(String tag, String value, int keepPrefix, String rowClass) {
        Label tagLabel = UiUtils.label(tag, "compare-tag");
        javafx.scene.Node valueNode;

        if (keepPrefix >= 0 && keepPrefix < value.length()) {
            Text kept = new Text(value.substring(0, keepPrefix));
            kept.getStyleClass().add("compare-value");
            Text lost = new Text(value.substring(keepPrefix));
            lost.getStyleClass().addAll("compare-value", "compare-lost");
            valueNode = new TextFlow(kept, lost);
        } else {
            valueNode = UiUtils.label(value, "compare-value");
        }

        HBox row = UiUtils.row(10, tagLabel, valueNode);
        if (keepPrefix >= 0) {
            row.getChildren().addAll(UiUtils.hSpacer(),
                    UiUtils.label("第 " + (keepPrefix + 1) + " 位起丢失", "status-error"));
        } else if ("compare-row-good".equals(rowClass)) {
            row.getChildren().addAll(UiUtils.hSpacer(),
                    UiUtils.row(4, Icons.check("#4d7a4d", 10), UiUtils.label("一致", "status-ok")));
        }
        row.getStyleClass().add("compare-row");
        if (rowClass != null) {
            row.getStyleClass().add(rowClass);
        }
        return row;
    }

    private String findLongSample(List<ColumnMeta> exactColumns) {
        for (ColumnMeta col : exactColumns) {
            int index = current.columns().indexOf(col);
            if (index < 0) {
                continue;
            }
            for (var row : current.rows()) {
                String value = row.get(index);
                if (Exporters.isLongNumber(col, value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 执行

    private HBox buildFoot() {
        progress.setPrefWidth(180);
        progress.setVisible(false);

        Button cancel = UiUtils.toolButton("取消", null);
        Button run = UiUtils.toolButton("开始导出", null, "primary");
        cancel.setOnAction(e -> stage.close());
        run.setOnAction(e -> runExport(run));

        // 这句原来是写死的「游标流式读取」，可它只对扫全表那一项成立——
        // 选「当前页」时导出的就是内存里那几百行，说自己在流式读取是假话。
        // 让它跟着选中的范围走
        rangeHint.getStyleClass().add("hint");
        rangeHint.setWrapText(true);
        rangeGroup.selectedToggleProperty().addListener((o, was, is) -> updateRangeHint());
        updateRangeHint();

        HBox foot = UiUtils.row(12, rangeHint,
                UiUtils.hSpacer(), progressLabel, progress, cancel, run);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    /** 底下那句话：这次导出到底怎么取数、有什么代价。 */
    private void updateRangeHint() {
        if (rangeAll.isSelected()) {
            rangeHint.setText("分页扫描整张表，内存占用与行数无关");
        } else if (rerunQuery != null && rerunQuery.isSelected()) {
            rangeHint.setText("重新执行这条语句，游标流式读取；"
                    + "内存占用与行数无关，但取的是此刻的数据，可能与屏幕上不同");
        } else {
            rangeHint.setText(fromTable
                    ? "只写出当前这一页已经取回的行"
                    : "只写出已经取回的这些行；要完整结果请选「重新执行，导出全部」");
        }
    }

    private void runExport(Button trigger) {
        options.setFormat(currentFormat())
                .setIncludeHeader(headerBox.isSelected())
                .setNullAsEmpty(nullEmptyBox.isSelected())
                .setTableName(tableName)
                .setDialect(session.connection().dialect())
                .setIncludeDdl(ddlBox.isSelected() && !ddlBox.isDisabled())
                .setTarget(Paths.get(pathField.getText().trim()));
        options.setDdl(options.includeDdl() ? buildDdl() : null);

        if (precisionGroup.getSelectedToggle() != null) {
            options.setLongNumberMode(
                    (ExportOptions.LongNumberMode) precisionGroup.getSelectedToggle().getUserData());
        }

        RowSource source;
        if (rangeAll.isSelected()) {
            source = RowSource.ofTable(session.connection(), schema, tableName, null, 1000,
                    totalRows);
        } else if (rerunQuery != null && rerunQuery.isSelected()) {
            // 列取自上一次执行的结果：导出要先写表头，而那时游标还一行没读
            source = RowSource.ofQuery(session.connection(), schema, querySql,
                    current.columns(), -1);
        } else {
            source = RowSource.of(current);
        }

        trigger.setDisable(true);
        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("导出中…");

        long expected = source.estimatedTotal();
        context.queryService().submit(() -> Exporters.export(source, options, written -> {
            if (expected > 0) {
                Platform.runLater(() -> {
                    progress.setProgress((double) written / expected);
                    progressLabel.setText(UiUtils.groupDigits(written) + " / "
                            + UiUtils.groupDigits(expected));
                });
            }
        })).whenComplete((written, error) -> Platform.runLater(() -> {
            trigger.setDisable(false);
            progress.setVisible(false);
            if (error != null) {
                UiUtils.showError(stage, "导出失败", error);
                progressLabel.setText("");
                return;
            }
            UiUtils.showInfo(stage, "导出完成",
                    "已写出 " + UiUtils.groupDigits(written) + " 行到\n" + options.target());
            stage.close();
        }));
    }
}
