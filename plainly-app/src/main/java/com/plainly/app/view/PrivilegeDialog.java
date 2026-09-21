package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.security.PrivilegeService;
import com.plainly.driver.ConnectionConfig;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SplitPane;
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
 * 用户与权限（只读）。
 *
 * <p>「谁能动这张表」是排查线上问题时最常问的一件事。这一版只看不改：
 * 改权限发错一条就可能让生产库上的应用连不上，而各家的权限模型差得很远，
 * 没有真实实例验证过的 GRANT 不该发出去。界面上把这句话说明白，
 * 而不是摆一个点了没反应的「新建用户」。
 */
public class PrivilegeDialog {

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final TextField filter = new TextField();
    private final TableView<PrivilegeService.User> userTable = new TableView<>();
    private final TableView<PrivilegeService.Grant> grantTable = new TableView<>();
    private final Label note = UiUtils.label("", "hint");

    private List<PrivilegeService.User> allUsers = List.of();
    private Stage stage;

    public PrivilegeDialog(AppContext context) {
        this.context = context;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("用户与权限");

        VBox root = new VBox(buildHead(), buildToolbar(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.key(Icons.ACCENT, 14),
                UiUtils.label("只读。这一版不提供改权限——发错一条 GRANT 的代价太大", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildToolbar() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(240);
        connBox.valueProperty().addListener((o, was, is) -> loadUsers());

        filter.setPromptText("按用户名过滤");
        filter.setPrefWidth(200);
        filter.textProperty().addListener((o, was, is) -> applyFilter());

        Button refresh = UiUtils.toolButton("刷新", Icons.refresh(Icons.NEUTRAL, 12));
        refresh.setOnAction(e -> loadUsers());

        HBox bar = UiUtils.row(8, connBox, refresh, filter, UiUtils.hSpacer(), note);
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private SplitPane buildBody() {
        userTable.getStyleClass().add("data-grid");
        userTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        userTable.setPlaceholder(UiUtils.label("选一个连接", "hint"));
        userTable.getColumns().add(userCol("用户", PrivilegeService.User::display, 200));
        userTable.getColumns().add(userCol("说明", PrivilegeService.User::note, 220));
        userTable.getSelectionModel().selectedItemProperty()
                .addListener((o, was, is) -> loadGrants(is));

        grantTable.getStyleClass().add("data-grid");
        grantTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        grantTable.setPlaceholder(UiUtils.label("在左边选一个用户", "hint"));
        grantTable.getColumns().add(grantCol("作用于", PrivilegeService.Grant::scope, 200));
        grantTable.getColumns().add(grantCol("权限", PrivilegeService.Grant::detail, 520));

        VBox left = UiUtils.column(6, UiUtils.label("用户 / 角色", "section-label"), userTable);
        left.setPadding(new Insets(10));
        VBox.setVgrow(userTable, Priority.ALWAYS);

        VBox right = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("权限", "section-label"),
                        UiUtils.label("MySQL 显示 SHOW GRANTS 的原文；"
                                + "PostgreSQL 显示角色成员关系与表级授权", "hint")),
                grantTable);
        right.setPadding(new Insets(10));
        VBox.setVgrow(grantTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.36);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private TableColumn<PrivilegeService.User, String> userCol(
            String title, java.util.function.Function<PrivilegeService.User, String> getter,
            double width) {
        TableColumn<PrivilegeService.User, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    private TableColumn<PrivilegeService.Grant, String> grantCol(
            String title, java.util.function.Function<PrivilegeService.Grant, String> getter,
            double width) {
        TableColumn<PrivilegeService.Grant, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private void loadUsers() {
        ConnectionConfig config = connBox.getValue();
        if (config == null) {
            return;
        }
        note.setText("读取中…");
        grantTable.setItems(FXCollections.observableArrayList());
        context.queryService()
                .submit(() -> PrivilegeService.listUsers(context.openSession(config).connection()))
                .whenComplete((users, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        // 「读不到」和「这个库没有用户」是两回事。原因照原样显示，
                        // 用户才知道是该换个账号，还是这一家本来就不支持
                        allUsers = List.of();
                        userTable.setItems(FXCollections.observableArrayList());
                        note.setText(UiUtils.rootMessage(error));
                        return;
                    }
                    allUsers = users;
                    applyFilter();
                    note.setText("共 " + users.size() + " 个用户 / 角色");
                }));
    }

    private void applyFilter() {
        String needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase();
        userTable.setItems(FXCollections.observableArrayList(
                allUsers.stream()
                        .filter(u -> needle.isEmpty()
                                || u.display().toLowerCase().contains(needle))
                        .toList()));
    }

    private void loadGrants(PrivilegeService.User user) {
        ConnectionConfig config = connBox.getValue();
        if (config == null || user == null) {
            return;
        }
        context.queryService()
                .submit(() -> PrivilegeService.grantsOf(
                        context.openSession(config).connection(), user))
                .whenComplete((grants, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        grantTable.setItems(FXCollections.observableArrayList());
                        note.setText("读不到 " + user.display() + " 的权限："
                                + UiUtils.rootMessage(error));
                        return;
                    }
                    grantTable.setItems(FXCollections.observableArrayList(grants));
                    note.setText(user.display() + " · " + grants.size() + " 条");
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
