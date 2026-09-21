import com.plainly.app.AppContext;
import com.plainly.app.ui.CopySelection;
import com.plainly.app.view.ConnectionTreePane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 在<b>真实的</b>连接树上按 Ctrl+C，复制出来的是名字还是 Java 对象。
 *
 * <h2>为什么必须用真的那棵树</h2>
 * 用户报的就是这个：选中一张表，复制出来是
 * {@code TableNode[config=..., schema=..., table=...]}。
 * 原因是树的单元格整块是 {@code setGraphic} 画的、{@code getText()} 恒为空，
 * 于是一路退回 {@code toString()}。
 *
 * <p>这种「渲染方式导致的失败」仿制不出来——照着写一棵普通的树，
 * 每个单元格都有 text，怎么测都是对的。
 *
 * <p>只读连接清单，不连任何数据库。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/TreeCopyProbe.java
 * </pre>
 */
public class TreeCopyProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext();
        ConnectionTreePane pane = new ConnectionTreePane(context);
        stage.setScene(new Scene(new StackPane(pane), 420, 640));
        stage.show();

        delay(900, () -> {
            try {
                run(pane);
            } catch (RuntimeException e) {
                LOG.append("探针自己出错了：").append(e).append(NL);
            }
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            context.close();
            Platform.exit();
        });
    }

    private void run(Parent pane) {
        TreeView<?> tree = find(pane, TreeView.class);
        check("找到了连接树", tree != null, "没找到 TreeView");
        if (tree == null) {
            return;
        }
        TreeItem<?> root = tree.getRoot();
        check("树上有连接", root != null && !root.getChildren().isEmpty(),
                "根节点是空的——本机没有保存过连接？");
        if (root == null || root.getChildren().isEmpty()) {
            return;
        }

        // 连接这一层不用展开就能选。
        // 按下标选：通配的 TreeView<?> 上，select(TreeItem<?>) 过不了泛型检查
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(1);
        tree.layout();
        String got = CopySelection.textOf(tree);
        LOG.append("选中第一个连接 → ").append(show(got)).append(NL);
        check("连接复制出的是连接名，不是 Java 对象",
                got != null && !got.contains("Node[") && !got.contains("config="),
                show(got));
        check("也没把类型色块上的字母和「在线」角标一起带出来",
                got != null && !got.contains("在线"), show(got));

        tableNode(tree);
    }

    /**
     * 用户报的正是表节点这一条。
     *
     * <p>展开到表需要真连上库，而那既慢又不一定成功。这里直接往树里插一个
     * 表节点再选中它——走的是同一条真实路径（同一棵树、同一个 provider、
     * 同一个 textOf），只是省掉了连库那一步。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void tableNode(TreeView<?> tree) {
        com.plainly.driver.ConnectionConfig cfg = new com.plainly.driver.ConnectionConfig()
                .setName("探针连接").setType(com.plainly.driver.DbType.H2);
        // id 不能留空：渲染那一路会拿它当 map 的键，null 键直接 NPE
        cfg.setId("probe-tree-copy");
        com.plainly.driver.meta.DbObjects.TableInfo table =
                new com.plainly.driver.meta.DbObjects.TableInfo(
                        "PUBLIC", "t_order_detail",
                        com.plainly.driver.meta.DbObjects.ObjectKind.TABLE, "", 100);
        Object node = new ConnectionTreePane.NodeData.TableNode(cfg, "PUBLIC", table);

        TreeItem item = new TreeItem(node);
        ((TreeItem) tree.getRoot()).getChildren().add(item);
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(item);
        tree.layout();

        String got = CopySelection.textOf(tree);
        LOG.append("选中一张表 → ").append(show(got)).append(NL);
        check("表复制出的是表名", "t_order_detail".equals(got), show(got));
        check("不是 Java 对象的 toString",
                got != null && !got.contains("TableNode[") && !got.contains("config="),
                show(got));
    }

    // ------------------------------------------------------------------ 辅助

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
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

    private static String show(String s) {
        return s == null ? "(null)" : "[" + s.replace(String.valueOf((char) 10), " ⏎ ") + "]";
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            LOG.append("  ").append(what).append(NL);
        } else {
            fail++;
            LOG.append("× ").append(what).append("   实际：").append(detail).append(NL);
        }
    }

    public static void main(String[] args) {
        launch(TreeCopyProbe.class, args);
    }
}
