package com.plainly.app.view;

import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/**
 * 事务控制：自动提交开关 + 提交 / 回滚。
 *
 * <h2>为什么需要它</h2>
 * 在生产库上跑一条 UPDATE，正确的做法是「先跑、看影响了几行、再决定要不要留下」。
 * 只有自动提交的话，语句一发出去就已经落库了，看到影响行数是 12000 的那一刻，
 * 能做的只剩下从备份里捞。这个工具本来就在执行前拦没有 WHERE 的 UPDATE / DELETE，
 * 手动事务是同一件事的下半场——拦不住的那些，还能撤回来。
 *
 * <h2>作用范围是整条连接，不是这个标签页</h2>
 * 一条连接配置对应一个物理连接（见 AppContext），这条连接上开着的所有标签页
 * 共用同一个事务。所以在 SQL 编辑器里开了手动事务，同一条连接的表页里
 * 改数据保存，那些改动也进同一个事务、同样要按「提交」才落库。
 *
 * <p>这一点必须在界面上说出来，而不是让用户自己发现：他很可能在 A 页开了事务，
 * 在 B 页改完数据关掉页面，然后以为已经存进去了。所以开关旁边常驻一句话，
 * 而且未提交时整条控件变色——那是最该被看见的状态。
 */
public class TransactionBar extends HBox {

    private final DbSession session;
    private final Consumer<String> status;

    private final ToggleButton manual = new ToggleButton("手动事务");
    private final Button commit = UiUtils.toolButton("提交", Icons.check(Icons.ACCENT, 12), "accent");
    private final Button rollback = UiUtils.toolButton("回滚", Icons.refresh("#a0402a", 12), "danger");
    private final Label state = UiUtils.label("", "hint");

    /** 注册到会话上的刷新回调。挂在字段上是为了离开界面时摘掉，不然每开一个标签页就多一个。 */
    private final Runnable listener = this::refresh;

    public TransactionBar(DbSession session, Consumer<String> status) {
        super(8);
        this.session = session;
        this.status = status;

        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getStyleClass().add("tx-bar");

        manual.getStyleClass().add("tool-toggle");
        manual.setTooltip(new Tooltip("""
                开启后，执行的写语句不会立即落库，要按「提交」才生效，按「回滚」可以整批撤销。
                作用于整条连接：这条连接上所有标签页的写都在同一个事务里。"""));
        manual.setOnAction(e -> toggleManual());

        commit.setOnAction(e -> run(true));
        rollback.setOnAction(e -> run(false));

        getChildren().addAll(manual, commit, rollback, state);

        // 别的标签页提交了，这里的「未提交」得跟着灭。挂钩挂在「有没有进场景」上：
        // 关掉的标签页要是还攥着一个刷新回调，连接上的监听器就只增不减
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                session.removeTransactionListener(listener);
            } else {
                session.addTransactionListener(listener);
                refresh();
            }
        });

        refresh();
    }

    /**
     * 这条连接支持手动事务吗。不支持时整条控件收起来。
     *
     * <p>{@code managed=false} 才是真的不占位置——只设 visible 会在工具条上
     * 留一段莫名其妙的空白。
     */
    public void applyVisibility() {
        boolean supported = session.connection().supportsManualCommit();
        setVisible(supported);
        setManaged(supported);
    }

    private void toggleManual() {
        boolean want = manual.isSelected();
        try {
            session.connection().setAutoCommit(!want);
            status.accept(want
                    ? "已开启手动事务：写语句要按「提交」才落库（作用于整条连接「"
                            + session.config().name() + "」）"
                    : "已切回自动提交");
        } catch (RuntimeException e) {
            // 手上还有没提交的改动时驱动会拒绝——把开关拨回去，
            // 否则界面显示的状态和连接的真实状态就对不上了
            UiUtils.showError(window(), "切换失败", e);
        }
        session.fireTransactionChanged();
    }

    private void run(boolean isCommit) {
        try {
            if (isCommit) {
                session.connection().commit();
                status.accept("已提交");
            } else {
                session.connection().rollback();
                status.accept("已回滚，这个事务里的改动全部撤销");
            }
        } catch (RuntimeException e) {
            UiUtils.showError(window(), isCommit ? "提交失败" : "回滚失败", e);
        }
        session.fireTransactionChanged();
    }

    /** 按连接此刻的真实状态重画。所有状态都从连接问，不在这里另存一份。 */
    public void refresh() {
        boolean supported = session.connection().supportsManualCommit();
        if (!supported) {
            return;
        }
        boolean manualMode = session.manualTransaction();
        boolean pending = session.hasPendingTransaction();

        manual.setSelected(manualMode);
        commit.setDisable(!manualMode);
        rollback.setDisable(!manualMode);

        if (!manualMode) {
            state.setText("自动提交");
        } else if (pending) {
            state.setText("有未提交的改动");
        } else {
            state.setText("事务已开始 · 暂无改动");
        }
        state.getStyleClass().removeAll("tx-pending", "hint");
        state.getStyleClass().add(pending ? "tx-pending" : "hint");

        getStyleClass().remove("tx-bar-pending");
        if (pending) {
            getStyleClass().add("tx-bar-pending");
        }
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
