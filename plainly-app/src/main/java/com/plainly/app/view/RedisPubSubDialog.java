package com.plainly.app.view;

import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.kv.KeyValueStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道监听与发布。
 *
 * <h2>为什么单独开一个窗口，而不是做成一个标签页</h2>
 * 监听是<b>常驻</b>的：用户开着它去做别的事，消息在这儿滚。做成标签页的话，
 * 切走就看不见了，而「切走的这段时间里到底有没有消息」正是监听要回答的问题。
 *
 * <h2>它占着一条独立连接</h2>
 * {@code SUBSCRIBE} 会把连接切进订阅模式，之后那条连接不再响应普通命令。
 * 所以订阅用的是另开的一条（见 {@code RedisSubscription}），而窗口一关就断掉——
 * 忘记断的话，服务端那边会一直挂着一个订阅者，而界面上再也找不到它。
 */
public class RedisPubSubDialog {

    /** 最多留多少条消息。 */
    private static final int MAX_MESSAGES = 2000;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final DbSession session;
    private final KeyValueStore store;

    private final TextField channelField = new TextField();
    private final TextField patternField = new TextField();
    private final ObservableList<KeyValueStore.Message> messages =
            FXCollections.observableArrayList();
    private final ListView<KeyValueStore.Message> list = new ListView<>(messages);
    private final Label status = UiUtils.label("未订阅", "hint");

    private final TextField publishChannel = new TextField();
    private final TextField publishPayload = new TextField();

    private KeyValueStore.Subscription subscription;
    private Stage stage;

    public RedisPubSubDialog(DbSession session) {
        this.session = session;
        this.store = (KeyValueStore) session.connection();
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);   // 常驻窗口，不挡住主界面
        stage.setTitle("频道监听 · " + session.config().name());

        VBox root = new VBox(buildHead(), buildSubscribeBar(), buildList(), buildPublishBar());
        Scene scene = new Scene(root, 760, 520);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        // 关窗口就断订阅。不断的话服务端会一直挂着一个订阅者，
        // 而界面上再也没有入口能关掉它
        stage.setOnHidden(e -> stopSubscription());
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.search(Icons.ACCENT, 14),
                UiUtils.hSpacer(), status);
        head.getStyleClass().add("dialog-head");
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    private VBox buildSubscribeBar() {
        channelField.setPromptText("频道，多个用逗号分隔，如 orders,alerts");
        patternField.setPromptText("通配频道，如 news.*（PSUBSCRIBE）");
        HBox.setHgrow(channelField, Priority.ALWAYS);
        HBox.setHgrow(patternField, Priority.ALWAYS);

        Button start = UiUtils.toolButton("开始监听", null, "accent");
        start.setOnAction(e -> startSubscription());
        Button stop = UiUtils.toolButton("停止", null);
        stop.setOnAction(e -> stopSubscription());
        Button clear = UiUtils.toolButton("清空", null);
        clear.setOnAction(e -> messages.clear());

        HBox one = UiUtils.row(8, UiUtils.label("频道", "form-label"), channelField);
        HBox two = UiUtils.row(8, UiUtils.label("通配", "form-label"), patternField,
                start, stop, clear);
        one.setAlignment(Pos.CENTER_LEFT);
        two.setAlignment(Pos.CENTER_LEFT);

        VBox box = UiUtils.column(6, one, two);
        box.setPadding(new Insets(10, 12, 8, 12));
        return box;
    }

    private VBox buildList() {
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(KeyValueStore.Message m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setText(null);
                    return;
                }
                String from = m.pattern().isEmpty()
                        ? m.channel()
                        : m.channel() + "（命中 " + m.pattern() + "）";
                setText(CLOCK.format(Instant.ofEpochMilli(m.receivedAtMillis()))
                        + "  [" + from + "]  " + m.payload());
            }
        });
        list.getStyleClass().add("mono");
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = UiUtils.column(4, UiUtils.label("消息", "section-label"), list);
        box.setPadding(new Insets(0, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildPublishBar() {
        publishChannel.setPromptText("频道");
        publishPayload.setPromptText("内容");
        HBox.setHgrow(publishPayload, Priority.ALWAYS);
        publishChannel.setPrefWidth(180);

        Button send = UiUtils.toolButton("发布", null, "accent");
        send.setOnAction(e -> publish());
        publishPayload.setOnAction(e -> publish());

        HBox bar = UiUtils.row(8, UiUtils.label("发布到", "form-label"),
                publishChannel, publishPayload, send);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox box = UiUtils.column(4, bar,
                UiUtils.label("发布是即发即弃的：当时没有订阅者，这条消息就直接丢掉，"
                        + "不会像队列那样存起来等人来取。", "hint"));
        box.setPadding(new Insets(0, 12, 10, 12));
        box.getStyleClass().add("dialog-foot");
        return box;
    }

    // ------------------------------------------------------------------ 动作

    private void startSubscription() {
        stopSubscription();
        List<String> channels = split(channelField.getText());
        List<String> patterns = split(patternField.getText());
        if (channels.isEmpty() && patterns.isEmpty()) {
            status.setText("先填一个频道或通配模式");
            return;
        }
        try {
            subscription = store.subscribe(channels, patterns,
                    // 回调在后台线程上，动界面前必须切回 FX 线程
                    m -> Platform.runLater(() -> append(m)),
                    err -> Platform.runLater(() -> status.setText("连接断了：" + err)));
            status.setText("正在监听 " + (channels.size() + patterns.size()) + " 个频道");
        } catch (RuntimeException e) {
            status.setText("订阅失败：" + UiUtils.rootMessage(e));
        }
    }

    private void stopSubscription() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
            status.setText("已停止");
        }
    }

    /**
     * 新消息加在最上面，并且<b>只有在用户没有往回翻</b>的时候才滚动。
     *
     * <p>正在翻旧消息时被自动滚回顶部，等于让人没法看——而消息流越忙，
     * 这件事越难受。
     */
    private void append(KeyValueStore.Message m) {
        boolean atTop = list.getSelectionModel().isEmpty();
        messages.add(0, m);
        // 超出上限就砍掉最老的。不砍的话，一个高频频道能在几分钟内把内存吃满
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
        if (atTop) {
            list.scrollTo(0);
        }
    }

    private void publish() {
        String channel = publishChannel.getText() == null ? "" : publishChannel.getText().trim();
        if (channel.isEmpty()) {
            status.setText("先填要发到哪个频道");
            return;
        }
        try {
            long n = store.publish(channel, publishPayload.getText() == null
                    ? "" : publishPayload.getText());
            status.setText(n == 0
                    ? "已发出，但当时没有订阅者，这条消息已经丢掉"
                    : "已发出，" + n + " 个订阅者收到");
        } catch (RuntimeException e) {
            status.setText("发布失败：" + UiUtils.rootMessage(e));
        }
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,，\\s]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
