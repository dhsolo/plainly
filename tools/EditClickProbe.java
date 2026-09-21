import com.plainly.app.view.DataGridPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * 双击进不进编辑态——分两步问。
 *
 * <p>先直接调 {@code table.edit(...)}：不通就说明 editable 那条链断了
 * （TableView / TableColumn / Cell 三级只要有一级是 false 就编辑不了）。
 * 再补一次真的鼠标双击：前一步通、这一步不通，问题就出在事件这一侧。
 * 两步分开，才知道该改哪儿。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/EditClickProbe.java</pre>
 */
public class EditClickProbe extends Application {

    private static int exitCode = 0;

    @Override
    public void start(Stage stage) {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-editclick");
        DbConnection conn = JdbcConnections.open(cfg);
        QueryResult result = conn.execute("SELECT * FROM ORDERS", 20);

        DataGridPane grid = new DataGridPane();
        grid.setResult(result);
        TableView<Integer> table = grid.table();

        Scene scene = new Scene(grid, 1000, 500);
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("编辑探针");
        stage.show();

        after(1.0, () -> {
            System.out.println("结果集 isEditable      = " + result.isEditable());
            System.out.println("TableView.isEditable  = " + table.isEditable());
            System.out.println("Column[6].isEditable  = " + table.getColumns().get(6).isEditable());

            // 第一步：直接调 edit()
            table.getSelectionModel().select(0, table.getColumns().get(6));
            table.edit(0, table.getColumns().get(6));
            System.out.println("edit() 之后 editingCell = " + table.getEditingCell());

            table.edit(-1, null);

            // 第二步：真的双击一下那个格子
            TableCell<?, ?> cell = findCell(table, 0, 6);
            if (cell == null) {
                System.out.println("没找到目标单元格");
                exitCode = 1;
            } else {
                System.out.println("目标单元格文本 = [" + cell.getText() + "]  可编辑="
                        + cell.isEditable());
                click(cell, 1);
                click(cell, 2);
                System.out.println("双击之后 editingCell   = " + table.getEditingCell());
                System.out.println("单元格 isEditing       = " + cell.isEditing());
                if (table.getEditingCell() == null) {
                    exitCode = 1;
                }
            }
            Platform.exit();
        });
    }

    /** 按行列在场景里找那个真实的单元格节点。 */
    private static TableCell<?, ?> findCell(TableView<?> table, int row, int col) {
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell<?, ?> c
                    && c.getTableRow() != null && c.getTableRow().getIndex() == row
                    && c.getTableColumn() == table.getColumns().get(col)) {
                return c;
            }
        }
        return null;
    }

    private static void click(Node node, int count) {
        double x = node.getBoundsInLocal().getWidth() / 2;
        double y = node.getBoundsInLocal().getHeight() / 2;
        for (var type : new javafx.event.EventType[] {
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            @SuppressWarnings("unchecked")
            javafx.event.EventType<MouseEvent> t = (javafx.event.EventType<MouseEvent>) type;
            // synthesized 必须是 false：JavaFX 把 synthesized 当成「触摸屏合成的事件」，
            // 单元格的行为类对它走的是另一条分支，拿它测双击会得出假结论
            Event.fireEvent(node, new MouseEvent(t, x, y, x, y, MouseButton.PRIMARY, count,
                    false, false, false, false, true, false, false, false, false, true, null));
        }
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }

    public static void main(String[] args) {
        launch(EditClickProbe.class, args);
        System.exit(exitCode);
    }
}
