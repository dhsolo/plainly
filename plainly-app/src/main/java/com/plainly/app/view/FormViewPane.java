package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.edit.EditBuffer;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 表单视图：一次只看一行。
 *
 * <p>字段一多，网格里一行就得横着拖半天，长文本更是只能看见开头。表单把一行铺开，
 * 每个字段旁边直接标出它的类型、是不是主键、能不能为空。
 *
 * <p>改动写进和网格同一个 {@link EditBuffer}——两边看到的脏值是同一份，
 * 保存也走同一条路。两套缓冲区迟早会各说各话。
 *
 * <p>翻记录只在已取回的这一页里翻。跨页要重新取数，那是分页器的事：
 * 假装能一路翻到第 128 万行，只会让人以为数据已经在手上了。
 */
public class FormViewPane extends BorderPane {

    private final VBox fieldBox = new VBox(2);
    private final Label positionLabel = UiUtils.label("—");
    private final Label dirtyLabel = UiUtils.label("", "tree-badge");
    private final Button first = UiUtils.toolButton("⇤", null);
    private final Button prev = UiUtils.toolButton("‹", null);
    private final Button next = UiUtils.toolButton("›", null);
    private final Button last = UiUtils.toolButton("⇥", null);

    private QueryResult result;
    private EditBuffer buffer;
    private int rowIndex;
    private Runnable onDirtyChanged = () -> { };

    public FormViewPane() {
        getStyleClass().add("form-view");

        ScrollPane scroll = new ScrollPane(fieldBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");

        setTop(buildToolbar());
        setCenter(scroll);
    }

    public void setOnDirtyChanged(Runnable handler) {
        this.onDirtyChanged = handler;
    }

    /** 换了一页数据。行号回到第一行——上一页的第 37 行和这一页的第 37 行没有关系。 */
    public void setResult(QueryResult result, EditBuffer buffer) {
        this.result = result;
        this.buffer = buffer;
        this.rowIndex = 0;
        render();
    }

    /** 网格里选中了某一行，表单跟着走到那一行。 */
    public void showRow(int index) {
        if (result == null || index < 0 || index >= result.rows().size()) {
            return;
        }
        rowIndex = index;
        render();
    }

    public int rowIndex() {
        return rowIndex;
    }

    private HBox buildToolbar() {
        first.setOnAction(e -> go(0));
        prev.setOnAction(e -> go(rowIndex - 1));
        next.setOnAction(e -> go(rowIndex + 1));
        last.setOnAction(e -> go(result == null ? 0 : result.rows().size() - 1));

        HBox bar = UiUtils.row(6, first, prev, positionLabel, next, last,
                UiUtils.vSeparator(), dirtyLabel, UiUtils.hSpacer(),
                UiUtils.label("只在已取回的这一页里翻，跨页请用下方分页器", "hint"));
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private void go(int index) {
        if (result == null || index < 0 || index >= result.rows().size()) {
            return;
        }
        rowIndex = index;
        render();
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        fieldBox.getChildren().clear();
        if (result == null || result.rows().isEmpty()) {
            positionLabel.setText("—");
            fieldBox.getChildren().add(UiUtils.label("这一页没有数据", "hint"));
            updateNav();
            return;
        }

        positionLabel.setText("第 " + (rowIndex + 1) + " / " + result.rows().size() + " 行");
        String reason = result.readOnlyReason();
        if (reason != null) {
            HBox bar = UiUtils.row(6, Icons.lock("#8a6d1f", 11),
                    UiUtils.label("只读 · " + reason, "readonly-text"));
            bar.getStyleClass().add("readonly-bar");
            fieldBox.getChildren().add(bar);
        }

        for (int i = 0; i < result.columns().size(); i++) {
            fieldBox.getChildren().add(fieldRow(result.columns().get(i), i, reason == null));
        }
        refreshDirtyBadge();
        updateNav();
    }

    private HBox fieldRow(ColumnMeta meta, int columnIndex, boolean editable) {
        Label name = UiUtils.label(meta.label(), "form-field-name");
        name.setMinWidth(150);
        name.setPrefWidth(150);

        String value = buffer == null ? null : buffer.displayValue(rowIndex, columnIndex);

        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(value == null ? "(NULL)" : "");
        HBox.setHgrow(field, Priority.ALWAYS);

        CheckBox nullBox = new CheckBox("NULL");
        nullBox.setSelected(value == null);
        nullBox.setDisable(!editable || !meta.nullable());

        boolean writable = editable && !meta.autoIncrement();
        field.setEditable(writable);
        field.setDisable(value == null);

        field.textProperty().addListener((o, was, is) -> {
            if (!writable || nullBox.isSelected()) {
                return;
            }
            buffer.set(rowIndex, columnIndex, is);
            refreshDirtyBadge();
            onDirtyChanged.run();
        });

        // NULL 和空字符串是两个值。勾选框在这里不是样式问题，是语义问题
        nullBox.setOnAction(e -> {
            if (!writable) {
                return;
            }
            if (nullBox.isSelected()) {
                buffer.set(rowIndex, columnIndex, null);
                field.setDisable(true);
                field.setText("");
                field.setPromptText("(NULL)");
            } else {
                field.setDisable(false);
                field.setPromptText("");
                buffer.set(rowIndex, columnIndex, field.getText());
            }
            refreshDirtyBadge();
            onDirtyChanged.run();
        });

        HBox badges = UiUtils.row(6, UiUtils.label(meta.displayType(), "hint"));
        if (meta.partOfKey()) {
            badges.getChildren().add(UiUtils.label("主键", "tree-badge"));
        }
        if (!meta.nullable()) {
            badges.getChildren().add(UiUtils.label("NOT NULL", "hint"));
        }
        if (meta.autoIncrement()) {
            badges.getChildren().add(UiUtils.label("自增 · 只读", "hint"));
        }
        if (meta.riskyInDouble()) {
            badges.getChildren().add(UiUtils.label("精确数值 · 全程按原文传递", "badge-exact"));
        }
        badges.setMinWidth(300);
        badges.setPrefWidth(300);
        badges.setAlignment(Pos.CENTER_LEFT);

        HBox row = UiUtils.row(10, name, field, nullBox, badges);
        row.setPadding(new Insets(5, 12, 5, 12));
        row.getStyleClass().add("form-field-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshDirtyBadge() {
        int count = buffer == null ? 0 : buffer.changeCount();
        dirtyLabel.setText(count == 0 ? "" : count + " 处未保存修改");
        dirtyLabel.setVisible(count > 0);
    }

    private void updateNav() {
        int size = result == null ? 0 : result.rows().size();
        first.setDisable(rowIndex <= 0);
        prev.setDisable(rowIndex <= 0);
        next.setDisable(rowIndex >= size - 1);
        last.setDisable(rowIndex >= size - 1);
    }
}
