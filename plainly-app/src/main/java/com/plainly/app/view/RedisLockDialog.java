package com.plainly.app.view;

import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.kv.KeyValueStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import javafx.util.Duration;

import java.util.List;
import java.util.function.Function;

/**
 * 分布式锁。
 *
 * <h2>释放锁这件事必须校验持有者</h2>
 * 界面上「释放」按的不是 {@code DEL}，而是一段在服务端一次跑完的比对加删除
 * （见 {@code KeyValueStore.releaseLock}）。差别在于：从看到这一行、
 * 到点下按钮，中间可能过了几秒——这几秒里锁完全可能过期并被别人拿到。
 * 直接 {@code DEL} 删掉的就是<b>别人刚拿到的锁</b>，两个进程同时进临界区，
 * 而且事后极难查。
 *
 * <h2>「像锁」由用户说了算</h2>
 * Redis 里锁就是一个带过期时间的普通字符串键，和缓存长得一模一样。
 * 这里不去猜哪些键是锁——猜错的话，用户会对着一屏缓存以为那是锁，
 * 或者更糟，把缓存当锁释放掉。所以模式由用户填，默认给个常见的 {@code lock:*}。
 */
public class RedisLockDialog {

    private static final int LIMIT = 500;

    private final DbSession session;
    private final KeyValueStore store;

    private final TextField patternField = new TextField("lock:*");
    private final ObservableList<KeyValueStore.LockInfo> locks =
            FXCollections.observableArrayList();
    private final TableView<KeyValueStore.LockInfo> table = new TableView<>(locks);
    private final Label status = UiUtils.label("未扫描", "hint");

    private Timeline timer;
    private Stage stage;

    public RedisLockDialog(DbSession session) {
        this.session = session;
        this.store = (KeyValueStore) session.connection();
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("分布式锁 · " + session.config().name());

        VBox root = new VBox(buildHead(), buildBar(), buildTable(), buildFoot());
        Scene scene = new Scene(root, 820, 520);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> stopTimer());
        stage.show();
        scan();
    }

    private HBox buildHead() {
        HBox box = UiUtils.row(8, Icons.key(Icons.ACCENT, 14),
                UiUtils.hSpacer(), status);
        box.getStyleClass().add("dialog-head");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildBar() {
        patternField.setPromptText("键名模式，如 lock:*");
        HBox.setHgrow(patternField, Priority.ALWAYS);

        Button scan = UiUtils.toolButton("扫描", null, "accent");
        scan.setOnAction(e -> scan());
        patternField.setOnAction(e -> scan());

        Button auto = UiUtils.toolButton("每 3 秒刷新", null);
        auto.setOnAction(e -> toggleTimer(auto));

        Button release = UiUtils.toolButton("释放选中", Icons.minus("#a0402a", 12), "danger");
        release.setOnAction(e -> releaseSelected());

        HBox bar = UiUtils.row(8, UiUtils.label("模式", "form-label"), patternField,
                scan, auto, UiUtils.vSeparator(), release);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 6, 12));
        return bar;
    }

    private VBox buildTable() {
        table.getColumns().addAll(List.of(
                column("键", KeyValueStore.LockInfo::key, 300),
                column("持有者", KeyValueStore.LockInfo::holder, 220),
                column("剩余", this::describeTtl, 140)));
        table.getStyleClass().add("data-grid");
        table.setPlaceholder(UiUtils.label("没有匹配的键", "hint"));
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox box = UiUtils.column(4, table);
        box.setPadding(new Insets(0, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private <T> TableColumn<KeyValueStore.LockInfo, String> column(
            String title, Function<KeyValueStore.LockInfo, String> get, double width) {
        TableColumn<KeyValueStore.LockInfo, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(get.apply(c.getValue())));
        return col;
    }

    /**
     * 没有过期时间要说得刺眼一点。
     *
     * <p>那通常是个 bug：持有方一旦崩了，这把锁永远不会自己释放，
     * 而所有等它的进程会一直等下去。
     */
    private String describeTtl(KeyValueStore.LockInfo lock) {
        if (lock.neverExpires()) {
            return "永不过期（可能是 bug）";
        }
        long s = lock.ttlSeconds();
        if (s < 60) {
            return s + " 秒";
        }
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    private VBox buildFoot() {
        VBox box = UiUtils.column(2,
                UiUtils.label("「释放」不是直接删：它在服务端一步完成「比对持有者 + 删除」。"
                        + "从你看到这一行到点下按钮，锁可能已经过期并被别人拿到——"
                        + "直接删就会删掉别人刚拿到的锁。", "hint"),
                UiUtils.label("Redis 里锁就是个带过期时间的普通字符串键，和缓存长得一样，"
                        + "所以哪些算锁由上面的模式决定。", "hint"));
        box.setPadding(new Insets(0, 12, 10, 12));
        box.getStyleClass().add("dialog-foot");
        return box;
    }

    // ------------------------------------------------------------------ 动作

    private void toggleTimer(Button button) {
        if (timer != null) {
            stopTimer();
            button.setText("每 3 秒刷新");
            return;
        }
        timer = new Timeline(new KeyFrame(Duration.seconds(3), e -> scan()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        button.setText("停止刷新");
        status.setText("每 3 秒刷新");
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private void scan() {
        String pattern = patternField.getText() == null ? "" : patternField.getText().trim();
        new Thread(() -> {
            try {
                List<KeyValueStore.LockInfo> found = store.scanLocks(
                        session.connection().config().database(), pattern, LIMIT);
                Platform.runLater(() -> {
                    // 保住选中项：定时刷新时把用户的选择顶掉，
                    // 会让「选中再点释放」这个动作在刷新的那一刻失手
                    KeyValueStore.LockInfo selected =
                            table.getSelectionModel().getSelectedItem();
                    locks.setAll(found);
                    if (selected != null) {
                        found.stream().filter(l -> l.key().equals(selected.key())).findFirst()
                                .ifPresent(l -> table.getSelectionModel().select(l));
                    }
                    status.setText(found.size() + " 把 · " + java.time.LocalTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText(UiUtils.rootMessage(e));
                    stopTimer();
                });
            }
        }, "plainly-lock-scan").start();
    }

    private void releaseSelected() {
        KeyValueStore.LockInfo lock = table.getSelectionModel().getSelectedItem();
        if (lock == null) {
            status.setText("先选中一把锁");
            return;
        }
        boolean ok = UiUtils.confirm(stage, "释放锁",
                "要释放这把锁吗？\n\n键：" + lock.key()
                        + "\n持有者：" + lock.holder()
                        + "\n\n只有持有者仍然是上面这个时才会真的删掉。"
                        + "如果持有方还在正常工作，释放会让它失去互斥保护。");
        if (!ok) {
            return;
        }
        try {
            boolean released = store.releaseLock(
                    session.connection().config().database(), lock.key(), lock.holder());
            status.setText(released
                    ? "已释放 " + lock.key()
                    : "没有释放：持有者已经变了，或者这把锁本来就已经过期了");
            scan();
        } catch (RuntimeException e) {
            status.setText(UiUtils.rootMessage(e));
        }
    }
}
