package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.edit.EditBuffer;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import com.plainly.driver.query.FilterSpec;
import com.plainly.driver.meta.DbObjects.TableStructure;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 一张表的浏览页：数据 / 结构 / DDL 三视图，加分页与写回。 */
public class TableTabPane extends BorderPane {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;

    private final DataGridPane grid = new DataGridPane();
    private final TableDesignerPane designer;
    private final DdlPane ddlPane = new DdlPane();
    private final FormViewPane formView = new FormViewPane();
    private final IndexPane indexPane;
    private final RelationPane keyPane;
    private final RelationPane triggerPane;
    private final StackPane content = new StackPane();

    private final Button saveButton;
    private final Button discardButton;
    private final Button filterButton =
            UiUtils.toolButton("筛选", Icons.filter(Icons.NEUTRAL, 12));
    private final Label filterLabel = UiUtils.label("", "tree-badge");
    /** 操作区。内容跟着当前视图重建，见 {@link #rebuildActions(int)}。 */
    private final HBox actions = UiUtils.row(8);
    /** 操作条最前面那个标签，写明这一行的操作是对哪个视图的。 */
    private final Label actionScope = UiUtils.label("", "action-scope");

    /** 键名搜索框，只在键值库上出现。 */
    private final TextField keySearchField = new TextField();
    /** 搜索框旁边那句「实际匹配 xxx」。 */
    private final Label keySearchHint = UiUtils.label("", "hint");
    /** 键值库上贴在网格顶上的那一行搜索栏；关系库上为 null。 */
    private HBox keySearchBar;

    /** 六个视图的名字。既用在开关上，也用在操作条的归属标签上，只写一处。 */
    private static final String[] VIEW_NAMES =
            {"数据", "表单", "结构", "DDL", "索引", "外键", "触发器"};
    /** 底部分页条。只有数据和表单视图翻页，其余视图上它说的不是自己的事。 */
    private HBox pager;
    private final Label pageLabel = UiUtils.label("—");
    private final Label totalLabel = UiUtils.label("");
    private final ComboBox<Integer> pageSize = new ComboBox<>();

    private int offset;
    private long totalRows = -1;
    /** 当前的筛选与排序。空的时候就是原来的行为：取全表、按物理顺序。 */
    private FilterSpec filter = FilterSpec.empty();
    private List<ColumnInfo> filterColumns = List.of();
    private Consumer<String> statusSink = s -> { };
    /**
     * 事务控制条。
     *
     * <p>表页上也要有：在网格里改完数据按「保存」，那些 INSERT/UPDATE/DELETE
     * 同样进当前连接的事务。只在 SQL 编辑器里摆一个开关的话，用户在表页改完数据、
     * 关掉页面，会以为已经存进去了。
     */
    private TransactionBar txBar;

    public TableTabPane(AppContext context, DbSession session, String schema, String table) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        // 复制成 INSERT 时要写表名。结果集元数据不一定给得出来（视图、跨库、驱动差异），
        // 而这里本来就知道是哪张表
        grid.setTableNameHint(schema == null || schema.isBlank() ? table : schema + "." + table);

        saveButton = UiUtils.toolButton("保存", Icons.check(Icons.ACCENT, 12), "accent");
        discardButton = UiUtils.toolButton("放弃", null);
        saveButton.setDisable(true);
        discardButton.setDisable(true);
        saveButton.setOnAction(e -> commitEdits());
        discardButton.setOnAction(e -> {
            grid.revertEdits();
            formView.showRow(formView.rowIndex());
            refreshDirtyState();
        });

        grid.setOnDirtyChanged(this::refreshDirtyState);

        txBar = new TransactionBar(session, msg -> statusSink.accept(msg));
        txBar.applyVisibility();

        designer = new TableDesignerPane(context, session, schema, table);
        designer.setStatusSink(msg -> statusSink.accept(msg));
        // 结构改了之后数据页必须重新取数：列可能已经增删改名
        designer.setOnApplied(() -> {
            // 列可能被改名或删掉了，旧的筛选会指向不存在的列，直接清掉更诚实
            filter = FilterSpec.empty();
            filterColumns = List.of();
            refreshFilterLabel();
            loadPage();
            loadCount();
        });

        // 点列头排序。键值库上不给：那一页是按键名前缀列出来的，没有「按列排」这回事。
        // MongoDB 给：sort 是下推到服务端执行的，和关系库一样
        if (!isKeyValue()) {
            grid.setOnSortRequested(this::toggleSort);
        }

        indexPane = new IndexPane(context, session, schema, table);
        keyPane = new RelationPane(context, session, schema, table, true);
        triggerPane = new RelationPane(context, session, schema, table, false);

        formView.setOnDirtyChanged(this::refreshDirtyState);

