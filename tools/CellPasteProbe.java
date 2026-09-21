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
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.util.List;

/**
 * 编辑中的格子按 Ctrl+V，粘的是<b>光标处那一段</b>，不是整格覆盖。
 *
 * <h2>为什么要有这个探针</h2>
 * 数据网格在 {@code table} 上挂了一个 KEY_PRESSED <b>事件过滤器</b>处理 Ctrl+V，
 * 走的是「整片粘贴」那条路（按制表符和换行铺到若干格里）。过滤器跑在捕获阶段，
 * 比格子里那个输入框<b>早</b>——于是双击进编辑之后再按 Ctrl+V，
 * 用户以为是在输入框里粘一段字，实际触发的是整格覆盖，选区和光标位置全都不算数。
 *
 * <p>这件事光读代码容易判断错：要说清楚「事件先到谁那里」，得把真实的
 * {@code DataGridPane} 搭起来、真的进编辑、真的发一个 Ctrl+V 下去看。
 * 所以这里就这么做，顺便把输入框的尺寸也量一遍——整格覆盖会调
 * {@code table.refresh()}，那会让编辑中的格子重建，输入框缩回默认大小。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/CellPasteProbe.java
 * </pre>
 */
public class CellPasteProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    /** 格子里原有的值。挑一个长一点的，好让「插在中间」和「整格覆盖」结果明显不同。 */
    private static final String ORIGINAL = "abcdefgh";
    /** 剪贴板里的东西。不含制表符与换行——单格粘贴才是这里要考的。 */
    private static final String PASTED = "XY";
    /** 尺寸比对的容差，单位像素。 */
    private static final double TOLERANCE = 2;

    @Override
    public void start(Stage stage) {
        DataGridPane grid = new DataGridPane();
        grid.setResult(sample());

        Scene scene = new Scene(grid, 800, 300);
        // 挂上应用的样式表：不挂的话看到的是 modena 的默认样子，
        // 截图里那片蓝色根本不是这个项目的配色，样式对不对无从判断
        scene.getStylesheets().add(
                DataGridPane.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("CellPasteProbe");
        stage.show();

        // 等表格真的布局完：没有布局就没有行高列宽，尺寸那一项无从量起
        delay(900, () -> {
            try {
                run(grid);
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
        ClipboardContent content = new ClipboardContent();
        content.putString(PASTED);
        Clipboard.getSystemClipboard().setContent(content);

        // 进编辑：第 0 行、结果集里的第 1 列（name，可改）。
        // 按 userData 找而不是按位置数——网格最左边还有一列「行首」，它不属于结果集
        TableColumn<Integer, ?> column = dataColumn(grid, 1);
        grid.table().getSelectionModel().clearAndSelect(0, column);
        grid.table().edit(0, column);
        grid.table().layout();

        TableCell<Integer, ?> cell = editingCell(grid);
        if (cell == null) {
            fail++;
            log("进不了编辑状态，后面几项没法考");
            return;
        }
        if (!(cell.getGraphic() instanceof TextField field)) {
            fail++;
            log("编辑中的格子里没有输入框，后面几项没法考");
            return;
        }

        // 先量一遍「按 Ctrl+V 之前」的尺寸，后面才说得清缩水是不是这一下造成的
        log("按键前：格子 " + size(cell) + "，输入框 " + size(field));
        // 容差 2px 而不是 1px：格子有一条边框，输入框摆在边框以内，
        // 两边量出来本来就会差那么一点，那不是「没盖住」
        check("进编辑时输入框就该盖住格子（宽）",
                Math.abs(field.getWidth() - cell.getWidth()) <= TOLERANCE);
        check("进编辑时输入框就该盖住格子（高）",
                Math.abs(field.getHeight() - cell.getHeight()) <= TOLERANCE);

        // 把光标放在第 3 个字符后面，并选中接着的 2 个字符：
        // 正确的行为是把选中那两个字换成剪贴板内容，其余原样留着
        field.selectRange(3, 5);
        field.requestFocus();

        Event.fireEvent(field, ctrlV());
        grid.table().layout();

        // 编辑中的那个格子可能已经被 refresh() 换成了另一个对象，重新找一遍
        TableCell<Integer, ?> after = editingCell(grid);
        if (after == null) {
            fail++;
            log("Ctrl+V 之后编辑状态没了——整格覆盖那条路把编辑中断了");
            log("编辑缓冲里第 0 行第 1 列 = " + grid.editBuffer().displayValue(0, 1));
            return;
        }
        TextField now = after.getGraphic() instanceof TextField f ? f : null;
        if (now == null) {
            fail++;
            log("Ctrl+V 之后格子里没有输入框了");
            return;
        }

        String text = now.getText();
        String expected = ORIGINAL.substring(0, 3) + PASTED + ORIGINAL.substring(5);
        check("粘的是选中那一段：期待 " + expected + "，实际 " + text, expected.equals(text));
        check("整格没有被剪贴板内容顶掉（实际 " + text + "）", !PASTED.equals(text));
        check("没有走「整片粘贴」那条路写编辑缓冲（缓冲里 = "
                + grid.editBuffer().displayValue(0, 1) + "）", !grid.editBuffer().isDirty(0, 1));

        log("按键后：格子 " + size(after) + "，输入框 " + size(now));
        check("输入框宽度仍与格子一致", Math.abs(now.getWidth() - after.getWidth()) <= TOLERANCE);
        check("输入框高度仍与格子一致", Math.abs(now.getHeight() - after.getHeight()) <= TOLERANCE);

        notEditing(grid);
    }

    /**
     * 反过来的一半：<b>没在</b>编辑的时候，Ctrl+V 仍然要走网格的整片粘贴。
     *
     * <p>这一项和上面几项一样重要。上面是「别抢输入框的」，这里是「该抢的还得抢」——
     * 把守卫写宽一点点（比如改成「只要表格在编辑状态就一律让开」），
     * 上面几项照样全绿，而整片粘贴会安静地失效。
     */
    private void notEditing(DataGridPane grid) {
        grid.table().edit(-1, null);
        grid.table().layout();

        TableColumn<Integer, ?> column = dataColumn(grid, 1);
        grid.table().getSelectionModel().clearAndSelect(1, column);
        grid.table().requestFocus();

        Event.fireEvent(grid.table(), ctrlV());
        grid.table().layout();

        check("没在编辑时 Ctrl+V 仍然整格粘贴（第 1 行第 1 列 = "
                        + grid.editBuffer().displayValue(1, 1) + "）",
                PASTED.equals(grid.editBuffer().displayValue(1, 1)));
    }

    private static String size(javafx.scene.layout.Region region) {
        return fmt(region.getWidth()) + " x " + fmt(region.getHeight());
    }

    /** 结果集里第 index 列对应的那个表格列。 */
    private static TableColumn<Integer, ?> dataColumn(DataGridPane grid, int index) {
        for (TableColumn<Integer, ?> column : grid.table().getVisibleLeafColumns()) {
            if (column.getUserData() instanceof Integer marker && marker == index) {
                return column;
            }
        }
        throw new IllegalStateException("找不到结果集第 " + index + " 列对应的表格列");
    }

    private static KeyEvent ctrlV() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V,
                false, true, false, false);
    }

    private static TableCell<Integer, ?> editingCell(DataGridPane grid) {
        for (Node node : grid.table().lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && cell.isEditing()) {
                @SuppressWarnings("unchecked")
                TableCell<Integer, ?> typed = (TableCell<Integer, ?>) cell;
                return typed;
            }
        }
        return null;
    }

    /** 一份两列两行、可编辑的结果集：id 是主键，name 拿来改。 */
    private static QueryResult sample() {
        ColumnMeta id = new ColumnMeta("id", "id", "BIGINT", TypeCategory.INTEGER,
                20, 0, false, "demo", "t_demo", true, false);
        ColumnMeta name = new ColumnMeta("name", "name", "VARCHAR", TypeCategory.STRING,
                64, 0, true, "demo", "t_demo", false, false);
        return QueryResult.of(List.of(id, name),
                List.of(new Row(new String[]{"1", ORIGINAL}),
                        new Row(new String[]{"2", "ijklmnop"})),
                1, false, "SELECT id, name FROM t_demo");
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
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
        launch(CellPasteProbe.class, args);
    }
}
