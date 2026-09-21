import com.plainly.app.ui.WindowState;
import com.plainly.core.store.LocalStore;
import com.plainly.core.store.UiState;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 窗口几何存了、读回来对不对，以及那块屏没了会怎样。
 *
 * <p>最要紧的是最后一条：存下来的是<b>上次那套显示器</b>上的坐标。
 * 副屏拔掉之后照搬坐标，窗口会开在物理上不存在的位置——进程活着、界面永远看不见。
 * 那种失败在开发机上几乎不会遇到，只会发生在用户身上，所以只能在这里造出来验。
 *
 * <p>用临时配置库，<b>不碰</b> %APPDATA%\\Plainly\\plainly.db。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/WindowStateProbe.java</pre>
 */
public class WindowStateProbe extends Application {

    public static void main(String[] args) {
        launch(WindowStateProbe.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Path dir = Files.createTempDirectory("plainly-window-probe");
        try (LocalStore store = new LocalStore(dir.resolve("plainly.db"))) {
            UiState state = new UiState(store);
            WindowState windows = new WindowState(state);

            var screen = Screen.getPrimary().getVisualBounds();
            System.out.println("主屏可视区 " + (int) screen.getWidth()
                    + "x" + (int) screen.getHeight()
                    + " @ (" + (int) screen.getMinX() + "," + (int) screen.getMinY() + ")");

            // ---- 一、没存过：用默认值，且不超出屏幕
            //
            // 每个用例都要真的 show()：centerOnScreen() 在窗口显示<b>之前</b>调用时
            // 只是清掉「位置由程序指定」的标记，真正的居中发生在 show() 那一刻。
            // 不显示就去读 getX()，拿到的是 NaN——那是探针自己没做对，不是代码的问题
            stage.setScene(new Scene(new StackPane(), 300, 200));
            stage.setMinWidth(1000);
            stage.setMinHeight(640);
            windows.restore(stage);
            stage.show();
            print("首次启动", stage);
            check("尺寸不超出屏幕",
                    stage.getWidth() <= screen.getWidth() + 1
                            && stage.getHeight() <= screen.getHeight() + 1);

            // ---- 二、存了一个正常几何，读回来要一样
            stage.setX(screen.getMinX() + 60);
            stage.setY(screen.getMinY() + 40);
            stage.setWidth(1180);
            stage.setHeight(720);
            windows.save(stage);

            Stage second = open(windows);
            print("再次启动", second);
            check("宽高原样回来", second.getWidth() == 1180 && second.getHeight() == 720);
            check("位置原样回来",
                    second.getX() == screen.getMinX() + 60
                            && second.getY() == screen.getMinY() + 40);
            second.hide();

            // ---- 三、副屏拔掉：存一个远在屏幕之外的坐标
            state.setGeometry(new UiState.Geometry(9000, 6000, 1180, 720, false));
            Stage third = open(windows);
            print("副屏没了", third);
            check("没有照搬那个看不见的坐标", third.getX() != 9000);
            check("窗口落在屏幕里",
                    third.getX() >= screen.getMinX() - 1
                            && third.getX() < screen.getMaxX());
            check("尺寸仍然保留", third.getWidth() == 1180);
            third.hide();

            // ---- 四、上次在大屏上拉满，这次屏幕小：尺寸要收进来
            state.setGeometry(new UiState.Geometry(0, 0, 5120, 2880, false));
            Stage fourth = open(windows);
            print("换了小屏", fourth);
            check("宽度收进屏幕", fourth.getWidth() <= screen.getWidth() + 1);
            check("高度收进屏幕", fourth.getHeight() <= screen.getHeight() + 1);
            fourth.hide();
            stage.hide();

            // ---- 五、最大化时不要把铺满屏幕的尺寸当成还原尺寸存下来
            state.setGeometry(new UiState.Geometry(100, 100, 1180, 720, false));
            Stage fifth = new Stage();
            fifth.setScene(new Scene(new StackPane(), 300, 200));
            fifth.setX(0);
            fifth.setY(0);
            fifth.setWidth(screen.getWidth());
            fifth.setHeight(screen.getHeight());
            fifth.setMaximized(true);
            windows.save(fifth);
            UiState.Geometry after = state.geometry();
            System.out.println("  最大化后存下的还原尺寸 = "
                    + (int) after.width() + "x" + (int) after.height()
                    + "，maximized=" + after.maximized());
            check("还原尺寸仍是用户自己拉的那个",
                    after.width() == 1180 && after.height() == 720);
            check("最大化状态记下来了", after.maximized());

            // ---- 六、侧栏分隔线
            state.putDouble(UiState.SIDEBAR_DIVIDER, 0.31);
            check("侧栏宽度存得下读得回",
                    Math.abs(state.getDouble(UiState.SIDEBAR_DIVIDER, 0.23) - 0.31) < 1e-9);

            // ---- 七、上次的目录
            state.put(UiState.LAST_DIRECTORY, dir.toString());
            check("上次目录存得下读得回",
                    dir.toString().equals(state.get(UiState.LAST_DIRECTORY, null)));
        } finally {
            clean(dir);
            Platform.exit();
        }
    }

    /** 按存下来的几何开一个窗口，走的顺序和 PlainlyApp 一样：先 restore 再 show。 */
    private static Stage open(WindowState windows) {
        Stage stage = new Stage();
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        windows.restore(stage);
        stage.show();
        return stage;
    }

    private static void print(String label, Stage stage) {
        System.out.println();
        System.out.println("=== " + label + " ===");
        System.out.println("  " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                + " @ (" + (int) stage.getX() + "," + (int) stage.getY() + ")"
                + (stage.isMaximized() ? " 最大化" : ""));
    }

    private static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "[对] " : "[错] ") + what);
    }

    private static void clean(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清不掉不影响结论
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
