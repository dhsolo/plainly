package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.export.DataDictionary;
import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据字典：把库结构导成一份能交出去的文档。
 *
 * <p>结构信息在工具里到处都是，但都只能看。评审、交付、对接第三方要的是
 * 一份能发出去的文件——现在的替代办法是一张表一张表地截图。
 */
public class DataDictionaryDialog {

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private final TextField titleField = new TextField();
    private final TextField filter = new TextField();
    private final ChoiceBox<String> formatBox = new ChoiceBox<>(FXCollections.observableArrayList(
            "HTML（带目录，发给人看）", "Markdown（进 wiki 或代码仓库）",
            "xlsx（一行一个字段，方便筛选）", "CSV（一行一个字段）"));
    private final TableView<Entry> tables = new TableView<>();
    private final Label status = UiUtils.label("", "hint");

    private List<Entry> allEntries = List.of();
    private Stage stage;

    public DataDictionaryDialog(AppContext context) {
        this.context = context;
    }

    /** 一行：勾选状态 + 一张表。 */
    public static final class Entry {
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);
        private final TableInfo info;

        Entry(TableInfo info) {
            this.info = info;
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("数据字典");

        VBox root = new VBox(buildHead(), buildToolbar(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 900, 620);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        formatBox.getSelectionModel().selectFirst();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8, Icons.file(Icons.ACCENT, 14),
                UiUtils.label("表、字段、类型、默认值、注释、索引、外键，导成一份文件", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildToolbar() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(220);
        connBox.valueProperty().addListener((o, was, is) -> loadSchemas());

        schemaBox.setPrefWidth(180);
        schemaBox.valueProperty().addListener((o, was, is) -> loadTables());

        filter.setPromptText("按表名过滤");
        filter.setPrefWidth(160);
        filter.textProperty().addListener((o, was, is) -> applyFilter());

        HBox bar = UiUtils.row(8, connBox, UiUtils.label("库", "hint"), schemaBox,
                filter, UiUtils.hSpacer(), status);
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private VBox buildBody() {
        tables.getStyleClass().add("data-grid");
        tables.setEditable(true);
        tables.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tables.setPlaceholder(UiUtils.label("先选连接和库", "hint"));

        TableColumn<Entry, Boolean> check = new TableColumn<>("");
        check.setPrefWidth(36);
        check.setCellValueFactory(c -> c.getValue().selectedProperty());
        check.setCellFactory(CheckBoxTableCell.forTableColumn(check));
        tables.getColumns().add(check);
        tables.getColumns().add(text("表", e -> e.info.name(), 200));
        tables.getColumns().add(text("类型", e -> e.info.kind().label(), 70));
        tables.getColumns().add(text("说明", e -> e.info.comment() == null ? "" : e.info.comment(), 260));
        tables.getColumns().add(text("行数估算",
                e -> e.info.rowEstimate() < 0 ? "未知" : UiUtils.approxCount(e.info.rowEstimate()), 90));

        titleField.setPromptText("文档标题，留空则用库名");
        titleField.setPrefWidth(240);

        VBox box = UiUtils.column(8,
                UiUtils.row(10, UiUtils.label("选择要写进字典的表", "section-label"),
                        UiUtils.hSpacer(), selectAll(true), selectAll(false)),
                tables,
                UiUtils.row(8, UiUtils.label("标题", "hint"), titleField,
                        UiUtils.label("格式", "hint"), formatBox));
        box.setPadding(new Insets(12));
        VBox.setVgrow(tables, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button go = UiUtils.toolButton("生成并保存…", Icons.export("#ffffff", 12), "primary");
        go.setOnAction(e -> generate());

        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close, go);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private TableColumn<Entry, String> text(String title,
                                           java.util.function.Function<Entry, String> getter,
                                           double width) {
        TableColumn<Entry, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    private Button selectAll(boolean value) {
        Button b = UiUtils.toolButton(value ? "全选" : "全不选", null);
        b.setOnAction(e -> tables.getItems().forEach(i -> i.selectedProperty().set(value)));
        return b;
    }

    // ------------------------------------------------------------------ 装填

    private void loadSchemas() {
        ConnectionConfig config = connBox.getValue();
        if (config == null) {
            return;
        }
        status.setText("读取库列表…");
        context.queryService().submit(() -> context.openSession(config).schemas())
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        status.setText(UiUtils.rootMessage(error));
                        return;
                    }
                    schemaBox.setItems(FXCollections.observableArrayList(
                            list.stream().map(s -> s.name()).toList()));
                    list.stream().filter(s -> s.isDefault()).findFirst()
                            .ifPresent(s -> schemaBox.setValue(s.name()));
                    status.setText("");
                }));
    }

    private void loadTables() {
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        if (config == null || schema == null) {
            return;
        }
        status.setText("读取表列表…");
        context.queryService().submit(() -> context.openSession(config).tables(schema))
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        status.setText(UiUtils.rootMessage(error));
                        return;
                    }
                    List<Entry> entries = new ArrayList<>();
                    for (TableInfo t : list) {
                        // 函数和存储过程没有字段清单，进不了这份文档。
                        // 视图有列，留着——很多库对外暴露的正是视图
                        if (t.kind() == ObjectKind.TABLE || t.kind() == ObjectKind.VIEW) {
                            entries.add(new Entry(t));
                        }
                    }
                    allEntries = entries;
                    applyFilter();
                    status.setText("共 " + entries.size() + " 张表 / 视图");
                }));
    }

    private void applyFilter() {
        String needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase();
        tables.setItems(FXCollections.observableArrayList(
                allEntries.stream()
                        .filter(e -> needle.isEmpty()
                                || e.info.name().toLowerCase().contains(needle))
                        .toList()));
    }

    // ------------------------------------------------------------------ 生成

    private void generate() {
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        if (config == null || schema == null) {
            UiUtils.showInfo(stage, "还差点东西", "先选连接和库。");
            return;
        }
        List<String> picked = new ArrayList<>();
        for (Entry e : tables.getItems()) {
            if (e.selectedProperty().get()) {
                picked.add(e.info.name());
            }
        }
        if (picked.isEmpty()) {
            UiUtils.showInfo(stage, "没有选中任何表", "先勾上要写进字典的那几张。");
            return;
        }

        int format = formatBox.getSelectionModel().getSelectedIndex();
        String extension = switch (format) {
            case 1 -> "md";
            case 2 -> "xlsx";
            case 3 -> "csv";
            default -> "html";
        };
        String title = titleField.getText().isBlank()
                ? schema + " 数据字典" : titleField.getText().trim();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存数据字典");
        chooser.setInitialFileName(schema + "-数据字典." + extension);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(extension.toUpperCase() + " 文件", "*." + extension));
        File file = FileDialogs.save(stage, context.uiState(), chooser);
        if (file == null) {
            return;
        }

        status.setText("正在读取结构…");
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            List<DataDictionary.TableDoc> docs = DataDictionary.collect(
                    session.connection(), schema, picked,
                    name -> Platform.runLater(() -> status.setText("读取 " + name + "…")));
            switch (format) {
                case 1 -> Files.writeString(file.toPath(),
                        DataDictionary.renderMarkdown(title, schema, docs), StandardCharsets.UTF_8);
                case 2, 3 -> Exporters.export(DataDictionary.asRows(schema, docs),
                        new ExportOptions()
                                .setFormat(format == 2
                                        ? ExportOptions.Format.XLSX : ExportOptions.Format.CSV)
                                .setTarget(file.toPath())
                                .setIncludeHeader(true),
                        n -> { });
                default -> Files.writeString(file.toPath(),
                        DataDictionary.renderHtml(title, schema, docs), StandardCharsets.UTF_8);
            }
            return docs.size();
        }).whenComplete((count, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("");
                UiUtils.showError(stage, "生成失败", error);
                return;
            }
            status.setText("已生成 " + count + " 张表");
            UiUtils.showInfo(stage, "数据字典已生成",
                    file.getAbsolutePath() + System.lineSeparator()
                            + "共 " + count + " 张表。");
        }));
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
