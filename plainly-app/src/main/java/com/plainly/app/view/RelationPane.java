package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 外键与触发器的查看页。
 *
 * <p>这两样东西平时看不见，出事时却正是原因所在：一条 UPDATE 报出一张根本没提到的表，
 * 多半是触发器；一行数据删不掉，多半是别人拿外键指着它。
 * 所以它们不该只存在于 DDL 文本里，得单列出来能一眼扫过。
 *
 * <p>只读。改触发器和外键要写 DDL，和结构设计器是两码事，这里不掺和。
 */
public class RelationPane extends VBox {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;

    private final TableView<ForeignKeyInfo> outgoing = new TableView<>();
    private final TableView<ForeignKeyInfo> incoming = new TableView<>();
    /**
     * 触发器按「时机 + 事件」分组。
     *
     * <p>平铺成一张表时，六种触发器混在一起按名字排；而看触发器时想问的往往是
     * 「往这张表插入的时候会跑什么」——那是个分类问题，不是排序问题。
     * 所以分组：父行是 BEFORE INSERT 这样的分类，子行才是具体的触发器。
     */
    private final TreeTableView<TriggerNode> triggers = new TreeTableView<>();
    private final TextArea triggerBody = new TextArea();
    /** 触发器列表为空时那句解释：是读不到，还是真的没有。 */
    private final Label triggerNote = UiUtils.label("", "hint");

    /** 压成一行用的正则。写成常量是为了避开源码里的反斜杠转义。 */
    private static final String ONE_LINE = "\\s+";

    private final boolean foreignKeys;
    private boolean loaded;

    /** 树上的一行：分类行的 {@code trigger} 为 null。 */
    private record TriggerNode(String label, TriggerInfo trigger) {
    }

    /** @param foreignKeys true 看外键，false 看触发器 */
    public RelationPane(AppContext context, DbSession session, String schema, String table,
                        boolean foreignKeys) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        this.foreignKeys = foreignKeys;

