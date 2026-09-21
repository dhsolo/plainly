package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.HistoryStore;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 查询历史与收藏。
 *
 * <p>历史自动记，收藏手动标。跑过的语句只记语句本身和它跑得怎么样——
 * 结果动辄几十万行，而且过一会儿就不是当时那个结果了。
 */
public class HistoryDialog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final AppContext context;
    private final HistoryStore store;

    private final TableView<HistoryStore.Entry> table = new TableView<>();
    private final TextField search = new TextField();
    private final CheckBox onlyFavorites = new CheckBox("只看收藏");
    private final TextArea preview = new TextArea();

    private Stage stage;
    private Consumer<String> onUse = sql -> { };

    public HistoryDialog(AppContext context) {
        this.context = context;
        this.store = context.historyStore();
    }

    /** 「用这条」时把语句送回编辑器。 */
    public void setOnUse(Consumer<String> handler) {
        this.onUse = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("查询历史");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 940, 680);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        reload();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.search(Icons.ACCENT, 14),
                UiUtils.label("跑过的语句都在这儿 · "
                        + com.plainly.core.store.Retention.describe(), "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        search.setPromptText("搜语句内容");
        search.textProperty().addListener((o, was, is) -> reload());
        HBox.setHgrow(search, Priority.ALWAYS);
        onlyFavorites.setOnAction(e -> reload());

        table.getStyleClass().add("data-grid");
        table.setPlaceholder(UiUtils.label("还没有记录", "hint"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<HistoryStore.Entry, String> star = new TableColumn<>("★");
        star.setMaxWidth(40);
        star.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().favorite() ? "★" : ""));

        TableColumn<HistoryStore.Entry, String> when = new TableColumn<>("时间");
        when.setMaxWidth(140);
        when.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().executedAt().format(STAMP)));

        TableColumn<HistoryStore.Entry, String> sql = new TableColumn<>("语句");
        sql.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().title() != null && !c.getValue().title().isBlank()
                        ? c.getValue().title() + " · " + c.getValue().oneLine()
                        : c.getValue().oneLine()));

        TableColumn<HistoryStore.Entry, String> result = new TableColumn<>("结果");
        result.setMaxWidth(150);
        result.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().succeeded()
                        ? c.getValue().rowCount() + " 行 · " + c.getValue().elapsedMillis() + " ms"
                        : "失败"));

        table.getColumns().addAll(star, when, sql, result);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) ->
                preview.setText(is == null ? "" : is.sql()));

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(8);

        VBox box = UiUtils.column(8,
                UiUtils.row(10, search, onlyFavorites),
                table,
                UiUtils.label("语句全文", "section-label"),
                preview);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button favorite = UiUtils.toolButton("收藏 / 取消", Icons.check(Icons.NEUTRAL, 12));
        favorite.setOnAction(e -> toggleFavorite());
        Button delete = UiUtils.toolButton("删除", Icons.minus("#a0402a", 12), "danger");
        delete.setOnAction(e -> deleteSelected());
        Button clear = UiUtils.toolButton("清空历史", null);
        clear.setOnAction(e -> clearHistory());
        Button use = UiUtils.toolButton("用这条", null, "primary");
        use.setOnAction(e -> {
            HistoryStore.Entry selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                onUse.accept(selected.sql());
                stage.close();
            }
        });
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, favorite, delete, clear, UiUtils.hSpacer(), close, use);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private void reload() {
        table.setItems(FXCollections.observableArrayList(
                store.list(search.getText(), onlyFavorites.isSelected(), 300)));
    }

    /** 收藏时顺手起个名字：三个月后回来看，「日报口径」比一段 SQL 好认得多。 */
    private void toggleFavorite() {
        HistoryStore.Entry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (selected.favorite()) {
            store.setFavorite(selected.id(), false, null);
            reload();
            return;
        }
        TextInputDialog input = new TextInputDialog(
                selected.title() == null ? "" : selected.title());
        input.initOwner(stage);
        input.setTitle("收藏这条查询");
        input.setHeaderText("给它起个名字（可留空）");
        input.setContentText("名称");
        input.showAndWait().ifPresent(name -> {
            store.setFavorite(selected.id(), true, name.isBlank() ? null : name.trim());
            reload();
        });
    }

    private void deleteSelected() {
        HistoryStore.Entry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        store.delete(selected.id());
        reload();
    }

    private void clearHistory() {
        if (!UiUtils.confirm(stage, "清空历史",
                "会删掉所有未收藏的记录。收藏的那些留着。")) {
            return;
        }
        int removed = store.clearHistory();
        reload();
        UiUtils.showInfo(stage, "已清空", "删掉了 " + removed + " 条。");
    }
}
