package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.TabStore;
import com.plainly.driver.ConnectionConfig;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * 收藏夹：保存下来的标签页。
 *
 * <p>保存和收藏进的是同一张表，这里一并列出来，收藏的排在前面并带星。
 * 分成两个清单看着清楚，用起来是灾难——用户记得的是「我存过那段 SQL」，
 * 不是「我当时按的是保存还是收藏」。
 */
public class FavoritesDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AppContext context;
    private final TableView<TabStore.SavedTab> table = new TableView<>();
    private final TextField search = new TextField();
    private final CheckBox onlyFavorites = new CheckBox("只看收藏");
    private final Label summary = UiUtils.label("", "hint");

    private Stage stage;
    private Consumer<TabStore.SavedTab> onOpen = t -> { };

    public FavoritesDialog(AppContext context) {
        this.context = context;
    }

    /** 双击或按「打开」时回调。真正怎么打开由主窗口决定——它才拿得到标签页那一排。 */
    public void setOnOpen(Consumer<TabStore.SavedTab> handler) {
        this.onOpen = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("收藏夹");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 860, 560);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        reload();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.check(Icons.ACCENT, 14),
                UiUtils.label("保存过的查询与表，双击打开", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        search.setPromptText("按名字或内容过滤");
        search.textProperty().addListener((o, was, is) -> reload());
        HBox.setHgrow(search, Priority.ALWAYS);
        onlyFavorites.setOnAction(e -> reload());

        buildTable();
        VBox body = UiUtils.column(10, UiUtils.row(10, search, onlyFavorites), table);
        body.setPadding(new Insets(12, 14, 8, 14));
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        return body;
    }

    private void buildTable() {
        table.getStyleClass().add("data-grid");
        table.setPlaceholder(UiUtils.label("还没有保存过标签页。在标签页上右键，选「保存」或「收藏」。",
                "hint"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelected();
            }
        });

        TableColumn<TabStore.SavedTab, String> star = new TableColumn<>("");
        star.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().favorite() ? "★" : ""));
        star.setMaxWidth(40);

        TableColumn<TabStore.SavedTab, String> title = new TableColumn<>("名字");
        title.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));

        TableColumn<TabStore.SavedTab, String> kind = new TableColumn<>("种类");
        kind.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().kind() == TabStore.Kind.TABLE ? "表" : "查询"));
        kind.setMaxWidth(60);

        TableColumn<TabStore.SavedTab, String> where = new TableColumn<>("连接 · 库");
        where.setCellValueFactory(c -> new SimpleStringProperty(describeTarget(c.getValue())));

        TableColumn<TabStore.SavedTab, String> content = new TableColumn<>("内容");
        content.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().oneLine()));

        TableColumn<TabStore.SavedTab, String> when = new TableColumn<>("保存时间");
        when.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().updatedAt().format(WHEN)));
        when.setMaxWidth(110);

        table.getColumns().addAll(star, title, kind, where, content, when);
    }

    /** 连接可能已经被删了——那样这条收藏打不开，得让用户当场看出来。 */
    private String describeTarget(TabStore.SavedTab tab) {
        String name = context.registry().listAll().stream()
                .filter(c -> c.id().equals(tab.connectionId()))
                .map(ConnectionConfig::name)
                .findFirst()
                .orElse("(连接已删除)");
        return tab.schema() == null || tab.schema().isBlank() ? name : name + " · " + tab.schema();
    }

    private HBox buildFoot() {
        Button open = UiUtils.toolButton("打开", null, "primary");
        open.setOnAction(e -> openSelected());

        Button toggle = UiUtils.toolButton("收藏 / 取消", Icons.check(Icons.NEUTRAL, 12));
        toggle.setOnAction(e -> {
            TabStore.SavedTab one = selected();
            if (one != null) {
                context.tabStore().setFavorite(one.id(), !one.favorite());
                reload();
            }
        });

        Button delete = UiUtils.toolButton("删除", Icons.minus("#a0402a", 12));
        delete.setOnAction(e -> {
            TabStore.SavedTab one = selected();
            if (one != null && UiUtils.confirm(stage, "删除",
                    "从收藏夹里删掉「" + one.title() + "」？内容不再保留。")) {
                context.tabStore().delete(one.id());
                reload();
            }
        });

        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, summary, UiUtils.hSpacer(), toggle, delete, close, open);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private TabStore.SavedTab selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void openSelected() {
        TabStore.SavedTab one = selected();
        if (one == null) {
            return;
        }
        onOpen.accept(one);
        stage.close();
    }

    private void reload() {
        List<TabStore.SavedTab> all = context.tabStore().list(onlyFavorites.isSelected());
        String keyword = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        List<TabStore.SavedTab> shown = keyword.isEmpty() ? all
                : all.stream().filter(t -> matches(t, keyword)).toList();
        table.setItems(FXCollections.observableArrayList(shown));
        summary.setText(shown.size() + " 条" + (all.size() == shown.size() ? ""
                : " · 共 " + all.size() + " 条"));
    }

    private static boolean matches(TabStore.SavedTab tab, String keyword) {
        return tab.title().toLowerCase().contains(keyword)
                || tab.oneLine().toLowerCase().contains(keyword);
    }
}
