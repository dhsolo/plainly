package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 左侧连接树：连接 → 库 → 表/视图。展开时才去查元数据。 */
public class ConnectionTreePane extends VBox {

    /** 树节点承载的数据。 */
    public sealed interface NodeData {
        /**
         * 一个连接分组，树上表现为可折叠的目录。
         *
         * @param count 组里有几条连接。折起来之后这是唯一还看得见的信息
         */
        record FolderNode(String name, int count) implements NodeData {
        }

        record ConnectionNode(ConnectionConfig config) implements NodeData {
        }

        record SchemaNode(ConnectionConfig config, SchemaInfo schema) implements NodeData {
        }

        record GroupNode(ConnectionConfig config, String schema, ObjectKind kind) implements NodeData {
        }

        record TableNode(ConnectionConfig config, String schema, TableInfo table) implements NodeData {
        }
    }

    private final AppContext context;
    private final TreeView<NodeData> tree = new TreeView<>();

    private BiConsumer<DbSession, TableInfo> onOpenTable = (s, t) -> { };
    private Consumer<ConnectionConfig> onEditConnection = c -> { };
    private Consumer<String> statusSink = s -> { };
    private SidebarSearch search;
    private Label treeHeader;

    /**
     * 正在取数、还没展开的节点。
     *
     * <p>两个用处：一是这一行要显示「连接中…」，二是双击手快连点两下时不重复发请求。
     */
    private final java.util.Set<TreeItem<NodeData>> loading = new java.util.HashSet<>();

    public ConnectionTreePane(AppContext context) {
        this.context = context;
        getStyleClass().add("sidebar");
        setMinWidth(246);
        setPrefWidth(246);

        tree.setShowRoot(false);
        tree.setCellFactory(v -> new NodeCell());
        /*
         * 告诉全局复制：树上的一行该复制成什么。
         *
         * 不声明的话只有两条退路，都不对：条目的 toString() 是
         * TableNode[config=..., schema=..., table=...] 这种给调试看的东西；
         * 从渲染出的图形里抓字，会把类型色块上的字母和「在线」角标一起带出来，
         * 变成「M 192.168.1.7 在线」。
         *
         * 用户选中一张表按 Ctrl+C，要的就是表名。
         */
        com.plainly.app.ui.CopySelection.provideTextFor(tree, item -> {
            if (item instanceof NodeData.TableNode n) {
                return n.table().name();
            }
            if (item instanceof NodeData.SchemaNode n) {
                return n.schema().name();
            }
            if (item instanceof NodeData.ConnectionNode n) {
                return n.config().name();
            }
            if (item instanceof NodeData.FolderNode n) {
                return n.name();
            }
            if (item instanceof NodeData.GroupNode n) {
                return n.kind().label();
            }
            return null;
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activateSelected();
            }
        });

        search = new SidebarSearch(context);
        // 双击 / 回车：先在树上定位，再打开表。
        // 顺序有讲究：先定位，用户回头看左边就知道这张表挂在哪个库下面
        search.setOnOpen((session, table) -> {
            revealFromSearch(session, table);
            onOpenTable.accept(session, table);
        });
        // 单击：只定位，不打开
        search.setOnReveal(this::revealFromSearch);
        // 搜索态和树互斥：搜的时候左边整块是结果，清空就回到树。
        // 两个都不隐藏的话，本来就窄的侧栏会被劈成两半，两边都不够用
        search.setOnActiveChanged(active -> {
            treeHeader.setVisible(!active);
            treeHeader.setManaged(!active);
            tree.setVisible(!active);
            tree.setManaged(!active);
            VBox.setVgrow(search, active ? Priority.ALWAYS : Priority.NEVER);
        });

