import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * 树的双击展开：TreeView 自己到底管不管。
 *
 * <p>这是「双击上级不展开」那个修法的前提。原来的代码在双击时又翻了一次展开状态，
 * 如果 TreeView 默认根本不翻，那删掉我们这一次反而彻底不展开了。
 * 所以先用真实双击确认默认行为存在——用原生 TreeView，不牵扯任何连接。
 *
 * <p>会短暂占用鼠标，测完把指针放回原处。
 *
 * <pre>java -cp "plainly-app/target/deps/*" tools/TreeExpandProbe.java</pre>
 */
public class TreeExpandProbe extends Application {

    private static int exitCode = 0;

    @Override
    public void start(Stage stage) {
        TreeItem<String> root = new TreeItem<>("根");
        TreeItem<String> branch = new TreeItem<>("上级");
        branch.getChildren().add(new TreeItem<>("下级"));
        root.getChildren().add(branch);
        root.setExpanded(true);

        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);

        stage.setScene(new Scene(tree, 320, 220));
        stage.setTitle("树展开探针");
        stage.setX(150);
        stage.setY(150);
        stage.show();
        stage.toFront();

        Robot robot = new Robot();
        after(1.2, () -> {
            Point2D saved = robot.getMousePosition();
            TreeCell<?> cell = null;
            for (Node n : tree.lookupAll(".tree-cell")) {
                if (n instanceof TreeCell<?> c && "上级".equals(c.getText())) {
                    cell = c;
                }
            }
            if (cell == null) {
                System.out.println("没找到节点");
                exitCode = 1;
                Platform.exit();
                return;
            }
            System.out.println("双击前 expanded=" + branch.isExpanded());
            Point2D at = cell.localToScreen(cell.getBoundsInLocal().getWidth() / 2,
                    cell.getBoundsInLocal().getHeight() / 2);
            robot.mouseMove(at);
            robot.mouseClick(MouseButton.PRIMARY);
            robot.mouseClick(MouseButton.PRIMARY);

            after(0.8, () -> {
                boolean expanded = branch.isExpanded();
                System.out.println("双击后 expanded=" + expanded);
                System.out.println(expanded
                        ? "通过：TreeView 默认就在双击时展开——我们不该再翻一次"
                        : "失败：默认不展开，得自己处理");
                exitCode = expanded ? 0 : 1;
                robot.mouseMove(saved);
                Platform.exit();
            });
        });
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }

    public static void main(String[] args) {
        launch(TreeExpandProbe.class, args);
        System.exit(exitCode);
    }
}
