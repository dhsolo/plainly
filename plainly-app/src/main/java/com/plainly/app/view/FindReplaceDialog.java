package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.edit.EditBuffer;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * 查找替换与批量置值。
 *
 * <p>只改已取回的这一页，改动进的是和网格同一个编辑缓冲——**不会直接写库**，
 * 得像手工改单元格一样按「保存」才落盘。这一点必须说清楚：
 * 「批量替换」听起来像立刻生效，而它在这里只是替你敲了很多次键盘。
 *
 * <p>换句话说，它替代的是「一个个双击改」，不是 UPDATE 语句。
 * 要改整张表请用 SQL——几十万行不该靠翻页点鼠标。
 */
public class FindReplaceDialog {

    private final QueryResult result;
    private final EditBuffer buffer;

    private final ComboBox<String> columnBox = new ComboBox<>();
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox wholeCell = new CheckBox("整格匹配");
    private final CheckBox ignoreCase = new CheckBox("忽略大小写");
    private final CheckBox setNull = new CheckBox("置为 NULL");
    private final Label summary = UiUtils.label("", "hint");

    private Stage stage;
    private Runnable onChanged = () -> { };

    public FindReplaceDialog(QueryResult result, EditBuffer buffer) {
        this.result = result;
        this.buffer = buffer;
    }

    public void setOnChanged(Runnable handler) {
        this.onChanged = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("查找替换 / 批量置值");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 620, 420);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.search(Icons.ACCENT, 14),
                UiUtils.label("只改本页，改完仍需保存", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        columnBox.getItems().add("（全部列）");
        result.columns().forEach(c -> columnBox.getItems().add(c.name()));
        columnBox.setValue("（全部列）");
        columnBox.setPrefWidth(220);

        findField.setPromptText("要找的内容");
        replaceField.setPromptText("替换成");
        setNull.setOnAction(e -> replaceField.setDisable(setNull.isSelected()));

        VBox box = UiUtils.column(12,
                row("列", columnBox),
                row("查找", findField),
                row("替换为", replaceField),
                UiUtils.row(16, wholeCell, ignoreCase, setNull),
                UiUtils.label("「置为 NULL」和替换成空字符串是两回事——"
                        + "NULL 表示没有值，空串是一个长度为零的值。", "hint"),
                summary);
        box.setPadding(new Insets(14));
        return box;
    }

    private HBox row(String label, javafx.scene.Node field) {
        Label l = UiUtils.label(label, "form-label");
        l.setMinWidth(64);
        return UiUtils.row(10, l, field);
    }

    private HBox buildFoot() {
        Button count = UiUtils.toolButton("先数一数", null);
        count.setOnAction(e -> {
            int n = apply(true);
            summary.setText("本页有 " + n + " 个单元格会被改动");
        });
        Button replaceAll = UiUtils.toolButton("全部替换", null, "primary");
        replaceAll.setOnAction(e -> {
            int n = apply(false);
            summary.setText("已改动 " + n + " 个单元格 · 记得保存");
            onChanged.run();
        });
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close, count, replaceAll);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    /**
     * 扫一遍本页。
     *
     * @param dryRun 只数不改——批量改动前先知道会动多少格，这个习惯值得鼓励
     */
    private int apply(boolean dryRun) {
        String find = findField.getText() == null ? "" : findField.getText();
        if (find.isEmpty() && !setNull.isSelected()) {
            summary.setText("先填要找的内容");
            return 0;
        }
        List<Integer> columns = targetColumns();
        int changed = 0;

        for (int row = 0; row < result.rows().size(); row++) {
            for (int col : columns) {
                String value = buffer.displayValue(row, col);
                if (value == null) {
                    continue;
                }
                String next = replaced(value, find);
                if (next == null || next.equals(value)) {
                    continue;
                }
                changed++;
                if (!dryRun) {
                    buffer.set(row, col, setNull.isSelected() ? null : next);
                }
            }
        }
        return changed;
    }

    /** 命中了就返回替换后的值，没命中返回 null。 */
    private String replaced(String value, String find) {
        if (wholeCell.isSelected()) {
            boolean hit = ignoreCase.isSelected() ? value.equalsIgnoreCase(find)
                    : value.equals(find);
            if (!hit) {
                return null;
            }
            return setNull.isSelected() ? "" : replaceField.getText();
        }
        if (setNull.isSelected()) {
            boolean hit = ignoreCase.isSelected()
                    ? value.toLowerCase().contains(find.toLowerCase()) : value.contains(find);
            return hit ? "" : null;
        }
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        if (ignoreCase.isSelected()) {
            // 大小写不敏感的替换要按位置扫，不能用 replace——它只认原样的大小写
            StringBuilder sb = new StringBuilder();
            String lowerValue = value.toLowerCase();
            String lowerFind = find.toLowerCase();
            int from = 0;
            boolean hit = false;
            while (true) {
                int at = lowerValue.indexOf(lowerFind, from);
                if (at < 0) {
                    break;
                }
                hit = true;
                sb.append(value, from, at).append(replacement);
                from = at + find.length();
            }
            if (!hit) {
                return null;
            }
            return sb.append(value.substring(from)).toString();
        }
        return value.contains(find) ? value.replace(find, replacement) : null;
    }

    private List<Integer> targetColumns() {
        List<Integer> out = new ArrayList<>();
        String picked = columnBox.getValue();
        for (int i = 0; i < result.columns().size(); i++) {
            ColumnMeta meta = result.columns().get(i);
            if ("（全部列）".equals(picked) || meta.name().equals(picked)) {
                out.add(i);
            }
        }
        return out;
    }
}
