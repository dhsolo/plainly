import com.plainly.app.DbSession;
import com.plainly.app.view.RedisPubSubDialog;
import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import com.plainly.driver.kv.KeyValueStore;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 拿<b>真实的</b>频道监听窗口跑一遍：填频道、开始监听、发一条、看它出现没有。
 *
 * <h2>为什么不是仿制一个等价的窗口</h2>
 * 之前在结构设计器上吃过这个亏：照着写了一个等价的表格去验，结论是「通过」，
 * 而用户在界面上仍然丢改动——差的正是仿制品没复制到的那一部分。
 * 仿制品验不出仿制品没复制到的东西。
 *
 * <p>这里连的是真 Redis，会发几条 {@code plainly:probe:*} 的消息，不留痕迹
 * （发布是即发即弃的，不写键）。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/PubSubDialogProbe.java ["连接名"]
 * </pre>
 */
public class PubSubDialogProbe extends Application {

    private LocalStore store;

    /** 所有步骤跑完才收尾——分步之后，run() 返回时事情还没做完。 */
    private void finish() {
        System.out.print(LOG);
        if (store != null) {
            store.close();
        }
        Platform.exit();
    }

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static final String CHAN = "plainly:probe:ui";

    @Override
    public void start(Stage stage) {
        String name = getParameters().getRaw().isEmpty() ? "测试" : getParameters().getRaw().get(0);
        LocalStore local = new LocalStore();
        ConnectionRegistry registry =
                new ConnectionRegistry(local, CredentialStore.forCurrentPlatform());
        ConnectionConfig cfg = registry.listAll().stream()
                .filter(c -> c.name().equals(name) && c.type() == DbType.REDIS)
                .findFirst().orElse(null);
        if (cfg == null) {
            System.out.println("找不到 Redis 连接：" + name);
            Platform.exit();
            return;
        }
        DbSession session = new DbSession(cfg, Connections.open(registry.resolvePassword(cfg)));

        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();

        RedisPubSubDialog dialog = new RedisPubSubDialog(session);
        dialog.show(stage);

        this.store = local;
        delay(600, () -> {
            try {
                run(session);
            } catch (RuntimeException e) {
                LOG.append("探针自己出错了：").append(e).append(NL);
                finish();
            }
        });
    }

    /**
     * 分步跑，每一步之间把控制权还给事件循环。
     *
     * <h2>为什么不能在这里 sleep</h2>
     * 这个方法是从 PauseTransition 的回调进来的，跑在 <b>FX 线程</b>上。
     * 消息是后台线程通过 {@code Platform.runLater} 投递的——在 FX 线程上 sleep
     * 会把事件队列整个堵住，那个 runLater 永远排不上，列表于是始终是空的。
     *
     * <p>上一版就是这么写的，结论是「窗口没收到消息」，而驱动层明明收到了、
     * 订阅者数也是 1。<b>一个把被测代码的必要条件亲手破坏掉的测试，
     * 报出来的失败是假的。</b>
     */
    private void run(DbSession session) {
        Stage window = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith("频道监听")) {
                window = s;
            }
        }
        if (window == null) {
            LOG.append("× 没找到频道监听窗口").append(NL);
            finish();
            return;
        }
        Parent root = window.getScene().getRoot();

        TextField channel = findPrompt(root, "频道，多个用逗号分隔");
        Button start = findButton(root, "开始监听");
        ListView<?> list = find(root, ListView.class);
        Label status = findStatus(root);
        if (channel == null || start == null || list == null) {
            LOG.append("× 窗口里少了控件：频道框/开始按钮/消息列表").append(NL);
            finish();
            return;
        }

        channel.setText(CHAN);
        start.fire();
        LOG.append("点了「开始监听」，状态：")
                .append(status == null ? "?" : status.getText()).append(NL);

        Stage target = window;
        // 一、等订阅建立
        delay(800, () -> {
            long n = ((KeyValueStore) session.connection()).publish(CHAN, "来自探针的消息");
            LOG.append("发布出去，订阅者数：").append(n).append(NL);

            // 二、等消息经 runLater 投递到列表
            delay(900, () -> {
                LOG.append("消息列表里现在有 ").append(list.getItems().size()).append(" 条").append(NL);
                boolean got = list.getItems().stream()
                        .anyMatch(x -> String.valueOf(x).contains("来自探针的消息"));
                LOG.append(got ? "  正确，窗口收到了消息" : "× 窗口没收到消息").append(NL);
                LOG.append("状态栏：").append(status == null ? "?" : status.getText()).append(NL);

                // 三、关窗口应当把订阅断掉
                target.hide();
                delay(400, () -> {
                    long after = ((KeyValueStore) session.connection())
                            .publish(CHAN, "关掉之后再发");
                    LOG.append("关窗口后再发，订阅者数：").append(after)
                            .append(after == 0 ? "    正确，订阅已随窗口关闭"
                                    : "  × 订阅还挂着没断").append(NL);
                    finish();
                });
            });
        });
    }

    // ------------------------------------------------------------------ 工具

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    private static TextField findPrompt(Parent root, String prefix) {
        for (Node n : all(root)) {
            if (n instanceof TextField f && f.getPromptText() != null
                    && f.getPromptText().startsWith(prefix)) {
                return f;
            }
        }
        return null;
    }

    private static Button findButton(Parent root, String text) {
        for (Node n : all(root)) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private static Label findStatus(Parent root) {
        Label last = null;
        for (Node n : all(root)) {
            if (n instanceof Label l && l.getStyleClass().contains("hint")) {
                last = last == null ? l : last;
            }
        }
        return last;
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Parent root, Class<T> type) {
        for (Node n : all(root)) {
            if (type.isInstance(n)) {
                return (T) n;
            }
        }
        return null;
    }

    private static List<Node> all(Parent root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            out.add(child);
            if (child instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    public static void main(String[] args) {
        launch(PubSubDialogProbe.class, args);
    }
}
