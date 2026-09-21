package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.monitor.ServerMonitor;
import com.plainly.driver.ConnectionConfig;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 服务器状态。
 *
 * <p>轻量的一眼看：连接数、运行时长、当前会话。专业监控工具做得好得多，
 * 这里只解决「手边这个库现在忙不忙、有没有人卡住」。
 *
 * <p>问不到的指标就不显示——把不支持的项目显示成 0，比不显示更容易误导人。
 */
public class MonitorDialog {

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final TableView<ServerMonitor.Metric> metricTable = new TableView<>();
    private final TableView<ServerMonitor.Sessionment> sessionTable = new TableView<>();
    private final CheckBox autoRefresh = new CheckBox("每 5 秒自动刷新");
    private final Label note = UiUtils.label("", "hint");

    private final Button killButton =
            UiUtils.toolButton("结束会话", Icons.minus("#a0402a", 12), "danger");
    private final Button cancelButton =
            UiUtils.toolButton("取消它正在跑的语句", Icons.stop(Icons.NEUTRAL, 12));

    private Timeline timer;
    private Stage stage;
    /** 本工具自己这条连接的会话号，用来把自己那一行标出来。 */
    private String selfId;

    public MonitorDialog(AppContext context) {
        this.context = context;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("服务器状态");

        VBox root = new VBox(buildHead(), buildToolbar(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1040, 700);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        // 关窗时把定时器停掉，否则它会一直往一个已经没人看的界面里推数据
        stage.setOnHidden(e -> stopTimer());
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.plan(Icons.ACCENT, 14),
                UiUtils.label("轻量的一眼看，不替代专业监控", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildToolbar() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(240);
        connBox.valueProperty().addListener((o, was, is) -> refresh());

        Button refresh = UiUtils.toolButton("刷新", Icons.refresh(Icons.NEUTRAL, 12));
        refresh.setOnAction(e -> refresh());
        autoRefresh.setOnAction(e -> {
            if (autoRefresh.isSelected()) {
                startTimer();
            } else {
                stopTimer();
            }
        });

        HBox bar = UiUtils.row(8, connBox, refresh, autoRefresh, UiUtils.hSpacer(), note);
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private SplitPane buildBody() {
        metricTable.getStyleClass().add("data-grid");
        metricTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        metricTable.setPlaceholder(UiUtils.label("选一个连接", "hint"));

        TableColumn<ServerMonitor.Metric, String> name = new TableColumn<>("指标");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<ServerMonitor.Metric, String> value = new TableColumn<>("值");
        value.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value()));
        TableColumn<ServerMonitor.Metric, String> hint = new TableColumn<>("说明");
        hint.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().note()));
        metricTable.getColumns().addAll(name, value, hint);

        sessionTable.getStyleClass().add("data-grid");
        sessionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        sessionTable.setPlaceholder(UiUtils.label("没有会话，或没有查看权限", "hint"));

        sessionTable.getColumns().add(col("会话", ServerMonitor.Sessionment::id, 70));
        sessionTable.getColumns().add(col("用户", ServerMonitor.Sessionment::user, 110));
        sessionTable.getColumns().add(col("来自", ServerMonitor.Sessionment::host, 140));
        sessionTable.getColumns().add(col("库", ServerMonitor.Sessionment::database, 110));
        sessionTable.getColumns().add(col("状态", ServerMonitor.Sessionment::state, 130));
        sessionTable.getColumns().add(col("秒", ServerMonitor.Sessionment::seconds, 60));
        sessionTable.getColumns().add(col("语句", ServerMonitor.Sessionment::query, 320));

        killButton.setOnAction(e -> doKill(false));
        cancelButton.setOnAction(e -> doKill(true));
        sessionTable.getSelectionModel().selectedItemProperty()
                .addListener((o, was, is) -> refreshKillButtons());
        refreshKillButtons();

        VBox top = UiUtils.column(6, UiUtils.label("指标", "section-label"), metricTable);
        top.setPadding(new Insets(10));
        VBox.setVgrow(metricTable, Priority.ALWAYS);

