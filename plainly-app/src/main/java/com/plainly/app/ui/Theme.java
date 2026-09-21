package com.plainly.app.ui;

import com.plainly.core.store.UiState;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 亮色 / 暗色主题。
 *
 * <h2>换肤是怎么生效的</h2>
 * 两件事：
 * <ul>
 *   <li><b>样式</b>：整套配色在样式表里已经收成 {@code -sx-*} 令牌，
 *       给根节点挂上 {@code theme-dark} 这个类，令牌整体换一份取值。
 *       所有背景、边框、文字当场跟着变；</li>
 *   <li><b>图标</b>：图标是 SVGPath，颜色在创建时就画上去了，样式表管不着。
 *       {@link Icons#applyTheme} 用绑定解决——已经画出来的图标也会跟着变，
 *       不需要重启，也不需要重建界面。</li>
 * </ul>
 *
 * <h2>为什么要记着所有窗口</h2>
 * 每个对话框都是独立的 {@code Stage}，各有各的场景根节点，类要一个个挂。
 * 而对话框是随开随关的，所以挂钩放在 {@link UiUtils#brand} 里——
 * 每个窗口都会经过那儿，新开的窗口自然就带上了当前的主题。
 */
public final class Theme {

    private static final String DARK_CLASS = "theme-dark";

    private static boolean dark;

    /**
     * 已经挂上主题的场景。
     *
     * <p>用弱引用：对话框关掉之后，这里不该拦着它被回收。
     * 一个长期开着的主窗口 + 上百次开关对话框，强引用会把它们全留下。
     */
    private static final List<java.lang.ref.WeakReference<Scene>> scenes = new ArrayList<>();

    private Theme() {
    }

    public static boolean isDark() {
        return dark;
    }

    /** 从本机偏好里读出上次选的主题并应用。在建任何界面之前调一次。 */
    public static void restore(UiState state) {
        apply("dark".equals(state.get(UiState.THEME, "light")));
    }

    /** 切换主题并记下来。 */
    public static void toggle(UiState state) {
        apply(!dark);
        state.put(UiState.THEME, dark ? "dark" : "light");
    }

    /** 应用到当前所有窗口，以及之后新开的窗口。 */
    public static void apply(boolean useDark) {
        dark = useDark;
        Icons.applyTheme(useDark);
        scenes.removeIf(ref -> ref.get() == null);
        for (var ref : scenes) {
            Scene scene = ref.get();
            if (scene != null) {
                applyTo(scene);
            }
        }
    }

    /**
     * 把当前主题挂到一个场景上，并记着它，以便之后换肤时一并更新。
     *
     * <p>可以重复调用同一个场景：类是按存在与否加的，重复调不会叠加。
     */
    public static void register(Scene scene) {
        if (scene == null) {
            return;
        }
        boolean known = scenes.stream().anyMatch(ref -> ref.get() == scene);
        if (!known) {
            scenes.add(new java.lang.ref.WeakReference<>(scene));
        }
        applyTo(scene);
    }

    /**
     * 窗口还没有场景时也能挂上。
     *
     * <p>对话框的写法几乎都是「先 new Stage、brand、再 setScene」，
     * 挂钩的时候场景还不存在。监听 sceneProperty 就能等到它。
     */
    public static void follow(Stage stage) {
        if (stage == null) {
            return;
        }
        register(stage.getScene());
        stage.sceneProperty().addListener((obs, old, scene) -> register(scene));
    }

    private static void applyTo(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        if (dark) {
            if (!root.getStyleClass().contains(DARK_CLASS)) {
                root.getStyleClass().add(DARK_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }

    /** 弹出的 Alert 用的是自己的 DialogPane，样式类也得单独挂一次。 */
    public static void applyToDialog(javafx.scene.control.DialogPane pane) {
        if (pane == null) {
            return;
        }
        if (dark) {
            if (!pane.getStyleClass().contains(DARK_CLASS)) {
                pane.getStyleClass().add(DARK_CLASS);
            }
        } else {
            pane.getStyleClass().remove(DARK_CLASS);
        }
    }

    /** 当前主题在界面上的名字。 */
    public static String label() {
        return dark ? "暗色" : "亮色";
    }

    /** 切过去之后会变成什么，用在菜单项上——菜单说的该是「点了会怎样」。 */
    public static String nextLabel() {
        return dark ? "切换到亮色主题" : "切换到暗色主题";
    }

    /** 某个窗口。给弹窗定位用。 */
    public static Window ownerOf(Scene scene) {
        return scene == null ? null : scene.getWindow();
    }
}