        treeHeader = UiUtils.label("连接", "section-label");
        getChildren().addAll(search, treeHeader, tree);
        reload();
    }

    public void setOnOpenTable(BiConsumer<DbSession, TableInfo> handler) {
        this.onOpenTable = handler;
    }

    public void setOnEditConnection(Consumer<ConnectionConfig> handler) {
        this.onEditConnection = handler;
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink;
    }

    /**
     * 树上选中的目标。
     *
     * @param schema null 表示只选到了连接层
     * @param table  选中的是一张表时才有；「新建查询」据此预置一句 SELECT
     */
    public record Target(ConnectionConfig config, String schema, String table) {
    }

    /**
     * 当前选中项对应的连接与库。
     *
     * <p>选中表或分组时，取它所属的库——用户点着 shop_prod 下的某张表时，
     * 心里想的目标就是 shop_prod，不该再去猜。
     */
    public Target selectedTarget() {
        TreeItem<NodeData> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) {
            return null;
        }
        NodeData data = item.getValue();
        if (data instanceof NodeData.FolderNode) {
            // 目录本身不指向任何库。返回组里第一条连接会让「新建查询」
            // 悄悄落到一个用户没选中的连接上
            return null;
        }
        if (data instanceof NodeData.ConnectionNode n) {
            return new Target(n.config(), null, null);
        }
        if (data instanceof NodeData.SchemaNode n) {
            return new Target(n.config(), n.schema().name(), null);
        }
        if (data instanceof NodeData.GroupNode n) {
            return new Target(n.config(), n.schema(), null);
        }
        if (data instanceof NodeData.TableNode n) {
            // 视图也当表用：SELECT * FROM 一个视图同样成立
            return new Target(n.config(), n.schema(), n.table().name());
        }
        return null;
    }

    /** 重建整棵树。新增/删除连接后调用。 */
    /** 让 Ctrl+P 之类的快捷键把焦点送到搜索框。 */
    public void focusSearch() {
        search.focus();
    }

    public void reload() {
        // 结构可能变了，搜索索引跟着作废——不然搜出来的还是旧结构
        if (search != null) {
            search.invalidate();
        }
        TreeItem<NodeData> root = new TreeItem<>();
        for (java.util.Map.Entry<String, List<ConnectionConfig>> entry
                : byGroup(context.registry().listAll()).entrySet()) {
            if (entry.getKey().isEmpty()) {
                // 没分组的仍旧摆在最外层，不塞进一个「其它」目录里：
                // 那会让只用两三条连接、从不分组的人凭空多出一层要点开的东西
                entry.getValue().forEach(c -> root.getChildren().add(connectionItem(c)));
            } else {
                root.getChildren().add(folderItem(entry.getKey(), entry.getValue()));
            }
        }
        tree.setRoot(root);
    }

    /**
     * 按分组归拢，保持注册表给的顺序。
     *
     * <p>返回的 map 里，空串那一项是「没有分组」。用 LinkedHashMap 是必需的：
     * 注册表已经按「分组优先」排过序了（见 {@code ConnectionRegistry.listAll}），
     * 换成 HashMap 会把这个顺序打乱，于是目录每次启动都在不同的位置。
     */
    private java.util.Map<String, List<ConnectionConfig>> byGroup(List<ConnectionConfig> all) {
        java.util.Map<String, List<ConnectionConfig>> grouped = new java.util.LinkedHashMap<>();
        for (ConnectionConfig config : all) {
            String group = config.group() == null ? "" : config.group().trim();
            grouped.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(config);
        }
        return grouped;
    }

    /**
     * 一个分组目录。
     *
     * <p>默认展开：分组是为了归拢，不是为了藏起来。启动时全折着，用户得先点开
     * 每一个才能找到自己那条连接——那比不分组更慢。
     */
    private TreeItem<NodeData> folderItem(String name, List<ConnectionConfig> members) {
        TreeItem<NodeData> item =
                new TreeItem<>(new NodeData.FolderNode(name, members.size()));
        members.forEach(c -> item.getChildren().add(connectionItem(c)));
        item.setExpanded(true);
        return item;
    }

    private TreeItem<NodeData> connectionItem(ConnectionConfig config) {
        TreeItem<NodeData> item = new TreeItem<>(new NodeData.ConnectionNode(config));
        // 占位子节点：有它才会显示展开箭头，展开时替换成真内容
        item.getChildren().add(placeholder());
        item.expandedProperty().addListener((obs, was, is) -> {
            if (is && isPlaceholder(item)) {
                deferExpand(item, token -> loadSchemas(item, config, token));
            }
        });
        return item;
    }

    /**
     * 先把数取回来，再展开。
     *
     * <p>直接展开的话，用户先看到一行空白被顶出来——那是占位节点，它只是为了让展开箭头存在——
     * 一次数据库往返之后才换成真正的库列表。于是下面所有连接被推了两次，
     * 中间那一下就是「跳」。占位节点省不掉，但它不该被看见：把展开退回去，
     * 等孩子换成真的再展开，位移就只剩一次。
     */
    private void deferExpand(TreeItem<NodeData> item, java.util.function.LongConsumer load) {
        item.setExpanded(false);
        if (!loading.add(item)) {
            // 已经在路上了。手快连点两下不该发两次请求，那会连出两条会话来。
            // 「重新连接」是例外，它会先把这个标记清掉——见 reconnect
            return;
        }
        tree.refresh();
        load.accept(nextGeneration(item));
    }

    /**
     * 每个节点的加载代次。
     *
     * <h2>为什么需要它</h2>
     * 一次加载可能<b>永远回不来</b>（连接断在「对端不回也不断」的状态）。
     * 我们给它加了 30 秒上限，但上限到期时那个回调仍然会跑，而它做的事是
     * 「把孩子换回占位节点、弹一个错误框」。
     *
     * <p>如果用户在这 30 秒里点了「重新连接」并且已经连上了，那个迟到的回调
     * 就会<b>把刚加载好的一整棵子树清掉</b>，再配一个看不懂的错误框——
     * 用户看到的是「连上了，然后自己没了」。
     *
     * <p>所以每次开始加载都记一个代次，回调落地前先问一句「我还是最新的那次吗」。
     * 不是最新的就什么都不做：它的结果已经没人要了。
     */
    private final java.util.Map<TreeItem<NodeData>, Long> generation = new java.util.HashMap<>();

    private long nextGeneration(TreeItem<NodeData> item) {
        long next = generation.getOrDefault(item, 0L) + 1;
        generation.put(item, next);
        return next;
    }

    /** 这一次加载还算数吗。只在界面线程上调用。 */
    private boolean isCurrent(TreeItem<NodeData> item, long token) {
        return generation.getOrDefault(item, 0L) == token;
    }

    /**
     * 后台探一次活，死了就把会话丢掉并刷新树。
     *
     * <p>不能在这里同步探：这段跑在界面线程上，而探活要一次网络往返，
     * 断线时还要等满超时——整个窗口会僵在那儿。
     *
     * <p>也不能一失败就无条件丢掉会话：权限不足、库名写错、语法错误都会走到
     * 同一个失败分支，那些情况下连接好好的，丢掉它只会让用户莫名其妙地掉线。
     */
    private void forgetIfDead(ConnectionConfig config) {
        context.dropIfDead(config.id(), () -> Platform.runLater(() -> {
            tree.refresh();
            statusSink.accept(config.name() + " 的连接已断开，右键「连接」可重连");
        }));
    }

    /**
     * 给报错补一句可操作的话。
     *
     * <p>超时那一条尤其需要：{@code TimeoutException} 的 message 是 null，
     * 原样弹出来用户只会看到一个空白的错误框。
     */
    private Throwable describe(Throwable error, ConnectionConfig config) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof java.util.concurrent.TimeoutException) {
            return new com.plainly.driver.DbException(
                    "等了 " + LOAD_TIMEOUT_SECONDS + " 秒没有响应。"
                            + System.lineSeparator()
                            + "多半是这条连接已经断了而对端没有告知（网络中断、防火墙清理空闲连接）。"
                            + System.lineSeparator()
                            + "在 " + config.name() + " 上右键「重新连接」再试一次。");
        }
        return error;
    }

    private TreeItem<NodeData> placeholder() {
        return new TreeItem<>(null);
    }

    private boolean isPlaceholder(TreeItem<NodeData> item) {
        return item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null;
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 一次加载最多等多久。
     *
     * <p>为什么必须有这个上限：连接断在「对端不回也不断」那种状态时
     * （网络中断、防火墙丢包），socket 上的读没有任何超时，这个任务会<b>永远</b>挂着。
     * 挂着的后果不只是这一次没反应——{@code loading} 里的这一项永远不会被移除，
     * 而 {@link #deferExpand} 看到它就直接返回，于是<b>此后每一次展开都被静默忽略</b>，
     * 只能重启程序。
     *
     * <p>{@code orTimeout} 不能真的把那个卡住的线程弄醒（谁也不能），
     * 但它能让界面从这个状态里出来：报一句话，把 loading 标记清掉，让用户能再试。
     */
    private static final int LOAD_TIMEOUT_SECONDS = 30;

    private void loadSchemas(TreeItem<NodeData> item, ConnectionConfig config, long token) {
        statusSink.accept("正在连接 " + config.name() + " …");
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return session.schemas();
        }).orTimeout(LOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((schemas, error) -> Platform.runLater(() -> {
            if (!isCurrent(item, token)) {
                // 这一次已经被「重新连接」取代了。它的结果——不管成功还是超时——
                // 都不能再往界面上写，否则会盖掉后来那次的结果
                return;
            }
            loading.remove(item);
            if (error != null) {
                // 占位节点要放回去：清空孩子等于把展开箭头也拿掉，
                // 那之后这条连接再也点不开，只能重启——而连不上多半是临时的
                item.getChildren().setAll(placeholder());
                item.setExpanded(false);
                tree.refresh();
                // 连接可能已经死了。后台探一次，死了就把会话丢掉，
                // 这样树上那个「在线」角标才会消失、菜单里才会变成「连接」
                forgetIfDead(config);
                UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                        "连接 " + config.name() + " 失败", describe(error, config));
                statusSink.accept("连接失败");
                return;
            }
            List<TreeItem<NodeData>> children = new java.util.ArrayList<>();
            for (SchemaInfo schema : schemas) {
                children.add(schemaItem(config, schema));
            }
            // 先换孩子再展开：这样只有一次位移，中间不会闪过那行空白
            item.getChildren().setAll(children);
            item.setExpanded(true);
            statusSink.accept("已连接 " + config.name());
            tree.refresh();
        }));
    }

    private TreeItem<NodeData> schemaItem(ConnectionConfig config, SchemaInfo schema) {
        TreeItem<NodeData> item = new TreeItem<>(new NodeData.SchemaNode(config, schema));
        item.getChildren().add(placeholder());
        item.expandedProperty().addListener((obs, was, is) -> {
            if (is && isPlaceholder(item)) {
                deferExpand(item, token -> loadObjects(item, config, schema.name(), token));
            }
        });
        if (schema.isDefault()) {
            item.setExpanded(true);
        }
        return item;
    }

    private void loadObjects(TreeItem<NodeData> item, ConnectionConfig config, String schema,
                             long token) {
        context.queryService().submit(() -> context.openSession(config).tables(schema))
                .orTimeout(LOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((tables, error) -> Platform.runLater(() -> {
                    if (!isCurrent(item, token)) {
                        return; // 见 loadSchemas 里同样的判断
                    }
                    loading.remove(item);
                    if (error != null) {
                        item.getChildren().setAll(placeholder());
                        item.setExpanded(false);
                        tree.refresh();
                        forgetIfDead(config);
                        UiUtils.showError(getScene().getWindow(), "读取 " + schema + " 失败",
                                describe(error, config));
                        return;
                    }
                    item.getChildren().clear();
                    addGroup(item, config, schema, tables, ObjectKind.TABLE);
                    addGroup(item, config, schema, tables, ObjectKind.VIEW);
                    // 后面这三类不是每家都有：addGroup 里空分组会直接跳过，
                    // 所以 MySQL 上不会冒出一个空的「序列」，PostgreSQL 上也不会有「事件」
                    addGroup(item, config, schema, tables, ObjectKind.MATERIALIZED_VIEW);
                    addGroup(item, config, schema, tables, ObjectKind.SEQUENCE);
                    addGroup(item, config, schema, tables, ObjectKind.EVENT);
                    // 空库就让孩子空着——不能放回占位节点：展开它又会触发一次加载，
                    // 加载完还是空，如此往复，转成死循环
                    item.setExpanded(true);
                    tree.refresh();
                }));
    }

    private void addGroup(TreeItem<NodeData> parent, ConnectionConfig config, String schema,
                          List<TableInfo> all, ObjectKind kind) {
        List<TableInfo> filtered = all.stream().filter(t -> t.kind() == kind).toList();
        if (filtered.isEmpty()) {
            return;
        }
        TreeItem<NodeData> group = new TreeItem<>(new NodeData.GroupNode(config, schema, kind));
        filtered.forEach(t ->
                group.getChildren().add(new TreeItem<>(new NodeData.TableNode(config, schema, t))));
        group.setExpanded(kind == ObjectKind.TABLE && filtered.size() <= 40);
        parent.getChildren().add(group);
    }

    // ------------------------------------------------------------------ 定位

    /** 从搜索结果跳到树上：先退出搜索态（树被结果盖着时定位是看不见的），再展开过去。 */
    private void revealFromSearch(DbSession session, TableInfo table) {
        search.close();
        reveal(session.config(), table.schema(), table.name());
    }


    /**
     * 把树展开到某张表，选中它，滚到看得见的地方。
     *
     * <h2>为什么要一层层链起来</h2>
     * 这棵树是<b>懒加载</b>的：连接下面挂的是一个占位节点，展开时才去读库列表；
     * 库下面同样，展开时才去读表列表。每一层都要一次数据库往返，中间隔着
     * {@code Platform.runLater}。所以不能写成「展开三层然后选中」——
     * 那样第二层执行时第一层的孩子还没长出来，一路扑空。
     *
     * <p>这里的办法是：每层先看孩子在不在，在就直接往下走；不在就挂一个<b>一次性</b>的
     * 监听器等它长出来，长出来再往下走。层与层之间由回调串起来。
     */
    public void reveal(ConnectionConfig config, String schema, String tableName) {
        TreeItem<NodeData> root = tree.getRoot();
        if (root == null) {
            return;
        }
        TreeItem<NodeData> connection = findConnection(root, config.id());
        if (connection == null) {
            statusSink.accept("树上找不到连接 " + config.name());
            return;
        }

        descend(connection,
                d -> d instanceof NodeData.SchemaNode n && n.schema().name().equals(schema),
                schemaItem -> {
                    schemaItem.setExpanded(true);
                    whenChildrenReady(schemaItem, () -> {
                        // 表和视图分在两个组里，挨个组去找
                        for (TreeItem<NodeData> group : schemaItem.getChildren()) {
                            TreeItem<NodeData> table = find(group, d ->
                                    d instanceof NodeData.TableNode n
                                            && n.table().name().equalsIgnoreCase(tableName));
                            if (table != null) {
                                group.setExpanded(true);
                                select(table);
                                return;
                            }
                        }
                        statusSink.accept("在 " + schema + " 里找不到 " + tableName);
                    });
                },
                () -> statusSink.accept("在 " + config.name() + " 里找不到库 " + schema));
    }

    /** 选中并滚过去，同时把焦点交给树——用户接下来多半要用方向键继续走。 */
    private void select(TreeItem<NodeData> item) {
        tree.getSelectionModel().select(item);
        int row = tree.getRow(item);
        if (row >= 0) {
            tree.scrollTo(Math.max(0, row - 3));
        }
        tree.requestFocus();
    }

    /**
     * 展开 {@code parent}，等它的孩子就位，再在孩子里按 {@code match} 找一个交给 {@code next}。
     *
     * @param missing 孩子都到齐了却没找到时调用
     */
    private void descend(TreeItem<NodeData> parent,
                         java.util.function.Predicate<NodeData> match,
                         java.util.function.Consumer<TreeItem<NodeData>> next,
                         Runnable missing) {
        parent.setExpanded(true);
        whenChildrenReady(parent, () -> {
            TreeItem<NodeData> hit = find(parent, match);
            if (hit == null) {
                missing.run();
            } else {
                next.accept(hit);
            }
        });
    }

    /**
     * 孩子就位之后跑一次 {@code action}。
     *
     * <p>已经是真内容就立刻跑；还挂着占位节点就挂一个一次性监听器等着。
     * 监听器必须自己摘掉——不摘的话，以后每次刷新这个节点都会再跑一遍，
     * 用户会莫名其妙地被拽到一个几分钟前搜过的表上。
     */
    private void whenChildrenReady(TreeItem<NodeData> parent, Runnable action) {
        if (!isPlaceholder(parent) && !parent.getChildren().isEmpty()) {
            action.run();
            return;
        }
        javafx.collections.ListChangeListener<TreeItem<NodeData>> once =
                new javafx.collections.ListChangeListener<>() {
                    private boolean done;

                    @Override
                    public void onChanged(Change<? extends TreeItem<NodeData>> c) {
                        if (done || isPlaceholder(parent) || parent.getChildren().isEmpty()) {
                            return; // 还在加载，等下一次变化
                        }
                        done = true;
                        parent.getChildren().removeListener(this);
                        // 关键的一步：孩子是一条条加进去的，这个监听器在<b>第一条</b>
                        // 就会被叫醒。此时列表里只有一个库，去找第二个库必然扑空——
                        // 而且不报错，只是什么都没发生。
                        // 整批添加都发生在同一轮 runLater 里，所以再排一次 runLater，
                        // 就落在那一轮之后，那时才是真的到齐了。
                        Platform.runLater(action);
                    }
                };
        parent.getChildren().addListener(once);
    }

    /**
     * 按 id 找一条连接，顶层和分组目录里都找。
     *
     * <p>不能只看根的直接孩子：一旦这条连接归了组，它就挂在目录下面一层。
     * 找不到的后果是搜索结果双击之后「什么也没发生」——最难查的那种失效，
     * 因为它只在分了组的用户身上出现。
     */
    private TreeItem<NodeData> findConnection(TreeItem<NodeData> root, String id) {
        for (TreeItem<NodeData> child : root.getChildren()) {
            NodeData data = child.getValue();
            if (data instanceof NodeData.ConnectionNode n && n.config().id().equals(id)) {
                return child;
            }
            if (data instanceof NodeData.FolderNode) {
                TreeItem<NodeData> hit = findConnection(child, id);
                if (hit != null) {
                    // 目录折起来的时候定位过去也看不见，顺手展开
                    child.setExpanded(true);
                    return hit;
                }
            }
        }
        return null;
    }

    private TreeItem<NodeData> find(TreeItem<NodeData> parent,
                                    java.util.function.Predicate<NodeData> match) {
        for (TreeItem<NodeData> child : parent.getChildren()) {
            if (child.getValue() != null && match.test(child.getValue())) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 交互

    /** 编辑一个已有的视图定义。 */
    private void editObject(NodeData.TableNode node, ObjectEditorDialog.Kind kind) {
        DbSession session = context.sessionFor(node.config().id());
        if (session == null) {
            return;
        }
        ObjectEditorDialog dialog = new ObjectEditorDialog(context, session,
                node.schema(), kind, node.table().name());
        dialog.setOnApplied(() -> statusSink.accept("已更新" + kind.label()));
        dialog.show(getScene().getWindow());
    }

    /** 在这个库里新建一个视图 / 函数 / 触发器。 */
    private void newObject(ConnectionConfig config, String schemaName,
                           ObjectEditorDialog.Kind kind, TreeItem<NodeData> item) {
        DbSession session = context.sessionFor(config.id());
        if (session == null) {
            return;
        }
        ObjectEditorDialog dialog = new ObjectEditorDialog(context, session,
                schemaName, kind, null);
        dialog.setOnApplied(() -> {
            item.getChildren().setAll(placeholder());
            item.setExpanded(false);
            item.setExpanded(true);
            statusSink.accept("已新建" + kind.label());
        });
        dialog.show(getScene().getWindow());
    }

    /** 往一张表里灌测试数据。 */
    /** 照着这张表建一张新表。 */
    private void copyTable(NodeData.TableNode node) {
        DbSession session = context.sessionFor(node.config().id());
        if (session == null) {
            return;
        }
        CopyTableDialog dialog =
                new CopyTableDialog(context, session, node.schema(), node.table());
        dialog.setOnCopied(() -> {
            // 新表得在树上出现：把这个库的孩子退回占位节点，下次展开重新读
            TreeItem<NodeData> schemaItem = selectedSchemaItem();
            if (schemaItem != null) {
                schemaItem.getChildren().setAll(placeholder());
                schemaItem.setExpanded(false);
                schemaItem.setExpanded(true);
            }
            statusSink.accept("已复制 " + node.table().name());
        });
        dialog.show(getScene().getWindow());
    }

    /**
     * 当前选中项往上找到的那个库节点。
     *
     * <p>不能写死「往上两层」：表挂在「表 / 视图」分组下面，而分组挂在库下面，
     * 层数还会随连接是否分了组而变。一路往上找到库为止才是稳的。
     */
    private TreeItem<NodeData> selectedSchemaItem() {
        TreeItem<NodeData> item = tree.getSelectionModel().getSelectedItem();
        while (item != null && !(item.getValue() instanceof NodeData.SchemaNode)) {
            item = item.getParent();
        }
        return item;
    }

    private void generateData(NodeData.TableNode node) {
        DbSession session = context.sessionFor(node.config().id());
        if (session == null) {
            return;
        }
        try {
            GenerateDataDialog dialog = new GenerateDataDialog(context, session,
                    node.schema(), node.table().name(),
                    session.structure(node.schema(), node.table().name()).columns());
            dialog.setOnFinished(() -> statusSink.accept("已生成测试数据"));
            dialog.show(getScene().getWindow());
        } catch (RuntimeException e) {
            UiUtils.showError(getScene().getWindow(), "读取表结构失败", e);
        }
    }

    /**
     * 在这个库里建一张表。
     *
     * <p>建完把这个库的子树打回未加载状态再展开一次，让新表自然出现——
     * 比手工往树里插一个节点可靠：真实的表名、真实的类型，都由重新读取的元数据说了算。
     */
    private void newTable(ConnectionConfig config, String schemaName, TreeItem<NodeData> item) {
        DbSession session = context.sessionFor(config.id());
        if (session == null) {
            return;
        }
        NewTableDialog dialog = new NewTableDialog(context, session, schemaName);
        dialog.setOnCreated(() -> {
            item.getChildren().setAll(placeholder());
            item.setExpanded(false);
            item.setExpanded(true);
            statusSink.accept("已在 " + schemaName + " 建表");
        });
        dialog.showAndWait(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 双击。
     *
     * <p>这里只管 TreeView 自己不处理的那一类——表要打开。
     * 分支节点的展开／收起交给 TreeView 的默认行为：它已经在双击时翻过一次了，
     * 我们再翻一次，一个双击被翻两回，表现出来正好是「双击上级不展开」。
     *
     * <p>连接和库的展开会自动触发取数（见 {@code expandedProperty} 上的监听），
     * 所以双击一个没连的连接，展开的同时就把连接建起来了。
     */
    private void activateSelected() {
        TreeItem<NodeData> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) {
            return;
        }
        if (item.getValue() instanceof NodeData.TableNode node) {
            DbSession session = context.sessionFor(node.config().id());
            if (session == null) {
                return;
            }
            // 序列和事件没有行可看，双击它们打开的是定义而不是数据网格
            if (!node.table().kind().hasRows()) {
                showObjectDefinition(node);
                return;
            }
            onOpenTable.accept(session, node.table());
        }
    }

    /**
     * 查看一个序列 / 事件 / 物化视图的定义。
     *
     * <p>只读。改它们各家语法差得很远（{@code ALTER SEQUENCE RESTART}、
     * {@code ALTER EVENT}、{@code REFRESH MATERIALIZED VIEW}），
     * 没有真实实例验证过就不发那条语句——这一版只把它们显示出来。
     */
    private void showObjectDefinition(NodeData.TableNode node) {
        DbSession session = context.sessionFor(node.config().id());
        if (session == null) {
            return;
        }
        String kindLabel = node.table().kind().label();
        context.queryService().submit(() -> {
            String sql = session.connection().dialect().objectDefinitionQuery(
                    node.table().kind(), node.schema(), node.table().name());
            if (sql == null) {
                // 说清楚是「这一家读不出来」，不是「这个对象是空的」
                return "这一版读不到 " + session.config().type().displayName()
                        + " 的" + kindLabel + "定义。"
                        + System.lineSeparator() + System.lineSeparator()
                        + "已知的信息：" + System.lineSeparator()
                        + "名称：" + node.table().name() + System.lineSeparator()
                        + "说明：" + node.table().comment();
            }
            String text = session.connection().scalar(sql);
            return text == null || text.isBlank()
                    ? "读到的定义是空的。多半是权限不够，或者这个对象刚被删掉了。" : text;
        }).whenComplete((text, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                        "读取" + kindLabel + "定义失败", error);
                return;
            }
            UiUtils.showInfo(getScene() == null ? null : getScene().getWindow(),
                    kindLabel + " " + node.table().name(), text);
        }));
    }

    // ------------------------------------------------------------------ 树上的动作

    /**
     * 清空一张表。
     *
     * <p>确认框里写的是<b>这一家的真实行为</b>（{@code truncateNote}），不是一句笼统的
     * 「将清空数据」：MySQL 上它是 DDL、不能回滚、自增归零；SQLite 上它其实是 DELETE、
     * 自增不归零。这两件事的后果差得远，用户按之前有权知道自己按的是哪一种。
     *
     * <p>还会先查一遍「谁在引用这张表」——被外键指着的表清不掉，与其让数据库退回一条
     * 看不懂的错误，不如提前说明白。
     */
    private void truncateTable(NodeData.TableNode n) {
        DbSession session = context.sessionFor(n.config().id());
        if (session == null) {
            return;
        }
        String table = n.table().name();
        context.queryService()
                .submit(() -> session.connection().listReferencingKeys(n.schema(), table))
                .whenComplete((refs, error) -> Platform.runLater(() -> {
                    StringBuilder msg = new StringBuilder("表 ")
                            .append(n.schema()).append(".").append(table)
                            .append(" 里的数据会被全部清掉，表结构保留。\n\n")
                            .append(session.connection().dialect().truncateNote());
                    if (error == null && refs != null && !refs.isEmpty()) {
                        java.util.LinkedHashSet<String> from = new java.util.LinkedHashSet<>();
                        refs.forEach(k -> from.add(k.table()));
                        msg.append("\n\n注意：").append(String.join("、", from))
                                .append(" 用外键引用着这张表，多数数据库会因此直接拒绝清空。");
                    }
                    if (!UiUtils.confirm(getScene().getWindow(), "清空表 " + table,
                            msg.toString())) {
                        return;
                    }
                    runDdl(session, n.schema(), session.connection().dialect()
                                    .truncateTableDdl(n.schema(), table),
                            "已清空 " + table, () -> { });
                }));
    }

    /** 删表。同样先看谁在引用它——那些外键会跟着断。 */
    private void dropTable(NodeData.TableNode n) {
        DbSession session = context.sessionFor(n.config().id());
        if (session == null) {
            return;
        }
        String table = n.table().name();
        context.queryService()
                .submit(() -> session.connection().listReferencingKeys(n.schema(), table))
                .whenComplete((refs, error) -> Platform.runLater(() -> {
                    StringBuilder msg = new StringBuilder("表 ")
                            .append(n.schema()).append(".").append(table)
                            .append(" 连同里面的全部数据会被删掉，这条 DDL 撤不回来。");
                    if (error == null && refs != null && !refs.isEmpty()) {
                        java.util.LinkedHashSet<String> from = new java.util.LinkedHashSet<>();
                        refs.forEach(k -> from.add(k.table()));
                        msg.append("\n\n注意：").append(String.join("、", from))
                                .append(" 用外键引用着这张表。数据库多半会拒绝，"
                                        + "或者连带把那些外键也删掉。");
                    }
                    if (!UiUtils.confirm(getScene().getWindow(), "删除表 " + table,
                            msg.toString())) {
                        return;
                    }
                    TreeItem<NodeData> node = tree.getSelectionModel().getSelectedItem();
                    runDdl(session, n.schema(), session.connection().dialect()
                                    .dropTableDdl(n.schema(), table),
                            "已删除 " + table, () -> {
                                session.invalidate();
                                if (node != null && node.getParent() != null) {
                                    node.getParent().getChildren().remove(node);
                                }
                            });
                }));
    }

    /**
     * 连上，或者重连。
     *
     * <p>不管当前以为自己是什么状态，一律：丢掉旧会话 → 把这个节点退回占位 →
     * 展开。展开会触发 {@code loadSchemas}，那边的 {@code openSession}
     * 会真的建一条新连接。
     *
     * <p>「丢掉旧会话」这一步不能省。留着的话，{@code openSession} 拿到它、
     * 探活、发现是死的、再关掉——多绕一圈，而且那一圈要等探活超时。
     * 用户点的是「重新连接」，意思很明确，不必再替他确认一遍。
     */
    /**
     * 这条连接上有没有还没提交的事务；有就先问一句。
     *
     * <p>关掉连接会让未提交的事务被服务端回滚。用户按过「保存」、网格也刷新了，
     * 他没有理由知道那些改动还攥在事务里——只有这里能拦住。
     *
     * @return 可以继续往下做
     */
    private boolean confirmDropPendingTransaction(String connectionId, String name, String action) {
        com.plainly.app.DbSession session = context.sessionFor(connectionId);
        if (session == null || !session.hasPendingTransaction()) {
            return true;
        }
        return UiUtils.confirm(getScene() == null ? null : getScene().getWindow(),
                "还有没提交的事务",
                "「" + name + "」上有未提交的改动。" + action + "之后它们会被回滚掉，找不回来。"
                        + System.lineSeparator() + "确定" + action + "？");
    }

    private void reconnect(ConnectionConfig config, TreeItem<NodeData> item) {
        if (!confirmDropPendingTransaction(config.id(), config.name(), "重新连接")) {
            return;
        }
        context.closeSession(config.id());
        if (item != null) {
            // 上一次可能还挂在那儿回不来。清掉在途标记，否则 deferExpand
            // 会认为「已经在路上了」而直接返回——那正是这个 bug 最难受的一面：
            // 点「重新连接」同样没有反应，只能重启程序。
            //
            // 代次也一并作废：那个迟到的回调醒来时会发现自己已经过期，
            // 不会再来清掉这次加载好的东西
            loading.remove(item);
            nextGeneration(item);
            item.getChildren().setAll(placeholder());
            item.setExpanded(false);
            item.setExpanded(true);
        }
        tree.refresh();
    }

    // ------------------------------------------------------------------ 分组

    /**
     * 把一条连接挪进某个分组。
     *
     * <p>用输入框而不是「已有分组」的下拉：新建分组和挪进已有分组是同一个动作，
     * 拆成两个菜单项只会让人先想「我要建还是要选」。已有的名字写在提示里，
     * 照着敲就是归到同一组——这也是提示存在的理由。
     */
    private void moveToGroup(ConnectionConfig config) {
        List<String> existing = context.registry().listAll().stream()
                .map(ConnectionConfig::group)
                .filter(g -> g != null && !g.isBlank())
                .distinct().sorted().toList();
        String hint = existing.isEmpty()
                ? "还没有分组，敲一个名字就会建出来"
                : "已有分组：" + String.join("、", existing);
        String name = UiUtils.prompt(getScene().getWindow(), "移到分组", hint, config.group());
        if (name != null) {
            setGroup(config, name);
        }
    }

    private void setGroup(ConnectionConfig config, String group) {
        // 注册表里拿到的配置口令字段恒为 null，save 时会 COALESCE 保留库里的密文，
        // 所以改分组不会顺手把已保存的口令清掉
        context.registry().save(config.copy().setGroup(group));
        reload();
        statusSink.accept(group.isBlank()
                ? config.name() + " 已移出分组"
                : config.name() + " 已移到「" + group + "」");
    }

    /** 重命名一个分组：组里每一条连接都要改，不然会裂成两个组。 */
    private void renameGroup(String oldName) {
        String name = UiUtils.prompt(getScene().getWindow(), "重命名分组",
                "组里的每条连接都会跟着改", oldName);
        if (name == null || name.equals(oldName)) {
            return;
        }
        int moved = 0;
        for (ConnectionConfig config : context.registry().listAll()) {
            if (oldName.equals(config.group())) {
                context.registry().save(config.copy().setGroup(name));
                moved++;
            }
        }
        reload();
        statusSink.accept("已把 " + moved + " 条连接改到分组「" + name + "」");
    }

    private void newSchema(ConnectionConfig config) {
        DbSession session = context.sessionFor(config.id());
        if (session == null) {
            return;
        }
        TreeItem<NodeData> node = tree.getSelectionModel().getSelectedItem();
        NewSchemaDialog dialog = new NewSchemaDialog(context, session);
        dialog.setOnCreated(() -> {
            statusSink.accept("已新建库");
            // 让这条连接下次展开时重新读库列表
            if (node != null) {
                node.getChildren().setAll(placeholder());
                node.setExpanded(false);
            }
        });
        dialog.show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 这条连接上有没有「表结构」这回事。
     *
     * <p>右键菜单里那一半——新建表、新建视图、新建触发器、备份、生成测试数据、
     * 清空表、删除表——全都建立在「有表、有列、有 DDL」这个前提上。
     * Redis 一样也没有，每一项点下去都是一个错误弹窗。
     *
     * <p>把做不到的事摆在菜单里、等用户点了再报错，比不摆出来糟得多：
     * 用户会以为是软件坏了，而不是「这一家本来就没有这个概念」。
     *
     * <p>连接还没打开时按「有」算——那时菜单里能用的本来就只有「连接」。
     */
    private boolean structural(ConnectionConfig config) {
        DbSession session = context.sessionFor(config.id());
        return session == null || session.connection().dialect().hasTableStructure();
    }

    /**
     * 往这个库里导数据。
     *
     * <p>目标表留到对话框里选，不在这里先问一遍：从库节点点进来的人手上先有的是一份文件，
     * 表名往往要看过文件的列头才定得下来。
     */
    private void importInto(ConnectionConfig config, String schemaName) {
        DbSession session = context.sessionFor(config.id());
        if (session == null) {
            return;
        }
        ImportDialog dialog = new ImportDialog(context, session, schemaName);
        dialog.setOnFinished(() -> statusSink.accept("已导入数据到 " + schemaName));
        dialog.showAndWait(getScene() == null ? null : getScene().getWindow());
    }

    private void backupSchema(ConnectionConfig config, String schema) {
        BackupDialog dialog = new BackupDialog(context);
        dialog.preselect(config, schema);
        dialog.show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 跑一条 DDL，成功了刷新树并报一句，失败了原样把错误抛给用户。
     *
     * <p>要带上库名：走 {@link DbSession#executeDdl} 是为了先把连接切到那个库。
     * 这里的语句本来都带库限定，切不切都能跑；但让所有 DDL 走同一条路，
     * 才不会有下一个「忘了切库」的地方。
     */
    private void runDdl(DbSession session, String schema, String ddl,
                        String okMessage, Runnable after) {
        statusSink.accept("正在执行…");
        context.queryService()
                .submit(() -> session.executeDdl(schema, java.util.List.of(ddl)))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                "执行失败", error);
                        statusSink.accept("执行失败");
                        return;
                    }
                    after.run();
                    tree.refresh();
                    statusSink.accept(okMessage);
                }));
    }

    // ------------------------------------------------------------------ 单元格

    private class NodeCell extends TreeCell<NodeData> {
        @Override
        protected void updateItem(NodeData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                // 空行也要清样式类。不清的话，这个被回收的单元格会顶着上一行的底色
                // 留在树的下方——表现是列表末尾凭空多出几条发白的横带，
                // 而且展开收起得越多、被回收的空行越多，那些带子就越多
                markRowKind(null);
                return;
            }
            setText(null);
            setGraphic(render(item));
            setContextMenu(buildMenu(item));
            markRowKind(item);
        }

        /**
         * 按节点种类给整行挂个样式类。
         *
         * <p>一百多张表铺下来，行与行之间没有任何落差，眼睛只能靠数缩进找位置。
         * 给库和分组两层加上可辨认的底色，这棵树才有段落感。
         *
         * <p>每次都先清干净：JavaFX 的单元格是复用的，上一行的样式类留在身上，
         * 就会出现「某张表莫名其妙长得像分组」。
         *
         * <p>{@code data} 为 null 表示这一行现在是空的——同样要清，理由见
         * {@code updateItem} 里那段注释。
         */
        private void markRowKind(NodeData data) {
            getStyleClass().removeAll("tree-row-schema", "tree-row-group", "tree-row-folder");
            if (data instanceof NodeData.FolderNode) {
                getStyleClass().add("tree-row-folder");
            } else if (data instanceof NodeData.SchemaNode) {
                getStyleClass().add("tree-row-schema");
            } else if (data instanceof NodeData.GroupNode) {
                getStyleClass().add("tree-row-group");
            }
        }

        private HBox render(NodeData data) {
            if (data instanceof NodeData.FolderNode n) {
                Label name = UiUtils.label(n.name(), "tree-folder");
                HBox box = UiUtils.row(6, Icons.folder("#6a6a62", 13), name);
                box.getChildren().add(UiUtils.label(n.count() + " 个连接", "tree-count"));
                return box;
            }
            if (data instanceof NodeData.ConnectionNode n) {
                boolean connected = context.isConnected(n.config().id());
                Label name = new Label(n.config().name());
                name.setStyle("-fx-font-weight:bold;");
                // 按种类上色的字母标，替掉原来那个所有库共用的圆柱——
                // 六条连接摆一起时，那个图标什么也没说
                javafx.scene.layout.StackPane mark =
                        com.plainly.app.ui.DbMarks.mark(n.config().type(), 15, !connected);
                Tooltip.install(mark, new Tooltip(n.config().type().displayName()
                        + System.lineSeparator()
                        + (n.config().type().isFileBased()
                                ? n.config().filePath()
                                : n.config().host() + ":" + n.config().port())));
                HBox box = UiUtils.row(6, mark, name);
                // 标记色放在最前面：视线扫过树的时候先看到的就是它
                if (!n.config().color().isBlank()) {
                    javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
                    dot.setFill(javafx.scene.paint.Color.web(n.config().color()));
                    box.getChildren().add(0, dot);
                }
                // 取数期间这一行不展开（见 deferExpand），要是连个动静都没有，
                // 慢连接上看着就像那一下双击没生效——所以把状态写在行里
                if (loading.contains(getTreeItem())) {
                    box.getChildren().add(UiUtils.label("连接中…", "tree-count"));
                } else if (connected) {
                    // 同样紧跟在名字后面：右对齐时，名字一长这个角标就被推到
                    // 可视区外，只剩半个「在」字露在边上
                    box.getChildren().add(UiUtils.label("在线", "tree-badge"));
                }
                if (n.config().readOnly()) {
                    box.getChildren().add(UiUtils.label("只读", "tree-count"));
                }
                return box;
            }
            if (data instanceof NodeData.SchemaNode n) {
                Label name = new Label(n.schema().name());
                name.getStyleClass().add("tree-schema");
                // 库用圆柱，不用文件夹：文件夹已经是「连接分组」那一层的图标了，
                // 同一棵树上两层用同一个形状，扫一眼分不出自己在看哪一层
                HBox box = UiUtils.row(6, Icons.database("#a8781a", 13), name);
                // 默认库标出来：新建查询、导入这些动作在不指定时落到的就是它
                if (n.schema().isDefault()) {
                    box.getChildren().add(UiUtils.label("默认", "tree-count"));
                }
                if (loading.contains(getTreeItem())) {
                    box.getChildren().add(UiUtils.label("读取中…", "tree-count"));
                }
                return box;
            }
            if (data instanceof NodeData.GroupNode n) {
                /*
                 * 分组行原来只有一个「表」字，孤零零一行。
                 *
                 * 补的两样都是已经在手上的信息，不是装饰：一个和内容对应的图标，
                 * 以及这一组里有多少个对象——一百多张表的库，用户最先想知道的就是这个数，
                 * 否则只能自己往下数。
                 */
                Label label = UiUtils.label(groupLabel(n), "tree-group");
                HBox box = UiUtils.row(6, groupIcon(n), label);
                int count = getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                if (count > 0) {
                    // 紧跟在标签后面，不用弹性空白推到右边：树的单元格宽度跟着
                    // 最长的那一行走，右对齐的东西会被推出可视区（横向滚动条之外）
                    box.getChildren().add(UiUtils.label(UiUtils.groupDigits(count), "tree-count"));
                }
                return box;
            }
            if (data instanceof NodeData.TableNode n) {
                ObjectKind kind = n.table().kind();
                boolean view = kind == ObjectKind.VIEW || kind == ObjectKind.MATERIALIZED_VIEW;
                Label name = new Label(n.table().name());
                // 视图和表用不同的图标形状，再加一点色差——一串同色同形的行里，
                // 夹着的那几个视图光靠 12px 的图标形状认不出来
                HBox box = UiUtils.row(6, nodeIcon(kind, view), name);
                /*
                 * 行数估算。打开一张表之前最想知道的就是这个数——它决定了
                 * 是直接点开还是先加个条件。取自统计信息，跟着表清单一起回来，
                 * 不产生每表一次的 COUNT(*)（见 SqlDialect.tableRowCountQuery）。
                 *
                 * 视图上不显示：统计信息里没有视图，写个 0 会让人以为它查不出数据。
                 */
                long rows = n.table().rowEstimate();
                boolean hasRows = !view && rows >= 0;
                if (hasRows) {
                    Label count = UiUtils.label(UiUtils.approxCount(rows), "tree-count");
                    // 必须说明这是估算：不说，就会有人拿它去对账。
                    // InnoDB 上它是采样估出来的，几成的偏差很常见
                    Tooltip.install(count, new Tooltip(
                            "约 " + UiUtils.groupDigits(rows) + " 行（来自统计信息，非精确值）"));
                    box.getChildren().add(count);
                }
                // 表注释：JDBC 的 REMARKS 本来就跟着表清单一起回来了，一直没用上。
                // 一堆 acc_dev_xxx 里，中文注释是唯一能一眼分开它们的东西
                String comment = n.table().comment();
                if (comment != null && !comment.isBlank()) {
                    // 行数占掉了位置，注释就再让一点——表名才是这一行的主体
                    box.getChildren().add(
                            UiUtils.label(shorten(comment, hasRows ? 8 : 12), "tree-note"));
                }
                return box;
            }
            return UiUtils.row(6, new Label("…"));
        }

        /**
         * 这一组该叫什么。
         *
         * <p>Redis 上不能叫「表」：那一层是本工具按键名前缀分出来的组，
         * 服务端并不存在这样一个对象。叫它表，用户就会去点「删除表」「改结构」，
         * 然后发现每一项都被拒绝——而那本来就不该被看成表。
         */
        private static String groupLabel(NodeData.GroupNode n) {
            if (n.config().type() == com.plainly.driver.DbType.REDIS) {
                return "键空间";
            }
            // MongoDB 上「表」这一层就是集合。叫表会让人去点「改结构」，
            // 而集合根本没有结构可改
            if (n.config().type() == com.plainly.driver.DbType.MONGODB
                    && n.kind() == com.plainly.driver.meta.DbObjects.ObjectKind.TABLE) {
                return "集合";
            }
            return n.kind().label();
        }

        private ContextMenu buildMenu(NodeData data) {
            ContextMenu menu = new ContextMenu();
            if (data instanceof NodeData.FolderNode n) {
                menu.getItems().addAll(
                        item("展开全部", Icons.folder(Icons.NEUTRAL, 12), false,
                                e -> getTreeItem().getChildren()
                                        .forEach(c -> c.setExpanded(true))),
                        item("收起全部", Icons.folder(Icons.FAINT, 12), false,
                                e -> getTreeItem().getChildren()
                                        .forEach(c -> c.setExpanded(false))),
                        new SeparatorMenuItem(),
                        item("重命名分组…", Icons.file(Icons.NEUTRAL, 12), false,
                                e -> renameGroup(n.name())));
                return menu;
            }
            if (data instanceof NodeData.ConnectionNode n) {
                boolean connected = context.isConnected(n.config().id());

                /*
                 * 这一项<b>永远可点</b>。
                 *
                 * 原来写的是 open.setDisable(connected)——「已经连上了就不用再连」。
                 * 听起来对，实际上是这个 bug 的成因：connected 来自
                 * isClosed()，而它只知道「本地关过没有」。服务端把会话掐掉之后
                 * （放一夜、wait_timeout 到点、网络断过），它仍然回 true，
                 * 于是「连接」永远是灰的，用户点它永远没反应——而这恰恰是
                 * 他最需要它的时候。
                 *
                 * 判据不可靠，就不要拿它去挡路：连着的时候这一项叫「重新连接」，
                 * 做的事是丢掉旧会话重新连；没连的时候叫「连接」。两种都点得动。
                 */
                MenuItem open = item(connected ? "重新连接" : "连接",
                        Icons.database(Icons.ACCENT, 12), false,
                        e -> reconnect(n.config(), getTreeItem()));

                MenuItem edit = item("编辑连接…", Icons.file(Icons.NEUTRAL, 12), false,
                        e -> onEditConnection.accept(n.config()));

                MenuItem disconnect = item("断开", Icons.disconnect(Icons.NEUTRAL, 12), false, e -> {
                    if (!confirmDropPendingTransaction(n.config().id(), n.config().name(), "断开")) {
                        return;
                    }
                    context.closeSession(n.config().id());
                    getTreeItem().getChildren().clear();
                    getTreeItem().getChildren().add(placeholder());
                    getTreeItem().setExpanded(false);
                    tree.refresh();
                });
                disconnect.setDisable(!connected);

                MenuItem delete = item("删除连接", Icons.minus("#a0402a", 12), true, e -> {
                    if (UiUtils.confirm(getScene().getWindow(), "删除连接",
                            "确定删除「" + n.config().name() + "」？此操作不影响数据库本身。")) {
                        if (!confirmDropPendingTransaction(n.config().id(), n.config().name(), "删除")) {
                            return;
                        }
                        context.closeSession(n.config().id());
                        // 本机对这条连接的标注也一并清掉，不留孤儿记录：
                        // 留着的话，下次新建一条同名连接会莫名其妙带出一堆旧标注
                        context.virtualKeys().deleteForConnection(n.config().id());
                        context.registry().delete(n.config().id());
                        reload();
                    }
                });

                menu.getItems().addAll(open, edit, disconnect);
                // Redis 的库是编号固定的 0–15，由服务端配置决定，建不了
                if (structural(n.config())) {
                    MenuItem newSchema = item("新建库…", Icons.plus(Icons.ACCENT, 12), false,
                            e -> newSchema(n.config()));
                    newSchema.setDisable(!connected);
                    menu.getItems().addAll(new SeparatorMenuItem(), newSchema);
                }
                MenuItem moveTo = item("移到分组…", Icons.folder(Icons.NEUTRAL, 12), false,
                        e -> moveToGroup(n.config()));
                menu.getItems().addAll(new SeparatorMenuItem(), moveTo);
                if (!n.config().group().isBlank()) {
                    menu.getItems().add(item("移出分组「" + n.config().group() + "」",
                            Icons.minus(Icons.NEUTRAL, 12), false,
                            e -> setGroup(n.config(), "")));
                }
                menu.getItems().addAll(new SeparatorMenuItem(), delete);

            } else if (data instanceof NodeData.TableNode n) {
                boolean structural = structural(n.config());
                // 序列和事件没有行，打不开数据网格。它们只有一条定义可看——
                // 摆一个「打开表」在那里，点下去只会得到一个空网格或一条报错
                if (!n.table().kind().hasRows()) {
                    menu.getItems().add(item("查看定义…", Icons.view(Icons.ACCENT, 12), false,
                            e -> showObjectDefinition(n)));
                    return menu;
                }
                MenuItem open = item(structural ? "打开表" : "打开键空间",
                        Icons.table(Icons.ACCENT, 12), false, e -> activateSelected());
                MenuItem refresh = item(structural ? "刷新结构" : "刷新",
                        Icons.refresh(Icons.NEUTRAL, 12), false, e -> {
                            DbSession s = context.sessionFor(n.config().id());
                            if (s != null) {
                                s.invalidateTable(n.schema(), n.table().name());
                            }
                        });
                menu.getItems().addAll(open, refresh);

                // 下面几项都要有列定义和 DDL 才谈得上。键值库上一个也做不了，
                // 摆出来只会让人点了挨一个错误弹窗
                if (!structural) {
                    return menu;
                }

                MenuItem generate = item("生成测试数据…", Icons.plus(Icons.NEUTRAL, 12), false,
                        e -> generateData(n));
                menu.getItems().addAll(new SeparatorMenuItem(), generate);
                if (n.table().kind() == ObjectKind.TABLE) {
                    // 视图上没有可复制的结构：它的「结构」就是那条 SELECT，
                    // 要复制的话走「编辑视图定义」，那边看到的才是全文。
                    // 物化视图同理，它的结构来自那条 SELECT
                    menu.getItems().add(item("复制表…", Icons.copy(Icons.NEUTRAL, 12), false,
                            e -> copyTable(n)));
                }

                if (n.table().kind() == ObjectKind.MATERIALIZED_VIEW) {
                    // 物化视图查的是一份快照。改定义、清空、删除三件事各家语法都不一样
                    // （REFRESH MATERIALIZED VIEW 是 PG 的写法），没验证过就不发那条语句
                    menu.getItems().add(item("查看定义…", Icons.view(Icons.NEUTRAL, 12), false,
                            e -> showObjectDefinition(n)));
                } else if (n.table().kind() == ObjectKind.VIEW) {
                    menu.getItems().add(item("编辑视图定义…", Icons.view(Icons.NEUTRAL, 12), false,
                            e -> editObject(n, ObjectEditorDialog.Kind.VIEW)));
                } else {
                    // 清空和删除都不可逆，摆在分隔线之后、菜单最底下，
                    // 离「打开表」远一点，减少手滑
                    menu.getItems().addAll(new SeparatorMenuItem(),
                            item("清空表…", Icons.minus("#8a6d1f", 12), false,
                                    e -> truncateTable(n)),
                            item("删除表…", Icons.minus("#a0402a", 12), true,
                                    e -> dropTable(n)));
                }

            } else if (data instanceof NodeData.SchemaNode n) {
                MenuItem refreshSchema = item("刷新", Icons.refresh(Icons.NEUTRAL, 12), false,
                        e -> {
                            DbSession s = context.sessionFor(n.config().id());
                            if (s != null) {
                                s.invalidate();
                            }
                            getTreeItem().getChildren().setAll(placeholder());
                            getTreeItem().setExpanded(false);
                        });
                if (!structural(n.config())) {
                    // 建表 / 建视图 / 建触发器 / 备份，在键值库上全都没有对应的东西。
                    // 备份尤其危险：它会去生成建表语句，走到一半才发现没有列定义
                    menu.getItems().add(refreshSchema);
                    return menu;
                }

                MenuItem create = item("新建表…", Icons.plus(Icons.ACCENT, 12), false,
                        e -> newTable(n.config(), n.schema().name(), getTreeItem()));

                MenuItem newView = item("新建视图…", Icons.view(Icons.NEUTRAL, 12), false,
                        e -> newObject(n.config(), n.schema().name(),
                                ObjectEditorDialog.Kind.VIEW, getTreeItem()));
                MenuItem newRoutine = item("新建函数 / 存储过程…", Icons.file(Icons.NEUTRAL, 12),
                        false, e -> newObject(n.config(), n.schema().name(),
                                ObjectEditorDialog.Kind.ROUTINE, getTreeItem()));
                MenuItem newTrigger = item("新建触发器…", Icons.plan(Icons.NEUTRAL, 12), false,
                        e -> newObject(n.config(), n.schema().name(),
                                ObjectEditorDialog.Kind.TRIGGER, getTreeItem()));

                MenuItem refresh = item("刷新", Icons.refresh(Icons.NEUTRAL, 12), false, e -> {
                    DbSession s = context.sessionFor(n.config().id());
                    if (s != null) {
                        s.invalidate();
                    }
                    getTreeItem().getChildren().setAll(placeholder());
                    getTreeItem().setExpanded(false);
                });
                MenuItem importWizard = item("导入向导…", Icons.imports(Icons.NEUTRAL, 12), false,
                        e -> importInto(n.config(), n.schema().name()));
                MenuItem backup = item("备份这个库…", Icons.export(Icons.NEUTRAL, 12), false,
                        e -> backupSchema(n.config(), n.schema().name()));

                // 导入和备份是一进一出的一对，放在同一组里
                menu.getItems().addAll(create, newView, newRoutine, newTrigger,
                        new SeparatorMenuItem(), importWizard, backup,
                        new SeparatorMenuItem(), refresh);
            }
            return menu.getItems().isEmpty() ? null : menu;
        }

        /*
         * 树上按对象种类分的颜色。
         *
         * 原来一整棵树只有两三种灰蓝：表、视图、序列全是 #5a7a8a，分组行更是统一的浅灰，
         * 一屏几十行扫下来分不出哪行是哪类，只能逐个去认十二个像素的形状。
         * 一类一个色相之后，类型是「看见」的，不是「认出来」的。
         *
         * 这几个值都在 Icons 的调色板里登记过，所以深色主题下会自动换成对应的亮色版；
         * 随手写一个没登记的颜色，在深色底上会直接沉下去。
         */
        private static final String COLOR_TABLE = "#2a5d8f";
        private static final String COLOR_VIEW = "#8a3d6b";
        private static final String COLOR_SEQUENCE = "#4d7a4d";
        private static final String COLOR_EVENT = "#0f6f70";
        private static final String COLOR_KEY = "#a0402a";

        /**
         * 分组行图标的不透明度。
         *
         * <p>分组是容器，该比它装的东西轻一档；但原来那个统一的浅灰太轻了，
         * 在白底上几乎看不见。改成「和子节点同色、淡一档」，层级关系反而更清楚。
         */
        private static final double GROUP_FADE = 0.55;

        /** 树上一个对象节点的图标。序列和事件各有各的形状，别都长成表。 */
        private static javafx.scene.Node nodeIcon(ObjectKind kind, boolean view) {
            switch (kind) {
                case SEQUENCE:
                    return Icons.plus(COLOR_SEQUENCE, 12);
                case EVENT:
                    return Icons.plan(COLOR_EVENT, 12);
                default:
                    return view ? Icons.view(COLOR_VIEW, 12) : Icons.table(COLOR_TABLE, 12);
            }
        }

        /** 分组的图标：形状和颜色都跟它装的东西走，只是淡一档。 */
        private javafx.scene.Node groupIcon(NodeData.GroupNode n) {
            javafx.scene.Node icon;
            if (n.config().type() == com.plainly.driver.DbType.REDIS) {
                icon = Icons.key(COLOR_KEY, 11);
            } else {
                switch (n.kind()) {
                    case VIEW:
                    case MATERIALIZED_VIEW:
                        icon = Icons.view(COLOR_VIEW, 11);
                        break;
                    case SEQUENCE:
                        icon = Icons.plus(COLOR_SEQUENCE, 11);
                        break;
                    case EVENT:
                        icon = Icons.plan(COLOR_EVENT, 11);
                        break;
                    default:
                        icon = Icons.table(COLOR_TABLE, 11);
                        break;
                }
            }
            icon.setOpacity(GROUP_FADE);
            return icon;
        }

        /**
         * 注释截短。
         *
         * <p>侧栏只有两百多像素宽，一条长注释会把表名整个挤出可视区——
         * 而表名才是这一行的主体。截断比挤掉表名好。
         *
         * @param max 留给注释的字数。这一行已经显示了行数时要更紧一些
         */
        private static String shorten(String text, int max) {
            String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
            return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
        }

        /** 菜单项：统一带图标，破坏性操作标红。 */
        private MenuItem item(String text, javafx.scene.Node icon, boolean danger,
                              javafx.event.EventHandler<javafx.event.ActionEvent> action) {
            MenuItem mi = new MenuItem(text, icon);
            mi.setOnAction(action);
            if (danger) {
                mi.getStyleClass().add("menu-danger");
            }
            return mi;
        }
    }
}
