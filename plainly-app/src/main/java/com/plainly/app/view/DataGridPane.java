package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.edit.EditBuffer;
import com.plainly.core.export.ClipboardFormats;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.QueryResult;
import com.plainly.driver.TypeCategory;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 结果集网格。
 *
 * <h2>几个刻意的选择</h2>
 * <ul>
 *   <li>表格项是<b>行下标</b>而不是 Row 对象：单元格取值要经过 {@link EditBuffer}，
 *       有下标才能同时定位「原值」和「改后值」，脏值标记也才有着落。</li>
 *   <li>数值列右对齐 + 等宽字体：长数字只有对齐了才看得出位数差异。</li>
 *   <li>编辑用普通 TextField。绝不能用任何会替我们解析数字的输入控件——
 *       那等于在最后一米把前面所有的精度努力扔掉。</li>
 * </ul>
 */
public class DataGridPane extends VBox {

    private final TableView<Integer> table = new TableView<>();
    private final Label inspectorTitle = UiUtils.label("", "mono");
    private final Label inspectorType = UiUtils.label("", "hint");
    private final Label inspectorBadge = UiUtils.label("", "badge-exact");
    private final Label inspectorValue = UiUtils.label("", "inspector-value");
    private final Label inspectorPath = UiUtils.label("", "mono", "hint");

    private final Label readOnlyLabel = UiUtils.label("", "readonly-text");
    private final HBox readOnlyBar = buildReadOnlyBar();

    private QueryResult result;
    private EditBuffer editBuffer;

    /** 点了列头之后由外面决定怎么排——排序要下推到数据库，这里不知道分页和筛选。 */
    private java.util.function.Consumer<String> onSortRequested = null;
    /** 当前排的是哪一列、什么方向。只用来在表头上画那个箭头。 */
    private String sortColumn;
    private boolean sortDescending;
    /** 调用方知道这份数据出自哪张表时给一个；比从结果集元数据里猜更准。 */
    private String tableNameHint;
    private Runnable onDirtyChanged = () -> { };
    private Consumer<String> onStatus = s -> { };

    /**
     * Shift 在行首连选时的锚点，存的是<b>屏幕上的</b>行号。
     *
     * <p>不能存结果集行下标：连选要的是「从上次点的那一行连到这一行」，
     * 而这是屏幕上的相邻关系。排序换过之后两者就不是一回事了。
     */
    private int gutterAnchorRow = -1;

