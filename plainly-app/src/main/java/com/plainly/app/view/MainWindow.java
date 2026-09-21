package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.Theme;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.UiState;
import com.plainly.app.view.sql.SqlEditorPane;
import com.plainly.core.edit.EditBuffer;
import com.plainly.core.store.TabStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主窗口。 */
public class MainWindow extends BorderPane {

    /** 换行常量。刻意不用转义序列，免得在多层脚本传递中被吃掉。 */
    private static final String NL = System.lineSeparator();

    /*
     * 挂在 Tab 自己身上的几件私货，不另开一张 Map——那张 Map 得跟着标签页的增删维护，
     * 漏一次就是一条永远清不掉的记录。放在 Tab 上，标签页没了它们自然也没了。
     */
    private static final String SAVED_ID = "plainly.saved.id";
    private static final String SAVED_TITLE = "plainly.saved.title";
    private static final String SAVED_SQL = "plainly.saved.sql";
    private static final String SAVED_FAVORITE = "plainly.saved.favorite";
    private static final String CONNECTION_ID = "plainly.connection.id";
    /** 占位页上挂着的那条现场记录：连上之后照它填内容，没连上时照它再存一次现场。 */
    private static final String PENDING_TAB = "plainly.session.pending";

    private final AppContext context;
    private final ConnectionTreePane tree;
    private final TabPane tabs = new TabPane();

    private final Label statusServer = UiUtils.label("未连接");
    private final Label statusDot = new Label();
    private final Label statusMessage = UiUtils.label("");
    private final Label statusStore = UiUtils.label("");
    private final SplitPane split;

    public MainWindow(AppContext context) {
        this.context = context;
        this.tree = new ConnectionTreePane(context);

        tree.setOnOpenTable(this::openTable);
        tree.setOnEditConnection(this::editConnection);
        tree.setStatusSink(this::setStatus);

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        split = new SplitPane(tree, tabs);
        // 侧栏现在还要装搜索结果（连接 · 库 · 表 三段），0.19 太挤。
        // 用户拖过之后就记住他的，关窗时存下来（见 saveLayout）
        split.setDividerPositions(
                context.uiState().getDouble(UiState.SIDEBAR_DIVIDER, 0.23));
        SplitPane.setResizableWithParent(tree, false);

        // 顶上不再单独放一条「Plainly · 数据库管理工具」的品牌行：
        // 系统标题栏已经写着 Plainly，同一个词上下挨着出现两遍。
        // 去掉之后还顺手让出 34 像素——那是整整一行表格数据
        setTop(buildToolbar());
        setCenter(split);
        setBottom(buildStatusBar());

        statusStore.setText("配置库 " + context.localStore().path());
        installAccelerators();
    }

    /**
     * 记下侧栏宽度。
     *
     * <p>关窗时调一次，而不是拖动时就存：分隔线拖一下会连发几十个事件，
     * 每一个都写一次 SQLite 是白花的。而这个值只在下次启动时才被读。
     */
    public void saveLayout() {
        double[] positions = split.getDividerPositions();
        if (positions.length > 0 && positions[0] > 0.02 && positions[0] < 0.9) {
            // 边界值不存：0 是侧栏被拖没了，1 是主区被拖没了。
            // 那多半是误操作，存下来会让下次启动打开一个看着像坏了的界面
            context.uiState().putDouble(UiState.SIDEBAR_DIVIDER, positions[0]);
        }
    }

