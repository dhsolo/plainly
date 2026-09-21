package com.plainly.app.view;

import com.plainly.driver.ddl.ColumnDraft;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.StringConverter;

import java.util.function.Function;

/**
 * 字段草稿表格里那两种单元格：文本格和类型下拉格。
 *
 * <h2>为什么单独放一个文件</h2>
 * 编辑字段的表格出现在两个地方——改现有表的{@link TableDesignerPane 结构设计器}，
 * 和{@link NewTableDialog 新建表}对话框。它们看着像两张表，用户眼里却是同一件事：
 * 双击、改、点向别处。
 *
 * <p>之前这两处各用各的：设计器里写了一套带失焦提交的单元格，新建表对话框用的还是
 * JavaFX 自带的 {@code TextFieldTableCell} / {@code ComboBoxTableCell}。结果是
 * 同一个操作在两个窗口里行为不一样——在设计器里改完点别处会保存，在新建表里
 * <b>改动直接没了</b>。用户报的就是这个。
 *
 * <p>所以两边共用这一份。下面每条注释记的都是踩过的坑，抄一遍等于把坑也复制一份，
 * 而且只会修好被人报上来的那一份。
 */
final class DraftFieldCells {

    private DraftFieldCells() {
    }

    /** 不着色。新建表对话框里每一行都是新的，标色反而是噪音。 */
    static final Function<ColumnDraft, String> NO_STYLE = d -> "";

    /**
     * 编辑结束后把焦点还给字段表。
     *
     * <h2>为什么这一步不能省</h2>
     * 编辑控件被移除之后，JavaFX 会把焦点交给场景里下一个可获焦的节点——
     * 在这两个界面上那正好都是上方的<b>表名输入框</b>。而 TextField 一获得焦点
     * 就会把内容全选中。
     *
     * <p>于是出现两个后果，第二个是真危险：
     * <ul>
     *   <li>选完一个数据类型，上面的表名莫名其妙变成了选中态；</li>
     *   <li>用户此时随手敲一个键，<b>整个表名就被替换掉了</b>——
     *       而他以为自己还在编辑字段。</li>
     * </ul>
     *
     * <p>把焦点还给表格，方向键接着能用，表名也碰不到。
     */
    static void returnFocusToTable(TableCell<?, ?> cell) {
        TableView<?> table = cell.getTableView();
        if (table != null && table.getScene() != null) {
            Platform.runLater(table::requestFocus);
        }
    }

    /**
     * 文本格：字段名、长度、默认值、注释都用它。
     *
     * <p>{@code rowStyle} 给出这一行的底色。设计器用它把新增行标绿、改过的行标黄；
     * 新建表对话框传 {@link #NO_STYLE}。
     */
    static final class TextCell extends TextFieldTableCell<ColumnDraft, String> {

        private final Function<ColumnDraft, String> rowStyle;
        /** Esc 监听挂过了没有。 */
        private boolean escapeHookInstalled;
        /** 按过 Esc 没有——它和「点到别处」走同一个 cancelEdit，只能靠这个标志分辨。 */
        private boolean escapePressed;
        /** 进入编辑那一刻的坐标；取消时 getEditingCell() 已经是 null 了，现拿拿不到。 */
        private TablePosition<ColumnDraft, String> editingPosition;

        TextCell(StringConverter<String> converter, Function<ColumnDraft, String> rowStyle) {
            super(converter);
            this.rowStyle = rowStyle;
        }

