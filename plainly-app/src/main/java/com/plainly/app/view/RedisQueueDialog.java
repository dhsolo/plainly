package com.plainly.app.view;

import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.kv.KeyValueStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Deque;

/**
 * 队列监听。
 *
 * <h2>只看不取</h2>
 * 这个窗口用的全是纯读命令（{@code LLEN}/{@code LRANGE}/{@code XLEN}/
 * {@code XRANGE}/{@code XINFO}），<b>绝不消费</b>。监听工具把别人的消息取走，
 * 是这个功能能犯的最严重的错误——消费方收不到、消息没了，
 * 而且没有任何痕迹指向这个工具。界面上也把这句话写出来，
 * 免得用户不敢开着它看生产队列。
 *
 * <h2>为什么要记住历史值</h2>
 * 队列监听真正要回答的是「在涨还是在消」，而不是「现在多少条」。
 * 单看一个数字看不出方向，所以留最近若干次采样，显示变化量和一条数字条。
 */
public class RedisQueueDialog {

    /** 队头看几条。 */
    private static final int PEEK = 20;

    /** 留多少次采样。 */
    private static final int HISTORY = 30;

    private final DbSession session;
    private final KeyValueStore store;

    private final TextField keyField = new TextField();
    private final ComboBox<Integer> intervalBox = new ComboBox<>();
    private final Label depthLabel = UiUtils.label("—", "dialog-title");
    private final Label trendLabel = UiUtils.label("", "hint");
    private final Label historyLabel = UiUtils.label("", "mono");
    private final Label status = UiUtils.label("未开始", "hint");

    private final ObservableList<String> head = FXCollections.observableArrayList();
    private final ObservableList<KeyValueStore.ConsumerGroup> groups =
            FXCollections.observableArrayList();
    private final TableView<KeyValueStore.ConsumerGroup> groupTable = new TableView<>(groups);

    private final Deque<Long> depths = new ArrayDeque<>();
    private Timeline timer;
    private Stage stage;

    public RedisQueueDialog(DbSession session, String initialKey) {
        this.session = session;
        this.store = (KeyValueStore) session.connection();
        if (initialKey != null) {
            keyField.setText(initialKey);
        }
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("队列监听 · " + session.config().name());

        VBox root = new VBox(buildHead(), buildBar(), buildDepth(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 780, 560);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> stopTimer());
        stage.show();
    }

