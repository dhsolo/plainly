package com.plainly.app.ui;

import com.plainly.core.store.UiState;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 记住窗口上次的大小和位置，下次原样打开。
 *
 * <h2>为什么不能存了就直接用</h2>
 * 存下来的坐标是<b>上次那套显示器</b>上的坐标。笔记本在公司接着外接屏，
 * 窗口拖到副屏、关掉；回家一开——那个位置在物理上已经不存在了。
 * JavaFX 不会拦这件事：窗口老老实实开在屏幕外，进程活着、界面永远看不见，
 * 用户只能以为程序坏了。
 *
 * <p>所以每次恢复都要拿当前的屏幕布局重新校一遍：位置落不进任何一块屏幕，
 * 就只留尺寸、丢掉位置，让窗口居中开出来。<b>尺寸也要收进屏幕里</b>——
 * 上次在 2560 宽的屏上拉满，这次在 1366 的笔记本上打开，
 * 底部那条状态栏和按钮会整个掉到可视区外面。
 */
public final class WindowState {

    /** 出厂尺寸。屏幕更小时会按下面的规则收窄。 */
    private static final double DEFAULT_WIDTH = 1440;
    private static final double DEFAULT_HEIGHT = 900;

    /**
     * 判定「这个位置还在屏幕上」时，要求露出来的最小面积。
     *
     * <p>不要求整个窗口都在屏幕内：跨屏摆放、故意让窗口右侧超出边界，都是正常用法。
     * 只要还剩这么大一块能看见、能拖，用户就抓得住它。
     */
    private static final double MIN_VISIBLE = 120;

    private final UiState state;

    public WindowState(UiState state) {
        this.state = state;
    }

    /** 按存下来的几何摆好窗口。没存过、或者存的位置已经不在屏幕上，就用默认值。 */
    public void restore(Stage stage) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        UiState.Geometry saved = state.geometry();

        if (saved == null) {
            stage.setWidth(Math.min(DEFAULT_WIDTH, screen.getWidth()));
            stage.setHeight(Math.min(DEFAULT_HEIGHT, screen.getHeight()));
            stage.centerOnScreen();
            return;
        }

        // 尺寸先收进当前屏幕：宁可比用户上次拉的小，也不能让按钮掉到可视区外
        stage.setWidth(clamp(saved.width(), stage.getMinWidth(), screen.getWidth()));
        stage.setHeight(clamp(saved.height(), stage.getMinHeight(), screen.getHeight()));

        if (visibleSomewhere(saved)) {
            stage.setX(saved.x());
            stage.setY(saved.y());
        } else {
            // 上次那块屏没了。只保留尺寸，位置交给居中——
            // 照搬坐标会把窗口开到看不见的地方，那和没启动没有区别
            stage.centerOnScreen();
        }
        // 最大化放在最后：先摆好还原尺寸，取消最大化时才回得到用户自己拉的那个大小
        stage.setMaximized(saved.maximized());
    }

    /**
     * 记下当前几何。
     *
     * <p>最大化时 {@code getWidth/getHeight} 给的是铺满屏幕的尺寸，
     * 存了它，用户下次取消最大化会得到一个和屏幕一样大的「还原」窗口——
     * 于是「还原」按钮看上去什么也没做。所以最大化状态下沿用上一次存过的还原尺寸。
     */
    public void save(Stage stage) {
        UiState.Geometry previous = state.geometry();
        boolean maximized = stage.isMaximized();

        double x = maximized && previous != null ? previous.x() : stage.getX();
        double y = maximized && previous != null ? previous.y() : stage.getY();
        double width = maximized && previous != null ? previous.width() : stage.getWidth();
        double height = maximized && previous != null ? previous.height() : stage.getHeight();

        // 最小化时 JavaFX 会给出负数或零，存进去下次就是一个开不出来的窗口
        if (width < 200 || height < 200) {
            return;
        }
        state.setGeometry(new UiState.Geometry(x, y, width, height, maximized));
    }

    /** 这个矩形在任何一块屏幕上还露得出足够大的一块吗。 */
    private static boolean visibleSomewhere(UiState.Geometry geometry) {
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D bounds = screen.getVisualBounds();
            double overlapX = Math.min(geometry.x() + geometry.width(), bounds.getMaxX())
                    - Math.max(geometry.x(), bounds.getMinX());
            double overlapY = Math.min(geometry.y() + geometry.height(), bounds.getMaxY())
                    - Math.max(geometry.y(), bounds.getMinY());
            if (overlapX >= MIN_VISIBLE && overlapY >= MIN_VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        double low = min > 0 ? min : 0;
        return Math.max(low, Math.min(value, max));
    }
}
