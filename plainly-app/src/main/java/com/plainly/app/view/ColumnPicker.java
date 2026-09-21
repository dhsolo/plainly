package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 挑几列，而且<b>记住顺序</b>。
 *
 * <h2>为什么不是一排复选框</h2>
 * 复选框给出的是一个集合，而这里需要的是序列——顺序在两个地方都有实际后果：
 * <ul>
 *   <li><b>索引</b>：{@code (a, b)} 和 {@code (b, a)} 是两个不同的索引。
 *       前者能加速按 a 过滤的查询，后者不能（最左前缀）。用集合做界面，
 *       等于让用户在一件有后果的事情上无法表达意图。</li>
 *   <li><b>外键</b>：本表的列和目标表的列是<b>按位置配对</b>的。
 *       顺序错了，约束仍然建得起来，只是配错了对——这种错不会报错，
 *       会一直安静地存在，直到某天数据对不上。</li>
 * </ul>
 *
 * <p>所以这里是「下拉挑一个 → 加到列表 → 可以上下移」。列表本身就是顺序，
 * 用户看得见自己选了什么、按什么次序。
 */
public class ColumnPicker extends VBox {

    private final ComboBox<String> available = new ComboBox<>();
    private final ListView<String> chosen = new ListView<>();
    private Runnable onChanged = () -> { };

    /**
     * @param title  这一栏的标题。两个选择器并排时，标题让它们对得齐——
     *               不然一边多一行、一边少一行，看着像布局塌了
     * @param prompt 下拉框里的占位文字
     */
    public ColumnPicker(String title, String prompt) {
        super(6);
        available.setPromptText(prompt);
        available.setPrefWidth(190);
        available.setMinWidth(190);

        /*
         * 高度要有<b>下限</b>。
         *
         * 只设 prefHeight 是不够的：这个选择器被放进一个空间紧张的表单里，
         * VBox 一压就把它缩到不足一行——加进去的列于是看不见，
         * 仅存的那一行连下划线都被裁掉（customer_id 显示成 customer id）。
         * 而这一栏的全部意义就是「让用户看见自己选了哪几列、什么顺序」。
         */
        chosen.setMinHeight(96);
        chosen.setPrefHeight(120);
        // column-list 给它一圈边框和底色：没有边框时，那几行列名浮在表单上，
        // 看不出是一个列表，也就看不出哪些是已选的
        chosen.getStyleClass().addAll("completion-list", "column-list");
        chosen.setPlaceholder(UiUtils.label("还没有选列", "hint"));

        Button add = UiUtils.toolButton("加入", Icons.plus(Icons.NEUTRAL, 11));
        add.setOnAction(e -> addSelected());
        // 双击下拉里的项直接加入，省一次点击
        available.setOnAction(e -> { });

        Button up = UiUtils.toolButton("↑", null);
        up.setOnAction(e -> move(-1));
        Button down = UiUtils.toolButton("↓", null);
        down.setOnAction(e -> move(1));
        Button remove = UiUtils.toolButton("移除", Icons.minus("#a0402a", 11));
        remove.setOnAction(e -> removeSelected());

        HBox top = UiUtils.row(6, available, add);
        HBox bottom = UiUtils.row(6, up, down, remove);

        getChildren().addAll(UiUtils.label(title, "form-label"), top, chosen, bottom);
        VBox.setVgrow(chosen, Priority.SOMETIMES);
    }

    public void setOnChanged(Runnable handler) {
        this.onChanged = handler;
    }

    /** 候选列。已经选中的仍留在下拉里——同一列在一个索引里出现两次是错的，加入时会挡掉。 */
    public void setAvailable(List<String> names) {
        available.setItems(FXCollections.observableArrayList(names));
    }

    public List<String> selected() {
        return new ArrayList<>(chosen.getItems());
    }

    public void setSelected(List<String> names) {
        chosen.setItems(FXCollections.observableArrayList(names));
        onChanged.run();
    }

    public void clear() {
        chosen.getItems().clear();
        available.setValue(null);
        onChanged.run();
    }

    public boolean isEmpty() {
        return chosen.getItems().isEmpty();
    }

    private void addSelected() {
        String name = available.getValue();
        if (name == null || chosen.getItems().contains(name)) {
            // 同一列加两次在索引里是语法错误，在外键里是配错对，两种都不该放过去
            return;
        }
        chosen.getItems().add(name);
        onChanged.run();
    }

    private void removeSelected() {
        int i = chosen.getSelectionModel().getSelectedIndex();
        if (i >= 0) {
            chosen.getItems().remove(i);
            onChanged.run();
        }
    }

    private void move(int delta) {
        int i = chosen.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= chosen.getItems().size()) {
            return;
        }
        String moved = chosen.getItems().remove(i);
        chosen.getItems().add(j, moved);
        chosen.getSelectionModel().select(j);
        onChanged.run();
    }
}
