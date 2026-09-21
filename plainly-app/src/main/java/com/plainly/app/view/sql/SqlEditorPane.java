package com.plainly.app.view.sql;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.sql.SqlRisk;
import com.plainly.core.sql.SqlScopes;
import com.plainly.app.view.DataGridPane;
import com.plainly.app.view.ExportDialog;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlScript;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.wellbehaved.event.EventPattern;
import org.fxmisc.wellbehaved.event.InputMap;
import org.fxmisc.wellbehaved.event.Nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** SQL 编辑器：着色、补全、执行、结果集。 */
public class SqlEditorPane extends BorderPane {

    /** 候选的种类，决定图标与排序。 */
    public enum Kind {
        COLUMN, TABLE, KEYWORD, FUNCTION, SNIPPET
    }

    /**
     * 补全候选。
     *
     * @param insertText 真正插进编辑器的文本。多数候选就是 {@code name} 本身，
     *                   唯独代码片段不是——列表里显示的是「分页查询」四个字，
     *                   插进去的是整段 SQL。两者混用的话，选中片段只会插进去一个名字
     */
    public record Suggestion(String name, String detail, Kind kind, String insertText) {

        public Suggestion(String name, String detail, Kind kind) {
            this(name, detail, kind, name);
        }
    }

    private final AppContext context;
    private final DbSession session;

    /**
     * 当前目标库。可变——同一条连接上可以开多个查询页，各自打不同的库；
     * 执行前会把连接切到它，否则不带库名限定的 SQL 会跑到别的库上去。
     */
    private String schema;
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private Consumer<String> onSchemaChanged = s -> { };

    private final CodeArea editor = new CodeArea();
    private final DataGridPane grid = new DataGridPane();
    private final ComboBox<Integer> rowLimit = new ComboBox<>();
    private final Label resultLabel = UiUtils.label("", "hint");
    private final Label messageLabel = UiUtils.label("", "hint");
    /** 自动换行的开关记在这里，下次打开还是上次那个选择。 */
    private static final String WRAP_KEY = "sql.wrapText";

    private final Button runButton;
    private final Button stopButton;
    private final Button exportButton;

    /**
     * 结果集可写回时才出现的那几个按钮。
     *
     * <p>不可写回时整组收起来而不是置灰：置灰只说明「现在不能按」，
     * 收起来配合网格上方那条只读说明，才说得清「为什么不能」。
     */
    private final Button addRowButton = UiUtils.toolButton("新增行", null);
    private final Button deleteRowButton = UiUtils.toolButton("删除行", null);
    private final Button saveEditsButton = UiUtils.toolButton("保存", null, "accent");
    private final Button discardEditsButton = UiUtils.toolButton("放弃", null);

    private final Popup completionPopup = new Popup();
    private final ListView<Suggestion> completionList = new ListView<>();
    private final Label completionHead = UiUtils.label("");

    private Consumer<String> statusSink = s -> { };
    private volatile boolean running;

    /** 执行失败时盖住结果区的那块面板，以及里面那段可复制的报错正文。 */
    private javafx.scene.layout.VBox errorPanel;
    private final javafx.scene.control.TextArea errorText = new javafx.scene.control.TextArea();

    /** 事务控制条。手动事务是这个编辑器最常用它的地方——在这里跑 UPDATE，在这里决定留不留。 */
    private com.plainly.app.view.TransactionBar txBar;

    /**
     * 代码片段的一份内存副本。
     *
     * <p>补全在<b>每一次按键</b>上都会跑一遍。片段存在本机的 SQLite 里，
     * 每敲一个字符查一次库，就是每敲一个字符一次文件 IO——本机库很快，
     * 但这条路上不该有任何 IO。片段只在打开编辑器和管理窗口关闭时重读。
     */
    private List<com.plainly.core.store.SnippetStore.Snippet> snippetCache = List.of();

    public SqlEditorPane(AppContext context, DbSession session, String schema) {
        this.context = context;
        this.session = session;
        this.schema = schema;

        runButton = UiUtils.toolButton("执行  F5", Icons.play("#ffffff", 11), "primary");
        stopButton = UiUtils.toolButton("停止", Icons.stop(Icons.FAINT, 11));
        exportButton = UiUtils.toolButton("导出结果", Icons.export(Icons.NEUTRAL, 11));
        stopButton.setDisable(true);
        exportButton.setDisable(true);

        runButton.setOnAction(e -> execute(false));
        stopButton.setOnAction(e -> cancel());
        exportButton.setOnAction(e -> openExport());

        setupEditor();
        setupCompletion();

        SplitPane split = new SplitPane(new VirtualizedScrollPane<>(editor), buildResultArea());
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.42);

        setTop(buildToolbar());
        setCenter(split);

