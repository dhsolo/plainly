import com.plainly.app.AppContext;
import com.plainly.app.view.MainWindow;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 拿用户已存的那条 Redis 连接，把界面真走一遍，看错在哪一步。
 *
 * <p>只发读命令。装了一个未捕获异常处理器：界面里的失败常常被吞进后台任务，
 * 只在弹窗上留一句话，控制台什么都没有——所以这里把它抓出来打全。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RedisUiDiag.java</pre>
 */
public class RedisUiDiag extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private AppContext context;
    private MainWindow window;
    private TreeView<?> tree;
    private String connectionName;

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("\n！！未捕获异常（线程 " + t.getName() + "）");
            dump(e);
        });

        context = new AppContext();
        for (ConnectionConfig c : context.registry().listAll()) {
            if (c.type() == DbType.REDIS) {
                connectionName = c.name();
            }
        }
        if (connectionName == null) {
            System.out.println("连接列表里没有 Redis 连接");
            Platform.exit();
            return;
        }
        System.out.println("用的是连接「" + connectionName + "」");

        window = new MainWindow(context);
        Scene scene = new Scene(window, 1280, 760);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Redis 界面诊断");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
            System.out.println("\n[1] 展开连接");
            expand(connectionName);
        });
        step(() -> {
            reportDialogs();
            System.out.println("[2] 展开 db0");
            expand("db0");
        });
        step(() -> {
            reportDialogs();
            System.out.println("[3] 展开键空间");
            expandGroup();
        });
        step(() -> {
            reportDialogs();
            System.out.println("[4] 树上现在这些行：");
            printTree();
            printMenus();
            System.out.println("[5] 双击第一个键空间");
            openFirstKeyspace();
        });
        step(this::reportDialogs);
        step(() -> {
            reportDialogs();
            System.out.println("\n视图页签：" + viewTabs());
            System.out.println("网格行数：" + gridRows());
            System.out.println("状态栏：" + statusText());
            write(scene.snapshot(null), "redis-ui-diag");
        });
        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    /** 界面上冒出来的弹窗——错误多半就在那上面。 */
    private void reportDialogs() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null && s != window.getScene().getWindow()) {
                String title = s.getTitle();
                if (title == null || title.equals("Redis 界面诊断")) {
                    continue;
                }
                System.out.println("  ！弹窗：" + title);
                collectText(s.getScene().getRoot()).forEach(t -> System.out.println("      " + t));
                s.close();
            }
        }
    }

    private static List<String> collectText(Node node) {
        List<String> out = new ArrayList<>();
        walk(node, out);
        return out;
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof javafx.scene.control.Labeled l
                && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof javafx.scene.control.TextInputControl t
                && t.getText() != null && !t.getText().isBlank()) {
            out.add(t.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out));
        }
    }

    private String statusText() {
        List<String> texts = new ArrayList<>();
        for (Node n : window.lookupAll(".status-bar")) {
            texts.addAll(collectText(n));
        }
        return String.join(" | ", texts);
    }

    private List<String> viewTabs() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".view-tab")) {
            if (n instanceof javafx.scene.control.ToggleButton b) {
                out.add(b.getText());
            }
        }
        return out;
    }

    private int gridRows() {
        for (Node n : window.lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t && !t.getColumns().isEmpty()) {
                return t.getItems().size();
            }
        }
        return -1;
    }

    private void printTree() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            int d = 0;
            for (TreeItem<?> p = item.getParent(); p != null; p = p.getParent()) {
                d++;
            }
            System.out.println("      " + "  ".repeat(d) + item.getValue());
        }
    }

    /**
     * 每种节点的右键菜单里到底摆着什么。
     *
     * <p>这是本次要看的重点：菜单项是<b>点下去才失败</b>的东西，
     * 光看界面看不出来，非得把菜单本身列出来对一遍。
     */
    private void printMenus() {
        System.out.println();
        System.out.println("[4b] 右键菜单：");
        String[] kinds = {"ConnectionNode[config=" + connectionName,
                "SchemaNode[config=" + connectionName,
                "GroupNode[config=" + connectionName,
                "TableNode[config=" + connectionName};
        for (String kind : kinds) {
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                TreeItem<?> item = tree.getTreeItem(i);
                if (!String.valueOf(item.getValue()).startsWith(kind)) {
                    continue;
                }
                javafx.scene.control.TreeCell<?> cell = cellFor(i);
                String label = kind.substring(0, kind.indexOf('['));
                if (cell == null || cell.getContextMenu() == null) {
                    System.out.println("      " + label + "：（没有菜单）");
                } else {
                    List<String> items = new ArrayList<>();
                    cell.getContextMenu().getItems().forEach(mi ->
                            items.add(mi.getText() == null ? "—" : mi.getText()));
                    System.out.println("      " + label + "：" + items);
                }
                break;
            }
        }
    }

    /** 找到第 i 行对应的那个单元格——菜单挂在单元格上，不在数据上。 */
    private javafx.scene.control.TreeCell<?> cellFor(int row) {
        for (Node n : tree.lookupAll(".tree-cell")) {
            if (n instanceof javafx.scene.control.TreeCell<?> c && c.getIndex() == row) {
                return c;
            }
        }
        return null;
    }

    private void expand(String contains) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).contains(contains)) {
                item.setExpanded(true);
                return;
            }
        }
        System.out.println("  树上没有含「" + contains + "」的行");
    }

    private void expandGroup() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).startsWith("GroupNode")) {
                item.setExpanded(true);
                return;
            }
        }
        System.out.println("  树上没有分组节点");
    }

    private void openFirstKeyspace() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).startsWith("TableNode")) {
                tree.getSelectionModel().select(i);
                tree.scrollTo(i);
                javafx.event.Event.fireEvent(tree, new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, true, false, false, null));
                return;
            }
        }
        System.out.println("  树上没有键空间节点");
    }

    private static void dump(Throwable e) {
        Throwable t = e;
        while (t != null) {
            System.out.println("   " + t.getClass().getName() + ": " + t.getMessage());
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith("com.plainly")) {
                    System.out.println("      @ " + el);
                }
            }
            t = t.getCause();
        }
    }

    private void step(Runnable action) {
        at += 1.8;
        PauseTransition p = new PauseTransition(Duration.seconds(at));
        p.setOnFinished(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                System.out.println("！步骤里抛了：");
                dump(ex);
            }
        });
        p.play();
    }

    private static void write(Image img, String name) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", new File(OUT + name + ".png"));
            System.out.println("→ " + name + ".png");
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(RedisUiDiag.class, args);
        System.exit(0);
    }
}