        content.getChildren().addAll(grid, formView, designer, ddlPane,
                indexPane, keyPane, triggerPane);
        showView(0);
        filterLabel.setVisible(false);

        setTop(buildToolbar());
        setCenter(content);
        installKeySearchBar();
        pager = buildPager();
        setBottom(pager);

        loadPage();
        loadCount();
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink;
    }

    public DataGridPane grid() {
        return grid;
    }

    public String schema() {
        return schema;
    }

    public String table() {
        return table;
    }

    public DbSession session() {
        return session;
    }

    // ------------------------------------------------------------------ 顶部

    /**
     * 表页顶部：<b>两行</b>，上面是视图，下面是对当前视图的操作。
     *
     * <h2>为什么必须分成两行</h2>
     * 之前挤在一行里，六个视图开关和后面的操作按钮长得一模一样——同样的边框、
     * 同样的字号、同样的间距，中间只隔一条细线。视觉上就是一排平级的东西，
     * 而它们的语义完全不同：前六个是「我在看哪一页」（六选一、有持续状态），
     * 后几个是「对这一页做点什么」（点一下就完事）。
     *
     * <p>操作区改成跟着视图变之后更糟：切一下视图，那半行就换一批按钮，
     * 更像是同一排菜单在莫名其妙地改内容。
     *
     * <p>所以拆开，而且<b>两行长得不一样</b>：上行做成贴着内容的标签页，
     * 下行是普通工具条，最前面还写着这一行的操作属于谁。层级不用猜。
     */
    private VBox buildToolbar() {
        ToggleGroup group = new ToggleGroup();
        HBox viewBar = UiUtils.row(0);
        // 没有表结构的库（Redis）只留数据视图。后面五个点进去都是空的，
        // 摆在那里只会引人去点，然后以为是没加载出来
        int shown = session.connection().dialect().hasTableStructure() ? VIEW_NAMES.length : 1;
        for (int i = 0; i < shown; i++) {
            viewBar.getChildren().add(viewToggle(VIEW_NAMES[i], group, i));
        }
        ((ToggleButton) viewBar.getChildren().get(0)).setSelected(true);
        viewBar.getStyleClass().add("view-strip");

        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox actionBar = UiUtils.row(8, actionScope, actions,
                UiUtils.hSpacer(), filterLabel);
        actionBar.getStyleClass().add("grid-toolbar");

        return UiUtils.column(0, viewBar, actionBar);
    }

    /** 按当前视图重建操作区。 */
    private void rebuildActions(int view) {
        actions.getChildren().clear();
        actionScope.setText(VIEW_NAMES[view] + " ·");

        Button reload = UiUtils.toolButton("刷新", Icons.refresh(Icons.NEUTRAL, 12));
        reload.setOnAction(e -> {
            session.invalidateTable(schema, table);
            loadPage();
            loadCount();
            indexPane.reload();
            keyPane.reload();
            triggerPane.reload();
        });
        actions.getChildren().add(reload);

        switch (view) {
            case 0: // 数据
                filterButton.setOnAction(e -> openFilterDialog());

                Button findBtn = UiUtils.toolButton("查找替换", Icons.search(Icons.NEUTRAL, 12));
                findBtn.setOnAction(e -> openFindReplace());

                Button importBtn = UiUtils.toolButton("导入", Icons.plus(Icons.NEUTRAL, 12));
                importBtn.setOnAction(e -> openImportDialog());

                Button exportBtn = UiUtils.toolButton("导出", Icons.export(Icons.NEUTRAL, 12));
                exportBtn.setOnAction(e -> openExportDialog());

                // 增行 / 删行。和改值走同一条路：只动编辑缓冲，按「保存」才落库。
                // 文档库上换个说法：那里一行就是一篇文档，叫「行」会让人以为是表格里的行
                boolean documents = !isKeyValue()
                        && !session.connection().dialect().hasTableStructure();
                Button addRowBtn = UiUtils.toolButton(documents ? "新增文档" : "新增行",
                        Icons.plus(Icons.NEUTRAL, 12));
                addRowBtn.setOnAction(e -> grid.addRow());

                Button deleteRowBtn = UiUtils.toolButton(documents ? "删除文档" : "删除行",
                        Icons.minus("#a0402a", 12));
                deleteRowBtn.setOnAction(e -> grid.deleteSelectedRows());

                if (isKeyValue()) {
                    // 键值库：筛选和查找替换在这儿是骗人的
                    // （Redis 只认键名通配，值上的条件服务端不管；筛一半比不筛更危险），
                    // 导入是关系库那套按列灌数据。改数据走下面这几个按钮
                    actions.getChildren().addAll(keyActions());
                    actions.getChildren().addAll(UiUtils.vSeparator(), exportBtn);
                } else if (documents) {
                    /*
                     * 文档库（MongoDB）。
                     *
                     * 给的是筛选、导出和整套增删改——这几条都真的走到了服务端：
                     * 筛选翻成 Mongo 的查询文档，写回按 _id 定位。
                     *
                     * 不给的是「导入」和「查找替换」：那两条在关系库上依赖固定的列集合，
                     * 而集合里每篇文档的字段可以不一样。它们也许能跑通，
                     * 但没在真机上验过，摆出来就是在承诺一件没验证过的事。
                     */
                    actions.getChildren().addAll(filterButton, exportBtn,
                            UiUtils.vSeparator(), addRowBtn, deleteRowBtn,
                            UiUtils.vSeparator(), saveButton, discardButton);
                } else {
                    actions.getChildren().addAll(filterButton, findBtn, importBtn, exportBtn,
                            UiUtils.vSeparator(), addRowBtn, deleteRowBtn,
                            UiUtils.vSeparator(), saveButton, discardButton,
                            UiUtils.vSeparator(), txBar);
                }
                break;
            case 1: // 表单
                actions.getChildren().addAll(UiUtils.vSeparator(), saveButton, discardButton);
                break;
            case 4: // 索引
                Button editIndexes = UiUtils.toolButton("新建 / 修改 / 删除索引…",
                        Icons.key(Icons.NEUTRAL, 12));
                editIndexes.setOnAction(e -> openKeyEditor(0));
                actions.getChildren().add(editIndexes);
                break;
            case 5: // 外键
                Button editKeys = UiUtils.toolButton("新建 / 修改 / 删除外键…",
                        Icons.key(Icons.NEUTRAL, 12));
                editKeys.setOnAction(e -> openKeyEditor(1));

                // 很多线上库刻意不建物理外键。虚拟外键让用户把关系标下来，
                // ER 图和这一页都认它——但库上没有任何约束，那一点在对话框里写着
                Button virtualKeys = UiUtils.toolButton("虚拟外键…", Icons.plan(Icons.NEUTRAL, 12));
                virtualKeys.setOnAction(e -> new VirtualKeyDialog(context, session, schema, table)
                        .setOnChanged(keyPane::reload)
                        .show(getScene() == null ? null : getScene().getWindow()));

                actions.getChildren().addAll(editKeys, virtualKeys);
                break;
            case 6: // 触发器
                Button newTrigger = UiUtils.toolButton("新建触发器…",
                        Icons.plus(Icons.NEUTRAL, 12));
                newTrigger.setOnAction(e -> openTriggerEditor(null));

                // 不写成「改…」：一个字加省略号看着像被截断了，
                // 而这一行的按钮全都设了最小宽度，本来就不会截
                Button editTrigger = UiUtils.toolButton("修改触发器…",
                        Icons.format(Icons.NEUTRAL, 12));
                editTrigger.setOnAction(e -> {
                    TriggerInfo selected = triggerPane.selectedTrigger();
                    if (selected == null) {
                        statusSink.accept("先在列表里选一个触发器");
                        return;
                    }
                    openTriggerEditor(selected);
                });

                Button dropTrigger = UiUtils.toolButton("删除触发器",
                        Icons.minus("#a0402a", 12), "danger");
                dropTrigger.setOnAction(e -> dropTrigger());

                actions.getChildren().addAll(newTrigger, editTrigger, dropTrigger);

                // 有些库根本不能用 SQL 写触发器（H2 的触发器体是 Java 类）。
                // 删除仍然可以，所以只灰掉新建和修改这两个。
                // 完整理由摆在下面的面板里，不往工具条上塞——那句话有四十来字，
                // 塞进来会把整条工具条撑到 1283px，正是刚修掉的毛病
                if (session.connection().dialect().triggerUnsupportedReason() != null) {
                    newTrigger.setDisable(true);
                    editTrigger.setDisable(true);
                }
                break;
            default:
                // 结构页和 DDL 页各自带着自己的按钮，这里除了刷新没别的可做
                break;
        }
    }

    /**
     * 打开索引 / 外键编辑器。
     *
     * @param tab 0 停在索引标签，1 停在外键标签——用户从哪一页点进来就停在哪一页，
     *            不让他再点一下
     */
    private void openKeyEditor(int tab) {
        KeyEditorDialog dialog = new KeyEditorDialog(context, session, schema, table);
        dialog.setOnApplied(() -> {
            indexPane.reload();
            keyPane.reload();
            loadStructure(3);
        });
        dialog.show(getScene() == null ? null : getScene().getWindow(), tab);
    }

    private void openTriggerEditor(TriggerInfo existing) {
        TriggerEditorDialog dialog =
                new TriggerEditorDialog(context, session, schema, table, existing);
        dialog.setOnApplied(() -> {
            triggerPane.reload();
            statusSink.accept(existing == null ? "触发器已创建" : "触发器已重建");
        });
        dialog.show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 删触发器。
     *
     * <p>DDL 删不回来，所以这里必须问一次，而且要把名字写进去——
     * 「确定删除吗」问不出任何信息，用户点是的时候并不知道自己在删哪一个。
     */
    private void dropTrigger() {
        TriggerInfo selected = triggerPane.selectedTrigger();
        if (selected == null) {
            statusSink.accept("先在列表里选一个触发器");
            return;
        }
        if (!UiUtils.confirm(getScene() == null ? null : getScene().getWindow(),
                "删除触发器 " + selected.name(),
                "这条 DDL 撤不回来。删掉之后，这张表上的"
                        + (selected.event() == null || selected.event().isBlank()
                        ? "相应" : selected.event())
                        + "操作不会再触发它。")) {
            return;
        }
        String ddl = session.connection().dialect()
                .dropTriggerDdl(schema, table, selected.name());
        context.queryService()
                .submit(() -> session.executeDdl(schema, List.of(ddl)))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                "删除失败", error);
                        return;
                    }
                    triggerPane.reload();
                    statusSink.accept("已删除触发器 " + selected.name());
                }));
    }

    /**
     * 一个视图开关。
     *
     * <p>样式走 {@code .view-tab}，不再借用 {@code .tool-button}——正是「借用」
     * 让它和下面那排操作按钮长成了一个样。选中态交给 CSS 的 {@code :selected}，
     * 不用在代码里手工加减样式类。
     */
    private ToggleButton viewToggle(String text, ToggleGroup group, int index) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.getStyleClass().add("view-tab");
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        // 再点一下当前这个不该把它取消掉——取消之后一个视图都没选中，界面就空了
        b.setOnAction(e -> {
            b.setSelected(true);
            showView(index);
        });
        return b;
    }

    private void showView(int index) {
        rebuildActions(index);
        // 分页条讲的是数据的第几页。摆在 DDL 页或外键页下面，说的不是眼前这份内容，
        // 只会让人以为「外键也有 5,006 条」。managed=false 才是真的不占位置
        if (pager != null) {
            boolean paged = index == 0 || index == 1;
            pager.setVisible(paged);
            pager.setManaged(paged);
        }
        grid.setVisible(index == 0);
        formView.setVisible(index == 1);
        designer.setVisible(index == 2);
        ddlPane.setVisible(index == 3);
        indexPane.setVisible(index == 4);
        keyPane.setVisible(index == 5);
        triggerPane.setVisible(index == 6);
        // 切到表单时跟着网格里选中的那一行走，别让人切过去还得自己找回来
        if (index == 1) {
            int selected = grid.table().getSelectionModel().getSelectedIndex();
            if (selected >= 0) {
                formView.showRow(selected);
            }
        }
        // 设计器自己负责加载与刷新；这里只管 DDL 页
        if (index == 3) {
            loadStructure(index);
        }
        // 索引、外键、触发器的元数据查询在大库上并不便宜，切过去才取，且只取一次
        if (index == 4) {
            indexPane.loadIfNeeded();
        }
        if (index == 5) {
            keyPane.loadIfNeeded();
        }
        if (index == 6) {
            triggerPane.loadIfNeeded();
        }
    }

    // ------------------------------------------------------------------ 底部

    private HBox buildPager() {
        Button prev = UiUtils.toolButton("‹", null);
        Button next = UiUtils.toolButton("›", null);
        prev.setOnAction(e -> {
            offset = Math.max(0, offset - pageSize.getValue());
            loadPage();
        });
        next.setOnAction(e -> {
            offset += pageSize.getValue();
            loadPage();
        });

        pageSize.getItems().addAll(100, 200, 500, 1000);
        pageSize.setValue(200);
        pageSize.setOnAction(e -> {
            offset = 0;
            loadPage();
        });

        HBox bar = UiUtils.row(10, prev, next, pageLabel,
                UiUtils.label("/"), totalLabel,
                UiUtils.vSeparator(), UiUtils.label("每页"), pageSize,
                UiUtils.hSpacer(),
                UiUtils.label(isKeyValue()
                        // Redis 上「下推」这句话是假的：既没有下推，也没有排序。
                        // 键列表是一次扫出来的快照，翻页在快照上切片，见 RedisConnection
                        ? "键列表是一次扫描的快照（30 秒内不重扫），翻页在快照上切片"
                        : "排序与筛选下推数据库执行，非本页内排序", "hint"));
        bar.getStyleClass().add("pager");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ------------------------------------------------------------------ 加载

    private void loadPage() {
        int limit = pageSize.getValue();
        SqlDialect.PreparedSql sql = session.connection().dialect()
                .selectPage(schema, table, filter, filterColumns, limit, offset);
        List<String> values = boundValues();
        statusSink.accept("正在读取 " + table + " …");

        context.queryService()
                .submit(() -> session.attributeToTable(
                        session.connection().executeQuery(sql, values, limit), schema, table))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                "读取 " + table + " 失败", error);
                        statusSink.accept("读取失败");
                        return;
                    }
                    grid.setResult(result);
                    formView.setResult(result, grid.editBuffer());
                    refreshDirtyState();
                    pageLabel.setText((offset + 1) + " – " + (offset + result.rows().size()));
                    statusSink.accept("取回 " + result.rows().size() + " 行 · 耗时 "
                            + result.elapsedMillis() + " ms");
                }));
    }

    /** 行数单独异步取：大表上 COUNT(*) 是全表扫描，不能挡着数据显示。 */
    private void loadCount() {
        totalLabel.setText("统计中…");
        // 计数必须用同一套筛选条件，否则翻页会翻到空页
        SqlDialect.PreparedSql sql = session.connection().dialect()
                .countRows(schema, table, filter, filterColumns);
        List<String> values = boundValues();
        context.queryService()
                .submit(() -> session.connection().executeQuery(sql, values, 1))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null || result == null || result.rows().isEmpty()) {
                        totalLabel.setText("未知");
                        return;
                    }
                    String value = result.rows().get(0).get(0);
                    try {
                        totalRows = Long.parseLong(value.trim());
                        totalLabel.setText(UiUtils.groupDigits(totalRows) + " 行"
                                + (filter.conditions().isEmpty() ? "" : "（已筛选）"));
                    } catch (NumberFormatException e) {
                        totalLabel.setText(value + " 行");
                    }
                }));
    }

    /** 条件里的值，顺序必须和占位符一致。 */
    private List<String> boundValues() {
        return filter.conditions().stream().flatMap(c -> c.values().stream()).toList();
    }

    /**
     * 打开筛选对话框。
     *
     * <p>要列的类型才能把值绑对，所以先确保表结构在手上——结构是带缓存的，
     * 第二次打开不会再查一遍。
     */
    private void openFilterDialog() {
        try {
            filterColumns = session.structure(schema, table).columns();
        } catch (RuntimeException e) {
            UiUtils.showError(getScene().getWindow(), "读取表结构失败，无法筛选", e);
            return;
        }
        new FilterDialog(session, schema, table, filterColumns)
                .showAndWait(getScene().getWindow(), filter)
                .ifPresent(spec -> {
                    filter = spec;
                    offset = 0;
                    refreshFilterLabel();
                    loadPage();
                    loadCount();
                });
    }

    /** 导入要目标表的列才能做映射，所以先把结构取到手。 */
    private void openImportDialog() {
        List<ColumnInfo> columns;
        try {
            columns = session.structure(schema, table).columns();
        } catch (RuntimeException e) {
            UiUtils.showError(getScene().getWindow(), "读取表结构失败，无法导入", e);
            return;
        }
        ImportDialog dialog = new ImportDialog(context, session, schema, table, columns);
        dialog.setOnFinished(() -> {
            offset = 0;
            loadPage();
            loadCount();
        });
        dialog.showAndWait(getScene().getWindow());
    }

    /** 查找替换只动本页的编辑缓冲，所以必须有结果、且这一页可编辑。 */
    private void openFindReplace() {
        if (grid.result() == null || grid.editBuffer() == null) {
            return;
        }
        String reason = grid.result().readOnlyReason();
        if (reason != null) {
            UiUtils.showInfo(getScene().getWindow(), "这一页不能改", reason);
            return;
        }
        FindReplaceDialog dialog = new FindReplaceDialog(grid.result(), grid.editBuffer());
        dialog.setOnChanged(() -> {
            grid.table().refresh();
            refreshDirtyState();
        });
        dialog.show(getScene().getWindow());
    }

    /**
     * 点一次列头换一种排法：升序 → 降序 → 不排。
     *
     * <p>排序<b>下推给数据库</b>，不是在当前页里排——页内排出来的顺序是假的：
     * 一万行的表只取了两百行，按金额排出来的「最大」只是这两百行里的最大。
     * 所以每次都回到第一页重新取。
     *
     * <p>点列头只管一列。多列排序仍然走「筛选与排序」那个对话框——
     * 那里能表达优先级，而连点几个列头表达不了。
     */
    private void toggleSort(String column) {
        /*
         * 先确保列定义在手上。
         *
         * selectPage 会把「找不到列定义」的排序项默默丢掉——而 filterColumns
         * 原来只在打开过筛选对话框之后才加载。不补这一步，点列头会毫无反应：
         * 语句照发，ORDER BY 那一段被悄悄删掉了，最难查的那种。
         */
        if (filterColumns.isEmpty()) {
            try {
                filterColumns = session.structure(schema, table).columns();
            } catch (RuntimeException e) {
                UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                        "读取表结构失败，无法排序", e);
                return;
            }
        }

        FilterSpec.Sort current = filter.sorts().size() == 1 ? filter.sorts().get(0) : null;
        List<FilterSpec.Sort> next;
        if (current == null || !current.column().equals(column)) {
            next = List.of(new FilterSpec.Sort(column, false));
        } else if (!current.descending()) {
            next = List.of(new FilterSpec.Sort(column, true));
        } else {
            next = List.of();
        }

        filter = new FilterSpec(filter.conditions(), next);
        grid.setSort(next.isEmpty() ? null : column, !next.isEmpty() && next.get(0).descending());
        offset = 0;
        refreshFilterLabel();
        loadPage();
    }

    private void refreshFilterLabel() {
        int conditions = filter.conditions().size();
        int sorts = filter.sorts().size();
        if (conditions == 0 && sorts == 0) {
            filterLabel.setText("");
            filterLabel.setVisible(false);
            filterButton.getStyleClass().remove("primary");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (conditions > 0) {
            sb.append(conditions).append(" 个条件");
        }
        if (sorts > 0) {
            sb.append(sb.length() > 0 ? " · " : "").append(sorts).append(" 项排序");
        }
        filterLabel.setText(sb.toString());
        filterLabel.setVisible(true);
        if (!filterButton.getStyleClass().contains("primary")) {
            filterButton.getStyleClass().add("primary");
        }
    }

    private void loadStructure(int view) {
        context.queryService().submit(() -> session.structure(schema, table))
                .whenComplete((structure, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                "读取表结构失败", error);
                        return;
                    }
                    ddlPane.setStructure(session.connection().dialect(), schema, structure);
                }));
    }

    // ------------------------------------------------------------------ 写回

    private void refreshDirtyState() {
        EditBuffer buffer = grid.editBuffer();
        boolean dirty = buffer != null && buffer.hasChanges();
        saveButton.setDisable(!dirty);
        discardButton.setDisable(!dirty);
        saveButton.setText(dirty ? "保存 (" + buffer.changeCount() + ")" : "保存");
        statusSink.accept(dirty ? buffer.describeChanges() + " · 未保存" : "");
    }

    /**
     * 把编辑缓冲里的改动写回数据库。
     *
     * <h2>删除要先问一句</h2>
     * 改值和新增写错了还能再改回来，删掉的行找不回来。所以只要这一批里有 DELETE，
     * 就先摆出条数让用户确认——和执行 SQL 前拦没有 WHERE 的 DELETE 是同一个道理。
     *
     * <h2>为什么不整批塞进一个事务</h2>
     * 因为多数库的 DML 事务是能回滚的，这一点本来可以做；但当前连接的自动提交状态
     * 由用户自己掌握（见「事务」那一组按钮）。在这里私自开一个事务，会把用户手上
     * 那个还没提交的事务搅乱。手动事务模式下这一批本来就在他的事务里，
     * 自动提交模式下则逐条落库——两种都符合用户此刻对这条连接的预期。
     */
    private void commitEdits() {
        saveButton.setDisable(true);
        GridWriteBack.commit(context, session, schema, table, grid, getScene().getWindow(),
                statusSink,
                () -> {
                    loadPage();
                    loadCount();   // 增删过行，总行数变了
                },
                this::refreshDirtyState);
    }

    // ------------------------------------------------------------------ 键值库

    /**
     * 这条连接是不是键值库。
     *
     * <h2>为什么不能拿「有没有表结构」当替身</h2>
     * 这两件事以前是同一件——没有表结构的只有 Redis。加进 MongoDB 之后就分家了：
     * 集合同样没有固定结构，但它<b>不是</b>键值库，没有键、没有 TTL，
     * 也不该出现键名搜索条。
     *
     * <p>拿替身判断的代价是具体的：{@link #keyValueStore()} 会把连接强转成
     * 键值存储接口，Mongo 走到那儿就是一次类型转换异常。所以这里直接问
     * 「它是不是那个接口」，而不是问一个碰巧相关的问题。
     */
    private boolean isKeyValue() {
        return session.connection() instanceof com.plainly.driver.kv.KeyValueStore;
    }

    /**
     * 键值库才有的键名搜索条，插在网格上方。
     *
     * <p>别的库这里什么也不做，理由见 {@link #isKeyValue()}。
     */
    private void installKeySearchBar() {
        if (!isKeyValue()) {
            return;
        }
        keySearchBar = keySearchBox();
        keySearchBar.getStyleClass().add("key-search-bar");
        VBox stack = new VBox(keySearchBar, content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        setCenter(stack);
    }

    private HBox keySearchBox() {
        keySearchField.setPromptText("搜索键名，如 1001 或 user:*:tmp");
        keySearchField.setPrefWidth(300);
        keySearchField.setMinWidth(180);
        keySearchField.setOnAction(e -> applyKeySearch());
        keySearchField.textProperty().addListener((o, was, is) -> updateKeySearchHint(is));

        Button go = UiUtils.toolButton("搜索", Icons.search(Icons.NEUTRAL, 12));
        go.setOnAction(e -> applyKeySearch());

        Button clear = UiUtils.toolButton("清除", null);
        clear.setOnAction(e -> {
            keySearchField.clear();
            applyKeySearch();
        });

        updateKeySearchHint(keySearchField.getText());
        keySearchHint.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        Label caption = UiUtils.label("搜索键名", "form-label");
        caption.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox box = UiUtils.row(8, caption, keySearchField, go, clear,
                keySearchHint, UiUtils.hSpacer());
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    private com.plainly.driver.kv.KeyValueStore keyValueStore() {
        return (com.plainly.driver.kv.KeyValueStore) session.connection();
    }

    /** 把输入变成 glob 并显示出来。空输入时什么也不说，免得平白多一行字。 */
    private void updateKeySearchHint(String text) {
        String glob = keyValueStore().searchPattern(text);
        if (glob.isEmpty()) {
            keySearchHint.setText("");
            return;
        }
        keySearchHint.setText(glob.equals(text == null ? "" : text.trim())
                ? "按通配符匹配" : "实际匹配 " + glob);
    }

    /**
     * 把搜索词挂到筛选条件上，走的还是原来那条取数管道。
     *
     * <p>好处是计数那一路自动也带上了：分页器上显示的是「搜到多少个」，
     * 而不是「这个键空间一共多少个」。两个数不一致的话，翻页会翻到空页。
     */
    private void applyKeySearch() {
        String glob = keyValueStore().searchPattern(keySearchField.getText());
        filter = glob.isEmpty()
                ? FilterSpec.empty()
                : new FilterSpec(List.of(new FilterSpec.Condition(
                        FilterSpec.Combiner.AND, com.plainly.driver.kv.KeyValueStore.KEY_COLUMN,
                        FilterSpec.Operator.EQ, glob, null)),
                        List.of());
        filterLabel.setText(glob.isEmpty() ? "" : "键名 " + glob);
        offset = 0;
        loadPage();
        loadCount();
    }

    /**
     * 键值库上的增删改。
     *
     * <p>为什么是四个独立按钮，而不是让人在网格里直接编辑：网格那一列是<b>预览</b>
     * （截断过的）。在截断的内容上改再保存，会把剩下的部分抹掉，而且保存还会显示成功。
     * 走「改值」进去的那个框，第一件事就是把完整的值重新读回来。
     */
    private List<javafx.scene.Node> keyActions() {
        Button create = UiUtils.toolButton("新建键", Icons.plus(Icons.NEUTRAL, 12));
        create.setOnAction(e -> openKeyDialog(null));

        Button edit = UiUtils.toolButton("改值", Icons.file(Icons.NEUTRAL, 12));
        edit.setOnAction(e -> {
            String key = singleSelectedKey("改值");
            if (key != null) {
                openKeyDialog(key);
            }
        });

        Button rename = UiUtils.toolButton("重命名", Icons.format(Icons.NEUTRAL, 12));
        rename.setOnAction(e -> renameSelectedKey());

        Button drop = UiUtils.toolButton("删除键", Icons.minus("#a0402a", 12), "danger");
        drop.setOnAction(e -> deleteSelectedKeys());

        // 频道监听：常驻窗口，不是标签页——用户开着它去做别的事，
        // 而「切走的这段时间有没有消息」正是监听要回答的问题
        Button pubsub = UiUtils.toolButton("频道监听", Icons.search(Icons.NEUTRAL, 12));
        pubsub.setOnAction(e -> new RedisPubSubDialog(session)
                .show(getScene() == null ? null : getScene().getWindow()));

        // 队列监听：选中某个键时带着它打开，省得再敲一遍键名
        Button queue = UiUtils.toolButton("队列监听", Icons.table(Icons.NEUTRAL, 12));
        queue.setOnAction(e -> new RedisQueueDialog(session, selectedKeyOrNull())
                .show(getScene() == null ? null : getScene().getWindow()));

        Button lockView = UiUtils.toolButton("分布式锁", Icons.key(Icons.NEUTRAL, 12));
        lockView.setOnAction(e -> new RedisLockDialog(session)
                .show(getScene() == null ? null : getScene().getWindow()));

        return List.of(create, edit, rename, drop,
                UiUtils.vSeparator(), pubsub, queue, lockView);
    }

    /**
     * 选中的那个键，没选就返回 null。
     *
     * <p>和 {@link #singleSelectedKey} 的区别是<b>不弹提示</b>：
     * 打开队列监听时带上当前选中的键只是个方便，没选也完全正常——
     * 用户可以在窗口里自己敲。为这种情况弹一个「先选一个键」的框是打扰。
     */
    private String selectedKeyOrNull() {
        List<String> keys = selectedKeys();
        return keys.size() == 1 ? keys.get(0) : null;
    }

    /** 选中的键；一个也没选或选了多个时说清楚，而不是默默拿第一个。 */
    private String singleSelectedKey(String action) {
        List<String> keys = selectedKeys();
        if (keys.size() != 1) {
            UiUtils.showInfo(getScene().getWindow(), action,
                    keys.isEmpty() ? "先在下面点中一个键。"
                            : "「" + action + "」一次只能对一个键，现在选中了 " + keys.size() + " 个。");
            return null;
        }
        return keys.get(0);
    }

    /** 选中行对应的键名。键在第一列——这是 RedisConnection 定的列顺序。 */
    private List<String> selectedKeys() {
        QueryResult result = grid.result();
        if (result == null) {
            return List.of();
        }
        List<String> keys = new java.util.ArrayList<>();
        for (int index : grid.selectedRowIndexes()) {
            if (index >= 0 && index < result.rows().size()) {
                keys.add(result.rows().get(index).get(0));
            }
        }
        return keys;
    }

    private void openKeyDialog(String key) {
        RedisKeyDialog dialog = new RedisKeyDialog(context, session, schema, key);
        dialog.setOnSaved(this::reloadAfterWrite);
        dialog.show(getScene().getWindow());
    }

    private void renameSelectedKey() {
        String key = singleSelectedKey("重命名");
        if (key == null) {
            return;
        }
        String target = UiUtils.prompt(getScene().getWindow(), "重命名",
                "把「" + key + "」改成：", key);
        if (target == null || target.isBlank() || target.equals(key)) {
            return;
        }
        com.plainly.driver.kv.KeyValueStore store =
                (com.plainly.driver.kv.KeyValueStore) session.connection();

        context.queryService().submit(() -> store.exists(schema, target))
                .whenComplete((exists, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene().getWindow(), "重命名失败", error);
                        return;
                    }
                    // RENAME 会直接覆盖同名的键，这是 Redis 的语义，不是本工具加的。
                    // 覆盖掉的那份没有任何办法找回来，所以先问一句
                    if (Boolean.TRUE.equals(exists) && !UiUtils.confirm(getScene().getWindow(),
                            "目标名已存在",
                            "「" + target + "」已经存在了。改名会把它整个覆盖掉，"
                            + "被覆盖的内容找不回来。确定继续？")) {
                        return;
                    }
                    context.queryService().submit(() -> {
                        store.rename(schema, key, target);
                        return null;
                    }).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            UiUtils.showError(getScene().getWindow(), "重命名失败", failure);
                            return;
                        }
                        statusSink.accept("已改名为 " + target);
                        reloadAfterWrite();
                    }));
                }));
    }

    private void deleteSelectedKeys() {
        List<String> keys = selectedKeys();
        if (keys.isEmpty()) {
            UiUtils.showInfo(getScene().getWindow(), "删除键", "先在下面点中要删的键。");
            return;
        }
        StringBuilder msg = new StringBuilder("将从 ").append(schema).append(" 删除 ")
                .append(keys.size()).append(" 个键，删掉就找不回来了：\n\n");
        keys.stream().limit(10).forEach(k -> msg.append("  ").append(k).append('\n'));
        if (keys.size() > 10) {
            msg.append("  …还有 ").append(keys.size() - 10).append(" 个\n");
        }
        if (!UiUtils.confirm(getScene().getWindow(), "删除键", msg.toString())) {
            return;
        }
        com.plainly.driver.kv.KeyValueStore store =
                (com.plainly.driver.kv.KeyValueStore) session.connection();
        context.queryService().submit(() -> store.delete(schema, keys))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene().getWindow(), "删除失败", error);
                        return;
                    }
                    statusSink.accept("已删除 " + count + " 个键");
                    reloadAfterWrite();
                }));
    }

    /** 写完之后重新取数：键名快照在驱动那边已经作废，这里只管刷新界面。 */
    private void reloadAfterWrite() {
        session.invalidateTable(schema, table);
        loadPage();
        loadCount();
    }

    private void openExportDialog() {
        QueryResult current = grid.result();
        if (current == null) {
            return;
        }
        new ExportDialog(context, session, schema, table, current, totalRows)
                .showAndWait(getScene().getWindow());
    }
}
