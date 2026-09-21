package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeNames;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.ddl.TableDiff;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.converter.DefaultStringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 表结构设计器。
 *
 * <h2>两条贯穿始终的原则</h2>
 * <ul>
 *   <li><b>预览即执行</b>：预览里的语句和点「应用」时跑的语句由同一次
 *       {@link TableDiff#compute} 产出，不存在看到一套、执行另一套的可能。</li>
 *   <li><b>做不到就明说</b>：SQLite 改不了列定义。与其生成一条注定失败的 SQL
 *       让用户点了才发现，不如在预览里直接标红并禁用「应用」。</li>
 * </ul>
 */
public class TableDesignerPane extends BorderPane {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String originalTable;

    private final TableView<ColumnDraft> fields = new TableView<>();
    private final ObservableList<ColumnDraft> drafts = FXCollections.observableArrayList();
    private final TableView<IndexInfo> indexes = new TableView<>();

    private final TextField tableNameField = new TextField();
    private final Label changeBadge = UiUtils.label("");
    private final VBox changeList = new VBox(3);
    private final TextArea sqlPreview = new TextArea();
    private final Label blockerLabel = UiUtils.label("");

    private final Button addButton;
    private final Button dropButton;
    private final Button upButton;
    private final Button downButton;
    private final Button applyButton;
    private final Button resetButton;

    private TableStructure structure;
    private List<TableChange> pendingChanges = List.of();
    private List<String> pendingSql = List.of();
    private boolean applicable;

    private Consumer<String> statusSink = s -> { };
    private Runnable onApplied = () -> { };

    public TableDesignerPane(AppContext context, DbSession session, String schema, String table) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.originalTable = table;

        addButton = UiUtils.toolButton("添加字段", Icons.plus(Icons.NEUTRAL, 11));
        dropButton = UiUtils.toolButton("删除", Icons.minus(Icons.NEUTRAL, 11));
        upButton = UiUtils.toolButton("上移", null);
        downButton = UiUtils.toolButton("下移", null);
        resetButton = UiUtils.toolButton("重置", null);
        applyButton = UiUtils.toolButton("应用变更", null, "primary");

        addButton.setOnAction(e -> addField());
        dropButton.setOnAction(e -> dropField());
        upButton.setOnAction(e -> move(-1));
        downButton.setOnAction(e -> move(1));
        resetButton.setOnAction(e -> reload());
        applyButton.setOnAction(e -> applyChanges());

        buildFieldTable();
        buildIndexTable();

        SplitPane split = new SplitPane(buildFieldsArea(), buildPreviewArea());
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.6);

        setTop(new VBox(buildNameBar(), buildToolbar()));
        setCenter(split);
        setBottom(buildFooter());

        fields.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> updateButtons());
        reload();
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink;
    }

    /** 结构变更成功后回调，让数据页重新取数。 */
    public void setOnApplied(Runnable r) {
        this.onApplied = r;
    }

    // ------------------------------------------------------------------ 顶部

    private HBox buildNameBar() {
        tableNameField.setPrefWidth(210);
        tableNameField.textProperty().addListener((o, was, is) -> recompute());

        Label engine = UiUtils.label("", "hint");
        engine.setText(session.connection().serverVersion());

        changeBadge.getStyleClass().add("badge-exact");
        changeBadge.setVisible(false);

        HBox bar = UiUtils.row(12,
                UiUtils.label("表名", "form-label"), tableNameField,
                UiUtils.vSeparator(), engine,
                UiUtils.hSpacer(), changeBadge);
        bar.getStyleClass().add("grid-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildToolbar() {
        HBox bar = UiUtils.row(5, addButton, dropButton, upButton, downButton,
                UiUtils.vSeparator(), resetButton,
                UiUtils.hSpacer(),
                UiUtils.label("DECIMAL 的精度与小数位直接决定读取时 BigDecimal 的 scale", "hint"));
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    // ------------------------------------------------------------------ 字段网格

    private void buildFieldTable() {
        fields.setItems(drafts);
        fields.setEditable(true);
        fields.getStyleClass().add("data-grid");
        fields.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        fields.setPlaceholder(UiUtils.label("正在读取结构…", "hint"));

        fields.getColumns().addAll(
                textColumn("字段名", 176, ColumnDraft::name, ColumnDraft::setName),
                typeColumn(),
                intColumn("长度", 72, ColumnDraft::precision, ColumnDraft::setPrecision,
                        d -> TypeNames.takesLength(d.nativeType())),
                intColumn("小数位", 70, ColumnDraft::scale, ColumnDraft::setScale,
                        d -> TypeNames.takesScale(d.nativeType())),
                checkColumn("非空", 62, d -> !d.nullable(), (d, v) -> d.setNullable(!v)),
                checkColumn("主键", 58, ColumnDraft::primaryKey, ColumnDraft::setPrimaryKey),
                checkColumn("自增", 58, ColumnDraft::autoIncrement, ColumnDraft::setAutoIncrement),
                textColumn("默认值", 150,
                        d -> d.defaultValue() == null ? "" : d.defaultValue(),
                        ColumnDraft::setDefaultValue),
                textColumn("注释", 220, ColumnDraft::comment, ColumnDraft::setComment));
    }

    private TableColumn<ColumnDraft, String> textColumn(String title, double width,
                                                        Function<ColumnDraft, String> getter,
                                                        BiConsumer<ColumnDraft, String> setter) {
        TableColumn<ColumnDraft, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> new SimpleStringProperty(nz(getter.apply(cd.getValue()))));
        col.setCellFactory(c -> new DraftFieldCells.TextCell(new DefaultStringConverter(), this::rowStyle));
        col.setOnEditCommit(e -> {
            setter.accept(e.getRowValue(), e.getNewValue());
            recompute();
            fields.refresh();
        });
        return col;
    }

    /** 类型列用可编辑下拉：既给常用类型，也允许敲库里特有的类型名。 */
    private TableColumn<ColumnDraft, String> typeColumn() {
        TableColumn<ColumnDraft, String> col = new TableColumn<>("数据类型");
        col.setPrefWidth(154);
        col.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().nativeType()));
        javafx.collections.ObservableList<String> types = FXCollections.observableArrayList(
                TypeNames.catalogFor(session.config().type()));
        col.setCellFactory(c -> new DraftFieldCells.TypeCell(types, this::rowStyle));
        col.setOnEditCommit(e -> {
            e.getRowValue().setNativeType(e.getNewValue());
            recompute();
            fields.refresh();
        });
        return col;
    }

    private TableColumn<ColumnDraft, String> intColumn(String title, double width,
                                                       Function<ColumnDraft, Integer> getter,
                                                       java.util.function.ObjIntConsumer<ColumnDraft> setter,
                                                       java.util.function.Predicate<ColumnDraft> applicableTo) {
        TableColumn<ColumnDraft, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> {
            ColumnDraft d = cd.getValue();
            if (!applicableTo.test(d) || getter.apply(d) <= 0) {
                return new SimpleStringProperty("");
            }
            return new SimpleStringProperty(String.valueOf(getter.apply(d)));
        });
        col.setCellFactory(c -> new DraftFieldCells.TextCell(new DefaultStringConverter(), this::rowStyle));
        col.setOnEditCommit(e -> {
            // 用普通文本框收数字，自己解析。绝不用会替我们做数值转换的输入控件
            String text = e.getNewValue() == null ? "" : e.getNewValue().trim();
            int value = 0;
            if (!text.isEmpty()) {
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    value = getter.apply(e.getRowValue());
                }
            }
            setter.accept(e.getRowValue(), value);
            recompute();
            fields.refresh();
        });
        return col;
    }

    private TableColumn<ColumnDraft, Boolean> checkColumn(String title, double width,
                                                          Function<ColumnDraft, Boolean> getter,
                                                          java.util.function.BiConsumer<ColumnDraft, Boolean> setter) {
        TableColumn<ColumnDraft, Boolean> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> {
            ColumnDraft d = cd.getValue();
            SimpleBooleanProperty property = new SimpleBooleanProperty(getter.apply(d));
            property.addListener((o, was, is) -> {
                setter.accept(d, is);
                recompute();
                Platform.runLater(fields::refresh);
            });
            return property;
        });
        col.setCellFactory(CheckBoxTableCell.forTableColumn(col));
        col.setEditable(true);
        return col;
    }

    /**
     * 一行的底色：新增标绿、改过标黄，其余不着色。
     *
     * <p>抽出来是因为类型那一列用的是另一种单元格（可编辑下拉），
     * 两边必须给出<b>同一个</b>结果——不然一行里会出现半截绿半截白。
     */
    private String rowStyle(ColumnDraft d) {
        if (d.isNew()) {
            return "-fx-background-color:-sx-green-bg;";
        }
        if (structure != null && isModified(d)) {
            return "-fx-background-color:-sx-amber-bg;";
        }
        return "";
    }

    private boolean isModified(ColumnDraft d) {
        return structure.columns().stream()
                .filter(c -> c.name().equals(d.originalName()))
                .findFirst()
                .map(c -> d.differsFrom(c) || !c.name().equals(d.name()))
                .orElse(false);
    }

    private void buildIndexTable() {
        indexes.getStyleClass().add("data-grid");
        indexes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        indexes.setPlaceholder(UiUtils.label("无索引", "hint"));
        indexes.getColumns().addAll(
                indexColumn("索引名", 200, IndexInfo::name),
                indexColumn("字段", 300, i -> String.join(", ", i.columns())),
                indexColumn("唯一", 70, i -> i.unique() ? "是" : "否"),
                indexColumn("主键", 70, i -> i.primary() ? "是" : "否"));
    }

    private TableColumn<IndexInfo, String> indexColumn(String title, double width,
                                                       Function<IndexInfo, String> getter) {
        TableColumn<IndexInfo, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new SimpleStringProperty(nz(getter.apply(cd.getValue()))));
        return c;
    }

    private VBox buildFieldsArea() {
        VBox.setVgrow(fields, Priority.ALWAYS);
        // 这一栏只是把索引摆出来给人对照着改字段用；真要改索引，
        // 在表页的「索引」页上（那儿有「新建 / 修改 / 删除索引…」）。
        //
        // 这里原来写的是「索引编辑将在下一版提供」——那句话在索引编辑做出来之后
        // 就成了假话，而它待在界面上，比不写更糟：用户照着它去别处找，或者干脆不找了
        HBox indexHead = UiUtils.row(12, UiUtils.label("索引", "section-label"),
                UiUtils.label("这里只列出来对照；要增删改索引，用表页的「索引」页", "hint"));
        indexHead.getStyleClass().add("grid-toolbar");
        indexes.setPrefHeight(120);
        indexes.setMinHeight(90);
        return UiUtils.column(0, fields, indexHead, indexes);
    }

    // ------------------------------------------------------------------ 预览

    private VBox buildPreviewArea() {
        sqlPreview.setEditable(false);
        sqlPreview.setStyle("-fx-font-family:'Cascadia Mono',Consolas,monospace;-fx-font-size:12.5px;");
        VBox.setVgrow(sqlPreview, Priority.ALWAYS);

        Button copy = UiUtils.toolButton("复制 SQL", null);
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(sqlPreview.getText());
            Clipboard.getSystemClipboard().setContent(content);
            statusSink.accept("已复制到剪贴板");
        });

        HBox head = UiUtils.row(12, UiUtils.label("变更预览", "section-label"),
                UiUtils.label("应用前生成，可复制到迁移脚本", "hint"),
                UiUtils.hSpacer(), copy);
        head.getStyleClass().add("grid-toolbar");

        changeList.setStyle("-fx-padding:8 12 8 12;");
        blockerLabel.setWrapText(true);
        blockerLabel.setVisible(false);
        blockerLabel.setManaged(false);

        return UiUtils.column(0, head, blockerLabel, changeList, sqlPreview);
    }

    private HBox buildFooter() {
        // 措辞必须跟着数据库的真实能力走。MySQL / H2 的 DDL 会隐式提交，
        // 说「失败整体回滚」是假话；等到某天真失败了，用户会据此做出错误判断。
        boolean atomic = session.connection().dialect().supportsTransactionalDdl();
        Label note = UiUtils.label(atomic
                ? "变更在一个事务内执行，任何一条失败则整体回滚"
                : "该数据库的 DDL 会隐式提交，无法回滚；失败时会告知已执行到第几条", "hint");
        if (!atomic) {
            note.getStyleClass().setAll("status-warn");
            note.setStyle("-fx-font-size:11px;");
        }
        HBox bar = UiUtils.row(12, note, UiUtils.hSpacer(), applyButton);
        bar.getStyleClass().add("dialog-foot");
        return bar;
    }

    // ------------------------------------------------------------------ 数据流

    private void reload() {
        statusSink.accept("正在读取表结构…");
        session.invalidateTable(schema, originalTable);
        context.queryService().submit(() -> session.structure(schema, originalTable))
                .whenComplete((s, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(window(), "读取表结构失败", error);
                        return;
                    }
                    structure = s;
                    tableNameField.setText(originalTable);
                    drafts.setAll(s.columns().stream().map(ColumnDraft::of).toList());
                    indexes.setItems(FXCollections.observableArrayList(s.indexes()));
                    fields.setPlaceholder(UiUtils.label("该表没有字段", "hint"));
                    recompute();
                    statusSink.accept("");
                }));
    }

    /** 重算差异 → 生成 DDL → 刷新预览。编辑器里每一次改动都会走这条路。 */
    private void recompute() {
        if (structure == null) {
            return;
        }
        changeList.getChildren().clear();
        applicable = false;

        List<String> problems = TableDiff.validate(new ArrayList<>(drafts));
        if (!problems.isEmpty()) {
            showBlocker("草稿还不能生成 DDL：\n· " + String.join("\n· ", problems));
            sqlPreview.setText("");
            changeBadge.setVisible(false);
            updateButtons();
            return;
        }

        pendingChanges = TableDiff.compute(structure, new ArrayList<>(drafts),
                tableNameField.getText().trim());

        if (pendingChanges.isEmpty()) {
            hideBlocker();
            sqlPreview.setText("-- 尚无改动");
            changeBadge.setVisible(false);
            changeList.getChildren().add(UiUtils.label("尚无改动", "hint"));
            updateButtons();
            return;
        }

        // 能力检查：把方言做不到的变更挑出来，禁用「应用」并说明原因
        SqlDialect dialect = session.connection().dialect();
        List<String> unsupported = new ArrayList<>();
        for (TableChange change : pendingChanges) {
            if (!dialect.supports(change)) {
                unsupported.add(change.describe() + " —— " + dialect.unsupportedReason(change));
            }
        }

        pendingChanges.forEach(c -> changeList.getChildren().add(renderChange(c, dialect)));
        changeBadge.setText(pendingChanges.size() + " 处结构变更待应用");
        changeBadge.setVisible(true);

        if (!unsupported.isEmpty()) {
            showBlocker("以下变更当前数据库做不到：\n· " + String.join("\n· ", unsupported));
            sqlPreview.setText("-- 存在无法生成的变更，见上方说明");
            updateButtons();
            return;
        }

        hideBlocker();
        try {
            List<SqlDialect.TableChangeSql> ddl =
                    dialect.ddlFor(schema, originalTable, pendingChanges);
            pendingSql = ddl.stream().map(SqlDialect.TableChangeSql::sql).toList();
            sqlPreview.setText(String.join(";\n", pendingSql) + (pendingSql.isEmpty() ? "" : ";"));
            applicable = true;
        } catch (RuntimeException e) {
            showBlocker(UiUtils.rootMessage(e));
            sqlPreview.setText("");
        }
        updateButtons();
    }

    private HBox renderChange(TableChange change, SqlDialect dialect) {
        String color;
        String tag;
        switch (change.risk()) {
            // 用令牌名而不是十六进制：暗色主题下这三个点要跟着提亮，
            // 否则深红压在深底上等于没画
            case DESTRUCTIVE -> {
                color = "-sx-red";
                tag = "会丢数据";
            }
            case DATA_DEPENDENT -> {
                color = "-sx-amber";
                tag = "取决于既有数据";
            }
            default -> {
                color = "-sx-green";
                tag = "安全";
            }
        }
        Label dot = new Label();
        dot.setMinSize(7, 7);
        dot.setMaxSize(7, 7);
        dot.setStyle("-fx-background-color:" + color + ";-fx-background-radius:4;");

        Label text = new Label(change.describe());
        text.setStyle("-fx-font-size:12px;");
        Label risk = new Label(tag);
        risk.setStyle("-fx-font-size:10.5px;-fx-text-fill:" + color + ";");

        HBox row = UiUtils.row(8, dot, text, UiUtils.hSpacer(), risk);
        if (!dialect.supports(change)) {
            text.setStyle("-fx-font-size:12px;-fx-text-fill:-sx-red;-fx-strikethrough:true;");
        }
        return row;
    }

    private void showBlocker(String message) {
        blockerLabel.setText(message);
        blockerLabel.getStyleClass().setAll("banner-error");
        blockerLabel.setVisible(true);
        blockerLabel.setManaged(true);
    }

    private void hideBlocker() {
        blockerLabel.setVisible(false);
        blockerLabel.setManaged(false);
    }

    // ------------------------------------------------------------------ 操作

    private void addField() {
        ColumnDraft d = ColumnDraft.added(uniqueName());
        // 默认类型也得按库来：ColumnDraft 在驱动契约层，那儿不认识 DbType，
        // 只能给一个通用的 VARCHAR；Oracle 上该用 VARCHAR2
        d.setNativeType(com.plainly.driver.TypeNames.defaultColumnType(session.config().type()));
        int at = fields.getSelectionModel().getSelectedIndex();
        if (at >= 0) {
            drafts.add(at + 1, d);
        } else {
            drafts.add(d);
        }
        fields.getSelectionModel().select(d);
        recompute();
    }

    private String uniqueName() {
        int n = 1;
        while (true) {
            String candidate = "column_" + n;
            boolean taken = drafts.stream().anyMatch(d -> d.name().equalsIgnoreCase(candidate));
            if (!taken) {
                return candidate;
            }
            n++;
        }
    }

    private void dropField() {
        ColumnDraft selected = fields.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (!selected.isNew() && !UiUtils.confirm(window(), "删除字段",
                "字段「" + selected.name() + "」的数据将在应用变更后永久丢失。确定继续？")) {
            return;
        }
        drafts.remove(selected);
        recompute();
    }

    /**
     * 移动字段。
     * <p>只允许移动新增的字段：已存在的列要调整物理顺序，
     * MySQL 需要 MODIFY ... AFTER 重写整列，PostgreSQL 则根本做不到（要重建表）。
     * 与其做一半，不如只支持能做对的那部分。
     */
    private void move(int delta) {
        int i = fields.getSelectionModel().getSelectedIndex();
        if (i < 0) {
            return;
        }
        int j = i + delta;
        if (j < 0 || j >= drafts.size()) {
            return;
        }
        ColumnDraft moving = drafts.get(i);
        if (!moving.isNew()) {
            statusSink.accept("只能调整新增字段的位置；已有字段的物理顺序需要重建表");
            return;
        }
        drafts.remove(i);
        drafts.add(j, moving);
        fields.getSelectionModel().select(j);
        recompute();
    }

    private void updateButtons() {
        ColumnDraft selected = fields.getSelectionModel().getSelectedItem();
        dropButton.setDisable(selected == null);
        upButton.setDisable(selected == null || !selected.isNew());
        downButton.setDisable(selected == null || !selected.isNew());
        applyButton.setDisable(!applicable || pendingChanges.isEmpty());
        resetButton.setDisable(pendingChanges.isEmpty());
    }

    private void applyChanges() {
        if (!applicable || pendingSql.isEmpty()) {
            return;
        }
        boolean destructive = pendingChanges.stream()
                .anyMatch(c -> c.risk() == TableChange.Risk.DESTRUCTIVE);
        String prompt = "将执行 " + pendingSql.size() + " 条语句："
                + (destructive ? "\n\n其中包含会永久丢失数据的操作。" : "");
        if (!UiUtils.confirm(window(), "应用结构变更", prompt)) {
            return;
        }

        applyButton.setDisable(true);
        statusSink.accept("正在应用结构变更…");
        List<String> statements = pendingSql;

        context.queryService().submit(() -> session.executeDdl(schema, statements))
                .whenComplete((count, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable root = error instanceof java.util.concurrent.CompletionException
                        ? error.getCause() : error;
                boolean partial = root instanceof com.plainly.driver.DdlBatchException dbe
                        && dbe.leftPartialState();
                UiUtils.showError(window(),
                        partial ? "结构变更失败，且库已被改了一半" : "结构变更失败", error);
                // 库可能已经变了，重新读一次结构，别让界面停在过时的草稿上
                session.invalidate();
                reload();
                return;
            }
            statusSink.accept("已应用 " + count + " 条结构变更");
            session.invalidate();
            reload();
            onApplied.run();
        }));
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
