package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.search.DataSearch;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 在库里找一个值。
 *
 * <p>典型场景：线上报错里带着一个 id，或者客服转来一个手机号，但没人记得它存在哪张表。
 * 这个窗口把「挨张表点开去看」变成「敲进去，等几秒」。
 *
 * <p>界面上有两句话是必须说的，不是免责声明而是使用前提：
 * <ul>
 *   <li><b>只搜文本列</b>——数字列要先转成文本才能比较，而转换会改变值的样子
 *       （{@code 1.50} 变 {@code 1.5}），那样搜到的和存的就对不上了；</li>
 *   <li><b>每张表有取回上限</b>——不然一个常见片段配一张千万行的表，
 *       就是把整个库拉进内存。</li>
 * </ul>
 */
public class DataSearchDialog {

    private static final int DEFAULT_LIMIT = 200;

    /** 表选择列表里的一项。 */
    private static class Pick {
        private final String name;
        private final BooleanProperty selected = new SimpleBooleanProperty(true);

        Pick(String name) {
            this.name = name;
        }

        BooleanProperty selectedProperty() {
            return selected;
        }
    }

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private final TextField needleField = new TextField();
    private final ComboBox<DataSearch.Mode> modeBox = new ComboBox<>();
    private final CheckBox ignoreCase = new CheckBox("忽略大小写");
    private final TextField limitField = new TextField(String.valueOf(DEFAULT_LIMIT));

    private final TextField tableFilter = new TextField();
    private final ListView<Pick> tableList = new ListView<>();
    private final ObservableList<Pick> allPicks = FXCollections.observableArrayList();

    private final TableView<DataSearch.Match> results = new TableView<>();
    private final Label progress = UiUtils.label("", "hint");
    private final Label summary = UiUtils.label("", "hint");

    private final Button runButton = UiUtils.toolButton("开始查找",
            Icons.search("#ffffff", 11), "primary");
    private final Button stopButton = UiUtils.toolButton("停止", Icons.stop(Icons.FAINT, 11));

    private AtomicBoolean cancelled = new AtomicBoolean();
    private BiConsumer<DbSession, TableInfo> onOpenTable = (s, t) -> { };
    private Stage stage;

    private ConnectionConfig initialConfig;
    private String initialSchema;

    public DataSearchDialog(AppContext context) {
        this.context = context;
    }

    /**
     * 预选连接和库。
     *
     * <p>用户点着左树上的某个库再点「查找数据」，想搜的就是它。
     * {@code schema} 为 null 时只定连接，库仍按默认库选。
     */
    public void preselect(ConnectionConfig config, String schema) {
        this.initialConfig = config;
        this.initialSchema = schema;
    }

    /** 双击一条结果时打开对应的表。 */
    public void setOnOpenTable(BiConsumer<DbSession, TableInfo> handler) {
        this.onOpenTable = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("在库中查找数据");

        VBox root = new VBox(buildHead(), buildToolbar(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1060, 700);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        if (initialConfig != null) {
            connBox.getSelectionModel().select(initialConfig);
        } else {
            DbSession current = context.anyConnected();
            if (current != null) {
                connBox.getSelectionModel().select(current.config());
            }
        }
        stage.setOnHidden(e -> cancelled.set(true));
        stage.show();
        Platform.runLater(needleField::requestFocus);
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.search(Icons.ACCENT, 14),
                UiUtils.label("只知道一个值，不知道它在哪张表", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildToolbar() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(210);
        connBox.valueProperty().addListener((o, was, is) -> loadSchemas());

        schemaBox.setPrefWidth(160);
        schemaBox.valueProperty().addListener((o, was, is) -> loadTables());

        needleField.setPromptText("要找的值");
        needleField.setPrefWidth(240);
        needleField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                run();
            }
        });

        modeBox.setItems(FXCollections.observableArrayList(DataSearch.Mode.values()));
        modeBox.getSelectionModel().select(DataSearch.Mode.CONTAINS);
        modeBox.setPrefWidth(110);

        // 默认开：不开的话，同一个词在 MySQL 上搜得到、在 PostgreSQL 上搜不到，
        // 而用户看不出为什么。统一成「都不区分」比「跟着数据库走」好解释得多
        ignoreCase.setSelected(true);
        ignoreCase.setTooltip(new Tooltip(
                "LIKE 区不区分大小写，取决于这一列的排序规则——MySQL 默认不区分，"
                        + "PostgreSQL 和 H2 区分。勾上就统一成不区分。\n"
                        + "代价：用 LOWER() 压平之后走不了索引。"
                        + "「以此开头」这种本来能走索引的模式，数据量大时可以取消勾选。"));

        limitField.setPrefWidth(70);
        limitField.setTooltip(new Tooltip(
                "每张表最多取回多少行。取回的行越多越慢，也越占内存。"));

        runButton.setOnAction(e -> run());
        stopButton.setOnAction(e -> cancelled.set(true));
        stopButton.setDisable(true);