        // 自己那一行标出来。把自己掐掉之后的表现是「点了结束会话，工具就断线了」——
        // 用户完全不会把这两件事联系起来
        sessionTable.setRowFactory(t -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(ServerMonitor.Sessionment item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-self");
                if (!empty && item != null && item.id() != null && item.id().equals(selfId)) {
                    getStyleClass().add("row-self");
                }
            }
        });

        VBox bottom = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("当前会话", "section-label"),
                        UiUtils.label("按已运行时长排序，最久的在前", "hint"),
                        UiUtils.hSpacer(), cancelButton, killButton),
                sessionTable);
        bottom.setPadding(new Insets(10));
        VBox.setVgrow(sessionTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.4);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private TableColumn<ServerMonitor.Sessionment, String> col(
            String title, java.util.function.Function<ServerMonitor.Sessionment, String> getter,
            double width) {
        TableColumn<ServerMonitor.Sessionment, String> c = new TableColumn<>(title);
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

    // ------------------------------------------------------------------ 结束会话

    /**
     * 按当前连接和选中行更新两个按钮。
     *
     * <p>三种「不能点」的理由完全不同，所以各自写进悬停里而不是一律灰掉了事：
     * 这一家没接、没选中行、选中的是本工具自己。灰着又不说为什么，
     * 用户只能反复点。
     */
    private void refreshKillButtons() {
        ConnectionConfig config = connBox.getValue();
        ServerMonitor.Sessionment picked = sessionTable.getSelectionModel().getSelectedItem();
        com.plainly.app.DbSession session =
                config == null ? null : context.sessionFor(config.id());

        boolean cancelShown = session != null && ServerMonitor.supportsCancelOnly(session.connection());
        cancelButton.setVisible(cancelShown);
        cancelButton.setManaged(cancelShown);

        String reason = session == null
                ? "先选一个已连接的连接"
                : ServerMonitor.killUnsupportedReason(session.connection());
        if (reason == null && picked == null) {
            reason = "先在下面点中一个会话";
        }
        if (reason == null && picked.id() != null && picked.id().equals(selfId)) {
            reason = "这是本工具自己的连接。掐掉它，界面会立刻断线——"
                    + "看起来就像软件崩了，而不是你刚做了什么";
        }
        killButton.setDisable(reason != null);
        cancelButton.setDisable(reason != null);
        String tip = reason == null
                ? "断开这个会话。它正在跑的语句会被中断，未提交的事务会回滚"
                : reason;
        killButton.setTooltip(new javafx.scene.control.Tooltip(tip));
        cancelButton.setTooltip(new javafx.scene.control.Tooltip(reason == null
                ? "只中断它当前那条语句，会话本身留着（PostgreSQL 的 pg_cancel_backend）"
                : reason));
    }

    private void doKill(boolean cancelOnly) {
        ConnectionConfig config = connBox.getValue();
        ServerMonitor.Sessionment picked = sessionTable.getSelectionModel().getSelectedItem();
        if (config == null || picked == null) {
            return;
        }
        // 别人的会话被掐掉，他手上没提交的事务就没了。问一句，并且把「谁、在跑什么」
        // 摆出来——会话号本身说明不了任何事，用户没法凭它判断这一下该不该点
        String what = cancelOnly ? "取消这个会话正在执行的语句" : "结束这个会话";
        StringBuilder ask = new StringBuilder(what).append("？")
                .append(System.lineSeparator())
                .append("会话 ").append(picked.id())
                .append("  ·  用户 ").append(picked.user())
                .append("  ·  已运行 ").append(picked.seconds()).append(" 秒");
        if (picked.query() != null && !picked.query().isBlank()) {
            ask.append(System.lineSeparator()).append("正在执行：")
                    .append(picked.query().length() > 200
                            ? picked.query().substring(0, 200) + "…" : picked.query());
        }
        if (!cancelOnly) {
            ask.append(System.lineSeparator())
                    .append("它未提交的事务会被回滚，那些改动找不回来。");
        }
        if (!UiUtils.confirm(stage, what, ask.toString())) {
            return;
        }

        context.queryService()
                .submit(() -> ServerMonitor.kill(
                        context.openSession(config).connection(), picked.id(), cancelOnly))
                .whenComplete((message, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "操作失败", error);
                        return;
                    }
                    note.setText(message);
                    refresh();
                }));
    }

    private void startTimer() {
        stopTimer();
        timer = new Timeline(new KeyFrame(Duration.seconds(5), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private void refresh() {
        ConnectionConfig config = connBox.getValue();
        if (config == null) {
            return;
        }
        context.queryService()
                .submit(() -> ServerMonitor.sample(context.openSession(config).connection()))
                .whenComplete((snapshot, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        note.setText(UiUtils.rootMessage(error));
                        return;
                    }
                    metricTable.setItems(
                            FXCollections.observableArrayList(snapshot.metrics()));
                    sessionTable.setItems(
                            FXCollections.observableArrayList(snapshot.sessions()));
                    selfId = snapshot.selfId();
                    note.setText(snapshot.problem() == null
                            ? "更新于 " + java.time.LocalTime.now().withNano(0)
                            : snapshot.problem());
                    refreshKillButtons();
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