    public DataGridPane() {
        getStyleClass().add("data-grid-pane");
        table.getStyleClass().add("data-grid");
        table.setPlaceholder(defaultPlaceholder());
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().setCellSelectionEnabled(true);
        // JavaFX 默认 SINGLE：不设这一行，拖选、Ctrl 点选、整行选中全都只留最后一格，
        // 而「复制 N 行 × M 列」这套机制自始至终只可能拿到一格
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getSelectionModel().getSelectedCells().addListener(
                (javafx.collections.ListChangeListener<javafx.scene.control.TablePosition>) c -> updateInspector());

        // 双击只读的格子什么都不会发生。不解释一句，用户只会以为是软件坏了
        table.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getClickCount() == 2 && result != null && !result.isEditable()) {
                onStatus.accept("只读：" + result.readOnlyReason());
            }
        });

        table.setContextMenu(buildContextMenu());
        table.setRowFactory(t -> new MarkedRow());

        // Ctrl+C 走「制表符分隔、不带表头」——这是从表格软件里复制出来的默认样子，
        // 粘回 Excel 正好落回原来的格子。要表头或别的格式走右键菜单
        table.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            /*
             * 正在编辑的格子里是一个真正的输入框，这几个键在它里面另有含义：
             * Ctrl+C 是「复制选中的那几个字」，Ctrl+V 是「在光标处粘一段进去」，
             * Shift+空格就是打一个空格。
             *
             * 下面这三条是<b>网格</b>的快捷键（整格复制、整片粘贴、选整行），
             * 而事件过滤器跑在捕获阶段，比输入框<b>早</b>。不在这里让开的话，
             * 双击进编辑再按 Ctrl+V，走的是「整片粘贴」那条路：
             * 整格被剪贴板内容顶掉，光标位置和选区全都不算数——用户想插两个字，
             * 结果整格原值没了。而且那条路末尾会 table.refresh()，
             * 编辑中的格子被重建，输入框缩回默认大小，看着像是界面坏了。
             *
             * 判断用事件的目标而不是 table.getEditingCell()：编辑状态的更新时机
             * 在不同 JavaFX 版本上不一样，而「这个键是打给谁的」是当场就确定的。
             */
            if (e.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }
            if (COPY.match(e)) {
                copyAs(ClipboardFormats.Format.TSV, false);
                e.consume();
            } else if (SELECT_ROW.match(e)) {
                // Shift+空格选整行，跟表格软件一致
                selectWholeRows();
                e.consume();
            } else if (PASTE.match(e)) {
                pasteFromClipboard();
                e.consume();
            }
        });

        getChildren().addAll(readOnlyBar, table, buildInspector());
    }

    private static final KeyCombination COPY =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    private static final KeyCombination SELECT_ROW =
            new KeyCodeCombination(KeyCode.SPACE, KeyCombination.SHIFT_DOWN);

    /** 换行、回车、制表符。写成常量，避开源码里的转义在多层脚本传递中被吃掉。 */
    private static final String LF = String.valueOf((char) 10);
    private static final String CR = String.valueOf((char) 13);
    private static final String TAB = String.valueOf((char) 9);

    private static final KeyCombination PASTE =
            new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    /**
     * 只读提示条。
     *
     * <p>可编辑时整条收起来（{@code managed=false} 才是真的不占位置，
     * 只设 {@code visible=false} 会留下一条空白）。
     */
    private HBox buildReadOnlyBar() {
        HBox bar = UiUtils.row(6, Icons.lock("#8a6d1f", 11), readOnlyLabel);
        bar.getStyleClass().add("readonly-bar");
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    public TableView<Integer> table() {
        return table;
    }

    public EditBuffer editBuffer() {
        return editBuffer;
    }

    public QueryResult result() {
        return result;
    }

    public void setOnDirtyChanged(Runnable r) {
        this.onDirtyChanged = r;
    }

    /**
     * 点列头要求按这一列排序。
     *
     * <p>没设这个回调时列头不可点：排序得由数据库来做（当前页只是几百行，
     * 在页内排出来的顺序是假的），而下推排序要重建分页查询——那是标签页的事。
     */
    public void setOnSortRequested(java.util.function.Consumer<String> handler) {
        this.onSortRequested = handler;
    }

    /** 告诉表头当前排的是哪一列。下一次 {@link #setResult} 重建表头时画上箭头。 */
    public void setSort(String column, boolean descending) {
        this.sortColumn = column;
        this.sortDescending = descending;
    }

    public void setOnStatus(Consumer<String> c) {
        this.onStatus = c;
    }

    // ------------------------------------------------------------------ 装填

    public void setResult(QueryResult result) {
        this.result = result;
        this.editBuffer = new EditBuffer(result);

        // 上一次 clear 可能留下一句「这条语句没有结果集」，那句话对这份结果不成立了
        table.setPlaceholder(defaultPlaceholder());
        table.getColumns().clear();
        String reason = result.readOnlyReason();
        table.setEditable(reason == null);
        readOnlyLabel.setText(reason == null ? "" : "只读 · " + reason);
        readOnlyBar.setVisible(reason != null);
        readOnlyBar.setManaged(reason != null);

        List<ColumnMeta> columns = result.columns();
        // 没有列的结果（DML 的受影响行数）不摆行首列：那里没有「行」可选
        if (!columns.isEmpty()) {
            table.getColumns().add(buildGutterColumn());
        }
        for (int i = 0; i < columns.size(); i++) {
            table.getColumns().add(buildColumn(columns.get(i), i));
        }
        gutterAnchorRow = -1;

        rebuildItems();
        clearInspector();
    }

    /**
     * 清空网格：结果、编辑缓冲、列、选区一起清掉。
     *
     * <h2>为什么需要它</h2>
     * 下面显示的必须是<b>刚跑的这一条</b>的结果。可执行报错、或者跑的是一条没有结果集的
     * 语句（UPDATE / DDL）时，调用方原来什么都不对网格做——于是上一条 SELECT 的那几行
     * 还摆在下面，而标题已经换成了「影响 3 行」。用户读到的是
     * 「我刚才那条语句返回了这些行」，而那是另一条语句的结果。
     *
     * <p>光把网格藏起来不够：结果对象还在，「导出结果」「新增行」这些按钮认的就是它，
     * 藏起来的那份数据照样能被导出去。所以要真的清掉。
     *
     * @param placeholder 空网格中间显示的那句话，说明这里为什么没有东西；
     *                    留空则回到默认的「没有数据」
     */
    public void clear(String placeholder) {
        result = null;
        editBuffer = null;
        gutterAnchorRow = -1;
        table.getSelectionModel().clearSelection();
        table.setEditable(false);
        table.getColumns().clear();
        table.setItems(FXCollections.observableArrayList());
        table.setPlaceholder(placeholder == null || placeholder.isBlank()
                ? defaultPlaceholder() : UiUtils.label(placeholder, "hint"));
        readOnlyLabel.setText("");
        readOnlyBar.setVisible(false);
        readOnlyBar.setManaged(false);
        clearInspector();
    }

    private static Label defaultPlaceholder() {
        return UiUtils.label("没有数据", "hint");
    }

    /**
     * 重建表项列表。
     *
     * <p>表项是行下标：{@code [0, base)} 是数据库里的行，后面接着编辑缓冲里的新增行。
     * 新增或撤销一个新增行之后必须调这里，光 {@code refresh()} 不行——
     * 那只是重画已有的行，行数本身没变。
     */
    private void rebuildItems() {
        int base = result == null ? 0 : result.rows().size();
        int extra = editBuffer == null ? 0 : editBuffer.newRowCount();
        List<Integer> indices = new ArrayList<>(base + extra);
        for (int i = 0; i < base + extra; i++) {
            indices.add(i);
        }
        table.setItems(FXCollections.observableArrayList(indices));
    }

    /** 行首那一列的宽度。放得下一个记号，又不至于占掉数据的地方。 */
    private static final double GUTTER_WIDTH = 26;

    /** 行首格子的说明。做成一份共用的：每个格子各建一个，滚起来是白造对象。 */
    private static final Tooltip GUTTER_TIP = new Tooltip(
            "点这里选中整行" + System.lineSeparator()
                    + "Shift 点：从上次那一行连选到这一行" + System.lineSeparator()
                    + "Ctrl 点：把这一行加进选区或者去掉");

    /**
     * 行首那一列：点一下选中整行。
     *
     * <h2>为什么要有它</h2>
     * 网格是按<b>格子</b>选的——编辑、单元格面板、按格复制都要这个。但「我要的是这一行」
     * 同样是天天在做的事：删行、复制成 INSERT、标记删除，全都以行为单位。
     * 原来只能靠 Shift+空格，那是个没人猜得到的键；现在鼠标点行首就行，
     * 和各家数据库客户端一样。
     *
     * <p>这一列不属于结果集，所以刻意<b>不挂</b> userData：复制、粘贴、单元格面板
     * 都是靠「列上挂着的结果集下标」认列的，认不出来的一律跳过——
     * 于是那些地方一行都不用改，也不会把行首这一列当成数据列去取值。
     */
    private TableColumn<Integer, String> buildGutterColumn() {
        TableColumn<Integer, String> column = new TableColumn<>();
        column.setSortable(false);
        column.setReorderable(false);   // 拖到中间去就不叫「行首」了
        column.setResizable(false);
        column.setEditable(false);
        // 三个宽度都要设死：只设 pref 的话，UNCONSTRAINED_RESIZE_POLICY
        // 仍然会在拖别的列时把它算进去一起分宽度
        column.setPrefWidth(GUTTER_WIDTH);
        column.setMinWidth(GUTTER_WIDTH);
        column.setMaxWidth(GUTTER_WIDTH);
        // 值恒为空：这一列显示的是行的状态，不是行的数据
        column.setCellValueFactory(cell -> null);
        column.setCellFactory(c -> new GutterCell());
        return column;
    }

    /**
     * 行首点下去之后选谁。
     *
     * <p>三种点法和表格软件一致：直接点是「只要这一行」，Shift 点是「从上次那一行
     * 连选到这一行」，Ctrl 点是「把这一行加进来或者去掉」。
     *
     * <p>选的是<b>整行所有可见列</b>，行首那一列也在内——只有整行都选上，
     * 这一行看起来才是整条亮的，而不是数据区亮着、行首那一格还是白的。
     */
    private void selectRowFromGutter(int viewRow, boolean extend, boolean toggle) {
        List<TableColumn<Integer, ?>> columns = new ArrayList<>(table.getVisibleLeafColumns());
        if (columns.isEmpty() || viewRow < 0 || viewRow >= table.getItems().size()) {
            return;
        }
        var selection = table.getSelectionModel();
        if (extend && gutterAnchorRow >= 0 && gutterAnchorRow < table.getItems().size()) {
            selection.clearSelection();
            for (int r = Math.min(gutterAnchorRow, viewRow);
                    r <= Math.max(gutterAnchorRow, viewRow); r++) {
                selectWholeRow(r, columns);
            }
            // 锚点不动：连着按 Shift 点几次，每次都该从同一头量起
        } else if (toggle && isWholeRowSelected(viewRow, columns)) {
            for (TableColumn<Integer, ?> column : columns) {
                selection.clearSelection(viewRow, column);
            }
            gutterAnchorRow = viewRow;
        } else {
            if (!toggle) {
                selection.clearSelection();
            }
            selectWholeRow(viewRow, columns);
            gutterAnchorRow = viewRow;
        }
        // 焦点留在表格上，否则紧接着的 Ctrl+C、Delete 打给了别的控件
        table.requestFocus();
    }

    private void selectWholeRow(int viewRow, List<TableColumn<Integer, ?>> columns) {
        for (TableColumn<Integer, ?> column : columns) {
            table.getSelectionModel().select(viewRow, column);
        }
    }

    private boolean isWholeRowSelected(int viewRow, List<TableColumn<Integer, ?>> columns) {
        for (TableColumn<Integer, ?> column : columns) {
            if (!table.getSelectionModel().isSelected(viewRow, column)) {
                return false;
            }
        }
        return true;
    }

    /** 屏幕上最左边那个真正的数据列。只有行首那一列时返回 null。 */
    private TableColumn<Integer, ?> firstDataColumn() {
        for (TableColumn<Integer, ?> column : table.getVisibleLeafColumns()) {
            if (column.getUserData() instanceof Integer) {
                return column;
            }
        }
        return null;
    }

    /**
     * 行首那一格。平时是空的，新增行和待删除行上画一个记号。
     *
     * <p>记号不是装饰：整行底色确实也标了新增和待删除，但底色是浅的，
     * 一屏十几行扫过去容易漏；行首这一列窄，记号在里面反而显眼。
     */
    private class GutterCell extends javafx.scene.control.TableCell<Integer, String> {

        GutterCell() {
            getStyleClass().add("grid-gutter");
            setCursor(javafx.scene.Cursor.HAND);
            Tooltip.install(this, GUTTER_TIP);
            /*
             * 用事件过滤器拦在前面，而不是 setOnMousePressed：
             * TableCell 自己的鼠标处理会把选区改成「只选这一格」。等它跑完再补选整行，
             * 中间那一下清空会让选区闪一次；更要命的是 Shift 连选的锚点已经被它挪走了，
             * 于是「从上次那一行连到这一行」变成「从刚才点的这一格连到这一行」。
             */
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY
                        || isEmpty() || getIndex() < 0) {
                    return;
                }
                selectRowFromGutter(getIndex(), e.isShiftDown(), e.isShortcutDown());
                e.consume();
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("gutter-new", "gutter-deleted");
            setText(null);
            if (empty || getTableRow() == null || getTableRow().getItem() == null
                    || editBuffer == null) {
                return;
            }
            int rowIndex = getTableRow().getItem();
            if (editBuffer.isNewRow(rowIndex)) {
                setText("+");
                getStyleClass().add("gutter-new");
            } else if (editBuffer.isDeleted(rowIndex)) {
                // 减号而不是 ×：× 在不少字体里比一行还高，会把行撑开
                setText("-");
                getStyleClass().add("gutter-deleted");
            }
        }
    }

    private TableColumn<Integer, String> buildColumn(ColumnMeta meta, int columnIndex) {
        TableColumn<Integer, String> column = new TableColumn<>();
        // 用户可以拖动表头改变列序，之后「屏幕上的第几列」就不再等于「结果集里的第几列」。
        // 复制和取值都要按结果集的下标走，所以把它挂在列上，别再靠位置去猜
        column.setUserData(columnIndex);
        column.setGraphic(buildHeader(meta));
        column.setPrefWidth(preferredWidth(meta));
        column.setSortable(false); // 排序下推数据库执行，不在已加载的页内排

        column.setCellValueFactory(cell ->
                new SimpleStringProperty(editBuffer.displayValue(cell.getValue(), columnIndex)));

        column.setCellFactory(c -> new ValueCell(meta, columnIndex));

        column.setOnEditCommit(event -> {
            int rowIndex = event.getRowValue();
            editBuffer.set(rowIndex, columnIndex, event.getNewValue());
            table.refresh();
            onDirtyChanged.run();
        });
        return column;
    }

    /**
     * 表头：列名一行，类型另起一行。
     *
     * <p>原来两个挤在同一行。列一窄，HBox 就把两边一起压缩，
     * 结果是列名被截成「order\u2026」而类型还稳稳占着位置——最该看清的东西反而先没了。
     * 拆成两行之后列名独占整个列宽，类型再长也抢不走。
     */
    private VBox buildHeader(ColumnMeta meta) {
        Label name = new Label(meta.label());
        name.getStyleClass().add("grid-head-name");
        name.setMaxWidth(Double.MAX_VALUE);

        HBox top = UiUtils.row(4, name);
        if (meta.partOfKey()) {
            top.getChildren().add(0, Icons.key(Icons.ACCENT, 10));
        }
        HBox.setHgrow(name, Priority.ALWAYS);
        // 正在按这一列排的话，箭头画在名字后面
        if (meta.name().equals(sortColumn)) {
            top.getChildren().add(UiUtils.label(sortDescending ? "▼" : "▲", "grid-head-sort"));
        }

        Label type = new Label(meta.displayType());
        type.getStyleClass().add("grid-head-type");
        type.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(1, top, type);
        box.getStyleClass().add("grid-head");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);

        // 列窄到连类型都放不下时，完整信息还能从悬停里拿到
        StringBuilder tip = new StringBuilder(meta.label()).append('\n').append(meta.displayType());
        if (meta.partOfKey()) {
            tip.append("\n主键");
        }
        if (!meta.nullable()) {
            tip.append("\nNOT NULL");
        }
        if (onSortRequested != null) {
            tip.append(System.lineSeparator()).append("点这里按本列排序（升序 → 降序 → 不排）");
            box.setOnMouseClicked(e -> onSortRequested.accept(meta.name()));
            box.setCursor(javafx.scene.Cursor.HAND);
        }
        Tooltip.install(box, new Tooltip(tip.toString()));
        return box;
    }

    private double preferredWidth(ColumnMeta meta) {
        // 两行排布之后，列宽由较宽的那一行决定，不再是两者之和
        double head = Math.max(textWidth(meta.label(), 11, true),
                textWidth(meta.displayType(), 9.5, false))
                + (meta.partOfKey() ? 14 : 0) + 22;
        if (meta.category().isExact()) {
            // 精确数值可能很长，宽度按 precision 估，避免一上来就被截断
            int digits = Math.max(meta.precision(), 12);
            return Math.min(320, Math.max(head, digits * 8 + 26));
        }
        return Math.min(280, Math.max(96, head));
    }

    /**
     * 按实际字体量一段文字有多宽。
     *
     * <p>不按字符数乘系数：中文列名一个字顶两个字母还多，估出来的宽度差得离谱，
     * 「订单金额」这种四个字的列名会被算成半列宽。
     */
    private static double textWidth(String text, double fontSize, boolean bold) {
        Text probe = new Text(text);
        probe.setFont(bold ? Font.font("System", FontWeight.BOLD, fontSize) : Font.font(fontSize));
        return probe.getLayoutBounds().getWidth();
    }

    // ------------------------------------------------------------------ 单元格

    /** 悬停提示里最多展示多少字符。再长就不是悬停能解决的事了。 */
    private static final int TIP_LIMIT = 1200;

    /** 负责 NULL / 数值 / 脏值三种呈现。 */
    private class ValueCell extends TextFieldTableCell<Integer, String> {

        private final ColumnMeta meta;
        private final int columnIndex;
        /** 格子本来的内边距。编辑时要把它让给输入框，结束了再还回来。 */
        private Insets cellPadding;
        /** Esc 监听挂过了没有。输入框是复用的，挂重复了没必要。 */
        private boolean escapeHookInstalled;
        /** 悬停提示。每个格子一个，反复用——格子本身就是回收复用的。 */
        private Tooltip hoverTip;

        ValueCell(ColumnMeta meta, int columnIndex) {
            super(IDENTITY);
            this.meta = meta;
            this.columnIndex = columnIndex;
            if (meta.category().isNumeric()) {
                getStyleClass().add("cell-numeric");
            }
            /*
             * 悬停看完整值。
             *
             * 为什么放在 MOUSE_ENTERED 而不是 updateItem：
             * 到底要不要提示，取决于文字有没有被列宽截断——而这个判断要量一次文字宽度。
             * updateItem 在滚动时每秒要跑上千次，把测量放那里等于给滚动加一道背景开销；
             * 而鼠标一次只能停在一个格子上，放这里只量一次。
             *
             * 顺带还比 updateItem 准：拖完列宽不会重新 updateItem，
             * 放那里算出来的「截断与否」很快就过期了。
             */
            setOnMouseEntered(e -> refreshTooltip());
        }

        /**
         * 重算这个格子该不该带悬停提示，以及提示里写什么。
         *
         * <h2>不是每个格子都给</h2>
         * 文字本来就显示得下、又没改过的格子，提示框只是把旁边的格子盖住，
         * 重复一遍眼睛已经看到的东西。只有两种情况值得弹：
         * <ul>
         *   <li>值被列宽截断了——不弹的话只能去拉列宽；</li>
         *   <li>格子上有看不见的信息：改过的格子的原值、
         *       新增行里「未填」和「NULL」的区别。</li>
         * </ul>
         */
        private void refreshTooltip() {
            String tip = tooltipText();
            if (tip == null) {
                setTooltip(null);
                return;
            }
            if (hoverTip == null) {
                hoverTip = new Tooltip();
                hoverTip.setWrapText(true);
                // 一条长值不换行的话，提示框会横着铺出屏幕，右半截照样看不见——
                // 跟列太窄是同一个毛病
                hoverTip.setMaxWidth(520);
                hoverTip.getStyleClass().add("grid-cell-tip");
                /*
                 * 默认是「停一秒才弹、五秒就收」。这两个数都不适合看数据：
                 * 等一秒的话，扫一列长值要一格一格地停，比拉列宽还慢；
                 * 五秒又不够读完一段长文本，读到一半提示自己没了。
                 */
                hoverTip.setShowDelay(javafx.util.Duration.millis(300));
                hoverTip.setShowDuration(javafx.util.Duration.minutes(2));
            }
            hoverTip.setText(tip);
            setTooltip(hoverTip);
        }

        /** 提示正文；不必弹就返回 {@code null}。 */
        private String tooltipText() {
            if (isEditing() || getTableRow() == null || getTableRow().getItem() == null) {
                return null;
            }
            int rowIndex = getTableRow().getItem();

            // 新增行里没填过的格子。屏幕上就三个字，截不断，
            // 但它和 NULL 的区别正是这个网格里最容易被理解错的一件事
            if (editBuffer != null && editBuffer.isNewRow(rowIndex)
                    && !editBuffer.isSet(rowIndex, columnIndex)) {
                return meta.autoIncrement()
                        ? "未填 · 不写进 INSERT，主键由数据库发号"
                        : "未填 · 不写进 INSERT，由数据库套默认值"
                                + System.lineSeparator()
                                + "（想真写一个 NULL 进去，双击后清空内容）";
            }

            String value = getItem();
            boolean dirty = editBuffer != null && editBuffer.isDirty(rowIndex, columnIndex);
            String original = dirty ? editBuffer.originalValue(rowIndex, columnIndex) : null;

            StringBuilder sb = new StringBuilder();
            if (value == null) {
                if (!dirty) {
                    return null;   // 屏幕上就写着 (NULL)，再弹一遍是废话
                }
                sb.append("(NULL)");
            } else {
                if (!dirty && !isTruncated(value)) {
                    return null;
                }
                sb.append(clip(value));
            }
            if (dirty) {
                sb.append(System.lineSeparator()).append("────").append(System.lineSeparator())
                        .append("原值：").append(original == null ? "(NULL)" : clip(original));
            }
            return sb.toString();
        }

        /**
         * 这段文字在当前列宽下是不是显示不全。
         *
         * <p>字体从格子自己身上取（{@code getFont()} 拿到的是样式表算完的结果），
         * 不把字号写死在代码里——写死的话，改一行 CSS 这里就静静地算错了。
         */
        private boolean isTruncated(String text) {
            double available = getWidth() - getPadding().getLeft() - getPadding().getRight();
            if (available <= 0) {
                return false;
            }
            Text probe = new Text(text);
            probe.setFont(getFont());
            return probe.getLayoutBounds().getWidth() > available;
        }

        /**
         * 长值截一截。
         *
         * <p>一个十几万字的 CLOB 弹成提示框，既盖满屏幕也看不过来，还得先把它排版一遍。
         * 完整内容双击单元格或看下方检查区。
         *
         * <p>截的只是<b>显示</b>，存的值一个字符也不会动。
         */
        private String clip(String text) {
            if (text.length() <= TIP_LIMIT) {
                return text;
            }
            return text.substring(0, TIP_LIMIT) + System.lineSeparator()
                    + "…共 " + text.length() + " 个字符，双击单元格看完整值";
        }

        /**
         * 进入编辑。
         *
         * <p>让输入框严丝合缝地盖住原来那个格子——尺寸一致、文字落在同一个 x 上。
         * 默认行为是把输入框按自己的首选尺寸摆在格子中央：它比格子窄一圈、矮一截，
         * 文字还往右跳几个像素，双击之后眼睛得重新找一次那个值在哪。
         *
         * <p>做法是格子把自己的内边距让出来（否则输入框只能待在内边距以内，
         * 怎么也盖不满），改由输入框自己带同样的内边距，文字位置于是不变。
         * 宽高绑到格子上而不是取一次当时的值：编辑当中拖动列宽，输入框得跟着走。
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
            if (!escapeHookInstalled) {
                escapeHookInstalled = true;
                // 用事件过滤器而不是 setOnKeyPressed：TextFieldTableCell 自己
                // 已经挂了按键处理，setOnKeyPressed 会把它顶掉，回车就不提交了
                field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
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
            Insets current = getPadding();
            if (cellPadding == null && !Insets.EMPTY.equals(current)) {
                cellPadding = current;
            }
            /*
             * 用内联样式而不是 setPadding：这两个内边距都由样式表规定
             * （.table-cell 与 .text-field），而样式表会在下一次 CSS 生效时
             * 把 setPadding 设的值盖回去——量出来就是「代码明明跑了，尺寸没变」。
             * 内联样式的优先级在样式表之上，压得住。
             */
            setStyle("-fx-padding: 0;");
            field.setStyle("-fx-padding: " + cssInsets(cellPadding) + ";");
            /*
             * 宽度可以绑：列宽由列自己定，和内容无关。
             * 高度<b>不能绑</b>——行高是由格子里的内容算出来的，把框的首选高度
             * 绑回格子高度就成了循环：框要多高取决于格子多高，格子多高又取决于框多高。
             * 上一版这么写，行高每次进编辑都长 0.4px，下面的行跟着挪。
             * 取进编辑那一刻的高度就够了：编辑期间行高本来也不该变。
             */
            field.prefWidthProperty().bind(widthProperty());
            if (getHeight() > 0) {
                field.setMinHeight(0);
                field.setPrefHeight(getHeight());
            }
        }

        /** Insets 转成 CSS 的「上 右 下 左」写法。 */
        private static String cssInsets(Insets insets) {
            if (insets == null) {
                return "0";
            }
            return String.format("%.1f %.1f %.1f %.1f", insets.getTop(), insets.getRight(),
                    insets.getBottom(), insets.getLeft());
        }

        /**
         * 记下按过 Esc 没有。
         *
         * <h2>为什么要靠这个标志来区分</h2>
         * 「按 Esc 放弃」和「点到别处去」在 JavaFX 里走的是<b>同一个</b>方法
         * （{@link #cancelEdit()}），从方法本身分不出是哪一种。而这两件事
         * 用户的意图正好相反：前者是「不要了」，后者是「改好了」。
         */
        private boolean escapePressed;

        /**
         * 进入编辑那一刻的坐标。
         *
         * <p>取消编辑时要用它把提交事件发出去。那时候
         * {@code getTableView().getEditingCell()} 已经被清成 null 了，
         * 现拿是拿不到的。
         */
        private TablePosition<Integer, String> editingPosition;

        /**
         * 结束编辑。
         *
         * <h2>为什么提交要挂在「取消」上</h2>
         * 上一版把提交挂在输入框的焦点监听上，不生效——顺序是反的：
         * 点另一个单元格时，TableView <b>先</b>把编辑取消掉（{@code edit(-1, null)}），
         * <b>再</b>发生焦点转移。等焦点监听器跑起来，{@code isEditing()} 已经是 false，
         * 那个提交分支根本进不去。
         *
         * <p>所以真正的挂钩点在这里：只要不是 Esc 取消的，就把当前内容提交掉。
         * 这也是表格软件的通行行为——改完点向别处就是改好了。
         *
         * <p>发的是列上那个 {@code editCommit} 事件，走的和按回车完全同一条路，
         * 不另开一条写回逻辑：两条路一旦分叉，迟早有一条会漏掉脏值标记或刷新。
         */
        @Override
        public void cancelEdit() {
            String typed = getGraphic() instanceof TextField f ? f.getText() : null;
            boolean shouldCommit = !escapePressed
                    && typed != null
                    && !typed.equals(getItem())
                    && editingPosition != null
                    && getTableColumn() != null
                    && getTableView() != null;
            TablePosition<Integer, String> pos = editingPosition;

            super.cancelEdit();
            restorePadding();

            if (shouldCommit) {
                javafx.event.Event.fireEvent(getTableColumn(),
                        new TableColumn.CellEditEvent<>(getTableView(), pos,
                                TableColumn.editCommitEvent(), typed));
            }
        }

        private void restorePadding() {
            if (!getStyle().isEmpty()) {
                // 清掉内联样式，内边距交还给样式表
                setStyle("");
            }
            if (getGraphic() instanceof TextField field) {
                field.prefWidthProperty().unbind();
            }
        }

        @Override
        public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (!isEditing()) {
                // 提交之后走的是这里，不是 cancelEdit
                restorePadding();
            }
            getStyleClass().removeAll("cell-null", "cell-dirty", "cell-unset");
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setText(null);
                return;
            }
            int rowIndex = getTableRow().getItem();

            if (isEditing()) {
                return;
            }
            // 新增行里没填过的格子。显示的不是 (NULL)——这两件事在 INSERT 里不一样：
            // 没填的列压根不进列清单，由数据库套默认值或自增；填了 NULL 才是真写个 NULL 进去
            if (editBuffer != null && editBuffer.isNewRow(rowIndex)
                    && !editBuffer.isSet(rowIndex, columnIndex)) {
                setText(meta.autoIncrement() ? "(自增)" : "(默认)");
                getStyleClass().add("cell-unset");
                return;
            }
            if (item == null) {
                setText("(NULL)");
                getStyleClass().add("cell-null");
            } else {
                setText(item);
            }
            if (editBuffer != null && editBuffer.isDirty(rowIndex, columnIndex)) {
                getStyleClass().add("cell-dirty");
            }
        }
    }

    /**
     * 带标记的行：新增行、待删除行各有各的底色。
     *
     * <p>为什么整行标而不是逐格标：删除和新增是<b>行</b>上的事。逐格标的话，
     * 一行里没被选中的那些格子看着还是正常的，用户很难认出这一整行都要没了。
     */
    private class MarkedRow extends javafx.scene.control.TableRow<Integer> {

        @Override
        protected void updateItem(Integer rowIndex, boolean empty) {
            super.updateItem(rowIndex, empty);
            getStyleClass().removeAll("row-new", "row-deleted");
            if (empty || rowIndex == null || editBuffer == null) {
                return;
            }
            if (editBuffer.isNewRow(rowIndex)) {
                getStyleClass().add("row-new");
            } else if (editBuffer.isDeleted(rowIndex)) {
                getStyleClass().add("row-deleted");
            }
        }
    }

    /**
     * 恒等转换器。
     * <p>刻意不做任何解析或格式化——转换器是精度最容易悄悄流失的地方。
     */
    private static final StringConverter<String> IDENTITY = new StringConverter<>() {
        @Override
        public String toString(String s) {
            return s;
        }

        @Override
        public String fromString(String s) {
            return s;
        }
    };

    // ------------------------------------------------------------------ 单元格面板

    private VBox buildInspector() {
        HBox head = UiUtils.row(12,
                UiUtils.label("单元格", "section-label"),
                inspectorTitle, inspectorType, UiUtils.hSpacer(), inspectorBadge);
        head.getStyleClass().add("inspector-head");

        inspectorValue.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openValueViewer();
            }
        });
        inspectorValue.setTooltip(new Tooltip("双击查看完整值"));

        HBox valueRow = UiUtils.row(10, UiUtils.label("原始值", "hint"), inspectorValue);
        HBox.setHgrow(inspectorValue, Priority.ALWAYS);
        inspectorValue.setMaxWidth(Double.MAX_VALUE);

        HBox pathRow = UiUtils.row(10, UiUtils.label("读取路径", "hint"), inspectorPath);

        VBox body = UiUtils.column(6, valueRow, pathRow);
        body.getStyleClass().add("inspector-body");

        VBox box = UiUtils.column(0, head, body);
        box.getStyleClass().add("inspector");
        box.setMinHeight(96);
        box.setPrefHeight(96);
        return box;
    }

    private void clearInspector() {
        inspectorTitle.setText("");
        // 工具条上原来挂着一句「双击单元格编辑，值按原文保存」。那句话是讲单元格的，
        // 放在这里比放在工具条上更该在——用户碰到单元格时眼睛正好看向这一栏，
        // 而工具条上它只是占着两百多像素，把真正要按的按钮挤成了「...」
        // 「双击可编辑」得看这份结果到底能不能编辑：只读的结果上说这句话，
        // 用户会双击半天以为软件坏了。理由本来就摆在 readOnlyReason 里
        inspectorType.setText(result != null && !result.isEditable()
                ? "选中一个单元格查看原始值。这份结果是只读的"
                : "选中一个单元格查看原始值；双击可编辑，值按原文保存，不经浮点转换");
        inspectorBadge.setVisible(false);
        inspectorValue.setText("");
        inspectorPath.setText("");
    }

    private void updateInspector() {
        // 取第一个真正落在数据列上的格子。整行选中时选区里第一个是行首那一格，
        // 它不对应结果集里的任何一列；拖动过表头之后「屏幕上的第几列」也不等于
        // 「结果集里的第几列」，所以这里一律走 dataColumnOf
        TablePosition<?, ?> pos = firstDataCell();
        if (pos == null || result == null) {
            clearInspector();
            return;
        }
        int columnIndex = dataColumnOf(pos);
        Integer rowIndex = table.getItems().get(pos.getRow());

        ColumnMeta meta = result.columns().get(columnIndex);
        String value = editBuffer.displayValue(rowIndex, columnIndex);

        boolean newRow = editBuffer.isNewRow(rowIndex);
        inspectorTitle.setText((meta.tableName().isBlank() ? "" : meta.tableName() + ".") + meta.name());
        inspectorType.setText(meta.displayType()
                + (meta.nullable() ? " · NULL" : " · NOT NULL")
                + " · 第 " + (rowIndex + 1) + " 行"
                + (meta.partOfKey() ? " · 主键" : "")
                + (newRow ? " · 还没保存的新增行" : ""));

        inspectorBadge.setVisible(meta.riskyInDouble());
        inspectorBadge.setText("精确数值 · 全程按原文传递");

        // 新增行里没填过的格子显示的是「没填」，不是 (NULL)——网格里那一格写的是
        // 「(默认)」，这一栏要是写成 (NULL)，两处对同一个格子的说法就打架了
        if (newRow && !editBuffer.isSet(rowIndex, columnIndex)) {
            inspectorValue.setText(meta.autoIncrement()
                    ? "(没填 · 由数据库的自增序列给值)"
                    : "(没填 · 这一列不会进 INSERT 的列清单)");
            inspectorPath.setText("未填的列不写进 INSERT，交给数据库套默认值");
            return;
        }
        inspectorValue.setText(value == null ? "(NULL)" : value);
        inspectorPath.setText(describeReadPath(meta, value));
    }

    /** 把这一列真实走过的读取路径写出来，让精度这件事在界面上可验证。 */
    private String describeReadPath(ColumnMeta meta, String value) {
        if (meta.category() == TypeCategory.EXACT_NUMERIC || meta.category() == TypeCategory.INTEGER) {
            String shape = "";
            if (value != null) {
                try {
                    BigDecimal d = new BigDecimal(value);
                    shape = "(precision=" + d.precision() + ", scale=" + d.scale() + ")";
                } catch (NumberFormatException ignored) {
                    // 值不是合法数字时不显示形状即可
                }
            }
            return "ResultSet.getBigDecimal() → BigDecimal" + shape + " → toPlainString()";
        }
        if (meta.category() == TypeCategory.BINARY) {
            return "ResultSet.getBytes() → 摘要显示，完整内容按需读取";
        }
        return "ResultSet.getString()";
    }

    // ------------------------------------------------------------------ 右键菜单

    /**
     * 右键菜单。
     *
     * <p>菜单项的可用状态在 {@code setOnShowing} 里现算：选中了什么、结果集能不能改，
     * 每次弹出时都可能不一样。建菜单时算一次然后一直用，很快就会出现
     * 「明明只读却能点『置为 NULL』」这种事。
     */
    private ContextMenu buildContextMenu() {
        MenuItem view = new MenuItem("查看完整值…");
        view.setOnAction(e -> openValueViewer());

        MenuItem copy = new MenuItem("复制");
        copy.setAccelerator(COPY);
        copy.setOnAction(e -> copyAs(ClipboardFormats.Format.TSV, false));

        MenuItem copyWithHeader = new MenuItem("复制（含列名）");
        copyWithHeader.setOnAction(e -> copyAs(ClipboardFormats.Format.TSV, true));

        Menu copyAsMenu = new Menu("复制为");
        for (ClipboardFormats.Format f : ClipboardFormats.Format.values()) {
            if (f == ClipboardFormats.Format.TSV) {
                continue; // 上面两项已经是它了
            }
            MenuItem item = new MenuItem(f.label() + "  ·  " + f.note());
            item.setOnAction(e -> copyAs(f, true));
            copyAsMenu.getItems().add(item);
        }

        MenuItem copyNames = new MenuItem("复制列名");
        copyNames.setOnAction(e -> copyColumnNames());

        MenuItem paste = new MenuItem("粘贴    Ctrl+V");
        paste.setOnAction(e -> pasteFromClipboard());

        MenuItem selectRows = new MenuItem("选中整行    Shift+空格");
        selectRows.setOnAction(e -> selectWholeRows());

        MenuItem copyInsert = new MenuItem("复制整行为 INSERT 语句");
        copyInsert.setOnAction(e -> copyRowsAsInsert());

        MenuItem setNull = new MenuItem("置为 NULL");
        setNull.setOnAction(e -> fill(null));

        MenuItem setEmpty = new MenuItem("置为空字符串");
        setEmpty.setOnAction(e -> fill(""));

        MenuItem addRow = new MenuItem("在末尾新增一行");
        addRow.setOnAction(e -> addRow());

        MenuItem deleteRows = new MenuItem("删除选中的行");
        deleteRows.setOnAction(e -> deleteSelectedRows());

        MenuItem resetCell = new MenuItem("退回默认值（只对新增行）");
        resetCell.setOnAction(e -> unsetSelected());

        ContextMenu menu = new ContextMenu(view, new SeparatorMenuItem(),
                copy, copyWithHeader, copyAsMenu, copyNames, paste,
                new SeparatorMenuItem(), selectRows, copyInsert,
                new SeparatorMenuItem(), addRow, deleteRows,
                new SeparatorMenuItem(), setNull, setEmpty, resetCell);

        menu.setOnShowing(e -> {
            boolean hasCells = !selectedRows().isEmpty() && !selectedDataColumns().isEmpty();
            boolean canEdit = result != null && result.isEditable();
            view.setDisable(anchorCell() == null);
            copy.setDisable(!hasCells);
            copyWithHeader.setDisable(!hasCells);
            copyAsMenu.setDisable(!hasCells);
            copyNames.setDisable(!hasCells);
            paste.setDisable(!hasCells || !canEdit
                    || !Clipboard.getSystemClipboard().hasString());
            // 这两项只要碰到了行就成立：整行 INSERT 用的是全部列，
            // 不看用户此刻圈中的是哪几格
            boolean hasRows = !selectedRows().isEmpty();
            selectRows.setDisable(!hasRows);
            copyInsert.setDisable(!hasRows);
            setNull.setDisable(!hasCells || !canEdit);
            setEmpty.setDisable(!hasCells || !canEdit);
            addRow.setDisable(!canEdit);
            deleteRows.setDisable(!hasRows || !canEdit);
            // 「退回默认值」只在选区里真有新增行的格子时才亮：
            // 对数据库里已有的行，这一项什么也做不了
            resetCell.setDisable(!hasCells || !canEdit || !selectionTouchesNewRow());
        });
        return menu;
    }

    private boolean selectionTouchesNewRow() {
        if (editBuffer == null) {
            return false;
        }
        for (int r : selectedRows()) {
            if (editBuffer.isNewRow(r)) {
                return true;
            }
        }
        return false;
    }

    /** 把选中的格子退回「没填」。只对新增行有意义。 */
    private void unsetSelected() {
        if (result == null || !result.isEditable()) {
            return;
        }
        int touched = 0;
        for (int r : selectedRows()) {
            if (!editBuffer.isNewRow(r)) {
                continue;
            }
            for (int c : selectedDataColumns()) {
                editBuffer.unset(r, c);
                touched++;
            }
        }
        table.refresh();
        onDirtyChanged.run();
        onStatus.accept("已把 " + touched + " 个格子退回默认值（这些列不会进 INSERT 的列清单）");
    }

    // ------------------------------------------------------------------ 选中区域

    /**
     * 选中的结果集列下标，按屏幕上从左到右的顺序。
     *
     * <p>取的是列上挂着的下标而不是它此刻排第几：用户拖过表头之后两者就不一样了，
     * 按位置取会复制出错位的一列。
     */
    private List<Integer> selectedDataColumns() {
        List<Integer> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        TreeSet<Integer> viewColumns = new TreeSet<>();
        for (TablePosition<?, ?> pos : table.getSelectionModel().getSelectedCells()) {
            if (pos.getColumn() >= 0) {
                viewColumns.add(pos.getColumn());
            }
        }
        for (int v : viewColumns) {
            if (v >= table.getVisibleLeafColumns().size()) {
                continue;
            }
            Object marker = table.getVisibleLeafColumn(v).getUserData();
            if (marker instanceof Integer index && index < result.columns().size()) {
                out.add(index);
            }
        }
        return out;
    }

    /**
     * 选中的行在结果集里的下标，按屏幕上从上到下的顺序。
     *
     * <p>对外开放是给键值库用的：「删除键」「改值」要知道用户点中了哪几行，
     * 而那几个操作不走网格自己的编辑缓冲区。
     */
    public List<Integer> selectedRowIndexes() {
        return selectedRows();
    }

    /** 选中的行在<b>屏幕上</b>的行号。扩选整行要用它——选区认的是视图坐标。 */
    private List<Integer> selectedViewRows() {
        TreeSet<Integer> rows = new TreeSet<>();
        for (TablePosition<?, ?> pos : table.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() >= 0 && pos.getRow() < table.getItems().size()) {
                rows.add(pos.getRow());
            }
        }
        return new ArrayList<>(rows);
    }

    private List<Integer> selectedRows() {
        List<Integer> out = new ArrayList<>();
        TreeSet<Integer> viewRows = new TreeSet<>();
        for (TablePosition<?, ?> pos : table.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() >= 0) {
                viewRows.add(pos.getRow());
            }
        }
        for (int v : viewRows) {
            if (v < table.getItems().size()) {
                out.add(table.getItems().get(v));
            }
        }
        return out;
    }

    /** 焦点所在的那一格：结果集行下标与列下标。没有选中时返回 null。 */
    private int[] anchorCell() {
        TablePosition<?, ?> pos = firstDataCell();
        if (pos == null) {
            return null;
        }
        return new int[]{table.getItems().get(pos.getRow()), dataColumnOf(pos)};
    }

    /**
     * 选区里第一个真正落在数据列上的格子；一个都没有时返回 null。
     *
     * <p>整行选中时选区里排头的是行首那一格，它不属于结果集。直接拿
     * {@code getSelectedCells().get(0)} 的地方都要改走这里，否则「选中一整行之后
     * 粘贴」会报「先选中要粘贴到的那一格」，而用户明明选着东西。
     */
    private TablePosition<?, ?> firstDataCell() {
        for (TablePosition<?, ?> pos : table.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() >= 0 && pos.getRow() < table.getItems().size()
                    && dataColumnOf(pos) >= 0) {
                return pos;
            }
        }
        return null;
    }

    /**
     * 一个选中格对应结果集里的第几列；行首那一列和任何非数据列返回 -1。
     *
     * <p>取的是列上挂着的下标而不是它此刻排第几——行首多了一列、用户又能拖动表头，
     * 「屏幕上的第几列」和「结果集里的第几列」早就不是一回事了。
     */
    private int dataColumnOf(TablePosition<?, ?> pos) {
        if (result == null || pos.getColumn() < 0
                || pos.getColumn() >= table.getVisibleLeafColumns().size()) {
            return -1;
        }
        Object marker = table.getVisibleLeafColumn(pos.getColumn()).getUserData();
        if (marker instanceof Integer index && index >= 0 && index < result.columns().size()) {
            return index;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 复制

    /**
     * 把选中的矩形区域复制走。
     *
     * <p>复制的是<b>屏幕上此刻显示的值</b>，也就是含未保存修改的那一份。
     * 改了三个格子还没按保存就去复制，拿到的应当是改后的样子——
     * 眼睛看到什么就复制什么，这条比「复制数据库里的原值」更符合直觉。
     */
    /**
     * 从剪贴板粘贴。
     *
     * <p>以选中的那一格为左上角，按制表符分隔铺开——这是从 Excel、从本工具自己
     * 「复制」出来的默认格式，两头对得上。
     *
     * <p>只写进<b>编辑缓冲</b>，不直接落库：粘错了还能撤（丢弃改动），
     * 落库仍然要按「保存」。这和逐格编辑是同一条路，用户不必学两套规则。
     *
     * <p>超出当前页的部分直接丢掉并说明。悄悄少粘几行，用户是发现不了的。
     */
    private void pasteFromClipboard() {
        String text = Clipboard.getSystemClipboard().getString();
        if (result == null || text == null || text.isEmpty()) {
            return;
        }
        if (!result.isEditable()) {
            onStatus.accept("只读：" + result.readOnlyReason());
            return;
        }
        int[] anchor = anchorCell();
        if (anchor == null) {
            onStatus.accept("先选中要粘贴到的那一格");
            return;
        }

        String[] lines = text.replace(CR, "").split(LF, -1);
        int rows = 0;
        int cells = 0;
        int skippedRows = 0;
        int skippedCells = 0;
        for (int r = 0; r < lines.length; r++) {
            if (r == lines.length - 1 && lines[r].isEmpty()) {
                break;   // 复制整行时末尾常带一个换行，那不是一行数据
            }
            int rowIndex = anchor[0] + r;
            // 上限是网格里的<b>总</b>行数，含还没保存的新增行——先加几个空行再整片粘进去，
            // 是从别处搬一批数据过来最顺手的做法
            if (rowIndex >= table.getItems().size()) {
                skippedRows++;
                continue;
            }
            String[] parts = lines[r].split(TAB, -1);
            for (int c = 0; c < parts.length; c++) {
                int columnIndex = anchor[1] + c;
                if (columnIndex >= result.columns().size()) {
                    skippedCells++;
                    continue;
                }
                editBuffer.set(rowIndex, columnIndex, parts[c]);
                cells++;
            }
            rows++;
        }

        table.refresh();
        onDirtyChanged.run();
        StringBuilder note = new StringBuilder("已粘贴 " + rows + " 行 · " + cells + " 格");
        if (skippedRows > 0) {
            note.append("，超出当前页的 ").append(skippedRows).append(" 行没粘");
        }
        if (skippedCells > 0) {
            note.append("，超出最后一列的 ").append(skippedCells).append(" 格没粘");
        }
        note.append("（还没写库，按保存才生效）");
        onStatus.accept(note.toString());
    }

    /**
     * 把选区扩成整行。
     *
     * <p>网格是按格子选的——这是编辑和单元格面板要的。但「复制这几行」是另一件常做的事，
     * 圈中一格再点这里，整行就都进选区了，之后所有复制动作都按整行走。
     */
    private void selectWholeRows() {
        List<Integer> viewRows = selectedViewRows();
        if (viewRows.isEmpty()) {
            return;
        }
        List<TableColumn<Integer, ?>> columns = new ArrayList<>(table.getVisibleLeafColumns());
        // 先把行号取出来再清选区：清完了就问不出刚才选的是哪几行了
        table.getSelectionModel().clearSelection();
        for (int viewRow : viewRows) {
            selectWholeRow(viewRow, columns);
        }
    }

    /**
     * 把选中的行复制成 INSERT 语句。
     *
     * <p>和「复制为 · INSERT 语句」的区别在列：那个只取圈中的几列，这个一律取<b>全部</b>列。
     * 少了列的 INSERT 插进去就是一行残缺数据——而且缺的往往是没圈到的那几个非空字段，
     * 到执行时才会以约束报错的形式暴露出来。
     */
    private void copyRowsAsInsert() {
        List<Integer> rows = selectedRows();
        if (result == null || rows.isEmpty()) {
            onStatus.accept("先选中要复制的行");
            return;
        }
        List<ColumnMeta> metas = result.columns();
        List<String[]> data = new ArrayList<>(rows.size());
        for (int r : rows) {
            String[] values = new String[metas.size()];
            for (int c = 0; c < metas.size(); c++) {
                values[c] = editBuffer.displayValue(r, c);
            }
            data.add(values);
        }

        String text = ClipboardFormats.render(ClipboardFormats.Format.SQL_INSERT,
                metas, data, true, tableName());
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        onStatus.accept("已复制 " + rows.size() + " 行 · INSERT 语句（" + metas.size() + " 列）");
    }

    private void copyAs(ClipboardFormats.Format format, boolean header) {
        List<Integer> rows = selectedRows();
        List<Integer> columns = selectedDataColumns();
        if (rows.isEmpty() || columns.isEmpty()) {
            onStatus.accept("先选中要复制的格子");
            return;
        }
        List<ColumnMeta> metas = new ArrayList<>(columns.size());
        for (int c : columns) {
            metas.add(result.columns().get(c));
        }
        List<String[]> data = new ArrayList<>(rows.size());
        for (int r : rows) {
            String[] values = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = editBuffer.displayValue(r, columns.get(i));
            }
            data.add(values);
        }

        String text = ClipboardFormats.render(format, metas, data, header, tableName());
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);

        onStatus.accept("已复制 " + rows.size() + " 行 × " + columns.size() + " 列 · "
                + format.label());
    }

    private void copyColumnNames() {
        List<Integer> columns = selectedDataColumns();
        if (columns.isEmpty()) {
            onStatus.accept("先选中要复制的列");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(result.columns().get(columns.get(i)).name());
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        onStatus.accept("已复制 " + columns.size() + " 个列名");
    }

    /** INSERT 语句用的表名。结果集来自多张表时这里为空，交给渲染方写个占位。 */
    /** 表页打开时告诉网格它是哪张表——复制成 INSERT 时要用。 */
    public void setTableNameHint(String name) {
        this.tableNameHint = name;
    }

    private String tableName() {
        if (tableNameHint != null && !tableNameHint.isBlank()) {
            return tableNameHint;
        }
        if (result == null || result.columns().isEmpty()) {
            return null;
        }
        return result.columns().get(0).tableName();
    }

    // ------------------------------------------------------------------ 批量置值

    /**
     * 把选中的格子统一设成某个值。
     *
     * <p>只动编辑缓冲，和一个个双击改是同一条路径——仍然要按保存才落库。
     * 「点一下就把一片格子写进数据库」这种事，不该在右键菜单里发生。
     */
    private void fill(String value) {
        if (result == null || !result.isEditable()) {
            onStatus.accept("只读：" + (result == null ? "没有结果" : result.readOnlyReason()));
            return;
        }
        List<Integer> rows = selectedRows();
        List<Integer> columns = selectedDataColumns();
        int touched = 0;
        List<String> refused = new ArrayList<>();
        for (int c : columns) {
            ColumnMeta meta = result.columns().get(c);
            if (value == null && !meta.nullable()) {
                // NOT NULL 的列在这里就拦下来，别等数据库退回一条看不懂的报错
                refused.add(meta.label());
                continue;
            }
            for (int r : rows) {
                editBuffer.set(r, c, value);
                touched++;
            }
        }
        table.refresh();
        onDirtyChanged.run();

        String what = value == null ? "NULL" : (value.isEmpty() ? "空字符串" : value);
        StringBuilder msg = new StringBuilder("已把 ").append(touched)
                .append(" 个格子设为 ").append(what).append("（还没保存）");
        if (!refused.isEmpty()) {
            msg.append(" · 跳过 NOT NULL 的列：").append(String.join("、", refused));
        }
        onStatus.accept(msg.toString());
    }

    // ------------------------------------------------------------------ 增行 / 删行

    /**
     * 丢弃全部未保存的改动。
     *
     * <p>不能只 {@code revertAll()} 加 {@code refresh()}：撤掉的新增行让行数变了，
     * 表项列表必须重建，否则网格上会留下几行取不到值的空行。
     */
    public void revertEdits() {
        if (editBuffer == null) {
            return;
        }
        editBuffer.revertAll();
        rebuildItems();
        table.refresh();
    }

    /**
     * 在末尾加一个空行。
     *
     * <p>只加进编辑缓冲，按「保存」才发 INSERT——和逐格改值是同一条路。
     * 加完把光标放到第一格：新增行摆在那里不填是没有意义的，
     * 而用户加完一行第一件事必然是开始填。
     */
    public void addRow() {
        if (result == null || !result.isEditable()) {
            onStatus.accept("只读：" + (result == null ? "没有结果" : result.readOnlyReason()));
            return;
        }
        int rowIndex = editBuffer.addRow();
        rebuildItems();
        table.scrollTo(rowIndex);
        // 第一个<b>数据</b>列，不是屏幕上的第一列——那是行首，改不了也没什么好填的
        TableColumn<Integer, ?> first = firstDataColumn();
        if (first != null) {
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(rowIndex, first);
            table.edit(rowIndex, first);
        }
        onDirtyChanged.run();
        onStatus.accept("已加一个新行（还没写库，按保存才生效）"
                + " · 没填的列按数据库的默认值和自增来");
    }

    /**
     * 把选中的行标记 / 取消标记为删除。
     *
     * <p>为什么是标记而不是当场删：这个项目里所有写库动作都收在「保存」那一下。
     * 让删除成为唯一一个「点了就没」的例外，等于在最危险的那个操作上
     * 取消了后悔的机会。已经标了的再点一次就取消标记。
     *
     * <p>新增行没有「标记删除」这回事——它还不在库里，直接撤掉。
     */
    public void deleteSelectedRows() {
        if (result == null || !result.isEditable()) {
            onStatus.accept("只读：" + (result == null ? "没有结果" : result.readOnlyReason()));
            return;
        }
        List<Integer> rows = selectedRows();
        if (rows.isEmpty()) {
            onStatus.accept("先选中要删的行");
            return;
        }

        // 撤掉的新增行会让它后面的行下标整体前移，所以从大到小撤，
        // 否则撤完第一个，后面收集到的下标就全错位了
        List<Integer> newOnes = new ArrayList<>();
        int marked = 0;
        int unmarked = 0;
        for (int r : rows) {
            if (editBuffer.isNewRow(r)) {
                newOnes.add(r);
            } else if (editBuffer.isDeleted(r)) {
                editBuffer.setDeleted(r, false);
                unmarked++;
            } else {
                editBuffer.setDeleted(r, true);
                marked++;
            }
        }
        newOnes.sort(java.util.Comparator.reverseOrder());
        for (int r : newOnes) {
            editBuffer.removeNewRow(r);
        }

        if (!newOnes.isEmpty()) {
            rebuildItems();
        }
        table.refresh();
        onDirtyChanged.run();

        List<String> notes = new ArrayList<>();
        if (marked > 0) {
            notes.add("标记删除 " + marked + " 行（按保存才真的删）");
        }
        if (unmarked > 0) {
            notes.add("取消删除标记 " + unmarked + " 行");
        }
        if (!newOnes.isEmpty()) {
            notes.add("撤掉 " + newOnes.size() + " 个还没保存的新增行");
        }
        onStatus.accept(String.join(" · ", notes));
    }

    // ------------------------------------------------------------------ 值查看器

    private void openValueViewer() {
        int[] cell = anchorCell();
        if (cell == null) {
            onStatus.accept("先选中一个格子");
            return;
        }
        int rowIndex = cell[0];
        int columnIndex = cell[1];
        ColumnMeta meta = result.columns().get(columnIndex);

        ValueViewerDialog dialog = new ValueViewerDialog(meta,
                editBuffer.displayValue(rowIndex, columnIndex),
                rowIndex + 1, result.isEditable());
        dialog.setOnApply(newValue -> {
            editBuffer.set(rowIndex, columnIndex, newValue);
            table.refresh();
            updateInspector();
            onDirtyChanged.run();
            onStatus.accept("已改 " + meta.label() + "（还没保存）");
        });
        dialog.show(getScene() == null ? null : getScene().getWindow());
    }

    public void notifyStatus(String message) {
        onStatus.accept(message);
    }
}
