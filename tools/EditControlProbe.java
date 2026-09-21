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
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.converter.DefaultStringConverter;

/**
 * 对照实验：同样的合成双击，一边打在原生 TableView 上，一边打在我们的网格上。
 *
 * <p>上一版探针里双击没进编辑态，但那既可能是网格的问题，也可能只是
 * 「代码合成的鼠标事件驱动不了 JavaFX 的单元格行为」。
 * 摆一个已知正确的对照组，才分得清是被测对象坏了还是量具坏了。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/EditControlProbe.java</pre>
 */
public class EditControlProbe extends Application {

    @Override
    public void start(Stage stage) {
        // 对照组：教科书写法的可编辑表格
        TableView<String> plain = new TableView<>(
                FXCollections.observableArrayList("甲", "乙", "丙"));
        plain.setEditable(true);
        TableColumn<String, String> col = new TableColumn<>("名字");
        col.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()));
        col.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()));
        col.setPrefWidth(160);
        plain.getColumns().add(col);

        // 被测组
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("probe").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("probe-editcontrol");
        DbConnection conn = JdbcConnections.open(cfg);
        QueryResult result = conn.execute("SELECT * FROM ORDERS", 20);
        DataGridPane grid = new DataGridPane();
        grid.setResult(result);

        HBox root = new HBox(10, plain, grid);
        Scene scene = new Scene(root, 1200, 460);
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("对照探针");
        stage.show();

        after(1.0, () -> {
            check("对照-原生表格", plain, 0, 0);
            check("被测-数据网格", grid.table(), 0, 6);
            conn.close();
            Platform.exit();
        });
    }

    private static void check(String tag, TableView<?> table, int row, int col) {
        TableCell<?, ?> cell = findCell(table, row, col);
        if (cell == null) {
            System.out.println(tag + "：没找到单元格");
            return;
        }
        table.getSelectionModel().clearSelection();
        click(cell, 1);
        click(cell, 2);
        System.out.println(tag + "：双击后 editing=" + (table.getEditingCell() != null)
                + "  cell.isEditing=" + cell.isEditing());
        table.edit(-1, null);
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

    private static void click(Node node, int count) {
        double x = node.getBoundsInLocal().getWidth() / 2;
        double y = node.getBoundsInLocal().getHeight() / 2;
        for (var type : new javafx.event.EventType[] {
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            @SuppressWarnings("unchecked")
            javafx.event.EventType<MouseEvent> t = (javafx.event.EventType<MouseEvent>) type;
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
        launch(EditControlProbe.class, args);
        System.exit(0);
    }
}