        HBox row = UiUtils.row(8,
                UiUtils.label("连接", "form-label"), connBox,
                UiUtils.label("库", "form-label"), schemaBox,
                UiUtils.label("查找", "form-label"), needleField, modeBox, ignoreCase,
                UiUtils.label("每表上限", "form-label"), limitField,
                UiUtils.hSpacer(), stopButton, runButton);
        row.getStyleClass().add("grid-toolbar");

        HBox note = UiUtils.row(6, Icons.warn("#8a6d1f", 11), UiUtils.label(
                "只搜文本列（CHAR/VARCHAR/TEXT/JSON）。数字和日期要先转成文本才能比较，"
                        + "而转换会改变值的写法——1.50 可能变成 1.5，那样「搜到的」和「存着的」就对不上了。",
                "hint"));
        note.getStyleClass().add("readonly-bar");

        return UiUtils.column(0, row, note);
    }

    private SplitPane buildBody() {
        // ---- 左：选哪些表
        tableFilter.setPromptText("过滤表名");
        tableFilter.textProperty().addListener((o, was, is) -> applyFilter());

        Button all = UiUtils.toolButton("全选", null);
        all.setOnAction(e -> tableList.getItems().forEach(p -> p.selectedProperty().set(true)));
        Button none = UiUtils.toolButton("全不选", null);
        none.setOnAction(e -> tableList.getItems().forEach(p -> p.selectedProperty().set(false)));

        tableList.setCellFactory(CheckBoxListCell.forListView(Pick::selectedProperty,
                new StringConverter<>() {
                    @Override
                    public String toString(Pick pick) {
                        return pick == null ? "" : pick.name;
                    }

                    @Override
                    public Pick fromString(String s) {
                        return null;
                    }
                }));
        tableList.setPlaceholder(UiUtils.label("先选一个库", "hint"));
        VBox.setVgrow(tableList, Priority.ALWAYS);

        VBox left = UiUtils.column(6,
                UiUtils.row(8, UiUtils.label("要搜的表", "section-label"),
                        UiUtils.hSpacer(), all, none),
                tableFilter, tableList);
        left.setPadding(new Insets(10));

        // ---- 右：结果
        results.getStyleClass().add("data-grid");
        results.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        results.setPlaceholder(UiUtils.label("还没有查找过", "hint"));
        results.getColumns().add(col("表", DataSearch.Match::table, 130));
        results.getColumns().add(col("列", DataSearch.Match::column, 130));
        results.getColumns().add(col("值", m -> preview(m.value()), 300));
        results.getColumns().add(col("定位", DataSearch.Match::locator, 200));
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelected();
            }
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
                copySelected();
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        VBox right = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("命中", "section-label"),
                        UiUtils.label("双击打开那张表；Ctrl+C 复制命中的值", "hint"),
                        UiUtils.hSpacer(), progress),
                results);
        right.setPadding(new Insets(10));

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.26);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private TableColumn<DataSearch.Match, String> col(
            String title, java.util.function.Function<DataSearch.Match, String> getter,
            double width) {
        TableColumn<DataSearch.Match, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    /** 结果表里的值只给一行预览：命中的往往是一大段文本，整段铺进去会把表格撑烂。 */
    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, summary, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 装填

    private void loadSchemas() {
        ConnectionConfig config = connBox.getValue();
        if (config == null) {
            return;
        }
        schemaBox.setItems(FXCollections.observableArrayList());
        allPicks.clear();
        tableList.setItems(FXCollections.observableArrayList());
        progress.setText("正在连接…");

        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return session.schemas();
        }).whenComplete((schemas, error) -> Platform.runLater(() -> {
            if (error != null) {
                progress.setText(UiUtils.rootMessage(error));
                return;
            }
            progress.setText("");
            List<String> names = new ArrayList<>();
            String preferred = null;
            for (SchemaInfo s : schemas) {
                names.add(s.name());
                if (s.isDefault()) {
                    preferred = s.name();
                }
            }
            schemaBox.setItems(FXCollections.observableArrayList(names));
            // 调用方指定的库优先于数据库自己报的「默认库」：他刚在树上点过它
            if (initialSchema != null && names.contains(initialSchema)) {
                schemaBox.getSelectionModel().select(initialSchema);
            } else if (preferred != null) {
                schemaBox.getSelectionModel().select(preferred);
            } else if (!names.isEmpty()) {
                schemaBox.getSelectionModel().selectFirst();
            }
        }));
    }

    private void loadTables() {
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        if (config == null || schema == null) {
            return;
        }
        progress.setText("正在读表列表…");
        context.queryService().submit(() -> context.openSession(config).tables(schema))
                .whenComplete((tables, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        progress.setText(UiUtils.rootMessage(error));
                        return;
                    }
                    allPicks.clear();
                    for (TableInfo t : tables) {
                        // 视图不搜：它背后可能是一条昂贵的联表查询，
                        // 而且视图里的数据在基表里本来就找得到，搜它等于把同一份数据搜两遍
                        if (t.kind() == ObjectKind.VIEW) {
                            continue;
                        }
                        allPicks.add(new Pick(t.name()));
                    }
                    applyFilter();
                    progress.setText(allPicks.size() + " 张表（视图不在范围内）");
                }));
    }

    private void applyFilter() {
        String f = tableFilter.getText() == null ? "" : tableFilter.getText().trim()
                .toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            tableList.setItems(allPicks);
            return;
        }
        ObservableList<Pick> shown = FXCollections.observableArrayList();
        for (Pick p : allPicks) {
            if (p.name.toLowerCase(Locale.ROOT).contains(f)) {
                shown.add(p);
            }
        }
        tableList.setItems(shown);
    }

    // ------------------------------------------------------------------ 执行

    private void run() {
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        String needle = needleField.getText();
        if (config == null || schema == null) {
            summary.setText("先选连接和库");
            return;
        }
        if (needle == null || needle.isEmpty()) {
            summary.setText("先输入要找的值");
            return;
        }
        List<String> tables = new ArrayList<>();
        for (Pick p : allPicks) {
            if (p.selectedProperty().get()) {
                tables.add(p.name);
            }
        }
        if (tables.isEmpty()) {
            summary.setText("一张表都没选");
            return;
        }

        int limit = parseLimit();
        DataSearch.Mode mode = modeBox.getValue();
        boolean fold = ignoreCase.isSelected();
        cancelled = new AtomicBoolean();
        AtomicBoolean flag = cancelled;

        runButton.setDisable(true);
        stopButton.setDisable(false);
        results.setItems(FXCollections.observableArrayList());
        summary.setText("");

        int total = tables.size();
        int[] done = {0};

        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return DataSearch.run(session.connection(), schema, tables, needle, mode, limit,
                    fold,
                    name -> Platform.runLater(() -> progress.setText(
                            "正在搜 " + name + "（" + (++done[0]) + "/" + total + "）")),
                    flag::get);
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            runButton.setDisable(false);
            stopButton.setDisable(true);
            progress.setText("");
            if (error != null) {
                summary.setText(UiUtils.rootMessage(error));
                return;
            }
            results.setItems(FXCollections.observableArrayList(result.matches()));
            summary.setText(describe(result, total));
        }));
    }

    /** 结果一句话讲清：找到多少、跳过多少、有没有被上限截断。 */
    private String describe(DataSearch.Result result, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.cancelled() ? "已停止 · " : "")
                .append("在 ").append(result.matchedTables()).append(" 张表里找到 ")
                .append(result.matches().size()).append(" 处 · 用时 ")
                .append(result.elapsedMillis()).append(" ms");

        List<String> skipped = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (DataSearch.TableOutcome t : result.tables()) {
            if (t.skipped() != null) {
                skipped.add(t.table());
            }
            if (t.truncated()) {
                truncated.add(t.table());
            }
            if (t.error() != null) {
                failed.add(t.table() + "（" + t.error() + "）");
            }
        }
        if (!skipped.isEmpty()) {
            sb.append(" · ").append(skipped.size()).append(" 张表没有文本列，跳过");
        }
        if (!truncated.isEmpty()) {
            // 这条必须说：不说的话，用户会以为「就这么多」，而实际上是「还有，只是没取」
            sb.append(" · 达到每表上限、还有更多没取：").append(String.join("、", truncated));
        }
        if (!failed.isEmpty()) {
            sb.append(" · 搜不了：").append(String.join("、", failed));
        }
        List<String> degraded = new ArrayList<>();
        for (DataSearch.TableOutcome t : result.tables()) {
            if (t.note() != null) {
                degraded.add(t.table() + "（" + t.note() + "）");
            }
        }
        if (!degraded.isEmpty()) {
            sb.append(" · ").append(String.join("、", degraded));
        }
        if (result.tables().size() < total) {
            sb.append(" · 剩下 ").append(total - result.tables().size()).append(" 张没搜");
        }
        return sb.toString();
    }

    private int parseLimit() {
        try {
            int v = Integer.parseInt(limitField.getText().trim());
            return Math.max(1, Math.min(v, 100_000));
        } catch (RuntimeException e) {
            limitField.setText(String.valueOf(DEFAULT_LIMIT));
            return DEFAULT_LIMIT;
        }
    }

    // ------------------------------------------------------------------ 结果操作

    private void openSelected() {
        DataSearch.Match match = results.getSelectionModel().getSelectedItem();
        ConnectionConfig config = connBox.getValue();
        if (match == null || config == null) {
            return;
        }
        DbSession session = context.sessionFor(config.id());
        if (session == null) {
            return;
        }
        onOpenTable.accept(session,
                new TableInfo(match.schema(), match.table(), ObjectKind.TABLE, "", -1));
    }

    /** 复制的是完整的值，不是表格里那行截断过的预览。 */
    private void copySelected() {
        DataSearch.Match match = results.getSelectionModel().getSelectedItem();
        if (match == null || match.value() == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(match.value());
        Clipboard.getSystemClipboard().setContent(content);
        summary.setText("已复制 " + match.table() + "." + match.column() + " 的完整值");
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
