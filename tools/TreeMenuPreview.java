import com.plainly.app.AppContext;
import com.plainly.app.view.MainWindow;
import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 左树三级节点的右键菜单，各拍一张。
 *
 * <p>菜单是 ContextMenu，是独立的弹出窗口——没法靠看主窗口的截图确认它长什么样，
 * 得把它真的弹出来再拍。这里也顺带确认新加的项挂在了正确的层级上：
 * 清空 / 删表在表上，新建库在连接上，备份在库上。
 *
 * <p>跑之前先关掉应用。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/TreeMenuPreview.java</pre>
 */
public class TreeMenuPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private TreeView<?> tree;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        MainWindow window = new MainWindow(context);

        Scene scene = new Scene(window, 1280, 720);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("树菜单取样");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
            System.out.println("树上有 " + (tree == null ? 0 : tree.getExpandedItemCount())
                    + " 个可见节点");
        });

        // 展开第一条连接 → 第一个库 → 表分组
        step(() -> select(0, true));
        step(() -> shoot("menu-connection", 0));

        step(() -> select(1, true));
        step(() -> shoot("menu-schema", 1));

        // 树是异步装载的：展开一层要等一次往返才会长出下一层。
        // 所以不能一轮走到底，得展开一层、等一拍、再展开下一层
        for (int pass = 0; pass < 5; pass++) {
            step(() -> {
                for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                    TreeItem<?> item = tree.getTreeItem(i);
                    if (item != null && !item.isLeaf() && !item.isExpanded()) {
                        item.setExpanded(true);
                    }
                }
            });
        }
        step(() -> {
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                TreeItem<?> item = tree.getTreeItem(i);
                if (item != null && item.getValue() != null
                        && item.getValue().getClass().getSimpleName().contains("TableNode")) {
                    tree.getSelectionModel().select(i);
                    tree.scrollTo(i);
                    System.out.println("选中表节点，行 " + i + "：" + item.getValue());
                    return;
                }
            }
            System.out.println("没找到表节点");
        });
        step(() -> shootSelected("menu-table"));

        step(() -> {
            context.close();
            Platform.exit();
        });
    }

    private void select(int row, boolean expand) {
        if (tree == null || row >= tree.getExpandedItemCount()) {
            return;
        }
        tree.getSelectionModel().select(row);
        TreeItem<?> item = tree.getTreeItem(row);
        if (expand && item != null) {
            item.setExpanded(true);
        }
    }

    private void shoot(String name, int row) {
        if (tree == null || row >= tree.getExpandedItemCount()) {
            System.out.println(name + "：行 " + row + " 不存在");
            return;
        }
        tree.getSelectionModel().select(row);
        shootSelected(name);
    }

    /** 把选中行的 ContextMenu 弹出来拍一张。 */
    private void shootSelected(String name) {
        int row = tree.getSelectionModel().getSelectedIndex();
        ContextMenu menu = null;
        for (Node n : tree.lookupAll(".tree-cell")) {
            if (n instanceof TreeCell<?> cell && cell.getIndex() == row) {
                menu = cell.getContextMenu();
            }
        }
        if (menu == null) {
            System.out.println(name + "：这一行没有右键菜单");
            return;
        }
        menu.show(tree, javafx.geometry.Side.RIGHT, 0, 0);
        ContextMenu shown = menu;
        PauseTransition p = new PauseTransition(Duration.seconds(0.6));
        p.setOnFinished(e -> {
            Window w = Window.getWindows().stream()
                    .filter(x -> x.isShowing() && x != tree.getScene().getWindow())
                    .findFirst().orElse(null);
            if (w == null) {
                System.out.println(name + "：菜单没弹出来");
            } else {
                write(w.getScene().snapshot(null), name);
                shown.hide();
            }
        });
        p.play();
    }

    private void step(Runnable action) {
        at += 1.6;
        PauseTransition p = new PauseTransition(Duration.seconds(at));
        p.setOnFinished(e -> action.run());
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
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(TreeMenuPreview.class, args);
        System.exit(0);
    }
}
