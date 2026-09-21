package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.VirtualKeyStore;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * 虚拟外键的标注。
 *
 * <h2>它做什么，不做什么</h2>
 * 很多线上库刻意不建物理外键，于是 ER 图上画出来是一堆孤岛，
 * 「哪张表跟哪张表有关系」只存在于人的脑子里。这里让用户把它标下来。
 *
 * <p><b>标注只存在本机</b>：目标库上不会多出任何约束，因而<b>没有任何强制力</b>——
 * 标了 {@code orders.user_id → users.id}，插一条 user_id 不存在的订单照样插得进去。
 * 这句话在界面上占了一整行，因为把它当成真外键的后果，是拿一个不存在的保证去做决定。
 */
public class VirtualKeyDialog {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;

    private final TableView<VirtualKeyStore.VirtualKey> list = new TableView<>();
    private final ComboBox<String> columnBox = new ComboBox<>();
    private final ComboBox<String> refTableBox = new ComboBox<>();
    private final ComboBox<String> refColumnBox = new ComboBox<>();
    private final TextField note = new TextField();
    private final Label status = UiUtils.label("", "hint");

    private Stage stage;
    private Runnable onChanged = () -> { };

    public VirtualKeyDialog(AppContext context, DbSession session, String schema, String table) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
    }

    public VirtualKeyDialog setOnChanged(Runnable action) {
        this.onChanged = action == null ? () -> { } : action;
        return this;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("虚拟外键 · " + table);

        VBox root = new VBox(buildHead(), buildEditor(), buildList(), buildFoot());
        Scene scene = new Scene(root, 860, 520);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        loadColumns();
        loadTables();
        reloadList();
    }

    private HBox buildHead() {
        Label warn = UiUtils.label(
                "标注只存在本机：目标库上不会多出约束，也不会拦住对不上的数据。"
                        + "它影响的只是 ER 图和外键栏的显示。", "hint");
        warn.setWrapText(true);
        HBox head = UiUtils.row(8, Icons.key(Icons.ACCENT, 14), warn);
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildEditor() {
        columnBox.setPrefWidth(170);
        refTableBox.setPrefWidth(200);
        refColumnBox.setPrefWidth(170);
        note.setPrefWidth(180);
        note.setPromptText("备注（可选）");

        refTableBox.valueProperty().addListener((o, was, is) -> loadRefColumns(is));

        Button add = UiUtils.toolButton("添加标注", Icons.plus(Icons.ACCENT, 12), "accent");
        add.setOnAction(e -> add());

        HBox row = UiUtils.row(8,
                UiUtils.label("本表的列", "hint"), columnBox,
                UiUtils.label("指向", "hint"), refTableBox, refColumnBox,
                note, add);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox box = UiUtils.column(6, row, status);
        box.setPadding(new Insets(12));
        return box;
    }

    private VBox buildList() {
        list.getStyleClass().add("data-grid");
        list.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        list.setPlaceholder(UiUtils.label("这张表还没有虚拟外键标注", "hint"));
        list.getColumns().add(col("本表的列", k -> String.join(", ", k.columns()), 150));
        list.getColumns().add(col("指向",
                k -> qualify(k.refSchema(), k.refTable()) + "."
                        + String.join(", ", k.refColumns()), 280));
        list.getColumns().add(col("备注", k -> k.note() == null ? "" : k.note(), 200));

        Button remove = UiUtils.toolButton("删除选中的标注", Icons.minus("#a0402a", 12), "danger");
        remove.setOnAction(e -> {
            VirtualKeyStore.VirtualKey picked = list.getSelectionModel().getSelectedItem();
            if (picked == null) {
                status.setText("先在下面选一条");
                return;
            }
            context.virtualKeys().delete(picked.id());
            status.setText("已删除一条标注");
            reloadList();
            onChanged.run();
        });

        VBox box = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("已有的标注", "section-label"),
                        UiUtils.hSpacer(), remove),
                list);
        box.setPadding(new Insets(12, 12, 6, 12));
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private TableColumn<VirtualKeyStore.VirtualKey, String> col(
            String title, java.util.function.Function<VirtualKeyStore.VirtualKey, String> getter,
            double width) {
        TableColumn<VirtualKeyStore.VirtualKey, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    // ------------------------------------------------------------------ 装填

    private void loadColumns() {
        context.queryService().submit(() -> session.structure(schema, table).columns())
                .whenComplete((columns, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        status.setText("读不到本表的列：" + UiUtils.rootMessage(error));
                        return;
                    }
                    columnBox.setItems(FXCollections.observableArrayList(
                            columns.stream().map(ColumnInfo::name).toList()));
                }));
    }

    private void loadTables() {
        context.queryService().submit(() -> session.tables(schema))
                .whenComplete((tables, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        status.setText("读不到表列表：" + UiUtils.rootMessage(error));
                        return;
                    }
                    refTableBox.setItems(FXCollections.observableArrayList(
                            tables.stream().map(TableInfo::name).toList()));
                }));
    }

    private void loadRefColumns(String refTable) {
        if (refTable == null) {
            return;
        }
        context.queryService().submit(() -> session.structure(schema, refTable).columns())
                .whenComplete((columns, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        status.setText("读不到 " + refTable + " 的列：" + UiUtils.rootMessage(error));
                        return;
                    }
                    List<String> names = columns.stream().map(ColumnInfo::name).toList();
                    refColumnBox.setItems(FXCollections.observableArrayList(names));
                    // 对面多半是主键。默认挑上它，省掉最常见的那一次选择
                    columns.stream().filter(ColumnInfo::primaryKey).findFirst()
                            .ifPresent(pk -> refColumnBox.setValue(pk.name()));
                }));
    }

    private void reloadList() {
        list.setItems(FXCollections.observableArrayList(
                context.virtualKeys().listOutgoing(session.config().id(), schema, table)));
    }

    private void add() {
        String column = columnBox.getValue();
        String refTable = refTableBox.getValue();
        String refColumn = refColumnBox.getValue();
        if (column == null || refTable == null || refColumn == null) {
            status.setText("三样都要选：本表的列、目标表、目标列");
            return;
        }
        try {
            context.virtualKeys().add(session.config().id(), schema, table, List.of(column),
                    schema, refTable, List.of(refColumn), note.getText());
            note.clear();
            status.setText("已标注 " + table + "." + column + " → " + refTable + "." + refColumn
                    + "（只影响显示，库上没有约束）");
            reloadList();
            onChanged.run();
        } catch (RuntimeException e) {
            status.setText(UiUtils.rootMessage(e));
        }
    }

    private String qualified() {
        return qualify(schema, table);
    }

    private static String qualify(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