    private HBox buildHead() {
        HBox box = UiUtils.row(8, Icons.table(Icons.ACCENT, 14),
                UiUtils.hSpacer(), status);
        box.getStyleClass().add("dialog-head");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildBar() {
        keyField.setPromptText("队列的键名（list 或 stream）");
        HBox.setHgrow(keyField, Priority.ALWAYS);

        intervalBox.getItems().addAll(1, 2, 5, 10);
        intervalBox.setValue(2);
        intervalBox.setPrefWidth(80);

        Button start = UiUtils.toolButton("开始", null, "accent");
        start.setOnAction(e -> startTimer());
        Button stop = UiUtils.toolButton("停止", null);
        stop.setOnAction(e -> stopTimer());
        Button once = UiUtils.toolButton("看一次", null);
        once.setOnAction(e -> sample());

        HBox bar = UiUtils.row(8, UiUtils.label("队列", "form-label"), keyField,
                UiUtils.label("每", "form-label"), intervalBox, UiUtils.label("秒", "form-label"),
                once, start, stop);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 6, 12));
        return bar;
    }

    private VBox buildDepth() {
        HBox line = UiUtils.row(10, UiUtils.label("待处理", "form-label"),
                depthLabel, trendLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox box = UiUtils.column(4, line, historyLabel);
        box.setPadding(new Insets(0, 12, 8, 12));
        return box;
    }

    private VBox buildBody() {
        ListView<String> headList = new ListView<>(head);
        headList.getStyleClass().add("mono");
        VBox.setVgrow(headList, Priority.ALWAYS);

        TableColumn<KeyValueStore.ConsumerGroup, String> name = column("消费组",
                g -> g.name(), 140);
        TableColumn<KeyValueStore.ConsumerGroup, String> consumers = column("消费者",
                g -> String.valueOf(g.consumers()), 70);
        TableColumn<KeyValueStore.ConsumerGroup, String> pending = column("未确认",
                g -> String.valueOf(g.pending()), 80);
        TableColumn<KeyValueStore.ConsumerGroup, String> lag = column("未投递",
                g -> g.lag() < 0 ? "—" : String.valueOf(g.lag()), 80);
        TableColumn<KeyValueStore.ConsumerGroup, String> last = column("最后投递",
                g -> g.lastDelivered(), 180);
        groupTable.getColumns().addAll(List.of(name, consumers, pending, lag, last));
        groupTable.getStyleClass().add("data-grid");
        groupTable.setPlaceholder(UiUtils.label("没有消费组（列表队列本来就没有）", "hint"));
        groupTable.setPrefHeight(150);

        VBox box = UiUtils.column(6,
                UiUtils.label("队头（下一批会被处理的）", "section-label"), headList,
                UiUtils.label("消费组", "section-label"), groupTable);
        box.setPadding(new Insets(0, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private static TableColumn<KeyValueStore.ConsumerGroup, String> column(
            String title, java.util.function.Function<KeyValueStore.ConsumerGroup, String> get,
            double width) {
        TableColumn<KeyValueStore.ConsumerGroup, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                get.apply(c.getValue())));
        return col;
    }

    private VBox buildFoot() {
        VBox box = UiUtils.column(2,
                UiUtils.label("这个窗口只读不取：用的是 LLEN / LRANGE / XLEN / XRANGE / XINFO，"
                        + "不会把消息从队列里取走。", "hint"),
                UiUtils.label("「未确认」一直涨通常意味着消费方处理失败或没在跑——"
                        + "这时队列长度可能看着很正常。", "hint"));
        box.setPadding(new Insets(0, 12, 10, 12));
        box.getStyleClass().add("dialog-foot");
        return box;
    }

    // ------------------------------------------------------------------ 采样

    private void startTimer() {
        stopTimer();
        if (keyField.getText() == null || keyField.getText().isBlank()) {
            status.setText("先填队列的键名");
            return;
        }
        sample();
        int seconds = intervalBox.getValue() == null ? 2 : intervalBox.getValue();
        timer = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> sample()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        status.setText("每 " + seconds + " 秒看一次");
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
            status.setText("已停止");
        }
    }

    /**
     * 取一次样。
     *
     * <p>放到后台线程上：这条连接可能正忙，或者网络慢。在 FX 线程上直接发命令，
     * 界面会一卡一卡的，而定时器每隔几秒就来一次，卡顿会一直持续。
     */
    private void sample() {
        String key = keyField.getText() == null ? "" : keyField.getText().trim();
        if (key.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                KeyValueStore.QueueSnapshot snap =
                        store.inspectQueue(session.connection().config().database(), key, PEEK);
                Platform.runLater(() -> render(snap));
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText(UiUtils.rootMessage(e));
                    stopTimer();
                });
            }
        }, "plainly-queue-sample").start();
    }

    private void render(KeyValueStore.QueueSnapshot snap) {
        depthLabel.setText(String.valueOf(snap.depth()));

        Long previous = depths.peekLast();
        if (previous != null) {
            long delta = snap.depth() - previous;
            trendLabel.setText(delta == 0 ? "持平"
                    : (delta > 0 ? "↑ 涨了 " + delta : "↓ 消了 " + (-delta)));
        }
        depths.addLast(snap.depth());
        while (depths.size() > HISTORY) {
            depths.removeFirst();
        }
        historyLabel.setText(String.join(" ",
                depths.stream().map(String::valueOf).toList()));

        head.setAll(snap.head());
        groups.setAll(snap.groups());
        status.setText(snap.type() + " · 采样于 " + java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
    }
}
