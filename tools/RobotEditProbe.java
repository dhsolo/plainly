import com.plainly.app.view.DataGridPane;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.converter.DefaultStringConverter;

/**
 * 用真实鼠标事件测双击进不进编辑态。
 *
 * <p>代码合成的 MouseEvent 驱动不了 JavaFX 的单元格行为——原生 TableView 做对照，
 * 合成双击一样进不去，说明是量具的问题。所以改用 {@link Robot}：
 * 它走的是系统那条路，和用户真的点一下没有区别。
 *
 * <p>会短暂移动鼠标指针，测完把指针放回原处。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RobotEditProbe.java</pre>
 */
public class RobotEditProbe extends Application {

    private static int exitCode = 0;
    private Robot robot;
    private Point2D savedMouse;

    @Override
    public void start(Stage stage) {
        robot = new Robot();

        TableView<String> plain = new TableView<>(
                FXCollections.observableArrayList("甲", "乙", "丙"));
        plain.setEditable(true);
        TableColumn<String, String> col = new TableColumn<>("对照列");
        col.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()));
        col.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()));
        col.setPrefWidth(150);
        plain.getColumns().add(col);
        plain.setPrefWidth(170);

        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-robot");
        DbConnection conn = JdbcConnections.open(cfg);
        QueryResult result = conn.execute("SELECT * FROM ORDERS", 20);
        DataGridPane grid = new DataGridPane();
        grid.setResult(result);

        HBox root = new HBox(10, plain, grid);
        Scene scene = new Scene(root, 1150, 420);
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("真实双击探针");
        stage.setX(120);
        stage.setY(120);
        stage.show();
        stage.toFront();
        stage.requestFocus();

        after(1.2, () -> {
            savedMouse = robot.getMousePosition();
            step(plain, 0, 0, "对照-原生表格", () ->
                    step(grid.table(), 0, 1, "被测-数据网格", () -> {
                        robot.mouseMove(savedMouse);
                        conn.close();
                        Platform.exit();
                    }));
        });
    }

    /** 双击一个格子，隔一小会儿再看结果——编辑态是下一帧才建立的。 */
    private void step(TableView<?> table, int row, int col, String tag, Runnable next) {
        TableCell<?, ?> cell = findCell(table, row, col);
        if (cell == null) {
            System.out.println(tag + "：没找到单元格");
            exitCode = 1;
            next.run();
            return;
        }
        Point2D at = cell.localToScreen(cell.getBoundsInLocal().getWidth() / 2,
                cell.getBoundsInLocal().getHeight() / 2);
        robot.mouseMove(at);
        robot.mouseClick(MouseButton.PRIMARY);
        robot.mouseClick(MouseButton.PRIMARY);

        after(0.6, () -> {
            boolean editing = table.getEditingCell() != null;
            System.out.println(tag + "：真实双击后 editing=" + editing);
            if (!editing) {
                exitCode = 1;
            }
            table.edit(-1, null);
            next.run();
        });
    }

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

    private static void after(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }

    public static void main(String[] args) {
        launch(RobotEditProbe.class, args);
        System.exit(exitCode);
    }
}
