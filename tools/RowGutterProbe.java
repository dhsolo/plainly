import com.plainly.app.view.DataGridPane;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.TypeCategory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 行首那一列：点一下选中整行，而且别把别的东西带坏。
 *
 * <h2>这个探针盯的是「带坏」那一半</h2>
 * 加一列看着无害，可网格里到处是「屏幕上的第几列」和「结果集里的第几列」之间的换算。
 * 行首这一列不属于结果集，一旦哪处漏了换算，表现是<b>错位一列</b>——
 * 单元格面板显示的是隔壁列的值、粘贴粘到隔壁列去，而且都不会报错。
 *
 * <p>所以这里除了考「点行首能不能选中整行」，还要考：整行选中之后粘贴落在第一个
 * <b>数据</b>列上（不是行首）、新增行的光标落在第一个数据列上、按行取到的行号没错位。
 *
 * <p>最后截一张图存到 tools/row-gutter.png，样式好不好看得用眼睛判断。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/RowGutterProbe.java
 * </pre>
 */
public class RowGutterProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    private static final String PASTED = "粘进来的";

    @Override
    public void start(Stage stage) {
        DataGridPane grid = new DataGridPane();
        grid.setResult(sample());

        Scene scene = new Scene(grid, 720, 260);
        // 挂上应用的样式表：不挂的话看到的是 modena 的默认样子，
        // 截图里那片蓝色根本不是这个项目的配色，样式对不对无从判断
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("RowGutterProbe");
        stage.show();

        delay(900, () -> {
            try {
                run(grid);
                shoot(scene, "tools/row-gutter.png");
            } catch (RuntimeException e) {
                fail++;
                log("探针自己出错了：" + e);
            }
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            Platform.exit();
        });
    }

    private void run(DataGridPane grid) {
        // 一、行首列摆在最前面，而且不冒充数据列
        TableColumn<Integer, ?> first = grid.table().getVisibleLeafColumns().get(0);
        check("行首列排在最左边（userData 为空才说明它不是数据列）",
                first.getUserData() == null);
        check("行首列不可拖动、不可改宽、不可编辑",
                !first.isReorderable() && !first.isResizable() && !first.isEditable());
        check("列数 = 行首 1 列 + 数据 3 列（实际 "
                        + grid.table().getVisibleLeafColumns().size() + "）",
                grid.table().getVisibleLeafColumns().size() == 4);

        // 二、直接点：只要这一行
        clickGutter(grid, 1, false, false);
        check("点行首选中整行：选中的行是 [1]（实际 " + grid.selectedRowIndexes() + "）",
                List.of(1).equals(grid.selectedRowIndexes()));
        check("整行的每一列都在选区里（含行首那一格）", wholeRowSelected(grid, 1));

        // 三、Shift 点：从上次那一行连选到这一行
        clickGutter(grid, 3, true, false);
        check("Shift 连选 1..3（实际 " + grid.selectedRowIndexes() + "）",
                List.of(1, 2, 3).equals(grid.selectedRowIndexes()));

        // 四、Ctrl 点已选中的那一行：把它去掉
        clickGutter(grid, 2, false, true);
        check("Ctrl 点已选中的行把它去掉，剩 [1, 3]（实际 " + grid.selectedRowIndexes() + "）",
                List.of(1, 3).equals(grid.selectedRowIndexes()));

        // 五、Ctrl 点没选中的那一行：加进来
        clickGutter(grid, 2, false, true);
        check("Ctrl 点没选中的行把它加回来，1..3（实际 " + grid.selectedRowIndexes() + "）",
                List.of(1, 2, 3).equals(grid.selectedRowIndexes()));

        // 选中态的样子也要看一眼：三行连选，行首那一格该跟着亮
        shoot(grid.getScene(), "tools/row-gutter-selected.png");

        // 没选中的那一行上，行首的底色该和数据区不一样——否则这一列是隐形的，
        // 用户根本不会想到去点它。样式表有没有真的套上，眼睛在缩略图上看不准，
        // 直接量像素
        TableCell<?, ?> gutter = gutterCell(grid, 0);
        Node dataCell = dataCell(grid, 0);
        if (gutter != null && dataCell != null) {
            int gutterColor = centerPixel(gutter);
            int dataColor = centerPixel(dataCell);
            check("行首列的底色和数据区不同（行首 " + hex(gutterColor)
                            + "，数据 " + hex(dataColor) + "）",
                    gutterColor != dataColor);
        } else {
            fail++;
            log("量不到底色：找不到第 0 行的格子");
        }

        // 六、整行选中之后粘贴：落在第一个数据列上，不是行首，也不错位
        ClipboardContent content = new ClipboardContent();
        content.putString(PASTED);
        Clipboard.getSystemClipboard().setContent(content);

        clickGutter(grid, 0, false, false);
        grid.table().requestFocus();
        Event.fireEvent(grid.table(), ctrlV());

        check("整行选中时粘贴落在第 0 列（实际 " + grid.editBuffer().displayValue(0, 0) + "）",
                PASTED.equals(grid.editBuffer().displayValue(0, 0)));
        check("没有错位粘到第 1 列（实际 " + grid.editBuffer().displayValue(0, 1) + "）",
                !PASTED.equals(grid.editBuffer().displayValue(0, 1)));

        // 七、新增行的光标落在第一个数据列上，不是行首
        grid.addRow();
        grid.table().layout();
        TablePosition<Integer, ?> editing = grid.table().getEditingCell();
        check("新增行直接进编辑，而且是在数据列上（实际列位置 "
                        + (editing == null ? "没进编辑" : editing.getColumn()) + "）",
                editing != null && editing.getColumn() == 1);
    }

    // ------------------------------------------------------------------ 动作

    private void clickGutter(DataGridPane grid, int viewRow, boolean shift, boolean control) {
        TableCell<?, ?> cell = gutterCell(grid, viewRow);
        if (cell == null) {
            fail++;
            log("找不到第 " + viewRow + " 行的行首格子");
            return;
        }
        Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_PRESSED,
                5, 5, 5, 5, MouseButton.PRIMARY, 1,
                shift, control, false, false,
                true, false, false, false, false, false, null));
        grid.table().layout();
    }

    private static TableCell<?, ?> gutterCell(DataGridPane grid, int viewRow) {
        for (Node node : grid.table().lookupAll(".grid-gutter")) {
            if (node instanceof TableCell<?, ?> cell && cell.getIndex() == viewRow
                    && !cell.isEmpty()) {
                return cell;
            }
        }
        return null;
    }

    /** 某一行上第一个数据格（跳过行首那一列）。 */
    private static Node dataCell(DataGridPane grid, int viewRow) {
        for (Node node : grid.table().lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && cell.getIndex() == viewRow
                    && !cell.isEmpty() && !cell.getStyleClass().contains("grid-gutter")) {
                return cell;
            }
        }
        return null;
    }

    /** 一个节点正中间那个像素的颜色。截图截的是整个场景，这里按节点位置换算过去。 */
    private static int centerPixel(Node node) {
        javafx.geometry.Bounds bounds = node.localToScene(node.getBoundsInLocal());
        WritableImage image = node.getScene().snapshot(null);
        int x = (int) Math.round(bounds.getMinX() + bounds.getWidth() / 2);
        int y = (int) Math.round(bounds.getMinY() + bounds.getHeight() / 2);
        x = Math.max(0, Math.min((int) image.getWidth() - 1, x));
        y = Math.max(0, Math.min((int) image.getHeight() - 1, y));
        return image.getPixelReader().getArgb(x, y);
    }

    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** 这一行在屏幕上的每一列是不是都进了选区。 */
    private static boolean wholeRowSelected(DataGridPane grid, int viewRow) {
        for (TableColumn<Integer, ?> column : grid.table().getVisibleLeafColumns()) {
            if (!grid.table().getSelectionModel().isSelected(viewRow, column)) {
                return false;
            }
        }
        return true;
    }

    /** 选区覆盖到的屏幕行号，去重排序。 */
    @SuppressWarnings("unused")
    private static List<Integer> selectedViewRows(DataGridPane grid) {
        TreeSet<Integer> rows = new TreeSet<>();
        for (TablePosition<?, ?> pos : grid.table().getSelectionModel().getSelectedCells()) {
            if (pos.getRow() >= 0) {
                rows.add(pos.getRow());
            }
        }
        return new ArrayList<>(rows);
    }

    private static KeyEvent ctrlV() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V,
                false, true, false, false);
    }

    // ------------------------------------------------------------------ 截图

    /**
     * 截一张图。
     *
     * <p>不走 {@code SwingFXUtils}——那在 javafx.swing 模块里，而这个项目的依赖只有
     * base / graphics / controls 三个。逐像素搬到 BufferedImage 一样能存，
     * 也就几万个像素的事。
     */
    private void shoot(Scene scene, String path) {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            File file = new File(path);
            javax.imageio.ImageIO.write(out, "png", file);
            log("截图：" + file.getAbsolutePath());
        } catch (java.io.IOException e) {
            log("截图存不下来：" + e);
        }
    }

    // ------------------------------------------------------------------ 数据

    private static QueryResult sample() {
        ColumnMeta id = new ColumnMeta("id", "id", "BIGINT", TypeCategory.INTEGER,
                20, 0, false, "demo", "t_order", true, false);
        ColumnMeta name = new ColumnMeta("customer", "customer", "VARCHAR", TypeCategory.STRING,
                64, 0, true, "demo", "t_order", false, false);
        ColumnMeta amount = new ColumnMeta("amount", "amount", "DECIMAL",
                TypeCategory.EXACT_NUMERIC, 18, 2, true, "demo", "t_order", false, false);
        return QueryResult.of(List.of(id, name, amount),
                List.of(new Row(new String[]{"1", "张三", "128.00"}),
                        new Row(new String[]{"2", "李四", "2048.50"}),
                        new Row(new String[]{"3", "王五", "9.99"}),
                        new Row(new String[]{"4", "赵六", "60000.00"})),
                1, false, "SELECT id, customer, amount FROM t_order");
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        log((ok ? "[通过] " : "[失败] ") + what);
    }

    private static void log(String line) {
        LOG.append(line).append(NL);
    }

    private static void delay(int millis, Runnable action) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> action.run());
        wait.play();
    }

    public static void main(String[] args) {
        // 传 class 而不是只传 args：单文件源码模式下类不在 classpath 上，
        // Application.launch(args) 会用 Class.forName 去找主类，必然 ClassNotFound
        launch(RowGutterProbe.class, args);
    }
}
