package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.search.ObjectIndex;
import com.plainly.core.search.ObjectSearch;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 左侧栏的对象搜索。
 *
 * <h2>为什么在左边，而不是顶栏</h2>
 * 它找的是表、视图、字段——全是左树里的东西；找到之后要做的事是「跳到那个对象」，
 * 也是一次树导航。放在顶栏最右边，离它作用的那棵树隔着整个窗口。
 *
 * <h2>为什么是结果列表，不是就地过滤树</h2>
 * 就地过滤只能过滤<b>已经加载进树</b>的节点。而这个用户有 12 个库，平时只展开其中一两个——
 * 没展开过的库里的表根本不在树上，过滤器搜不到，<b>而且用户无从得知自己漏了什么</b>。
 * 搜索最怕的就是「没搜到」和「没搜」长得一样。结果列表直接查元数据，覆盖全部已连接的库。
 *
 * <h2>索引而不是逐次查询</h2>
 * 每敲一个字符去查一轮元数据，实测 12 个库要 13 次往返约 150ms，而且<b>搜不到注释</b>
 * （JDBC 的名字模式只匹配列名）。这里改成一次性把表名、列名、注释拉进内存，
 * 之后全在本地匹配。索引按库缓存，刷新树时作废。
 */
public class SidebarSearch extends VBox {

    /** 最多显示多少条。再多，列表就成了一堵墙。 */
    private static final int LIMIT = 60;

    /** 一条命中，外加它属于哪个会话、哪张表。 */
    private record Row(ObjectIndex.Hit hit, DbSession session, TableInfo table) {
    }

    private final AppContext context;

    private final TextField field = new TextField();
    private final ListView<Row> list = new ListView<>();
    private final Label head = UiUtils.label("", "hint");
    private final Label foot = UiUtils.label("", "hint");
    private final VBox resultBox;