        setSpacing(0);
        getChildren().add(foreignKeys ? buildForeignKeys() : buildTriggers());
    }

    /** 当前选中的触发器；没选、选中的是分类行、或者这是外键页时都返回 null。 */
    public TriggerInfo selectedTrigger() {
        if (foreignKeys) {
            return null;
        }
        TreeItem<TriggerNode> item = triggers.getSelectionModel().getSelectedItem();
        return item == null || item.getValue() == null ? null : item.getValue().trigger();
    }

    /** 切到这一页才去取——外键和触发器的元数据查询在大库上并不便宜。 */
    public void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        reload();
    }

    public void reload() {
        if (foreignKeys) {
            context.queryService().submit(() -> List.of(
                            merge(session.connection().listForeignKeys(schema, table),
                                    virtual(true)),
                            merge(session.connection().listReferencingKeys(schema, table),
                                    virtual(false))))
                    .whenComplete((both, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                    "读取外键失败", error);
                            return;
                        }
                        outgoing.setItems(FXCollections.observableArrayList(both.get(0)));
                        incoming.setItems(FXCollections.observableArrayList(both.get(1)));
                    }));
        } else {
            context.queryService()
                    .submit(() -> session.connection().listTriggersDetailed(schema, table))
                    .whenComplete((listing, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                    "读取触发器失败", error);
                            return;
                        }
                        showGrouped(listing.triggers());
                        showTriggerDiagnosis(listing);
                        triggerBody.clear();
                    }));
        }
    }

    // ------------------------------------------------------------------ 外键

    /**
     * 本机标注的虚拟外键。
     *
     * <p>和数据库里读出来的物理外键摆在同一张表里，但「来源」那一列会写明是虚拟的——
     * 混在一起看不出区别的话，用户会以为库里真有这条约束，进而以为脏数据插不进来。
     */
    private List<ForeignKeyInfo> virtual(boolean outgoingDirection) {
        try {
            var store = context.virtualKeys();
            var keys = outgoingDirection
                    ? store.listOutgoing(session.config().id(), schema, table)
                    : store.listIncoming(session.config().id(), schema, table);
            return keys.stream()
                    .map(com.plainly.core.store.VirtualKeyStore.VirtualKey::toForeignKey)
                    .toList();
        } catch (RuntimeException e) {
            // 本机标注读不出来不该让整个外键页打不开——物理外键才是这一页的主体
            return List.of();
        }
    }

    private static List<ForeignKeyInfo> merge(List<ForeignKeyInfo> real,
                                              List<ForeignKeyInfo> virtual) {
        List<ForeignKeyInfo> out = new ArrayList<>(real);
        out.addAll(virtual);
        return out;
    }

    private SplitPane buildForeignKeys() {
        setupKeyTable(outgoing, true);
        setupKeyTable(incoming, false);

        VBox top = section("本表的外键", "本表的列指向别的表", outgoing);
        VBox bottom = section("指向本表的外键", "别的表拿外键指着本表——删数据前最该看这一栏", incoming);

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void setupKeyTable(TableView<ForeignKeyInfo> view, boolean isOutgoing) {
        view.getStyleClass().add("data-grid");
        view.setPlaceholder(UiUtils.label(isOutgoing ? "本表没有外键" : "没有别的表指向本表", "hint"));
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        view.getColumns().add(column("名称", 180, ForeignKeyInfo::name));
        view.getColumns().add(column(isOutgoing ? "本表的列" : "对方的列", 160,
                k -> String.join(", ", k.columns())));
        view.getColumns().add(column(isOutgoing ? "指向" : "来自", 220,
                k -> qualify(isOutgoing ? k.refSchema() : k.schema(),
                        isOutgoing ? k.refTable() : k.table())));
        view.getColumns().add(column(isOutgoing ? "对方的列" : "本表的列", 160,
                k -> String.join(", ", k.refColumns())));
        view.getColumns().add(column("更新时", 110, ForeignKeyInfo::onUpdate));
        view.getColumns().add(column("删除时", 110, ForeignKeyInfo::onDelete));
        // 虚拟外键在数据库上<b>没有任何约束效力</b>：标了 A.b → B.id，
        // 插一条对不上的数据照样插得进去。这一列就是为了不让人把两者当成一回事
        view.getColumns().add(column("来源", 110,
                k -> com.plainly.core.store.VirtualKeyStore.isVirtual(k)
                        ? "本机标注 · 无约束" : "数据库约束"));
    }

    private static String qualify(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    // ---------------------------------------------------------------- 触发器

    private SplitPane buildTriggers() {
        triggers.getStyleClass().addAll("data-grid", "trigger-tree");
        triggers.setShowRoot(false);
        triggers.setPlaceholder(UiUtils.label("这张表没有触发器", "hint"));
        triggers.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
        triggers.getColumns().add(treeColumn("分类 / 名称", 300, TriggerNode::label));
        triggers.getColumns().add(treeColumn("动作", 420,
                n -> n.trigger() == null ? "" : flatten(n.trigger().action())));

        // 触发器体动辄几十行，列表里只能挤成一行，选中之后在下面看全文
        triggers.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            TriggerInfo picked = is == null || is.getValue() == null
                    ? null : is.getValue().trigger();
            triggerBody.setText(picked == null || picked.action() == null ? "" : picked.action());
        });

        triggerBody.setEditable(false);
        triggerBody.getStyleClass().add("ddl-area");

        // 这一家不能用 SQL 写触发器时，把原因写在这儿。
        // 工具条上只是把按钮灰掉——灰掉而不说为什么，用户只会以为是软件坏了；
        // 而这句话有四十来字，放工具条上会把它撑爆，这里才是它该待的地方
        String why = session.connection().dialect().triggerUnsupportedReason();
        String hint = why == null
                ? "写入这张表时会连带跑起来的语句"
                : "写入这张表时会连带跑起来的语句 · " + why;

        triggerNote.setWrapText(true);
        triggerNote.setVisible(false);
        triggerNote.setManaged(false);

        VBox top = section("触发器", hint, triggers);
        top.getChildren().add(1, triggerNote);
        VBox bottom = section("触发器体", "选中上面一条看全文", triggerBody);

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    /**
     * 列表为空时，说清楚到底是「没有」还是「读不到」。
     *
     * <h2>为什么这一条值得单独做</h2>
     * 用户刚建完一个触发器、回到列表看到空的，他唯一能得出的结论是「创建失败了」——
     * 而真实原因可能是权限不够、或者这一家的数据字典视图和我们发的那条语句对不上。
     * 空列表把这两件完全不同的事显示成了同一个样子。
     *
     * <p>读不到时显示原因；确实读到了 0 条时，把<b>实际发出去的那条语句</b>摆出来——
     * 「这张表没有触发器」这个结论才是可核对的，用户可以拿它到别的客户端上跑一遍。
     */
    private void showTriggerDiagnosis(DbObjects.TriggerListing listing) {
        if (listing.problem() != null) {
            triggerNote.setText(listing.problem()
                    + (listing.query() == null ? "" : System.lineSeparator() + listing.query()));
            triggerNote.getStyleClass().setAll("status-error");
        } else if (listing.triggers().isEmpty() && listing.query() != null) {
            triggerNote.setText("这张表没有触发器。查询用的是："
                    + System.lineSeparator() + listing.query());
            triggerNote.getStyleClass().setAll("hint", "mono");
        } else {
            triggerNote.setVisible(false);
            triggerNote.setManaged(false);
            return;
        }
        triggerNote.setVisible(true);
        triggerNote.setManaged(true);
    }

    /**
     * 按分类摆进树里。
     *
     * <p>六个分类固定顺序（BEFORE 三个、AFTER 三个），<b>空的不显示</b>——
     * 一张只有一个触发器的表，摆出五个空分类只会让人多找一遍。
     * 认不出分类的归到「未分类」排在最后：至少还看得见，不会凭空消失。
     */
    private void showGrouped(List<TriggerInfo> list) {
        TreeItem<TriggerNode> root = new TreeItem<>();
        java.util.LinkedHashMap<String, List<TriggerInfo>> groups = new java.util.LinkedHashMap<>();
        for (DbObjects.TriggerTiming timing : DbObjects.TriggerTiming.values()) {
            for (DbObjects.TriggerEvent event : DbObjects.TriggerEvent.values()) {
                groups.put(timing.name() + " " + event.name(), new ArrayList<>());
            }
        }
        groups.put("未分类", new ArrayList<>());
        for (TriggerInfo one : list) {
            groups.computeIfAbsent(one.category(), k -> new ArrayList<>()).add(one);
        }

        for (var entry : groups.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            TreeItem<TriggerNode> group = new TreeItem<>(
                    new TriggerNode(entry.getKey() + "（" + entry.getValue().size() + "）", null));
            group.setExpanded(true);
            for (TriggerInfo one : entry.getValue()) {
                group.getChildren().add(new TreeItem<>(new TriggerNode(one.name(), one)));
            }
            root.getChildren().add(group);
        }
        triggers.setRoot(root);
    }

    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll(ONE_LINE, " ").trim();
    }

    private <T> TreeTableColumn<T, String> treeColumn(String title, double width,
                                                      Function<T, String> value) {
        TreeTableColumn<T, String> col = new TreeTableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getValue() == null ? "" : value.apply(c.getValue().getValue())));
        return col;
    }

    // ------------------------------------------------------------------ 公共

    private <T> TableColumn<T, String> column(String title, double width,
                                              Function<T, String> value) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    private VBox section(String title, String hint, javafx.scene.Node body) {
        Label label = UiUtils.label(title, "section-label");
        Label note = UiUtils.label(hint, "hint");
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox box = UiUtils.column(0, UiUtils.row(10, label, note), body);
        box.setPadding(new Insets(8, 10, 8, 10));
        return box;
    }
}
