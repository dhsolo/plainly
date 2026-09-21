import com.plainly.app.view.DataGridPane;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.TypeCategory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据网格的悬停提示：该弹的弹，不该弹的不弹。
 *
 * <h2>为什么两头都要验</h2>
 * 「加个 tooltip」看着只有一件事，其实有两件，而且第二件更容易做砸：
 * 给每个格子都挂上提示，扫一眼表格就会不停地弹出小框把旁边的数据盖住。
 * 只有<b>显示不全</b>的格子才需要它——所以短值那一路必须验「没有提示」，
 * 光验长值有提示等于只测了一半。
 *
 * <p>不连任何数据库，结果集是现造的。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/CellTooltipProbe.java
 * </pre>
 */
public class CellTooltipProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();

    /** 一个明显放不进 120 像素列宽的值。 */
    private static final String LONG =
            "这是一段很长的备注，列宽再怎么排也放不下，只能靠悬停或者手动拉宽列才能看全";
    private static final String SHORT = "abc";

    @Override
    public void start(Stage stage) {
        DataGridPane grid = new DataGridPane();
        grid.setResult(QueryResult.of(
                List.of(col("id"), col("note"), col("tag")),
                List.of(new Row(new String[] {"1", LONG, SHORT}),
                        new Row(new String[] {"2", SHORT, null})),
                1, false, "SELECT * FROM probe"));

        Scene scene = new Scene(new StackPane(grid), 520, 400);
        scene.getStylesheets().add(DataGridPane.class
                .getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        // 列宽故意收窄，逼出「显示不全」这个前提
        TableView<Integer> table = grid.table();
        for (TableColumn<Integer, ?> c : table.getColumns()) {
            c.setPrefWidth(120);
        }
        table.refresh();

        delay(700, () -> {
            try {
                run(grid, table);
            } catch (RuntimeException e) {
                LOG.append("探针自己出错了：").append(e).append(NL);
            }
            System.out.print(LOG);
            Platform.exit();
        });
    }

    private void run(DataGridPane grid, TableView<Integer> table) {
        table.layout();

        check("长值（列宽放不下）", cell(table, 0, 1), true);
        check("短值（放得下）", cell(table, 1, 1), false);
        check("NULL", cell(table, 1, 2), false);

        // 改一个格子，原值就成了界面上唯一看不见的东西
        grid.editBuffer().set(0, 2, "改过了");
        table.refresh();
        table.layout();
        TableCell<Integer, ?> dirty = cell(table, 0, 2);
        check("改过的短值", dirty, true);
        if (dirty != null) {
            hover(dirty);
            Tooltip tip = dirty.getTooltip();
            String text = tip == null ? "" : tip.getText();
            LOG.append("    提示正文：").append(text.replace(NL, " ⏎ ")).append(NL);
            LOG.append("    原值结论：").append(text.contains("原值：" + SHORT)
                    ? "  带上了原值" : "× 没带原值").append(NL);
        }

        // 长值的提示得是完整的那一段，不是屏幕上截断的那一截
        TableCell<Integer, ?> longCell = cell(table, 0, 1);
        if (longCell != null) {
            hover(longCell);
            Tooltip tip = longCell.getTooltip();
            LOG.append(NL).append("长值提示是否为完整原文：")
                    .append(tip != null && LONG.equals(tip.getText()) ? "  是" : "× 否").append(NL);
        }
    }

    /** 发一次 MOUSE_ENTERED，再看这个格子挂没挂提示。 */
    private void check(String what, TableCell<Integer, ?> cell, boolean expected) {
        if (cell == null) {
            LOG.append("× ").append(what).append("：没找到这个格子").append(NL);
            return;
        }
        hover(cell);
        boolean has = cell.getTooltip() != null;
        LOG.append(has == expected ? "  " : "× ").append(what)
                .append("：").append(has ? "有提示" : "没提示")
                .append("（应当").append(expected ? "有" : "没有").append("）").append(NL);
    }

    private static void hover(Node node) {
        javafx.geometry.Bounds b = node.localToScreen(node.getBoundsInLocal());
        double x = b == null ? 0 : b.getMinX() + 3;
        double y = b == null ? 0 : b.getMinY() + 3;
        javafx.event.Event.fireEvent(node, new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_ENTERED, 3, 3, x, y,
                javafx.scene.input.MouseButton.NONE, 0,
                false, false, false, false, false, false, false, true, false, true, null));
    }

    @SuppressWarnings("unchecked")
    private static TableCell<Integer, ?> cell(TableView<Integer> table, int row, int column) {
        TableColumn<Integer, ?> target = table.getColumns().get(column);
        for (Node n : all(table)) {
            if (n instanceof TableCell<?, ?> c && c.getTableColumn() == target
                    && c.getTableRow() != null && c.getTableRow().getIndex() == row
                    && !c.isEmpty()) {
                return (TableCell<Integer, ?>) c;
            }
        }
        return null;
    }

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    private static ColumnMeta col(String name) {
        return new ColumnMeta(name, name, "VARCHAR", TypeCategory.STRING,
                255, 0, true, "probe_schema", "probe", false, false);
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

    public static void main(String[] args) {
        launch(CellTooltipProbe.class, args);
    }
}