        /**
         * 结束编辑时把改动提交掉，除非按的是 Esc。
         *
         * <p>理由和数据网格里那一处完全一样：点另一个单元格时 TableView 先取消编辑、
         * 再转移焦点，所以挂在焦点上是不生效的，真正的挂钩点是 {@code cancelEdit}。
         * 详见 {@code DataGridPane.ValueCell.cancelEdit} 上的说明。
         */
        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing() || !(getGraphic() instanceof TextField field)) {
                return;
            }
            escapePressed = false;
            if (getTableRow() != null && getTableColumn() != null) {
                editingPosition = new TablePosition<>(
                        getTableView(), getTableRow().getIndex(), getTableColumn());
            }
            if (escapeHookInstalled) {
                return;
            }
            escapeHookInstalled = true;
            field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    escapePressed = true;
                }
            });
            /*
             * 第二个挂钩：焦点移到<b>表格外面</b>的控件上（点工具条上的按钮、
             * 点到 SQL 编辑器里去）。这条路 TableView <b>不会</b>调 cancelEdit——
             * 输入框只是失去焦点，编辑状态还在。挂在 cancelEdit 上的那一半
             * 在这里一次都不会触发，改动就那么没了。
             *
             * 两个挂钩各管一条路，缺一不可：
             *   点表格内的另一个格子 -> TableView 调 cancelEdit
             *   点表格外面的控件     -> 只有输入框失焦
             * 这两条都用探针真跑过（tools/CommitOnBlurProbe.java）。
             *
             * 不会重复提交：这里提交完编辑状态就结束了，之后就算再走一次
             * cancelEdit，那边的「值没变就不提交」也拦得住。
             */
            field.focusedProperty().addListener((o, was, focused) -> {
                if (!focused && isEditing()) {
                    commitEdit(field.getText());
                }
            });
        }

        @Override
        public void cancelEdit() {
            String typed = getGraphic() instanceof TextField f ? f.getText() : null;
            boolean shouldCommit = !escapePressed
                    && typed != null
                    && !typed.equals(getItem())
                    && editingPosition != null
                    && getTableColumn() != null
                    && getTableView() != null;
            var pos = editingPosition;

            super.cancelEdit();
            returnFocusToTable(this);

            if (shouldCommit) {
                Event.fireEvent(getTableColumn(), new TableColumn.CellEditEvent<>(
                        getTableView(), pos, TableColumn.editCommitEvent(), typed));
            }
        }

        @Override
        public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("cell-dirty", "cell-added");
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                // 空行也必须清掉内联样式。不清的话，这个被回收的单元格会顶着上一行的
                // 绿底或黄底留在表格下方——表现是「改了一行，别的行跟着变色」
                setStyle("");
                return;
            }
            setStyle(rowStyle.apply(getTableRow().getItem()));
        }
    }

    /**
     * 类型列的单元格：可编辑下拉，而且要和普通单元格长得一样高。
     *
     * <h2>为什么要专门写一个</h2>
     * 两件事默认都是错的：
     * <ul>
     *   <li>{@code ComboBoxTableCell} 弹出的下拉框自带内边距和边框，比格子高好几像素。
     *       一进编辑，这一行被撑高，<b>下面所有行跟着往下挪</b>——看着就像
     *       「点了一下类型，整张表都动了」；</li>
     *   <li>它不参与行底色，于是改过的行会出现「别的列是黄的、类型列是白的」。</li>
     * </ul>
     *
     * <p>做法和数据网格里的 {@code ValueCell} 一样：格子把内边距让出来，
     * 改由编辑控件自己带，文字位置因此不变；宽度绑到格子上，拖动列宽时跟着走。
     */
    static final class TypeCell extends ComboBoxTableCell<ColumnDraft, String> {

        private final Function<ColumnDraft, String> rowStyle;
        private Insets cellPadding;
        /** Esc 监听挂过了没有。 */
        private boolean escapeHookInstalled;
        /** 按过 Esc 没有。 */
        private boolean escapePressed;
        /** 进入编辑那一刻的坐标。 */
        private TablePosition<ColumnDraft, String> editingPosition;

        TypeCell(ObservableList<String> types, Function<ColumnDraft, String> rowStyle) {
            super(types);
            this.rowStyle = rowStyle;
            setComboBoxEditable(true);
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing() || !(getGraphic() instanceof ComboBox<?> box)) {
                return;
            }
            escapePressed = false;
            if (getTableRow() != null && getTableColumn() != null) {
                editingPosition = new TablePosition<>(
                        getTableView(), getTableRow().getIndex(), getTableColumn());
            }
            if (!escapeHookInstalled && box.getEditor() != null) {
                escapeHookInstalled = true;
                box.getEditor().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        escapePressed = true;
                    }
                });
                // 同上：焦点移到表格外面时只有失焦、没有 cancelEdit。
                // 比文本框多一个判断——下拉列表正开着不算失焦，
                // 那时候提交等于用户还没选就替他定了
                box.focusedProperty().addListener((o, was, focused) -> {
                    if (!focused && !box.isShowing() && isEditing()) {
                        commitEdit(box.getEditor().getText());
                    }
                });
                /*
                 * 从下拉列表里选中一项就提交。
                 *
                 * 不能只靠 JavaFX 内部那条路：ComboBoxTableCell 走的是下拉的
                 * <b>动作</b>事件，而「值变了」并不总是伴随一次动作事件。
                 * 实测（tools/DesignerEditProbe.java）里选中一项之后类型仍是旧值，
                 * 提交压根没发生——用户看到的是「我明明选了 DECIMAL，它还是 VARCHAR」。
                 *
                 * 盯着值本身最可靠：它变了就是用户做了选择。
                 */
                box.valueProperty().addListener((o, was, picked) -> {
                    if (picked != null && isEditing() && !picked.equals(getItem())) {
                        commitEdit(String.valueOf(picked));
                    }
                });
            }
            Insets current = getPadding();
            if (cellPadding == null && !Insets.EMPTY.equals(current)) {
                cellPadding = current;
            }
            // 内联样式压得住样式表，setPadding 压不住——样式表会在下一次 CSS
            // 生效时把它盖回去，量出来就是「代码明明跑了，尺寸没变」
            setStyle("-fx-padding: 0;");
            box.setStyle("-fx-padding: 0 0 0 " + (cellPadding == null ? 0 : cellPadding.getLeft())
                    + "; -fx-background-radius: 0; -fx-border-radius: 0;");
            box.prefWidthProperty().bind(widthProperty());
            if (getHeight() > 0) {
                box.setMinHeight(0);
                box.setPrefHeight(getHeight());
                box.setMaxHeight(getHeight());
            }
        }

        @Override
        public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (!isEditing()) {
                restore();
            }
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setStyle("");
                return;
            }
            if (!isEditing()) {
                setStyle(rowStyle.apply(getTableRow().getItem()));
            }
        }

        /**
         * 结束编辑时提交手敲进去的类型名，除非按的是 Esc。
         *
         * <p>从列表里选一个走的是正常的提交路径，这里管的是另一半：
         * 用户在可编辑下拉里<b>敲</b>了一个库里特有的类型名，然后点向别处。
         */
        @Override
        public void cancelEdit() {
            String typed = getGraphic() instanceof ComboBox<?> box && box.getEditor() != null
                    ? box.getEditor().getText() : null;
            boolean shouldCommit = !escapePressed
                    && typed != null
                    && !typed.isBlank()
                    && !typed.equals(getItem())
                    && editingPosition != null
                    && getTableColumn() != null
                    && getTableView() != null;
            var pos = editingPosition;

            super.cancelEdit();
            restore();

            if (shouldCommit) {
                Event.fireEvent(getTableColumn(), new TableColumn.CellEditEvent<>(
                        getTableView(), pos, TableColumn.editCommitEvent(), typed));
            }
        }

        /**
         * 退出编辑：把内边距还给样式表，解开宽度绑定。
         *
         * <h2>为什么必须在这儿清内联样式</h2>
         * 进入编辑时格子把自己的内边距让给了下拉框（{@code -fx-padding: 0}），
         * 那是内联样式，压过样式表。退出编辑不清掉的话，这一格的文字从此
         * <b>一直贴着左边</b>，和同列其它格子对不齐——而且要等到下一次
         * {@code updateItem} 才可能被覆盖，点击别处取消编辑那条路上根本不会触发。
         */
        private void restore() {
            if (getGraphic() instanceof ComboBox<?> box) {
                box.prefWidthProperty().unbind();
            }
            ColumnDraft d = getTableRow() == null ? null : getTableRow().getItem();
            setStyle(d == null ? "" : rowStyle.apply(d));
            returnFocusToTable(this);
        }
    }
}