        // 补全只读缓存（见 warmMetadata），所以开页的时候就把它捂上
        warmMetadata();
        reloadSnippets();
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink;
    }

    /** 目标库改变时通知外部（主窗口据此改标签页标题）。 */
    public void setOnSchemaChanged(Consumer<String> handler) {
        this.onSchemaChanged = handler;
    }

    public String schema() {
        return schema;
    }

    public CodeArea editor() {
        return editor;
    }

    /**
     * 预置一段 SQL（查询构建器、收藏、启动时恢复都走这里）。光标落在末尾，接着改就行。
     *
     * <p>着色必须在这里显式做一次，不能指望 {@code textProperty} 的监听器：
     * 新文本和编辑器里已有的<b>一模一样</b>时那个监听器不触发，而 {@code replaceText}
     * 已经把样式清掉了——于是恢复回来的标签页一片纯黑。恢复正好最容易撞上这种情况：
     * 存进去的那份，本来就是从这个编辑器里取出来的。
     */
    public void setSql(String sql) {
        editor.replaceText(sql);
        highlightNow();
        editor.moveTo(editor.getLength());
    }

    /** 按当前文本重算一遍着色。空文本跳过：0 长度的样式跨度没有意义。 */
    /**
     * 按当前文本重新着色。
     *
     * <h2>为什么程序改文本之后必须显式调它</h2>
     * 着色平时挂在 {@code textProperty} 的监听器上，用户敲字够用。
     * 但<b>整段替换</b>时不够：JavaFX 的属性不会为一个和旧值相等的新值发通知，
     * 而 {@code replaceText} 已经把样式段清空了。
     *
     * <p>「对一段已经排好版的 SQL 再点一次格式化」正好命中这个组合——
     * 前后文本完全一样，高亮却整片消失，而且要再敲一个字符才回来。
     */
    private void highlightNow() {
        if (editor.getLength() > 0) {
            editor.setStyleSpans(0, SqlHighlighter.computeHighlighting(editor.getText()));
        }
    }

    // ------------------------------------------------------------------ 编辑器

    private void setupEditor() {
        editor.getStyleClass().add("code-area");
        // 不直接用 LineNumberFactory：它在代码里设了字体，而那个族名在 Windows 上
        // 不存在，会安静地退回比例字体，把个位数行号的那几行整体推歪。理由见 LineNumbers
        editor.setParagraphGraphicFactory(LineNumbers.factory(editor));
        // 默认留空。原来预置的那两行示例每次新建都得先全选删掉，
        // 而且会让一个刚开的空白页被算成「有未保存内容」
        editor.textProperty().addListener((obs, old, text) -> highlightNow());

        // 按钮文案跟着选区走。圈了一段之后 F5 只跑那一段，
        // 这件事得在按下去之前就看得见，而不是跑完才发现范围不对
        editor.selectedTextProperty().addListener((obs, old, sel) ->
                runButton.setText(sel != null && !sel.isBlank() ? "执行选中  F5" : "执行  F5"));

        installKeyBindings();

        // 敲了字母、数字或点号之后刷新候选。
        // 用 KEY_TYPED 而不是 KEY_PRESSED：只有 KEY_TYPED 的 getCharacter() 一定有值。
        editor.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty()) {
                return;
            }
            char c = ch.charAt(0);
            if (isIdentifierChar(c)) {
                Platform.runLater(this::showCompletion);
                return;
            }
            /*
             * 敲了分隔符（空格、逗号、括号、运算符……）：这一轮补全就结束了，收起来。
             *
             * 不收的话，弹窗会一直挂在那儿挡着下面的代码。而且比「没反应」更糟——
             * 空格之后前缀变成空串，候选里于是匹配上了<b>所有</b>表名，
             * 列表不但不消失，还变得更长了。
             *
             * 想重新唤起就按 Ctrl+Space，那也是弹窗页脚里写着的那句话。
             */
            completionPopup.hide();
        });

        editor.caretPositionProperty().addListener((obs, old, pos) -> {
            if (completionPopup.isShowing()) {
                Platform.runLater(this::refreshCompletion);
            }
        });
    }

    /**
     * 按键绑定。
     *
     * <p>用 {@code Nodes.addInputMap} 而不是 {@code addEventFilter}：
     * RichTextFX 在构造时就用同一套机制注册了自己的键位（ENTER 换行、TAB 缩进），
     * 后加的 InputMap 优先级更高，正是为这种覆盖场景设计的。
     *
     * <p>{@code consumeWhen} 让这几个键<b>只在补全弹窗打开时</b>被拦截；
     * 弹窗关着的时候原有的换行与缩进行为完全不受影响。
     *
     * <h2>排查记录：光换事件机制是治不好的</h2>
     * 「弹窗开着按 Tab / Enter 没反应」的根因<b>不在</b>事件机制，而在焦点：
     * {@code Popup} 里的 ListView 默认可获得焦点，弹窗一显示就把焦点从编辑器抢走，
     * 按键随即被派发到 Popup 自己的场景里——编辑器上挂什么都收不到。
     *
     * <p>当时先后换成 filter、又换成 InputMap，都没用，因为方向就错了。
     * 真正的修复是 {@code setFocusTraversable(false)}，见 {@link #setupCompletion()}。
     * 判定方法：{@code tools/CompletionProbe.java} 在弹窗开与不开两种状态下各发一次 TAB，
     * 前者零派发、后者正常，一次就把范围从「事件机制」缩到了「焦点」。
     */
    private void installKeyBindings() {
        Nodes.addInputMap(editor, InputMap.sequence(
                InputMap.consumeWhen(EventPattern.keyPressed(KeyCode.ENTER),
                        this::completionShowing, e -> applySelectedSuggestion()),
                InputMap.consumeWhen(EventPattern.keyPressed(KeyCode.TAB),
                        this::completionShowing, e -> applySelectedSuggestion()),
                InputMap.consumeWhen(EventPattern.keyPressed(KeyCode.DOWN),
                        this::completionShowing, e -> moveSelection(1)),
                InputMap.consumeWhen(EventPattern.keyPressed(KeyCode.UP),
                        this::completionShowing, e -> moveSelection(-1)),
                InputMap.consumeWhen(EventPattern.keyPressed(KeyCode.ESCAPE),
                        this::completionShowing, e -> completionPopup.hide()),

                InputMap.consume(EventPattern.keyPressed(KeyCode.F5), e -> execute(false)),
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.ENTER, KeyCombination.CONTROL_DOWN),
                        e -> execute(true)),
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.SPACE, KeyCombination.CONTROL_DOWN),
                        e -> showCompletion()),
                // 键位说明同时写在 ShortcutsDialog 里，改了这里记得改那边
                InputMap.consume(
                        EventPattern.keyPressed(KeyCode.D, KeyCombination.CONTROL_DOWN),
                        e -> duplicateLines())));
    }

    /** 换行符。写成常量，避开源码里的转义在多层脚本传递中被吃掉。 */
    private static final String LF = String.valueOf((char) 10);

    /**
     * Ctrl+D：把光标所在行原样复制到下一行。
     *
     * <h2>几个刻意的选择</h2>
     * <ul>
     *   <li><b>光标跟到复制出来的那一行</b>，列不变。于是连按几下就是连着往下复制几行——
     *       光标留在原地的话，第二下复制的还是同一行，用户得自己按一次方向键。</li>
     *   <li><b>圈中了几行就整段复制这几行</b>，而不是只复制光标那一行。
     *       圈着三行按下去只多出一行，那是说不通的；而按<b>字符</b>复制选区
     *       （IntelliJ 的做法）和这里要的「复制到下一行」又不是一回事。</li>
     *   <li>选区末尾正好落在下一行行首时（整行整行地往下刷选出来的就是这样），
     *       那一行<b>不算</b>——它一个字符都没被选中。所以末尾按 {@code end - 1} 去定位。</li>
     * </ul>
     *
     * <p>整件事走一次 {@code insertText}，因此撤销也是一步一次，不会按出十几步来。
     */
    private void duplicateLines() {
        IndexRange selection = editor.getSelection();
        int caretParagraph = editor.getCurrentParagraph();
        int caretColumn = editor.getCaretColumn();

        int first;
        int last;
        if (selection.getLength() == 0) {
            first = caretParagraph;
            last = caretParagraph;
        } else {
            first = paragraphAt(selection.getStart());
            last = paragraphAt(selection.getEnd() - 1);
        }

        StringBuilder block = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                block.append(LF);
            }
            block.append(editor.getParagraph(i).getText());
        }

        // 插在最后一行的行尾，而不是下一行的行首：最后一行可能就是全文末尾，
        // 那里没有「下一行」可言
        editor.insertText(editor.getAbsolutePosition(last, editor.getParagraphLength(last)),
                LF + block);

        int target = last + 1 + Math.max(0, caretParagraph - first);
        editor.moveTo(target, Math.min(caretColumn, editor.getParagraphLength(target)));
        // 复制出来的那一行可能在可视区外面，尤其是在最后一屏上按的时候
        editor.requestFollowCaret();
        // 刻意不往状态栏写一句「已复制一行」：这是个高频的编辑动作，
        // 结果就在眼前，每按一下刷一句只会把状态栏里真正要紧的话顶掉
    }

    private int paragraphAt(int offset) {
        return editor.offsetToPosition(offset,
                org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
    }

    /**
     * 把补全要用的元数据在后台捂热。
     *
     * <h2>为什么补全一律只读缓存</h2>
     * 补全是<b>每敲一个字符</b>都要跑一遍的。原来它直接调
     * {@code session.tableNamesForCompletion} / {@code columnsForCompletion}，
     * 那两个在未命中缓存时是真的去查库——也就是把一次网络往返放进了击键路径上。
     *
     * <p>平时看不出来（缓存一热就没事了），出事的是这三种时候：
     * <ul>
     *   <li>查询页开在一个<b>没在树上展开过</b>的库上，第一次 Ctrl+空格 要等整个表清单；</li>
     *   <li>敲 {@code 别名.} 触发某张<b>没打开过</b>的表的结构查询；</li>
     *   <li>连接已经卡死——那就不是等一下，是整个窗口再也动不了。</li>
     * </ul>
     *
     * <p>所以：补全只读缓存，读不到就少给几个候选（本来就是辅助功能）；
     * 同时在这里把数据捂热，下一次击键就有了。
     */
    private void warmMetadata() {
        context.queryService().runAsync(() -> {
            try {
                session.tables(schema);
            } catch (RuntimeException ignored) {
                // 捂不热就算了，补全少给几个候选，不该因此打扰用户
            }
        });
    }

    /** 某张表的结构，后台捂热。同一张表短时间内只发一次。 */
    private void warmTable(String table) {
        if (session.cachedStructure(schema, table) != null) {
            return;
        }
        String key = schema + " " + table;
        if (!warming.add(key)) {
            return; // 已经在读了。补全每击一次键都会走到这儿，不能每次都发一遍
        }
        context.queryService().runAsync(() -> {
            try {
                session.structure(schema, table);
            } catch (RuntimeException ignored) {
                // 同上：补全是辅助功能，读不到就少给
            } finally {
                warming.remove(key);
            }
        });
    }

    /** 正在后台读结构的表，避免同一张表被连着发好几次。 */
    private final java.util.Set<String> warming =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean completionShowing() {
        return completionPopup.isShowing() && !completionList.getItems().isEmpty();
    }

    // -------------------------------------------------- 供自动化探针与测试观测

    /** 补全弹窗是否可见。 */
    public boolean completionVisible() {
        return completionPopup.isShowing();
    }

    /** 当前候选列表。 */
    public List<Suggestion> completionItems() {
        return List.copyOf(completionList.getItems());
    }

    /** 当前选中的候选，没有则为 null。 */
    public Suggestion completionSelection() {
        return completionList.getSelectionModel().getSelectedItem();
    }

    /** 候选图标：字段、表、关键字、函数各不相同，扫一眼就知道补的是什么。 */
    private static javafx.scene.Node iconFor(Kind kind) {
        switch (kind) {
            case COLUMN:
                return Icons.column(Icons.MUTED, 11);
            case TABLE:
                return Icons.table(Icons.MUTED, 11);
            case FUNCTION:
                return Icons.plan("#2a5d8f", 11);
            case SNIPPET:
                return Icons.file("#0f6f70", 11);
            case KEYWORD:
            default:
                return Icons.format("#8a3d6b", 11);
        }
    }

    // ------------------------------------------------------------------ 补全

    private void setupCompletion() {
        completionList.getStyleClass().add("completion-list");
        completionList.setPrefWidth(364);
        completionList.setPrefHeight(150);
        // 关键：弹窗里的控件一旦可获得焦点，Popup 显示时就会把焦点从编辑器抢走，
        // 按键随即被派发到 Popup 自己的场景里——编辑器上挂的 filter 和 InputMap
        // 一个都收不到，表现就是弹窗开着按 Tab/Enter 毫无反应。
        // 这条是本次排查的根因，改事件机制是治不好的。
        completionList.setFocusTraversable(false);
        // 空弹窗要有话说：这条只在「有解释但没候选」时才看得到
        completionList.setPlaceholder(UiUtils.label(
                "这个别名在光标所在的作用域里不存在", "hint"));
        completionList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Suggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label name = UiUtils.label(item.name(), "completion-name");
                Label type = UiUtils.label(item.detail(), "completion-type");
                HBox box = UiUtils.row(8, iconFor(item.kind()),
                        name, UiUtils.hSpacer(), type);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        completionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                applySelectedSuggestion();
            }
        });

        HBox head = UiUtils.row(6, completionHead);
        head.getStyleClass().add("completion-head");

        HBox foot = UiUtils.row(10,
                UiUtils.label("Tab 补全"), UiUtils.label("Ctrl+Space 唤起"),
                UiUtils.hSpacer(), UiUtils.label("别名按子查询分层解析"));
        foot.getStyleClass().add("completion-foot");

        VBox box = UiUtils.column(0, head, completionList, foot);
        box.getStyleClass().add("completion-popup");
        box.setFocusTraversable(false);
        completionPopup.getContent().add(box);
        completionPopup.setAutoHide(true);
    }

    /**
     * 这个字符还属于「正在敲一个名字」吗。
     *
     * <p>点号也算：{@code u.} 之后紧接着要补的正是那张表的字段，
     * 那一下不能把弹窗关掉。
     */
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_'
                || c == '$'          // MySQL 的标识符里合法
                || c > 127;          // 中文表名 / 列名
    }

    private void showCompletion() {
        List<Suggestion> suggestions = computeSuggestions();
        if (suggestions.isEmpty() && !completionHasNote) {
            completionPopup.hide();
            return;
        }
        completionList.setItems(FXCollections.observableArrayList(suggestions));
        completionList.getSelectionModel().selectFirst();

        Bounds caret = editor.getCaretBounds().orElse(null);
        if (caret == null) {
            completionPopup.hide();
            return;
        }
        completionPopup.show(editor, caret.getMinX() - 8, caret.getMaxY() + 2);
    }

    private void refreshCompletion() {
        if (completionPopup.isShowing()) {
            List<Suggestion> suggestions = computeSuggestions();
            if (suggestions.isEmpty() && !completionHasNote) {
                completionPopup.hide();
            } else {
                completionList.setItems(FXCollections.observableArrayList(suggestions));
                completionList.getSelectionModel().selectFirst();
            }
        }
    }

    /**
     * 这一轮虽然没有候选，但有话要说。
     *
     * <p>「一个候选都没有就把弹窗收起来」在别处是对的，唯独在
     * 「限定符解析不出来」这一种情况下不对：那正是用户最需要知道原因的时刻——
     * 他敲了 {@code c.} 却什么都没发生，只会以为补全坏了，
     * 而实际原因是 c 这个别名在光标所在的位置根本不存在。
     */
    private boolean completionHasNote;

    private List<Suggestion> computeSuggestions() {
        completionHasNote = false;
        SqlContext ctx = SqlContext.at(editor.getText(), editor.getCaretPosition());
        String prefix = ctx.word().toLowerCase(Locale.ROOT);
        List<Suggestion> out = new ArrayList<>();

        // 一、别名或表名限定之后：只补该关系的字段
        if (ctx.isQualified()) {
            return cap(qualifiedSuggestions(ctx, prefix, out));
        }

        // 二、FROM / JOIN 之后：只补表名。这个位置混进关键字只会碍事
        boolean tablesOnly = ctx.expectsTable();
        /*
         * 别的位置上，表名只可能当限定符用（ON t.id = ...、SELECT t.name）。
         * 而这张表在本句里如果<b>已经起过别名</b>，它的表名就不再是合法的限定符了——
         * 补出来的 CUSTOMERS.id 在 MySQL、PostgreSQL、Oracle 上都直接报错。
         * 所以那种时候补别名，而不是补表名。
         */
        Map<String, List<String>> aliases = tablesOnly ? Map.of() : aliasesByTable(ctx);
        for (String t : session.cachedTableNames(schema)) {
            if (!t.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            List<String> named = aliases.get(t.toLowerCase(Locale.ROOT));
            if (named == null) {
                out.add(new Suggestion(t, "表", Kind.TABLE));
                continue;
            }
            // 说明里写上表名：用户是照着表名敲进来的，得让他确认补的就是这张表。
            // 同一张表连了两次就有两个别名，都给出来，由他挑
            for (String alias : named) {
                out.add(new Suggestion(alias, "表 " + t + " 的别名", Kind.TABLE));
            }
        }
        if (tablesOnly) {
            completionHead.setText(schema + " 的表 · " + out.size() + " 项匹配");
            return cap(out);
        }

        // 二·五、把这个位置看得见的别名补出来。
        // 手写 SQL 时最常敲的其实就是别名——它们排在关键字前面
        for (SqlScopes.Relation r : ctx.visibleRelations()) {
            if (r.alias().toLowerCase(Locale.ROOT).startsWith(prefix)
                    && !r.alias().equalsIgnoreCase(r.table())) {
                out.add(0, new Suggestion(r.alias(), r.describe(), Kind.TABLE));
            }
        }

        // 二·六、这条语句里已经出现过的那些表的字段。
        //
        // 这是手写 SQL 时最常敲的东西：写完 FROM users 之后回到 SELECT 那儿补字段，
        // 想要的就是 users 的列。原来只有敲了 u. 这种限定符才给字段，
        // 不带限定符时一个字段都不提示——而多数单表查询根本不会去起别名。
        //
        // 排在别名之后、关键字之前：关键字用户自己会背，字段名才是要查的那个。
        List<SqlScopes.Relation> columnSources = relationsForColumns(ctx);
        for (SqlScopes.Relation r : columnSources) {
            if (r.kind() != SqlScopes.Kind.TABLE) {
                continue;   // 子查询和 CTE 的列由下面那段按它自己的列清单给
            }
            String table = r.simpleTable();
            // 结构没读过就先去后台读，这一次先不给。同步读的话，
            // 每敲一个字符就要等一次网络往返——连接卡死时整个窗口冻住
            warmTable(table);
            for (ColumnInfo c : session.cachedColumns(schema, table)) {
                if (!c.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    continue;
                }
                // 同名字段在多表查询里很常见（两张表都有 id）。带上表名，
                // 用户才知道自己补的是哪一个
                out.add(columnSuggestion(r, c.name(), c.displayType()));
            }
        }
        for (SqlScopes.Relation r : columnSources) {
            if (r.kind() == SqlScopes.Kind.TABLE) {
                continue;
            }
            for (String column : r.columns()) {
                if (column.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(columnSuggestion(r, column, r.kind().label()));
                }
            }
        }

        // 二·七、代码片段。排在关键字前面：用户特意起过前缀的东西，
        // 应当比一个通用关键字更靠前，否则每次都要往下翻几行才看得到自己存的那条
        if (!prefix.isEmpty()) {
            for (com.plainly.core.store.SnippetStore.Snippet sn : snippetCache) {
                String sp = sn.prefix() == null ? "" : sn.prefix().toLowerCase(Locale.ROOT);
                if (!sp.isEmpty() && sp.startsWith(prefix)) {
                    out.add(0, new Suggestion(sn.name(),
                            "片段 · " + sn.prefix(), Kind.SNIPPET, sn.body()));
                }
            }
        }

        // 三、其余位置：表名之后接上关键字与函数。
        // 缺了这一段就会出现「敲 sele 毫无提示」——着色认得 SELECT，补全却补不出来。
        if (!prefix.isEmpty()) {
            for (String k : SqlHighlighter.keywords()) {
                if (k.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(new Suggestion(k, "关键字", Kind.KEYWORD));
                }
            }
            for (String f : SqlHighlighter.functions()) {
                if (f.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(new Suggestion(f + "(", "函数", Kind.FUNCTION));
                }
            }
        }

        completionHead.setText((prefix.isEmpty()
                ? schema + " 的表 · " + out.size() + " 项"
                : schema + " · " + out.size() + " 项匹配「" + ctx.word() + "」")
                + (ctx.inSubquery() ? " · 光标在子查询里" : ""));
        return cap(out);
    }

    /**
     * {@code 别名.} 之后补什么。
     *
     * <p>三种情况分得很开，因为它们的字段来源完全不同：
     * <ul>
     *   <li><b>真实的表</b>——查元数据，带类型；</li>
     *   <li><b>派生表 / CTE</b>——字段是子查询 SELECT 列表里的那些名字，
     *       元数据里根本没有这么个东西，查也查不到；</li>
     *   <li><b>解析不出来</b>——这个名字在光标所在的位置确实不存在（多半是另一个
     *       子查询里的别名）。如实说一句，不从别处捡一个同名的糊弄过去。</li>
     * </ul>
     */
    private List<Suggestion> qualifiedSuggestions(SqlContext ctx, String prefix,
                                                  List<Suggestion> out) {
        SqlScopes.Relation relation = ctx.resolved();
        if (relation == null) {
            completionHasNote = true;
            completionHead.setText("在这个位置解析不出限定符 " + ctx.qualifier()
                    + (ctx.inSubquery() ? "（光标在子查询里，外层的别名才看得见）"
                            : "（子查询里的别名在外层是看不见的）"));
            return List.of();
        }

        if (relation.kind() == SqlScopes.Kind.TABLE) {
            String table = relation.simpleTable();
            // 这张表的结构还没读过就先去后台读，这一次先不给字段。
            // 同步读的话，敲一个点号就要等一次网络往返——连接卡死时整个窗口冻住
            warmTable(table);
            for (ColumnInfo c : session.cachedColumns(schema, table)) {
                if (c.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(new Suggestion(c.name(), c.displayType(), Kind.COLUMN));
                }
            }
            completionHead.setText(ctx.qualifier() + " → " + table + " 的字段 · "
                    + out.size() + " 项匹配");
            return out;
        }

        for (String column : relation.columns()) {
            if (column.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(new Suggestion(column, relation.kind().label() + "的列", Kind.COLUMN));
            }
        }
        String note = ctx.qualifier() + " → " + relation.kind().label() + " · "
                + out.size() + " 项匹配";
        if (relation.star()) {
            // 子查询写的是 SELECT *，列名取决于它引用的表。硬去展开要再解析一层，
            // 而这里没把握做对——说清楚比补一半出来强
            note += " · 它写的是 SELECT *，完整列名要看它引用的表";
        }
        completionHead.setText(note);
        return out;
    }

    /**
     * 本句里已经起过别名的表：表名（小写）→ 它的那些别名。
     *
     * <p>同一张表可以连两次（{@code FROM t a JOIN t b}），所以是一对多。
     */
    private static Map<String, List<String>> aliasesByTable(SqlContext ctx) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SqlScopes.Relation r : ctx.visibleRelations()) {
            if (!hasExplicitAlias(r) || r.simpleTable() == null) {
                continue;
            }
            out.computeIfAbsent(r.simpleTable().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(r.alias());
        }
        return out;
    }

    /**
     * 不带限定符时，字段该从哪些关系上取。
     *
     * <h2>为什么不能直接用 visibleRelations()</h2>
     * {@link SqlScopes} 对 {@code FROM t_order o} 会登记<b>两条</b>：别名 {@code o}，
     * 外加表名 {@code t_order} 自己——后者是为了认出
     * {@code SELECT t_order.id FROM t_order} 这种不起别名的写法。
     *
     * <p>照单全收的后果是字段列表整份重复一遍。这个重复<b>一直都在</b>，
     * 只是之前两份长得一模一样（都是光秃秃的 {@code id}），看不出来；
     * 现在带上别名之后变成 {@code o.id} 和 {@code id} 并排，才露出马脚。
     *
     * <p>而且那条光秃秃的根本用不了：一旦起了别名，标准 SQL 里表名就<b>不再</b>是
     * 合法的限定符（MySQL、PostgreSQL、Oracle 都会报错）。所以同一张表上
     * 只要有起过别名的那一条，就只留它。
     */
    private static List<SqlScopes.Relation> relationsForColumns(SqlContext ctx) {
        List<SqlScopes.Relation> all = ctx.visibleRelations();
        java.util.Set<String> aliased = new java.util.HashSet<>();
        for (SqlScopes.Relation r : all) {
            if (hasExplicitAlias(r)) {
                aliased.add(r.table().toLowerCase(Locale.ROOT));
            }
        }
        List<SqlScopes.Relation> out = new ArrayList<>();
        for (SqlScopes.Relation r : all) {
            boolean bareName = r.kind() == SqlScopes.Kind.TABLE && !hasExplicitAlias(r);
            if (bareName && aliased.contains(r.table().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /** 这个关系是不是<b>起过别名</b>——没起时 {@code alias()} 返回的就是表名本身。 */
    private static boolean hasExplicitAlias(SqlScopes.Relation r) {
        return r.alias() != null && r.table() != null
                && !r.alias().equalsIgnoreCase(r.table());
    }

    /**
     * 一条不带限定符的字段候选。
     *
     * <h2>起过别名的表，补出来的字段要带上别名</h2>
     * 这张表既然有别名，那这条 SQL 里引用它的字段就该写成 {@code toi.amount}——
     * 多表查询里不带限定符的 {@code amount} 轻则看不出是哪张表的，
     * 重则两张表都有这个字段，数据库直接报「列名有歧义」。
     * 让用户补完再手动回去加前缀，是把工具知道的事推给人做。
     *
     * <p>显示的也是带前缀的那份，不是只在插入时偷偷加上：候选列表里两条
     * {@code id} 分不出谁是谁，而 {@code toi.id} 和 {@code u.id} 一眼就分得开。
     * 前缀过滤仍然按<b>字段名</b>走，所以敲 {@code am} 照样能匹配到 {@code toi.amount}。
     *
     * <p>没起别名的表不加前缀：那时候 {@code alias()} 返回的就是表名本身，
     * 补出一个 {@code t_order_item.amount} 又长又不是用户想要的——
     * 单表查询根本不需要限定符，而那正是没起别名的常见情形。
     */
    private static Suggestion columnSuggestion(SqlScopes.Relation r, String column,
                                               String detail) {
        String display = hasExplicitAlias(r) ? r.alias() + "." + column : column;
        return new Suggestion(display, detail + " · " + r.alias(), Kind.COLUMN, display);
    }

    /**
     * 截断候选，顺手去重。
     *
     * <h2>去重不是锦上添花</h2>
     * 同一个名字会从好几条路径上各来一次：别名那一段会给出 {@code o}，
     * 表名那一段在这张表已经起过别名时也会给出 {@code o}。列表里并排两条
     * 一模一样的候选，用户只会以为自己看错了，或者怀疑它们指的是不同的东西。
     *
     * <p>留先来的那一条：排序是有讲究的（别名插在最前面，字段在关键字前面），
     * 留后来的会把这份讲究抹掉。
     */
    private static List<Suggestion> cap(List<Suggestion> all) {
        Map<String, Suggestion> unique = new LinkedHashMap<>();
        for (Suggestion one : all) {
            unique.putIfAbsent(one.kind() + "|" + one.name(), one);
        }
        List<Suggestion> out = new ArrayList<>(unique.values());
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    private void moveSelection(int delta) {
        int size = completionList.getItems().size();
        if (size == 0) {
            return;
        }
        int index = completionList.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod(index + delta, size);
        completionList.getSelectionModel().select(next);
        completionList.scrollTo(next);
    }

    /** 重读片段缓存。开页时一次，管理窗口关掉时一次。 */
    private void reloadSnippets() {
        try {
            snippetCache = context.snippets().list();
        } catch (RuntimeException e) {
            // 片段读不出来不该拦住编辑器：补全里少几条，别的照常
            snippetCache = List.of();
        }
    }

    /** 把一段文本插到光标处。片段管理窗口里的「插入」走这条路。 */
    public void insertAtCaret(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        editor.insertText(editor.getCaretPosition(), text);
        editor.requestFocus();
    }

    private void applySelectedSuggestion() {
        Suggestion selected = completionList.getSelectionModel().getSelectedItem();
        completionPopup.hide();
        if (selected == null) {
            return;
        }
        SqlContext ctx = SqlContext.at(editor.getText(), editor.getCaretPosition());
        int caret = editor.getCaretPosition();
        int replaceFrom = caret - ctx.word().length();
        editor.replaceText(replaceFrom, caret, withAlias(selected, ctx));
    }

    /**
     * {@code FROM} / {@code JOIN} 之后选了一张表，顺手给它起个别名。
     *
     * <h2>为什么值得自动做</h2>
     * 别名是手写 SQL 的第一步：没有它，后面每个字段都得写全表名，
     * 而这个工具的字段补全也只有在 {@code 别名.} 之后才给得最准。可起别名这件事
     * 本身没有任何创造性——用户十有八九就是取那几个首字母。
     * 工具知道表名、也知道这条语句里哪些名字已经被占了，那就该它来做。
     *
     * <p>起完之后光标落在别名后面，接着敲 {@code ON} 或者换行写 {@code WHERE} 都顺；
     * 不想要就一个退格连着删掉——别名是一整段连着的字符，删起来不费事。
     *
     * @return 真正要插进编辑器的文本
     */
    private String withAlias(Suggestion selected, SqlContext ctx) {
        String text = selected.insertText();
        if (selected.kind() != Kind.TABLE || !ctx.expectsAlias()) {
            return text;
        }
        /*
         * 已经被占掉的名字：这个位置看得见的每一个别名，以及没起别名的表名本身。
         *
         * 后者同样要躲——FROM ur JOIN user_role ur 里后面那个 ur 会把前面那张表遮掉，
         * 而这种错数据库不一定报，只会让查询悄悄查错对象。
         */
        List<String> taken = new ArrayList<>();
        for (SqlScopes.Relation r : ctx.visibleRelations()) {
            taken.add(r.alias());
            taken.add(r.table());
        }
        String alias = com.plainly.core.sql.SqlAliases.suggest(text, taken);
        return alias == null ? text : text + " " + alias;
    }

    // ------------------------------------------------------------------ 工具条

    /**
     * 自动换行开关。
     *
     * <h2>为什么值得有这一个按钮</h2>
     * 长 SQL（几十列的 INSERT 那种）拖横向滚动条会明显卡。实测在 1014 字符一行、
     * 40 行的文本上，横向滚动<b>每帧 75 毫秒</b>——大约 13 帧每秒，肉眼就是一顿一顿的。
     *
     * <p>要命的是这笔开销的大头不在语法着色上：同一份文本整篇只给一段样式，
     * 每帧仍要 61 毫秒。真正的原因是 RichTextFX 会把<b>整行</b>交给 TextFlow 排版，
     * 哪怕屏幕上只看得见一百来个字符——行有多长就排多长，横向滚动每一帧都要重来一遍。
     * 这一层在组件内部，我们改不动。
     *
     * <p>而打开自动换行之后，要排版的文字量被视口框住了：同样这份文本
     * <b>每帧 11 毫秒</b>，而且<b>不随行长变化</b>——4134 字符一行时仍是 10 毫秒。
     * 换句话说，这不是把问题缓解了一点，是把它整个绕开了。
     *
     * <p>所以不默认打开：换行会打乱对齐，写 SQL 时很多人要靠缩进看结构。
     * 给一个开关、记住选择，让撞上长 SQL 的人自己开。
     */
    private ToggleButton buildWrapToggle() {
        ToggleButton wrap = new ToggleButton("自动换行");
        wrap.getStyleClass().add("tool-button");
        wrap.setTooltip(new Tooltip(
                "长 SQL 拖横向滚动条会卡——行有多长，每一帧就要排多长的版。"
                        + System.lineSeparator()
                        + "打开之后没有横向滚动条，排版量被窗口框住，实测快 7 倍以上。"));
        wrap.setSelected(context.uiState().get(WRAP_KEY, "false").equals("true"));
        editor.setWrapText(wrap.isSelected());
        wrap.selectedProperty().addListener((obs, was, on) -> {
            editor.setWrapText(on);
            context.uiState().put(WRAP_KEY, String.valueOf(on));
        });
        return wrap;
    }

    private HBox buildToolbar() {
        Button current = UiUtils.toolButton("执行当前语句  Ctrl+Enter", Icons.playLine(Icons.NEUTRAL, 11));
        current.setOnAction(e -> execute(true));
        current.setTooltip(new Tooltip("光标所在的那一条（以分号分隔）；选中了片段则执行选中部分"));

        runButton.setTooltip(new Tooltip("默认执行整页 SQL；选中一段后只执行选中的那一段"));

        Button format = UiUtils.toolButton("格式化", Icons.format(Icons.NEUTRAL, 12));
        format.setOnAction(e -> formatSql());

        ToggleButton wrap = buildWrapToggle();

        Button explain = UiUtils.toolButton("执行计划", Icons.plan(Icons.NEUTRAL, 12));
        explain.setOnAction(e -> explain());

        Button snippets = UiUtils.toolButton("片段", Icons.file(Icons.NEUTRAL, 12));
        snippets.setTooltip(new Tooltip("可复用的写法，比如「分页查询」「找重复行」。在编辑器里敲片段的前缀（比如 page）就能直接补全出整段。"));
        snippets.setOnAction(e -> {
            com.plainly.app.view.SnippetDialog dialog =
                    new com.plainly.app.view.SnippetDialog(context);
            dialog.setOnUse(this::insertAtCaret);
            // 管理窗口里可能新增、改名、删掉片段。关窗时重读一次，
            // 否则补全里还是老的那份，用户会以为刚才存的没生效
            dialog.setOnClosed(this::reloadSnippets);
            dialog.show(getScene().getWindow());
        });

        Button history = UiUtils.toolButton("历史", Icons.search(Icons.NEUTRAL, 12));
        history.setOnAction(e -> {
            com.plainly.app.view.HistoryDialog dialog =
                    new com.plainly.app.view.HistoryDialog(context);
            dialog.setOnUse(this::setSql);
            dialog.show(getScene().getWindow());
        });

        rowLimit.getItems().addAll(200, 1000, 5000, 0);
        rowLimit.setValue(1000);
        rowLimit.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer v) {
                return v == null || v == 0 ? "不限行数" : UiUtils.groupDigits(v) + " 行";
            }

            @Override
            public Integer fromString(String s) {
                return 1000;
            }
        });

        schemaBox.setPrefWidth(190);
        schemaBox.setValue(schema);
        schemaBox.setOnAction(e -> {
            String picked = schemaBox.getValue();
            if (picked != null && !picked.equals(schema)) {
                schema = picked;
                onSchemaChanged.accept(schema);
                statusSink.accept("目标库已切到 " + schema);
                warmMetadata();
            }
        });
        // 库列表异步取，别让下拉挡住界面
        context.queryService().submit(session::schemas)
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null || list == null) {
                        return;
                    }
                    schemaBox.setItems(FXCollections.observableArrayList(
                            list.stream().map(SchemaInfo::name).toList()));
                    schemaBox.setValue(schema);
                }));

        txBar = new com.plainly.app.view.TransactionBar(session, m -> statusSink.accept(m));
        txBar.applyVisibility();

        HBox bar = UiUtils.row(8, runButton, current, stopButton, UiUtils.vSeparator(),
                format, wrap, explain, snippets, history,
                UiUtils.vSeparator(), txBar,
                UiUtils.hSpacer(),
                UiUtils.label(session.config().name(), "hint"),
                UiUtils.label("目标库", "hint"), schemaBox,
                UiUtils.vSeparator(),
                UiUtils.label("限制", "hint"), rowLimit);
        bar.getStyleClass().add("app-toolbar");
        return bar;
    }

    private VBox buildResultArea() {
        addRowButton.setOnAction(e -> grid.addRow());
        deleteRowButton.setOnAction(e -> grid.deleteSelectedRows());
        saveEditsButton.setOnAction(e -> commitGridEdits());
        discardEditsButton.setOnAction(e -> {
            if (grid.editBuffer() != null) {
                grid.revertEdits();
            }
            refreshEditState();
        });
        grid.setOnDirtyChanged(this::refreshEditState);

        HBox head = UiUtils.row(12, UiUtils.label("结果", "section-label"),
                resultLabel, UiUtils.hSpacer(),
                addRowButton, deleteRowButton, saveEditsButton, discardEditsButton,
                UiUtils.vSeparator(), exportButton);
        head.getStyleClass().add("grid-toolbar");
        refreshEditState();

        VBox.setVgrow(grid, Priority.ALWAYS);
        javafx.scene.layout.StackPane body = new javafx.scene.layout.StackPane(
                grid, buildErrorPanel());
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox box = UiUtils.column(0, head, body, messageLabel);
        return box;
    }

    /**
     * 执行失败时盖在结果区上的那块红色面板。
     *
     * <h2>为什么不能只在底下写一行字</h2>
     * 原来失败信息只出现在结果区最下面那行细字里。而用户按下 F5 之后，
     * 眼睛盯的是<b>结果区中间</b>——那儿一片空白，看着就像「查询返回 0 行」。
     * 一条 SQL 明明报错了，用户却以为查出来是空的，接着去改条件，
     * 而真正的原因在他视线之外的一行小字里。
     *
     * <p>所以失败时直接把结果区盖住：报错本来就没有结果可显示，
     * 那块地方空着也是空着，用来放最该被看见的东西正合适。
     *
     * <p>正文用可选中的文本域而不是 Label：数据库报错又长又关键，
     * 用户需要能复制出去搜索。
     */
    private javafx.scene.layout.VBox buildErrorPanel() {
        errorText.setEditable(false);
        errorText.setWrapText(true);
        errorText.getStyleClass().add("sql-error-detail");

        HBox head = UiUtils.row(8, Icons.warn("#a0402a", 15),
                UiUtils.label("执行失败", "sql-error-title"));
        head.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        javafx.scene.layout.VBox panel = UiUtils.column(8, head, errorText);
        VBox.setVgrow(errorText, Priority.ALWAYS);
        panel.getStyleClass().add("sql-error-panel");
        panel.setVisible(false);
        panel.setManaged(false);
        errorPanel = panel;
        return panel;
    }

    /** 显示或收起错误面板。收起时把结果网格还回来。 */
    private void showError(String message) {
        boolean failed = message != null;
        if (errorPanel != null) {
            errorPanel.setVisible(failed);
            errorPanel.setManaged(failed);
        }
        grid.setVisible(!failed);
        if (failed) {
            errorText.setText(message);
        }
    }

    // ------------------------------------------------------------------ 执行

    /** @param currentOnly 没有选中内容时，只执行光标所在的那条语句 */
    private void execute(boolean currentOnly) {
        if (running) {
            return;
        }
        boolean partial = hasSelection();
        String sql = sqlToRun(currentOnly);
        if (sql == null || sql.isBlank()) {
            return;
        }

        // 整页可能写了好几条，而 JDBC 一次只收一条（MySQL 默认还禁止多句），
        // 所以先切开再逐条发
        List<String> statements = splitToStatements(sql);
        if (statements.isEmpty()) {
            return;
        }
        if (!confirmWideReach(statements)) {
            return;
        }

        running = true;
        runButton.setDisable(true);
        stopButton.setDisable(false);
        exportButton.setDisable(true);
        resultLabel.setText("执行中…");
        note("");
        // 上一次的报错面板也要收掉：开始跑新的一条了，旧的错留在屏幕上
        // 会让人分不清这是刚才那次的还是这次的
        showError(null);
        statusSink.accept(runningHint(partial, statements.size()));

        int limit = rowLimit.getValue() == null ? 1000 : rowLimit.getValue();
        String targetSchema = schema;
        context.queryService().submit(() -> {
            // 同一条连接可能被别的查询页切到了别的库，每次执行都显式切回来
            session.connection().useSchema(targetSchema);
            return runScript(statements, limit);
        })
                .whenComplete((outcome, error) -> Platform.runLater(() -> {
                    running = false;
                    runButton.setDisable(false);
                    stopButton.setDisable(true);
                    // 刚才那条可能是写语句：手动事务下「有未提交的改动」要亮起来。
                    // 失败的时候也要通知——DML 报错前也可能已经改掉了一部分
                    session.fireTransactionChanged();

                    if (error != null) {
                        resultLabel.setText("执行失败");
                        // 底下那行不再重复报错——上面那块红面板已经把它说全了。
                        // 同一句话在一屏里出现两次，只会让人怀疑是两个错
                        note("");
                        /*
                         * 上一条的结果要真的清掉，不能只靠红面板盖住。
                         *
                         * 盖住只是看不见：结果对象还在网格里，而「导出结果」和那几个
                         * 编辑按钮认的就是它。于是失败之后仍然导得出一份数据，
                         * 导出来的是上一条语句的结果——而用户以为那是这一条的。
                         */
                        grid.clear("这次执行失败了，上一条的结果已经清掉");
                        refreshEditState();
                        exportButton.setDisable(true);
                        showError(UiUtils.rootMessage(error));
                        statusSink.accept("执行失败");
                        context.historyStore().record(session.config().id(), sql, 0, 0,
                                false, UiUtils.rootMessage(error));
                        return;
                    }
                    showError(null);
                    showOutcome(outcome);
                    context.historyStore().record(session.config().id(), sql,
                            outcome.millis(),
                            outcome.shown() == null ? outcome.updated() : outcome.shown().rows().size(),
                            true, null);
                    statusSink.accept(resultLabel.getText());
                }));
    }

    /**
     * 本次运行内用户是否已经说过「别再问了」。
     *
     * <p>按整个程序算，不按标签页：说这句话的人指的是这个工具，不是这一个页。
     * 也刻意<b>不</b>存进本机库——下次启动重新开始问。这道拦截的价值全在
     * 「那一下意外」，而意外通常发生在换了个库、换了个心境之后。
     */
    private static boolean wideReachMuted;

    /**
     * 影响面过大的语句，执行前问一句。
     *
     * <p>拦的是 {@code DELETE FROM t}、{@code UPDATE t SET ...} 这种漏写 WHERE 的语句：
     * 它们语法完全正确，数据库不会有任何异议，会安静地改掉整张表——而这里没有回滚可按。
     * 判断逻辑在 {@link SqlRisk}，那边有单元测试。
     *
     * @return true 表示可以继续执行
     */
    private boolean confirmWideReach(List<String> statements) {
        if (wideReachMuted) {
            return true;
        }
        List<SqlRisk.Risk> risks = SqlRisk.scan(statements);
        if (risks.isEmpty()) {
            return true;
        }

        VBox body = UiUtils.column(8);
        body.getChildren().add(UiUtils.label(
                "下面的语句不限定范围，执行之后没有撤销可按：", "hint"));
        // 只列前几条：一次贴进来几十条整表更新时，把它们全铺出来会把按钮顶出屏幕，
        // 而用户要做的决定并不会因为看到第 27 条而改变
        int shown = Math.min(risks.size(), 6);
        for (int i = 0; i < shown; i++) {
            SqlRisk.Risk risk = risks.get(i);
            Label line = UiUtils.label(risk.describe(), "risk-item");
            line.setWrapText(true);
            body.getChildren().add(line);
            Label detail = UiUtils.label(brief(risk.sql()), "risk-sql");
            detail.setWrapText(true);
            body.getChildren().add(detail);
        }
        if (risks.size() > shown) {
            body.getChildren().add(
                    UiUtils.label("……另有 " + (risks.size() - shown) + " 条", "hint"));
        }

        javafx.scene.control.CheckBox mute =
                new javafx.scene.control.CheckBox("本次运行不再提示");
        body.getChildren().add(mute);

        // 只有一条时直接把话说完整：「有 1 条语句影响面很大」是一句正确但没用的话
        String header = risks.size() == 1
                ? risks.get(0).kind().headline()
                : "有 " + risks.size() + " 条语句会一次性影响全部数据";
        boolean go = UiUtils.confirm(getScene() == null ? null : getScene().getWindow(),
                header, body, "仍然执行");
        // 只有真的按了「仍然执行」才记这个勾：在取消的框上勾了「不再提示」，
        // 意思是「这次不跑，以后也别拦」——把它当成前者会让下一条危险语句直接过去
        if (go && mute.isSelected()) {
            wideReachMuted = true;
        }
        return go;
    }

    /**
     * 把编辑器里的内容切成一条条要发出去的语句。
     *
     * <p>SQL 库按分号切。<b>键值库不能这么切</b>：Redis 的分号是普通字符，
     * {@code SET note "a;b"} 里那个分号是值的一部分，切开就把值弄坏了，
     * 而且 SQL 的注释规则（{@code --}、{@code /*}）在这里同样不成立。
     * 那边的规矩是<b>一行一条命令</b>，照它来。
     */
    private List<String> splitToStatements(String sql) {
        if (!session.connection().dialect().hasTableStructure()) {
            return sql.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
        boolean backslashEscapes = session.config().type() == DbType.MYSQL;
        return SqlScript.split(sql, backslashEscapes);
    }

    private String runningHint(boolean partial, int count) {
        if (count > 1) {
            return "执行中…共 " + count + " 条语句";
        }
        return partial ? "执行选中片段…" : "执行中…";
    }

    /** 一页脚本跑完的结果：展示用的结果集，加上整页的执行情况。 */
    private record ScriptOutcome(QueryResult shown, int count, int updated, long millis) {
    }

    /**
     * 逐条执行。
     *
     * <p>多个结果集只留最后一个——下面只有一个网格，与其静静换掉不如在提示里说清楚。
     * 中途报错也不能只报一句「失败」：前面那几条已经在库里生效了，
     * 用户必须知道停在哪一条、前面做过什么。
     */
    private ScriptOutcome runScript(List<String> statements, int limit) {
        long start = System.nanoTime();
        QueryResult shown = null;
        int updated = 0;
        for (int i = 0; i < statements.size(); i++) {
            String one = statements.get(i);
            QueryResult r;
            try {
                r = session.connection().execute(one, limit);
            } catch (RuntimeException e) {
                if (statements.size() == 1) {
                    throw e;
                }
                throw new DbException("第 " + (i + 1) + " 条语句执行失败：" + brief(one)
                        + (i > 0 ? "（前 " + i + " 条已执行，且已生效）" : ""), e);
            }
            if (r.isResultSet()) {
                shown = r;
            } else {
                updated += r.updateCount();
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new ScriptOutcome(shown, statements.size(), updated, millis);
    }

    /**
     * 结果区底下那行补充说明。
     *
     * <h2>它现在只说「结果之外」的事</h2>
     * 执行失败的详情归上面那块红面板——同一句报错在一屏里出现两次，
     * 只会让人怀疑是两个不同的错。这里留下的是别处看不到的两件事：
     * 结果被上限截断了，以及多语句脚本里「下面显示的是最后一个结果集」。
     *
     * <p>没话说的时候整行收起来（{@code managed=false} 才是真的不占位置，
     * 只设 visible 会在结果区底下留一条空白）。
     */
    private void note(String text) {
        boolean has = text != null && !text.isBlank();
        messageLabel.setText(has ? text : "");
        messageLabel.setVisible(has);
        messageLabel.setManaged(has);
    }

    // ------------------------------------------------------------------ 改查询结果

    /**
     * 按当前结果集能不能写回，决定那几个编辑按钮的去留。
     *
     * <h2>为什么这一步必须有</h2>
     * 网格自己早就会判「这份结果能不能改」，可编辑时就允许双击进单元格。
     * 但在这个页面上，改完<b>没有地方能保存</b>——改动只留在内存里，
     * 切一下结果就没了。能改却存不了，比干脆不让改更糟：用户会以为已经存了。
     *
     * <p>所以按钮和网格的可编辑判定必须绑在同一个依据上（{@code QueryResult.isEditable()}），
     * 一处放行、另一处就得跟上。
     */
    private void refreshEditState() {
        QueryResult r = grid.result();
        boolean editable = r != null && r.isEditable();
        for (javafx.scene.control.Button b : new javafx.scene.control.Button[] {
                addRowButton, deleteRowButton, saveEditsButton, discardEditsButton }) {
            b.setVisible(editable);
            b.setManaged(editable);
        }
        com.plainly.core.edit.EditBuffer buffer = grid.editBuffer();
        boolean dirty = editable && buffer != null && buffer.hasChanges();
        saveEditsButton.setDisable(!dirty);
        discardEditsButton.setDisable(!dirty);
        saveEditsButton.setText(dirty ? "保存 (" + buffer.changeCount() + ")" : "保存");
    }

    /**
     * 把结果网格里的改动写回去。
     *
     * <p>写回目标由结果集自己给（{@code QueryResult.source()}），不是拿页面上当前选的库
     * 去猜——查询里完全可以写 {@code other_db.t}，用当前库去限定就会写错库。
     * 只有在驱动压根不报模式名时才退回当前库：那种情况下这条 SELECT 里的表名
     * 本来也是不限定的，它当初就是按当前库解析的。
     */
    private void commitGridEdits() {
        QueryResult r = grid.result();
        QueryResult.Source src = r == null ? null : r.source();
        if (src == null) {
            return;
        }
        String targetSchema = src.schema().isBlank() ? schema : src.schema();
        saveEditsButton.setDisable(true);
        com.plainly.app.view.GridWriteBack.commit(context, session, targetSchema, src.table(),
                grid, getScene() == null ? null : getScene().getWindow(), statusSink,
                this::reloadGrid, this::refreshEditState);
    }

    /**
     * 写回成功之后重新取一次数。
     *
     * <p>不在本地打补丁了事：数据库可能给自增列发了号、套了默认值、跑了触发器，
     * 屏幕上那份是猜的，重取回来的才是真的。重跑的是<b>产出这份结果的那一条</b>语句，
     * 不是整页——整页里可能还有别的写语句，再跑一遍就是又改一次库。
     */
    private void reloadGrid() {
        QueryResult current = grid.result();
        String sql = current == null ? null : current.statement();
        if (sql == null || sql.isBlank()) {
            refreshEditState();
            return;
        }
        int limit = rowLimit.getValue() == null ? 1000 : rowLimit.getValue();
        String targetSchema = schema;
        context.queryService().submit(() -> {
            session.connection().useSchema(targetSchema);
            return session.connection().execute(sql, limit);
        }).whenComplete((res, error) -> Platform.runLater(() -> {
            if (error != null) {
                // 保存本身是成功的，别让重取失败看着像保存失败
                note("已保存，但重新取数失败：" + UiUtils.rootMessage(error));
                refreshEditState();
                return;
            }
            grid.setResult(res);
            refreshEditState();
        }));
    }

    private void showOutcome(ScriptOutcome outcome) {
        String prefix = outcome.count() > 1 ? "共 " + outcome.count() + " 条语句 · " : "";
        QueryResult shown = outcome.shown();
        if (shown == null) {
            resultLabel.setText(prefix + "影响 " + outcome.updated() + " 行 · 耗时 "
                    + outcome.millis() + " ms");
            note("");
            /*
             * 这一条没有结果集（UPDATE / DELETE / DDL 都是），那下面就不该还摆着
             * 上一条 SELECT 的行——标题写着「影响 3 行」，底下列着五行数据，
             * 两者指的根本不是同一条语句。
             */
            grid.clear("这条语句没有结果集");
            refreshEditState();
            exportButton.setDisable(true);
            return;
        }

        grid.setResult(shown);
        refreshEditState();
        exportButton.setDisable(shown.rows().isEmpty());
        resultLabel.setText(prefix + "返回 " + UiUtils.groupDigits(shown.rows().size())
                + " 行 · 耗时 " + outcome.millis() + " ms"
                + (shown.truncated() ? " · 已按上限截断" : ""));

        StringBuilder note = new StringBuilder();
        if (shown.truncated()) {
            note.append("结果超过取回上限，仅显示前 ").append(shown.rows().size()).append(" 行");
        }
        if (outcome.count() > 1) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append("下方为最后一个结果集");
            if (outcome.updated() > 0) {
                note.append("，其余语句共影响 ").append(outcome.updated()).append(" 行");
            }
        }
        note(note.toString());
    }

    /** 报错里用的语句片段：压成一行、截短，够认出是哪一条就行。 */
    private static String brief(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 57) + "…";
    }

    /**
     * 看执行计划。
     *
     * <p>各家 EXPLAIN 的输出形状差得远：MySQL 给一张宽表，PostgreSQL 给一列文本，
     * SQLite 给三列。所以不去解析它，原样把结果集摆出来——
     * 硬拗成统一的树，反而会把各家真正有用的那几列信息丢掉。
     *
     * <p>PostgreSQL 这边刻意不加 ANALYZE：那会真的执行语句。
     * 对一条 UPDATE 求执行计划时，这个区别就是「看一眼」和「改了数据」。
     */
    private void explain() {
        String sql = sqlToRun(false);
        if (sql == null || sql.isBlank()) {
            return;
        }
        List<String> statements = SqlScript.split(sql,
                session.config().type() == DbType.MYSQL);
        if (statements.isEmpty()) {
            return;
        }
        String one = statements.get(0);
        String explainSql = session.connection().dialect().explainQuery(one);
        String targetSchema = schema;

        statusSink.accept("正在取执行计划…");
        context.queryService().submit(() -> {
            session.connection().useSchema(targetSchema);
            return session.connection().execute(explainSql, 500);
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(getScene().getWindow(), "取执行计划失败", error);
                statusSink.accept("取执行计划失败");
                return;
            }
            showExplain(one, explainSql, result);
        }));
    }

    private void showExplain(String original, String explainSql, QueryResult result) {
        DataGridPane pane = new DataGridPane();
        pane.setResult(result);

        javafx.stage.Stage stage = new javafx.stage.Stage();
        UiUtils.brand(stage);
        stage.initOwner(getScene().getWindow());
        stage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        stage.setTitle("执行计划");

        HBox head = UiUtils.row(8, Icons.plan(Icons.ACCENT, 14),
                UiUtils.label(explainSql.length() > 90
                        ? explainSql.substring(0, 87) + "…" : explainSql, "hint"));
        head.getStyleClass().add("dialog-head");

        VBox box = new VBox(head, pane);
        VBox.setVgrow(pane, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.Scene scene = new javafx.scene.Scene(box, 1000, 560);
        scene.getStylesheets().addAll(getScene().getStylesheets());
        stage.setScene(scene);
        stage.show();
        statusSink.accept("执行计划已打开");
    }

    /**
     * 发一个取消请求。
     *
     * <p>必须放到后台线程上。MySQL 的取消不是往当前连接上塞个信号，而是
     * <b>另开一条连接</b>去发 {@code KILL QUERY}——那是一次完整的握手加往返。
     * 放在界面线程上，网络不通时整个窗口会僵住；而用户按「停止」的场合，
     * 恰恰多半就是网络或者服务端出了问题的时候。
     */
    private void cancel() {
        statusSink.accept("正在发送取消请求…");
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        context.queryService().runAsync(() -> {
            try {
                session.connection().cancel();
                Platform.runLater(() -> statusSink.accept("已发送取消请求"));
            } catch (RuntimeException e) {
                Platform.runLater(() -> UiUtils.showError(owner, "取消失败", e));
            }
        });
    }

    /**
     * 本次要执行的 SQL。
     *
     * <p>选中优先：手上圈了一段，就只跑那一段——这是数据库客户端里几乎一致的约定，
     * 也是在一大页脚本里只试跑一句的唯一顺手办法。
     * 没有选中时才看 {@code currentOnly}：要么整页，要么光标所在的那一条。
     */
    private String sqlToRun(boolean currentOnly) {
        if (hasSelection()) {
            return editor.getSelectedText().trim();
        }
        return currentOnly ? currentStatement() : editor.getText().trim();
    }

    private boolean hasSelection() {
        String selected = editor.getSelectedText();
        return selected != null && !selected.isBlank();
    }

    /** 光标所在的那一条语句（以分号分隔）。 */
    private String currentStatement() {
        String text = editor.getText();
        int caret = editor.getCaretPosition();
        int start = text.lastIndexOf(';', Math.max(0, caret - 1)) + 1;
        int end = text.indexOf(';', caret);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    /** 关键字换行的轻量格式化，够日常用。 */
    /**
     * 排版。
     *
     * <p>规则和字面量安全都在 {@link com.plainly.core.sql.SqlFormatter} 里，那儿有测试守着。
     * 这里只负责换文本，以及<b>把着色补回去</b>。
     */
    private void formatSql() {
        String formatted = com.plainly.core.sql.SqlFormatter.format(editor.getText());
        editor.replaceText(formatted);
        // 必须显式重着色，不能指望 textProperty 上那个监听器。
        // 第二次格式化时新旧文本完全相同，而 JavaFX 的属性不会为「相等的新值」发通知；
        // replaceText 却已经把样式段清掉了——表现就是「点第二次，高亮全没了」
        highlightNow();
    }

    private void openExport() {
        QueryResult result = grid.result();
        if (result == null || result.rows().isEmpty()) {
            return;
        }
        ExportDialog.forQuery(context, session, schema, result)
                .showAndWait(getScene().getWindow());
    }
}