    /** 每个「连接 id + 库名」一份索引。刷新树时整个清掉。 */
    private final Map<String, List<ObjectIndex.Entry>> indexes = new LinkedHashMap<>();

    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));
    private final AtomicInteger round = new AtomicInteger();

    private BiConsumer<DbSession, TableInfo> onOpen = (s, t) -> { };
    private BiConsumer<DbSession, TableInfo> onReveal = (s, t) -> { };
    private java.util.function.Consumer<Boolean> onActiveChanged = a -> { };

    public SidebarSearch(AppContext context) {
        this.context = context;
        setSpacing(0);

        buildField();
        resultBox = buildResults();

        getChildren().addAll(searchRow(), resultBox);
        VBox.setVgrow(resultBox, Priority.ALWAYS);
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    /** 双击 / 回车：定位到树上，并把表打开。 */
    public void setOnOpen(BiConsumer<DbSession, TableInfo> handler) {
        this.onOpen = handler;
    }

    /**
     * 单击：定位到树上，但不打开表。
     *
     * <p>分成两档是因为代价差得远：定位只是展开几层树，打开表要真去查一页数据。
     * 一边翻结果一边被打开一堆标签页，不是人要的。
     */
    public void setOnReveal(BiConsumer<DbSession, TableInfo> handler) {
        this.onReveal = handler;
    }

    /** 退出搜索态，回到树。定位到树上之前必须先退出——不然树被结果盖着，看不见。 */
    public void close() {
        field.clear();
        setActive(false);
    }

    /**
     * 进入 / 退出搜索时回调。
     *
     * <p>搜索态和树是<b>互斥</b>的：左边这块地方一次只显示一样东西。
     * 外面据此把树藏起来或放回来。
     */
    public void setOnActiveChanged(java.util.function.Consumer<Boolean> handler) {
        this.onActiveChanged = handler;
    }

    public void focus() {
        field.requestFocus();
        field.selectAll();
    }

    /** 树刷新了，索引跟着作废——不然搜出来的还是旧结构。 */
    public void invalidate() {
        indexes.clear();
    }

    /** 搜索是否正在进行（决定左侧显示结果还是树）。 */
    public boolean isActive() {
        return resultBox.isVisible();
    }

    // ------------------------------------------------------------------ 控件

    private HBox searchRow() {
        HBox row = UiUtils.row(6, Icons.search(Icons.MUTED, 12), field);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sidebar-search");
        return row;
    }

    private void buildField() {
        field.setPromptText("搜索表、视图、字段、注释");
        field.getStyleClass().add("sidebar-search-field");
        field.textProperty().addListener((o, was, is) -> {
            if (is == null || is.isBlank()) {
                setActive(false);
                return;
            }
            debounce.playFromStart();
        });
        debounce.setOnFinished(e -> search());

        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN:
                    move(1);
                    e.consume();
                    break;
                case UP:
                    move(-1);
                    e.consume();
                    break;
                case ENTER:
                    openSelected();
                    e.consume();
                    break;
                case ESCAPE:
                    field.clear();
                    setActive(false);
                    e.consume();
                    break;
                default:
                    break;
            }
        });
    }

    private VBox buildResults() {
        list.getStyleClass().add("search-results");
        list.setCellFactory(v -> new HitCell());
        list.setPlaceholder(UiUtils.label("没有匹配的对象", "hint"));
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelected();
            } else {
                revealSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                field.clear();
                setActive(false);
            }
        });

        HBox headRow = UiUtils.row(6, head);
        headRow.getStyleClass().add("search-head");
        HBox footRow = UiUtils.row(6, foot);
        footRow.getStyleClass().add("search-foot");

        VBox box = UiUtils.column(0, headRow, list, footRow);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    /** 搜索态与树态互斥。managed=false 才是真的不占位置。 */
    private void setActive(boolean active) {
        boolean was = resultBox.isManaged();
        resultBox.setVisible(active);
        resultBox.setManaged(active);
        if (!active) {
            list.setItems(FXCollections.observableArrayList());
        }
        if (was != active) {
            onActiveChanged.accept(active);
        }
    }

    /**
     * 一条结果，两行。
     *
     * <p>侧栏很窄（默认二百多像素），所以哪样东西放第一行是要挑的：
     * <b>名字和「为什么命中」放第一行，路径放第二行</b>。路径最长也最能容忍截断——
     * 看不全库名还能靠悬停；而注释要是被截掉，用户就看不出这条为什么排在这儿了。
     *
     * <p>单元格宽度绑到列表宽度：不绑的话内容会把列表撑宽，侧栏底下多出一条横向滚动条，
     * 而侧栏是不该横着滚的。
     */
    private class HitCell extends ListCell<Row> {

        HitCell() {
            // 减掉的是内边距和竖直滚动条的宽度
            prefWidthProperty().bind(list.widthProperty().subtract(24));
            setMaxWidth(javafx.scene.control.Control.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            ObjectIndex.Entry e = item.hit().entry();

            Label name = UiUtils.label(e.name(), "search-name");
            HBox top = UiUtils.row(6, iconFor(e.kind()), name);
            top.setAlignment(Pos.CENTER_LEFT);

            // 凭注释命中的，注释就是「为什么是它」，得摆在第一行
            if (item.hit().matchedComment() && !e.comment().isBlank()) {
                // 不给它 hgrow：徽章要贴着文字，拉满会变成一个大空盒子
                top.getChildren().add(UiUtils.label(e.comment(), "search-comment"));
            } else if (!e.detail().isBlank()) {
                top.getChildren().addAll(UiUtils.hSpacer(),
                        UiUtils.label(e.detail(), "search-type"));
            }

            String where = item.session().config().name() + " · " + e.path();
            Label sub = UiUtils.label(where, "search-path");
            sub.setMaxWidth(Double.MAX_VALUE);

            VBox box = UiUtils.column(1, top, sub);
            box.setMaxWidth(Double.MAX_VALUE);
            setGraphic(box);

            // 截断了也还有地方看全：完整路径、类型、注释都在悬停里
            StringBuilder tip = new StringBuilder(where).append('\n').append(e.name());
            if (!e.detail().isBlank()) {
                tip.append("  ").append(e.detail());
            }
            if (!e.comment().isBlank()) {
                tip.append('\n').append(e.comment());
            }
            setTooltip(new javafx.scene.control.Tooltip(tip.toString()));
        }
    }

    private static javafx.scene.Node iconFor(ObjectSearch.Kind kind) {
        switch (kind) {
            case VIEW:
                return Icons.view(Icons.MUTED, 11);
            case COLUMN:
                return Icons.column(Icons.MUTED, 11);
            case TABLE:
            default:
                return Icons.table(Icons.ACCENT, 11);
        }
    }

    // ------------------------------------------------------------------ 搜索

    private void search() {
        String needle = field.getText() == null ? "" : field.getText().strip();
        if (needle.isEmpty()) {
            setActive(false);
            return;
        }
        setActive(true);
        int mine = round.incrementAndGet();
        head.setText("正在搜索…");

        context.queryService().submit(() -> collect(needle))
                .whenComplete((rows, error) -> Platform.runLater(() -> {
                    if (mine != round.get()) {
                        return; // 用户已经又敲了字，这一轮作废
                    }
                    if (error != null) {
                        head.setText(UiUtils.rootMessage(error));
                        foot.setText("");
                        list.setItems(FXCollections.observableArrayList());
                        return;
                    }
                    render(needle, rows);
                }));
    }

    /** 后台线程：建索引（只建一次）、匹配、排名。这里不碰任何 JavaFX 对象。 */
    private List<Row> collect(String needle) {
        List<Row> rows = new ArrayList<>();
        int scanned = 0;
        int busy = 0;
        int offline = 0;

        for (ConnectionConfig config : context.registry().listAll()) {
            if (!context.isConnected(config.id())) {
                offline++;
                continue;
            }
            DbSession session = context.sessionFor(config.id());
            if (session == null || session.connection().isClosed()) {
                offline++;
                continue;
            }
            if (session.connection().isBusy()) {
                // 一条连接同时只服务一个查询。用户正跑着别的语句，搜索让路
                busy++;
                continue;
            }
            for (SchemaInfo schema : safeSchemas(session)) {
                List<ObjectIndex.Entry> index = indexOf(session, schema.name());
                scanned++;
                for (ObjectIndex.Hit hit : ObjectIndex.search(index, needle, LIMIT)) {
                    TableInfo owner = findTable(session, schema.name(), hit.entry().table());
                    if (owner == null) {
                        continue; // 列在，表却列不出来（权限差异）：列出来也点不开
                    }
                    rows.add(new Row(hit, session, owner));
                }
            }
        }
        rows.sort((a, b) -> ObjectIndex.ranking().compare(a.hit(), b.hit()));
        if (rows.size() > LIMIT) {
            rows = new ArrayList<>(rows.subList(0, LIMIT));
        }
        this.lastScanned = scanned;
        this.lastBusy = busy;
        this.lastOffline = offline;
        return rows;
    }

    private int lastScanned;
    private int lastBusy;
    private int lastOffline;

    /** 取（必要时先建）某个库的索引。 */
    private List<ObjectIndex.Entry> indexOf(DbSession session, String schema) {
        String key = session.config().id() + "/" + schema;
        return indexes.computeIfAbsent(key, k -> {
            List<ObjectIndex.Entry> entries = new ArrayList<>();
            try {
                entries.addAll(ObjectIndex.fromTables(session.tables(schema)));
            } catch (RuntimeException ignored) {
                // 读不到表列表就只剩列，能搜多少算多少
            }
            try {
                entries.addAll(ObjectIndex.fromColumns(
                        session.connection().listColumns(schema)));
            } catch (RuntimeException ignored) {
                // 权限不足时降级成「只搜表名」，而不是整个搜索坏掉
            }
            return entries;
        });
    }

    private TableInfo findTable(DbSession session, String schema, String name) {
        for (TableInfo t : safeTables(session, schema)) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    private static List<SchemaInfo> safeSchemas(DbSession session) {
        try {
            return session.schemas();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<TableInfo> safeTables(DbSession session, String schema) {
        try {
            return session.tables(schema);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void render(String needle, List<Row> rows) {
        list.setItems(FXCollections.observableArrayList(rows));
        if (!rows.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }

        head.setText(rows.isEmpty()
                ? "没找到「" + needle + "」"
                : rows.size() + " 项匹配"
                        + (rows.size() >= LIMIT ? "（只显示前 " + LIMIT + " 条）" : ""));

        StringBuilder note = new StringBuilder("已扫 ").append(lastScanned).append(" 个库");
        if (lastBusy > 0) {
            note.append(" · ").append(lastBusy).append(" 条连接正忙，跳过");
        }
        if (lastOffline > 0) {
            note.append(" · ").append(lastOffline).append(" 条未连接，不在范围内");
        }
        foot.setText(note.toString());
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int index = list.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod(index + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void openSelected() {
        Row row = list.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        onOpen.accept(row.session(), row.table());
    }

    /**
     * 只定位，不打开。
     *
     * <p>刻意<b>不</b>挂在「选中项变化」上：那样上下键翻结果时每翻一条就退出搜索态，
     * 结果列表当场消失，根本翻不动。要动手点一下，才算表达了「就是它」。
     */
    private void revealSelected() {
        Row row = list.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        onReveal.accept(row.session(), row.table());
    }
}