    /**
     * 快捷键。
     *
     * <p>挂在 Scene 上，所以要等 Scene 真的存在——构造函数跑的时候还没有。
     * 直接 {@code getScene().getAccelerators()} 会空指针，这是必须等一手的原因。
     */
    private void installAccelerators() {
        sceneProperty().addListener((o, was, is) -> {
            if (is == null) {
                return;
            }
            is.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN),
                    tree::focusSearch);
            is.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN,
                            KeyCombination.SHIFT_DOWN),
                    () -> openDataSearch(tree.selectedTarget()));
            // F1 是所有人找帮助时第一个按的键。这一页存在的意义就是被找到
            is.getAccelerators().put(new KeyCodeCombination(KeyCode.F1), this::openShortcuts);
        });
    }

    /**
     * 快捷键速查。
     *
     * <p>网格上那几件事（Shift+空格 选整行、Ctrl+V 粘贴、点列头排序）没有对应的按钮，
     * 不写在这里就等于不存在——用户不会去猜一个没人告诉过他的按键。
     */
    private void openShortcuts() {
        new ShortcutsDialog().show(window());
    }

    // ------------------------------------------------------------------ 顶栏

    /**
     * 顶部工具条。
     *
     * <h2>为什么把一半按钮收进下拉里</h2>
     * 原来十三个按钮平铺一行，加上搜索框一共要 1408px。1280 宽的屏上放不下，
     * 而 JavaFX 放不下时不是溢出，是<b>把每个按钮的文字都截成「...」</b>——
     * 「结构同步」「数据传输」「数据同步」「数据模型」于是全变成「数据...」，
     * 三个不同的功能长得一模一样，只能靠位置去记。那已经不是拥挤，是坏了。
     *
     * <p>分组的依据是使用频率，不是功能类别：天天点的留在外面，
     * 一周点一次的收进带名字的下拉。下拉的名字本身就说明了里面装的是什么，
     * 而且位置固定，不会随窗口宽度跳来跳去。
     */
    private HBox buildToolbar() {
        Button newConnection = UiUtils.toolButton("新建连接",
                Icons.plus(Icons.ACCENT, 13), "accent");
        newConnection.setOnAction(e -> createConnection());

        Button newQuery = UiUtils.toolButton("新建查询", Icons.file(Icons.NEUTRAL, 13));
        newQuery.setOnAction(e -> openQueryTab());

        Button refresh = UiUtils.toolButton("刷新", Icons.refresh(Icons.NEUTRAL, 13));
        refresh.setOnAction(e -> tree.reload());

        MenuButton transfer = UiUtils.toolMenu("数据流转", Icons.export(Icons.NEUTRAL, 13),
                UiUtils.menuItem("结构同步…", Icons.export(Icons.MUTED, 12),
                        () -> new StructureSyncDialog(context).show(window())),
                UiUtils.menuItem("数据传输…", Icons.database(Icons.MUTED, 12),
                        () -> new DataTransferDialog(context).show(window())),
                UiUtils.menuItem("数据同步…", Icons.refresh(Icons.MUTED, 12),
                        () -> new DataSyncDialog(context).show(window())));

        // 主题项的文字说的是「点了会怎样」，所以切换之后要跟着改。
        // 写成「当前：暗色」那种状态描述反而更难读——菜单项是动作，不是状态栏
        MenuItem themeItem = UiUtils.menuItem(Theme.nextLabel(), Icons.view(Icons.MUTED, 12),
                () -> { });
        themeItem.setOnAction(e -> {
            Theme.toggle(context.uiState());
            themeItem.setText(Theme.nextLabel());
            setStatus("已切换到" + Theme.label() + "主题");
        });

        MenuButton tools = UiUtils.toolMenu("工具", Icons.filter(Icons.NEUTRAL, 13),
                UiUtils.menuItem("收藏夹…", Icons.check(Icons.MUTED, 12), this::openFavorites),
                UiUtils.menuItem("查询构建器…", Icons.filter(Icons.MUTED, 12),
                        this::openQueryBuilder),
                UiUtils.menuItem("在库中查找数据…    Ctrl+Shift+F", Icons.search(Icons.MUTED, 12),
                        () -> openDataSearch(tree.selectedTarget())),
                UiUtils.menuItem("快捷键…    F1", Icons.file(Icons.MUTED, 12),
                        this::openShortcuts),
                UiUtils.menuItem("数据模型 / ER 图…", Icons.plan(Icons.MUTED, 12),
                        () -> new ErDiagramDialog(context).show(window())),
                UiUtils.menuItem("数据字典…", Icons.file(Icons.MUTED, 12),
                        () -> new DataDictionaryDialog(context).show(window())),
                UiUtils.menuItem("SQL 代码片段…", Icons.format(Icons.MUTED, 12),
                        () -> new SnippetDialog(context).show(window())),
                themeItem,
                new SeparatorMenuItem(),
                // 「关于」放这一组的最后：出问题时第一件要确认的是跑的哪一版，
                // 而用户找这种信息的第一反应就是翻菜单
                UiUtils.menuItem("关于 Plainly…", Icons.file(Icons.MUTED, 12),
                        () -> new AboutDialog(context).show(window())));

        MenuButton ops = UiUtils.toolMenu("运维", Icons.plan(Icons.NEUTRAL, 13),
                UiUtils.menuItem("备份与还原…", Icons.database(Icons.MUTED, 12),
                        () -> new BackupDialog(context).show(window())),
                UiUtils.menuItem("服务器状态…", Icons.plan(Icons.MUTED, 12),
                        () -> new MonitorDialog(context).show(window())),
                UiUtils.menuItem("计划任务…", Icons.plan(Icons.MUTED, 12),
                        () -> new TaskDialog(context).show(window())),
                UiUtils.menuItem("用户与权限…", Icons.key(Icons.MUTED, 12),
                        () -> new PrivilegeDialog(context).show(window())),
                UiUtils.menuItem("导出连接配置…", Icons.export(Icons.MUTED, 12),
                        () -> new ConnectionPortDialog(context).showExport(window())),
                UiUtils.menuItem("导入连接配置…", Icons.plus(Icons.MUTED, 12),
                        () -> new ConnectionPortDialog(context)
                                .setOnClosed(tree::reload)
                                .showImport(window())));

        // 搜索挪到左侧栏了：它找的是表、视图、字段——全是左树里的东西，
        // 找到之后要做的也是一次树导航。放在顶栏最右边，离它作用的那棵树隔着整个窗口
        HBox bar = UiUtils.row(8, newConnection, newQuery, UiUtils.vSeparator(), refresh,
                UiUtils.vSeparator(), transfer, tools, ops,
                UiUtils.hSpacer());
        bar.getStyleClass().add("app-toolbar");
        return bar;
    }

    // ------------------------------------------------------------------ 状态栏

    private HBox buildStatusBar() {
        statusDot.setMinSize(7, 7);
        statusDot.setMaxSize(7, 7);
        statusDot.setStyle("-fx-background-color:-sx-text-faint;-fx-background-radius:4;");

        HBox bar = UiUtils.row(12, statusDot, statusServer, UiUtils.hSpacer(),
                statusMessage, UiUtils.vSeparator(), statusStore);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    public void setStatus(String message) {
        statusMessage.setText(message == null ? "" : message);
    }

    private void setConnectedStatus(DbSession session) {
        statusServer.setText(session.connection().serverVersion()
                + " · " + session.config().name()
                + (session.config().readOnly() ? " · 只读" : ""));
        statusDot.setStyle("-fx-background-color:-sx-green;-fx-background-radius:4;");
    }

    // ------------------------------------------------------------------ 连接

    private void createConnection() {
        new ConnectionDialog(context, null).showAndWait(window())
                .ifPresent(saved -> {
                    tree.reload();
                    setStatus("已保存连接「" + saved.name() + "」");
                });
    }

    private void editConnection(ConnectionConfig config) {
        new ConnectionDialog(context, config).showAndWait(window())
                .ifPresent(saved -> {
                    context.closeSession(saved.id());
                    tree.reload();
                    setStatus("已更新连接「" + saved.name() + "」");
                });
    }

    // ------------------------------------------------------------------ 标签页

    /**
     * 打开「在库中查找数据」。
     *
     * <p>左树上正选着某个库时，直接把窗口开在那个库上——用户点着 orders 库再点这个按钮，
     * 想搜的就是它，不该再让他在两个下拉框里重选一遍。
     */
    private void openDataSearch(ConnectionTreePane.Target target) {
        DataSearchDialog dialog = new DataSearchDialog(context);
        if (target != null) {
            dialog.preselect(target.config(), target.schema());
        }
        dialog.setOnOpenTable(this::openTable);
        dialog.show(window());
    }

    private void openTable(DbSession session, TableInfo table) {
        setConnectedStatus(session);

        Tab existing = findTableTab(session.config().id(), table);
        if (existing != null) {
            tabs.getSelectionModel().select(existing);
            return;
        }

        Tab tab = new Tab();
        configureTableTab(tab, session, table);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /**
     * 把一个空标签页装成表页。
     *
     * <p>和 {@link #configureQueryTab} 一样，刻意<b>不</b>负责「加进去、选中」——
     * 恢复现场时标签页是先摆出来、后填内容的，那一路已经在列表里了。
     */
    private void configureTableTab(Tab tab, DbSession session, TableInfo table) {
        TableTabPane pane = new TableTabPane(context, session, table.schema(), table.name());
        pane.setStatusSink(this::setStatus);

        tab.setText(table.name());
        tab.setContent(pane);
        tab.setUserData(tableKey(session.config().id(), table));
        tab.setGraphic(Icons.table(Icons.ACCENT, 12));
        tab.setContextMenu(tabMenu(tab));
        describeTab(tab, session, table.schema(), "表 " + table.name());
    }

    /** 同一张表只开一页，靠这个键认人。 */
    private static String tableKey(String connectionId, TableInfo table) {
        return connectionId + "/" + table.qualifiedName();
    }

    private Tab findTableTab(String connectionId, TableInfo table) {
        String key = tableKey(connectionId, table);
        for (Tab existing : tabs.getTabs()) {
            if (key.equals(existing.getUserData())) {
                return existing;
            }
        }
        return null;
    }

    /**
     * 新建查询。
     *
     * <p>目标库的判定顺序，按「用户此刻在看什么」由近及远：
     * 左侧树的选中项 → 当前标签页 → 任意一条已连接的。
     * 树上选中的优先级最高——用户点着某个库再点「新建查询」，
     * 想的就是在那个库上写 SQL，不该再让他猜。
     */
    private void openQueryTab() {
        ConnectionTreePane.Target target = tree.selectedTarget();
        if (target != null) {
            openQueryFor(target.config(), target.schema(), null, target.table());
            return;
        }
        DbSession session = currentSession();
        if (session != null) {
            openQueryFor(session.config(), null);
            return;
        }
        UiUtils.showInfo(window(), "先选一个库",
                "在左侧点中一条连接或它下面的库，再点「新建查询」。");
    }

    /** 连接尚未建立时先连上，再开标签页——而不是让用户先手工展开一次。 */
    /**
     * 打开查询构建器。
     *
     * <p>目标库的取法和「新建查询」一致：先看左树选中的，再看当前标签页，
     * 最后退回任意已连接的连接——用户刚点过的东西就是他想操作的东西。
     */
    private void openQueryBuilder() {
        ConnectionTreePane.Target target = tree.selectedTarget();
        ConnectionConfig config = target == null ? null : target.config();
        String schema = target == null ? null : target.schema();
        if (config == null) {
            DbSession any = context.anyConnected();
            if (any == null) {
                UiUtils.showInfo(window(), "先连一个库", "查询构建器要先有一个已连接的库。");
                return;
            }
            config = any.config();
        }

        ConnectionConfig target2 = config;
        String wanted = schema;
        context.queryService().submit(() -> {
            DbSession session = context.openSession(target2);
            String resolved = wanted;
            if (resolved == null || resolved.isBlank()) {
                resolved = session.schemas().stream().filter(SchemaInfo::isDefault)
                        .map(SchemaInfo::name).findFirst()
                        .orElse(session.schemas().isEmpty() ? "" : session.schemas().get(0).name());
            }
            return new Object[]{session, resolved};
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(window(), "连接失败", error);
                return;
            }
            DbSession session = (DbSession) result[0];
            String resolved = (String) result[1];
            QueryBuilderDialog dialog = new QueryBuilderDialog(context, session, resolved);
            dialog.setOnSendToEditor((sql, s) -> openQueryFor(session.config(), s, sql));
            dialog.show(window());
        }));
    }

    private void openQueryFor(ConnectionConfig config, String schema) {
        openQueryFor(config, schema, null, null);
    }

    private void openQueryFor(ConnectionConfig config, String schema, String initialSql) {
        openQueryFor(config, schema, initialSql, null);
    }

    /**
     * @param starterTable 左树上选中的表。给了它就预置一句 {@code SELECT * FROM 它}——
     *                     用户点着一张表按「新建查询」，下一步要敲的几乎总是这句。
     *                     SQL 在连上之后才拼：表名怎么加引号是各家方言的事
     */
    private void openQueryFor(ConnectionConfig config, String schema, String initialSql,
                              String starterTable) {
        setStatus("正在准备查询窗口…");
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            String resolved = schema;
            if (resolved == null || resolved.isBlank()) {
                resolved = session.schemas().stream()
                        .filter(SchemaInfo::isDefault)
                        .map(SchemaInfo::name)
                        .findFirst()
                        .orElse(session.schemas().isEmpty()
                                ? "" : session.schemas().get(0).name());
            }
            return new Object[]{session, resolved};
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(window(), "连接 " + config.name() + " 失败", error);
                setStatus("连接失败");
                return;
            }
            DbSession session = (DbSession) result[0];
            String resolved = (String) result[1];
            String sql = initialSql;
            if (sql == null && starterTable != null) {
                sql = "SELECT * FROM "
                        + session.connection().dialect().quote(starterTable);
            }
            Tab tab = createQueryTab(config, session, resolved, sql);
            if (starterTable != null) {
                // 这句是工具替他写的，不是他的劳动成果：不该因为它就在关页时被问
                // 「有未保存内容，确定关闭吗」。改动一个字之后就算了
                tab.getProperties().put(SAVED_SQL, sql);
            }
            setConnectedStatus(session);
            setStatus("查询目标：" + config.name() + " · " + resolved);
        }));
    }

    /**
     * 造一个查询页并选中它。
     *
     * <p>「新建查询」「从收藏打开」「启动时恢复」三条路都走这里——它们造出来的
     * 必须是同一种标签页，否则右键菜单、保存状态、退出现场会各有各的缺漏。
     */
    private Tab createQueryTab(ConnectionConfig config, DbSession session, String schema,
                               String initialSql) {
        Tab tab = new Tab();
        configureQueryTab(tab, config, session, schema, initialSql);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        return tab;
    }

    /**
     * 把一个空标签页装成查询页。
     *
     * <p>从 {@link #createQueryTab} 里拆出来，是为了让「恢复现场」能先把标签页摆出来、
     * 等连接到位再往里填——那条路上标签页已经在列表里了，不能再加一次、也不该抢选中。
     */
    private void configureQueryTab(Tab tab, ConnectionConfig config, DbSession session,
                                   String schema, String initialSql) {
        SqlEditorPane editor = new SqlEditorPane(context, session, schema);
        editor.setStatusSink(this::setStatus);
        if (initialSql != null && !initialSql.isBlank()) {
            editor.setSql(initialSql);
        }

        tab.setText(queryTabTitle(config, schema));
        tab.setContent(editor);
        tab.setGraphic(Icons.file(Icons.ACCENT, 12));
        tab.setContextMenu(tabMenu(tab));
        tab.getProperties().put(CONNECTION_ID, config.id());
        describeTab(tab, session, schema, "查询");

        // 目标库改了要跟着改标题与悬浮说明，否则标签页上写的还是旧库名。
        // 但保存过的页有自己的名字，那是用户起的，不能被库名盖掉
        editor.setOnSchemaChanged(name -> {
            if (!tab.getProperties().containsKey(SAVED_TITLE)) {
                tab.setText(queryTabTitle(config, name));
            }
            describeTab(tab, session, name, "查询");
        });
    }

    /**
     * 标签页的右键菜单。
     *
     * <p>菜单上写的是「标签页」，那就一视同仁：查询页和表页混在同一排里，
     * 「关闭左侧标签页」若挑着关，用户数着位置点下去，剩下的是什么就说不准了。
     * 代价是表页里可能压着没保存的单元格改动——所以交给 {@link #closeTabs} 先问一声。
     */
    private ContextMenu tabMenu(Tab tab) {
        MenuItem save = UiUtils.menuItem("保存标签页…", Icons.export(Icons.ACCENT, 12),
                () -> saveTab(tab, false));
        MenuItem favorite = UiUtils.menuItem("收藏标签页…", Icons.check(Icons.NEUTRAL, 12),
                () -> saveTab(tab, true));

        MenuItem current = UiUtils.menuItem("关闭当前标签页", Icons.minus(Icons.NEUTRAL, 12),
                () -> closeTabs(List.of(tab)));
        MenuItem left = UiUtils.menuItem("关闭左侧标签页", Icons.minus(Icons.MUTED, 12),
                () -> closeTabs(tabsLeftOf(tab)));
        MenuItem right = UiUtils.menuItem("关闭右侧标签页", Icons.minus(Icons.MUTED, 12),
                () -> closeTabs(tabsRightOf(tab)));
        MenuItem all = UiUtils.menuItem("关闭所有标签页", Icons.minus(Icons.MUTED, 12),
                () -> closeTabs(new ArrayList<>(tabs.getTabs())));

        ContextMenu menu = new ContextMenu(save, favorite, new SeparatorMenuItem(),
                current, left, right, new SeparatorMenuItem(), all);
        // 每次弹出时重算：标签页会新增、关闭，也会被拖着换位置，
        // 建菜单那一刻算出来的左右关系撑不到下一次右键
        menu.setOnShowing(e -> {
            left.setDisable(tabsLeftOf(tab).isEmpty());
            right.setDisable(tabsRightOf(tab).isEmpty());
            // 表页没有「内容」可存——它显示的就是库里的数据本身。
            // 但它有位置，所以收藏是有意义的：记下连接·库·表，回头一键打回原处
            save.setDisable(!(tab.getContent() instanceof SqlEditorPane));
            save.setText(tab.getProperties().containsKey(SAVED_ID) ? "保存标签页" : "保存标签页…");
        });
        return menu;
    }

    private List<Tab> tabsLeftOf(Tab tab) {
        return new ArrayList<>(tabs.getTabs().subList(0, tabs.getTabs().indexOf(tab)));
    }

    private List<Tab> tabsRightOf(Tab tab) {
        return new ArrayList<>(tabs.getTabs()
                .subList(tabs.getTabs().indexOf(tab) + 1, tabs.getTabs().size()));
    }

    /**
     * 关掉一批标签页。
     *
     * <p>有未保存内容的页要先问一声，因为这些东西关掉就没了：查询页里写了还没执行的 SQL
     * （执行过的还能在历史里翻到），表页里改了还没写回的单元格。都干净就直接关——
     * 顺手关掉几个看完的页是常事，每次都弹窗只会让人学会闭着眼睛点确定。
     */
    private void closeTabs(List<Tab> doomed) {
        if (doomed.isEmpty()) {
            return;
        }
        long unsaved = doomed.stream().filter(MainWindow::hasUnsavedWork).count();
        if (unsaved > 0 && !UiUtils.confirm(window(), "关闭标签页",
                "将关闭 " + doomed.size() + " 个标签页，其中 " + unsaved
                        + " 个有未保存的内容。" + NL
                        + "没执行过的 SQL、没写回的单元格改动都不会保留。")) {
            return;
        }
        tabs.getTabs().removeAll(doomed);
    }

    /**
     * 保存或收藏一个标签页。
     *
     * <p>两个动作共用一条记录：收藏就是「保存 + 标个星」。第一次要起个名字，
     * 之后再按保存就直接覆盖那条——同一个标签页反复保存，堆出十条同名记录是没人想要的。
     */
    private void saveTab(Tab tab, boolean favorite) {
        boolean isQuery = tab.getContent() instanceof SqlEditorPane;
        if (isQuery && ((SqlEditorPane) tab.getContent()).editor().getText().isBlank()) {
            UiUtils.showInfo(window(), "这一页还是空的",
                    "写点 SQL 再保存。空白页恢复回来也还是空白页。");
            return;
        }

        Long id = (Long) tab.getProperties().get(SAVED_ID);
        String title = (String) tab.getProperties().get(SAVED_TITLE);
        if (id == null) {
            title = UiUtils.prompt(window(), favorite ? "收藏标签页" : "保存标签页",
                    "起个名字，之后在「收藏夹」里按这个名字找它", tab.getText());
            if (title == null) {
                return;
            }
        }

        boolean star = favorite || Boolean.TRUE.equals(tab.getProperties().get(SAVED_FAVORITE));
        TabStore.SavedTab entry = toSavedTab(tab, id == null ? 0 : id, title, star);
        if (entry == null) {
            return;
        }
        long saved = context.tabStore().save(entry);
        rememberSaved(tab, saved, title, entry.sql(), star);
        setStatus((favorite ? "已收藏：" : "已保存：") + title);
    }

    /** 把标签页翻译成一条可入库的记录；翻译不了（比如还没连上的表页）返回 null。 */
    private TabStore.SavedTab toSavedTab(Tab tab, long id, String title, boolean favorite) {
        if (tab.getContent() instanceof SqlEditorPane editor) {
            return new TabStore.SavedTab(id, TabStore.Kind.QUERY, title,
                    (String) tab.getProperties().get(CONNECTION_ID), editor.schema(),
                    null, editor.editor().getText(), favorite, java.time.LocalDateTime.now());
        }
        if (tab.getContent() instanceof TableTabPane pane) {
            return new TabStore.SavedTab(id, TabStore.Kind.TABLE, title,
                    pane.session().config().id(), pane.schema(), pane.table(),
                    null, favorite, java.time.LocalDateTime.now());
        }
        return null;
    }

    /**
     * 恢复之后，SQL 以现场里的那份为准。
     *
     * <p>保存过、之后又改了两行没保存的页，恢复回来必须是「改过的那份」，
     * 同时还得记得它和上次保存的不一样——否则用户以为改动还在，
     * 关的时候也不会被提醒。
     */
    private static void rememberEdited(Tab tab, String sqlFromSession) {
        if (tab.getContent() instanceof SqlEditorPane editor && sqlFromSession != null
                && !sqlFromSession.equals(editor.editor().getText())) {
            editor.setSql(sqlFromSession);
        }
    }

    /** 记下「这一页存到了哪条记录、存下去的是什么」，之后才判断得出它有没有新改动。 */
    private static void rememberSaved(Tab tab, long id, String title, String sql,
                                      boolean favorite) {
        tab.getProperties().put(SAVED_ID, id);
        tab.getProperties().put(SAVED_TITLE, title);
        tab.getProperties().put(SAVED_SQL, sql == null ? "" : sql);
        tab.getProperties().put(SAVED_FAVORITE, favorite);
        tab.setText(favorite ? "★ " + title : title);
    }

    /**
     * 这一页关掉会不会丢东西。
     *
     * <p>查询页：写了东西、而且和上次保存下去的不一样，才算有未保存内容——
     * 保存过之后一个字没动，再问一遍「要不要保存」是纯粹的噪音。
     */
    private static boolean hasUnsavedWork(Tab tab) {
        // 还没连上的占位页里看不到编辑器，但上次退出时它有没有未保存内容是记着的。
        // 不看这一条的话，「连得慢」会顺带把关页前的那句提醒也吃掉
        TabStore.SessionTab pending = pendingOf(tab);
        if (pending != null) {
            return pending.unsaved();
        }
        if (tab.getContent() instanceof SqlEditorPane editor) {
            String text = editor.editor().getText();
            return !text.isBlank() && !text.equals(tab.getProperties().get(SAVED_SQL));
        }
        if (tab.getContent() instanceof TableTabPane pane) {
            EditBuffer buffer = pane.grid().editBuffer();
            return buffer != null && buffer.hasChanges();
        }
        return false;
    }

    /** 收藏夹。选中一条就按它原本的样子打回来：查询页带着 SQL，表页直接打开那张表。 */
    private void openFavorites() {
        FavoritesDialog dialog = new FavoritesDialog(context);
        dialog.setOnOpen(this::openSaved);
        dialog.show(window());
    }

    private void openSaved(TabStore.SavedTab saved) {
        ConnectionConfig config = context.registry().listAll().stream()
                .filter(c -> c.id().equals(saved.connectionId()))
                .findFirst().orElse(null);
        if (config == null) {
            UiUtils.showInfo(window(), "打不开这条收藏",
                    "它所属的连接已经被删掉了。" + NL + "改到别的连接上打开的话，"
                            + "先新建一个查询页，再从收藏夹把 SQL 复制过去。");
            return;
        }
        if (saved.kind() == TabStore.Kind.TABLE) {
            openSavedTable(config, saved);
            return;
        }
        setStatus("正在打开「" + saved.title() + "」…");
        context.queryService().submit(() -> context.openSession(config))
                .whenComplete((session, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(window(), "连接 " + config.name() + " 失败", error);
                        return;
                    }
                    Tab tab = createQueryTab(config, session, saved.schema(), saved.sql());
                    // 打开的就是那条记录本身：之后按保存要写回它，而不是又存一条新的
                    rememberSaved(tab, saved.id(), saved.title(), saved.sql(), saved.favorite());
                    setConnectedStatus(session);
                    setStatus("已打开「" + saved.title() + "」");
                }));
    }

    private void openSavedTable(ConnectionConfig config, TabStore.SavedTab saved) {
        context.queryService().submit(() -> context.openSession(config))
                .whenComplete((session, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(window(), "连接 " + config.name() + " 失败", error);
                        return;
                    }
                    openTable(session, new TableInfo(saved.schema(), saved.table(),
                            com.plainly.driver.meta.DbObjects.ObjectKind.TABLE, "", -1));
                }));
    }

    // ------------------------------------------------------------------ 退出现场

    /**
     * 把这一排标签页记成一份现场。
     *
     * <p>退出时记一次，另外每 20 秒记一次。只在退出时记是不够的：
     * 任务管理器结束进程、断电、JVM 崩，都轮不到那次回调跑——而「直接关掉」这件事，
     * 用户口中的和 JavaFX 口中的常常不是一回事。
     *
     * <p>记不下来不该拦着用户退出，所以异常在这里咽掉：代价只是下次少问一句要不要恢复。
     */
    public void snapshotSession() {
        List<TabStore.SessionTab> snapshot = new ArrayList<>();
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            TabStore.SessionTab one = toSessionTab(tabs.getTabs().get(i), i);
            if (one != null) {
                snapshot.add(one);
            }
        }
        try {
            context.tabStore().saveSession(snapshot);
        } catch (RuntimeException ignored) {
            // 见方法注释
        }
    }

    private static long savedIdOf(Tab tab) {
        Object id = tab.getProperties().get(SAVED_ID);
        return id instanceof Long value ? value : 0;
    }

    private TabStore.SessionTab toSessionTab(Tab tab, int position) {
        if (tab.getContent() instanceof SqlEditorPane editor) {
            String connectionId = (String) tab.getProperties().get(CONNECTION_ID);
            if (connectionId == null) {
                return null;
            }
            return new TabStore.SessionTab(position, TabStore.Kind.QUERY, tab.getText(),
                    connectionId, editor.schema(), null, editor.editor().getText(),
                    hasUnsavedWork(tab), savedIdOf(tab));
        }
        if (tab.getContent() instanceof TableTabPane pane) {
            return new TabStore.SessionTab(position, TabStore.Kind.TABLE, tab.getText(),
                    pane.session().config().id(), pane.schema(), pane.table(), null,
                    hasUnsavedWork(tab), savedIdOf(tab));
        }
        /*
         * 还没填上内容的占位页：照它原来的记录再存一份，位置按现在的。
         *
         * 少了这一段，「连不上的那条连接」就等于「那几页没了」——用户下次启动时
         * 连问都不会被问一声。而连不上往往是临时的（VPN 没开、库在重启），
         * 不该让一次失败把现场删掉。
         */
        TabStore.SessionTab pending = pendingOf(tab);
        if (pending != null) {
            return new TabStore.SessionTab(position, pending.kind(), pending.title(),
                    pending.connectionId(), pending.schema(), pending.table(), pending.sql(),
                    pending.unsaved(), pending.savedId());
        }
        return null;
    }

    /** 每 20 秒记一次现场。间隔要比「用户输入到崩溃」的耐受度短，又不至于一直在写盘。 */
    private void startSessionAutoSave() {
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(20),
                        e -> snapshotSession()));
        timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timer.play();
    }

    /**
     * 启动时问一次要不要恢复上次的标签页。
     *
     * <p>要等窗口真的出来才能问——弹窗需要一个 owner，构造函数跑的时候还没有。
     */
    public void offerSessionRestore() {
        List<TabStore.SessionTab> last;
        try {
            last = context.tabStore().lastSession();
        } catch (RuntimeException e) {
            return;
        }
        if (last.isEmpty()) {
            startSessionAutoSave();
            return;
        }
        long unsaved = last.stream().filter(TabStore.SessionTab::unsaved).count();
        StringBuilder message = new StringBuilder("上次退出时还开着 ")
                .append(last.size()).append(" 个标签页");
        if (unsaved > 0) {
            message.append("，其中 ").append(unsaved).append(" 个的内容没有保存过");
        }
        message.append("。").append(NL).append(NL)
                .append("恢复会重新连上这些库：查询页连 SQL 一起回来，表页重新打开那张表。")
                .append(NL)
                .append("表里没写回的单元格改动恢复不了——它绑在当时取到的那一页数据上。");

        if (UiUtils.confirm(window(), "恢复上次的标签页", message.toString())) {
            restoreSession(last);
        } else {
            // 说了不恢复就别再问第二次，否则每次启动都要挡一下
            context.tabStore().clearSession();
        }
        startSessionAutoSave();
    }

    /**
     * 恢复一份现场。
     *
     * <h2>先摆页，再连库</h2>
     * 原来是反过来的：在后台把要用到的连接<b>挨个</b>连完，才回到界面线程一次性建页。
     * 于是用户点完「恢复」面对的是一片空白，要等的是「所有连接握手时间之和」——
     * 五个页分在三条连接上，就是三次握手串着等；其中一条连不上，还要把整个
     * 连接超时（默认十几秒）加进去，而那期间连一个标签页都看不到。
     *
     * <p>现在标签页在这个方法返回之前就全摆出来了：位置、顺序、标题都是对的，
     * 里面先放一块「正在连接 xxx…」。内容等各自的连接到位再填，而且几条连接
     * <b>同时</b>连——总时长从「相加」变成「取最大」，慢的那条也只拖住它自己那几页。
     *
     * <p>连不上的那几页不再只在状态栏里留一句话：页还在，里面写着为什么，
     * 旁边就是「重试」。连接掉线是常事，让他重点一下比重启一次程序划算。
     */
    private void restoreSession(List<TabStore.SessionTab> last) {
        setStatus("正在恢复上次的标签页…");

        Map<String, ConnectionConfig> configs = new LinkedHashMap<>();
        for (ConnectionConfig config : context.registry().listAll()) {
            configs.putIfAbsent(config.id(), config);
        }

        // 按连接分组：一条连接只连一次，它名下的几页一起填
        Map<String, List<Tab>> groups = new LinkedHashMap<>();
        Tab selected = null;
        for (TabStore.SessionTab one : last) {
            ConnectionConfig config =
                    one.connectionId() == null ? null : configs.get(one.connectionId());
            // 连接被删了，标签页也就无处安放；表页没记下表名同理
            if (config == null || (one.kind() == TabStore.Kind.TABLE && one.table() == null)) {
                continue;
            }
            Tab tab = placeholderTab(one, config);
            tabs.getTabs().add(tab);
            selected = tab;
            groups.computeIfAbsent(config.id(), k -> new ArrayList<>()).add(tab);
        }
        if (selected != null) {
            tabs.getSelectionModel().select(selected);
        }

        /*
         * 以下三个计数只在界面线程上动：每条连接的回调都先 Platform.runLater 切回来，
         * 所以不需要同步，也不能换成在后台线程里直接改。
         */
        List<String> failed = new ArrayList<>();
        int[] opened = {0};
        int[] waiting = {groups.size()};
        if (waiting[0] == 0) {
            finishRestore(last.size(), 0, failed);
            return;
        }
        for (Map.Entry<String, List<Tab>> group : groups.entrySet()) {
            connectAndFill(configs.get(group.getKey()), group.getValue(), (name, count) -> {
                if (name != null) {
                    failed.add(name);
                }
                opened[0] += count;
                if (--waiting[0] == 0) {
                    finishRestore(last.size(), opened[0], failed);
                }
            });
        }
    }

    /**
     * 连一条连接，连上之后把它名下的占位页填成真页。
     *
     * <p>回调收到的是「这条连接失败时的名字（成功为 null）」和「填成了几页」，
     * 调用方拿它凑总数。
     */
    private void connectAndFill(ConnectionConfig config, List<Tab> waiting,
                                java.util.function.ObjIntConsumer<String> done) {
        for (Tab tab : waiting) {
            restorePane(tab).waiting("正在连接 " + config.name() + " …");
        }
        context.queryService().submit(() -> context.openSession(config))
                .whenComplete((session, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        String why = UiUtils.rootMessage(error);
                        for (Tab tab : waiting) {
                            restorePane(tab).failed("连接 " + config.name() + " 失败" + NL + why,
                                    () -> retryRestore(tab));
                        }
                        done.accept(config.name(), 0);
                        return;
                    }
                    setConnectedStatus(session);
                    int filled = 0;
                    for (Tab tab : waiting) {
                        if (fillRestored(tab, config, session)) {
                            filled++;
                        }
                    }
                    done.accept(null, filled);
                }));
    }

    /** 「重试」按钮：只重连这一页要的那条连接。 */
    private void retryRestore(Tab tab) {
        TabStore.SessionTab one = pendingOf(tab);
        if (one == null) {
            return;
        }
        ConnectionConfig config = context.registry().listAll().stream()
                .filter(c -> c.id().equals(one.connectionId())).findFirst().orElse(null);
        if (config == null) {
            restorePane(tab).failed("这一页所属的连接已经被删掉了。", null);
            tab.getProperties().remove(PENDING_TAB);
            return;
        }
        connectAndFill(config, List.of(tab), (name, count) -> {
            if (count > 0) {
                setStatus("已恢复「" + tab.getText() + "」");
            }
        });
    }

    private void finishRestore(int total, int opened, List<String> failed) {
        context.tabStore().clearSession();
        String note = "已恢复 " + opened + " / " + total + " 个标签页";
        if (!failed.isEmpty()) {
            note = note + " · 连不上：" + String.join("、", failed);
        }
        setStatus(note);
    }

    /**
     * 占位页：位置、标题、图标都按现场里记的来，内容是一块「正在恢复…」。
     *
     * <p>表页的 userData 现在就写上，于是等待期间用户自己去树上点开同一张表，
     * 认得出这里已经有一页了，不会开出第二个。
     */
    private Tab placeholderTab(TabStore.SessionTab one, ConnectionConfig config) {
        String title = one.title() == null || one.title().isBlank()
                ? (one.kind() == TabStore.Kind.TABLE ? one.table() : config.name())
                : one.title();
        Tab tab = new Tab(title, new RestorePane());
        tab.setGraphic(one.kind() == TabStore.Kind.TABLE
                ? Icons.table(Icons.MUTED, 12) : Icons.file(Icons.MUTED, 12));
        tab.setContextMenu(tabMenu(tab));
        tab.getProperties().put(CONNECTION_ID, config.id());
        tab.getProperties().put(PENDING_TAB, one);
        if (one.kind() == TabStore.Kind.TABLE) {
            tab.setUserData(tableKey(config.id(), tableInfoOf(one)));
        }
        return tab;
    }

    /** 把占位页填成真页。已经被用户关掉的返回 false，不计进「已恢复」。 */
    private boolean fillRestored(Tab tab, ConnectionConfig config, DbSession session) {
        TabStore.SessionTab one = pendingOf(tab);
        if (one == null || !tabs.getTabs().contains(tab)) {
            // 等连接的那几秒里用户把它关了。这时候再往里塞内容，等于把页又变回来
            return false;
        }
        tab.getProperties().remove(PENDING_TAB);
        if (one.kind() == TabStore.Kind.TABLE) {
            configureTableTab(tab, session, tableInfoOf(one));
            return true;
        }
        configureQueryTab(tab, config, session, one.schema(), one.sql());
        // 保存过的页要把绑定接回去：否则名字丢了，再按一次保存还会另存一条
        if (one.savedId() > 0) {
            TabStore.SavedTab saved = context.tabStore().find(one.savedId());
            if (saved != null) {
                rememberSaved(tab, saved.id(), saved.title(), saved.sql(), saved.favorite());
                // 现场里的 SQL 才是用户最后看到的那份，保存过之后又改了的部分不能丢
                rememberEdited(tab, one.sql());
            }
        }
        return true;
    }

    private static TableInfo tableInfoOf(TabStore.SessionTab one) {
        return new TableInfo(one.schema(), one.table(),
                com.plainly.driver.meta.DbObjects.ObjectKind.TABLE, "", -1);
    }

    private static TabStore.SessionTab pendingOf(Tab tab) {
        Object value = tab.getProperties().get(PENDING_TAB);
        return value instanceof TabStore.SessionTab one ? one : null;
    }

    private static RestorePane restorePane(Tab tab) {
        return tab.getContent() instanceof RestorePane pane ? pane : new RestorePane();
    }

    /**
     * 占位页里显示的东西：一句话，外加失败时的「重试」。
     *
     * <p>做成一个类而不是临时拼的 Label，是因为这块内容要改三次
     * （正在恢复 → 正在连接 xxx → 连不上），每次都重建一个节点的话，
     * 标签页会跟着闪一下。
     */
    private static final class RestorePane extends VBox {

        private final Label note = UiUtils.label("正在恢复…", "hint");
        private final Button retry = UiUtils.toolButton("重试", null);

        RestorePane() {
            super(10);
            setAlignment(Pos.CENTER);
            showRetry(false);
            getChildren().addAll(note, retry);
        }

        void waiting(String text) {
            note.setText(text);
            showRetry(false);
        }

        void failed(String text, Runnable action) {
            note.setText(text);
            if (action != null) {
                retry.setOnAction(e -> action.run());
            }
            showRetry(action != null);
        }

        /** 隐藏的同时要取消 managed，否则那块高度还占着，文字不居中。 */
        private void showRetry(boolean visible) {
            retry.setVisible(visible);
            retry.setManaged(visible);
        }
    }

    /** 标签页标题写明「连接 · 库」，多开几个查询页时不必逐个点开确认目标。 */
    private String queryTabTitle(ConnectionConfig config, String schema) {
        return schema == null || schema.isBlank()
                ? config.name() : config.name() + " · " + schema;
    }

    /** 悬浮说明：把连接、库、服务端版本、只读状态一次讲清楚。 */
    private void describeTab(Tab tab, DbSession session, String schema, String kind) {
        StringBuilder sb = new StringBuilder(kind).append(NL)
                .append("连接：").append(session.config().name()).append(NL)
                .append("库：")
                .append(schema == null || schema.isBlank() ? "(默认)" : schema).append(NL)
                .append("服务端：").append(session.connection().serverVersion());
        if (session.config().readOnly()) {
            sb.append(NL).append("只读模式：写操作会被拦截");
        }
        tab.setTooltip(new Tooltip(sb.toString()));
    }

    /** 当前标签页所属的会话；没有标签页时取第一条已连接的。 */
    private DbSession currentSession() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof TableTabPane pane) {
            return pane.session();
        }
        for (ConnectionConfig config : context.registry().listAll()) {
            if (context.isConnected(config.id())) {
                return context.sessionFor(config.id());
            }
        }
        return null;
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
