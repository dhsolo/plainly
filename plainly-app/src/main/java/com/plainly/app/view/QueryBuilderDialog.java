package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.model.ModelService;
import com.plainly.core.query.QueryPlan;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.converter.DefaultStringConverter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 查询构建器：选表、连字段、挑列，不写 SQL 也能查数。
 *
 * <p>连接由外键约束自动推导，没有外键时按命名推测——和数据模型图用的是同一套规则，
 * 推测出来的连接会标出来，因为它可能是错的。
 *
 * <p>生成的 SQL 可以直接送进编辑器继续手改。改完就归用户，构建器不会反过来覆盖它：
 * 「双向同步」实际上就是把人写的 SQL 悄悄改掉。
 */
public class QueryBuilderDialog {

    /** 字段网格里的一行。 */
    public static class FieldRow {
        private final String alias;
        private final String table;
        private final String column;
        private String outputName = "";
        private QueryPlan.Aggregate aggregate = QueryPlan.Aggregate.NONE;
        private boolean output = true;
        private String sort = "";
        private String condition = "";

        FieldRow(String alias, String table, String column) {
            this.alias = alias;
            this.table = table;
            this.column = column;
        }

        public String source() {
            return table;
        }

        public String column() {
            return column;
        }
    }

    private final AppContext context;
    private final DbSession session;
    private final String schema;

    private final ListView<String> tableList = new ListView<>();
    private final ListView<String> pickedList = new ListView<>();
    private final TableView<QueryPlan.Join> joinTable = new TableView<>();
    private final TableView<FieldRow> fieldTable = new TableView<>();
    private final TextArea sqlArea = new TextArea();
    private final Label joinHint = UiUtils.label("", "hint");

    private final List<QueryPlan.Source> sources = new ArrayList<>();
    private final List<QueryPlan.Join> joins = new ArrayList<>();
    private final List<ColumnInfo> allColumns = new ArrayList<>();

    private Stage stage;
    private BiConsumer<String, String> onSendToEditor = (sql, schema) -> { };

    public QueryBuilderDialog(AppContext context, DbSession session, String schema) {
        this.context = context;
        this.session = session;
        this.schema = schema;
    }

    /** 「切到 SQL 编辑器」时把生成的语句交出去。 */
    public void setOnSendToEditor(BiConsumer<String, String> handler) {
        this.onSendToEditor = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("查询构建器");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1200, 820);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        loadTables();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.plan(Icons.ACCENT, 14),
                UiUtils.label(schema + " · 连接由外键推导，无外键时按命名推测", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private SplitPane buildBody() {
        tableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Button add = UiUtils.toolButton("加入查询", Icons.plus(Icons.NEUTRAL, 11));
        add.setOnAction(e -> addSelectedTables());
        Button clear = UiUtils.toolButton("清空", null);
        clear.setOnAction(e -> {
            sources.clear();
            joins.clear();
            fieldTable.getItems().clear();
            allColumns.clear();
            refresh();
        });

        VBox left = UiUtils.column(6,
                UiUtils.label("库中的表", "section-label"), tableList,
                UiUtils.row(6, add, clear),
                UiUtils.label("已加入", "section-label"), pickedList);
        left.setPadding(new Insets(10));
        left.setPrefWidth(260);
        VBox.setVgrow(tableList, Priority.ALWAYS);

        buildJoinTable();
        buildFieldTable();
        sqlArea.setEditable(false);
        sqlArea.getStyleClass().add("ddl-area");

        VBox right = UiUtils.column(8,
                UiUtils.row(10, UiUtils.label("连接", "section-label"), joinHint),
                joinTable,
                UiUtils.label("字段", "section-label"), fieldTable,
                UiUtils.row(10, UiUtils.label("生成的 SQL", "section-label"),
                        UiUtils.label("送进编辑器后手改的内容不会被构建器覆盖", "hint")),
                sqlArea);
        right.setPadding(new Insets(10));
        joinTable.setPrefHeight(120);
        VBox.setVgrow(fieldTable, Priority.ALWAYS);
        sqlArea.setPrefRowCount(9);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.22);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void buildJoinTable() {
        joinTable.setEditable(true);
        joinTable.getStyleClass().add("data-grid");
        joinTable.setPlaceholder(UiUtils.label("加入两张以上的表后，这里会给出推导出的连接", "hint"));
        joinTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<QueryPlan.Join, String> type = new TableColumn<>("方式");
        type.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type().sql()));
        type.setCellFactory(c -> new ComboBoxTableCell<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(QueryPlan.JoinType.values())
                        .map(QueryPlan.JoinType::sql).toList())));
        type.setOnEditCommit(e -> {
            int index = joins.indexOf(e.getRowValue());
            if (index >= 0) {
                for (QueryPlan.JoinType t : QueryPlan.JoinType.values()) {
                    if (t.sql().equals(e.getNewValue())) {
                        QueryPlan.Join old = joins.get(index);
                        joins.set(index, new QueryPlan.Join(t, old.leftAlias(), old.leftColumn(),
                                old.rightAlias(), old.rightColumn()));
                        break;
                    }
                }
                refresh();
            }
        });

        TableColumn<QueryPlan.Join, String> on = new TableColumn<>("连接条件");
        on.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().leftAlias() + "." + c.getValue().leftColumn() + " = "
                        + c.getValue().rightAlias() + "." + c.getValue().rightColumn()));

        joinTable.getColumns().addAll(type, on);
    }

    private void buildFieldTable() {
        fieldTable.setEditable(true);
        fieldTable.getStyleClass().add("data-grid");
        fieldTable.setPlaceholder(UiUtils.label("先把表加进查询", "hint"));
        fieldTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<FieldRow, String> source = new TableColumn<>("来源表");
        source.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source()));

        TableColumn<FieldRow, String> column = new TableColumn<>("字段");
        column.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().column()));

        TableColumn<FieldRow, Boolean> output = new TableColumn<>("输出");
        output.setMaxWidth(60);
        output.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().output));
        output.setCellFactory(c -> checkCell((row, value) -> row.output = value));

        TableColumn<FieldRow, String> aggregate = new TableColumn<>("聚合");
        aggregate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().aggregate.toString()));
        aggregate.setCellFactory(c -> new ComboBoxTableCell<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(QueryPlan.Aggregate.values())
                        .map(QueryPlan.Aggregate::toString).toList())));
        aggregate.setOnEditCommit(e -> {
            for (QueryPlan.Aggregate a : QueryPlan.Aggregate.values()) {
                if (a.toString().equals(e.getNewValue())) {
                    e.getRowValue().aggregate = a;
                    break;
                }
            }
            refresh();
        });

        TableColumn<FieldRow, String> alias = new TableColumn<>("别名");
        alias.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().outputName));
        alias.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()));
        alias.setOnEditCommit(e -> {
            e.getRowValue().outputName = e.getNewValue();
            refresh();
        });

        TableColumn<FieldRow, String> sort = new TableColumn<>("排序");
        sort.setMaxWidth(90);
        sort.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sort));
        sort.setCellFactory(c -> new ComboBoxTableCell<>(
                FXCollections.observableArrayList("", "ASC", "DESC")));
        sort.setOnEditCommit(e -> {
            e.getRowValue().sort = e.getNewValue();
            refresh();
        });

        TableColumn<FieldRow, String> condition = new TableColumn<>("条件");
        condition.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().condition));
        condition.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()));
        condition.setOnEditCommit(e -> {
            e.getRowValue().condition = e.getNewValue();
            refresh();
        });

        fieldTable.getColumns().addAll(source, column, output, aggregate, alias, sort, condition);
    }

    private javafx.scene.control.TableCell<FieldRow, Boolean> checkCell(
            java.util.function.BiConsumer<FieldRow, Boolean> setter) {
        return new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    FieldRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        setter.accept(row, box.isSelected());
                        refresh();
                    }
                });
            }

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(Boolean.TRUE.equals(value));
                setGraphic(box);
            }
        };
    }

    private HBox buildFoot() {
        Button toEditor = UiUtils.toolButton("送进 SQL 编辑器", Icons.playLine(Icons.NEUTRAL, 12));
        toEditor.setOnAction(e -> {
            onSendToEditor.accept(sqlArea.getText(), schema);
            stage.close();
        });
        Button copy = UiUtils.toolButton("复制", null);
        copy.setOnAction(e -> {
            sqlArea.selectAll();
            sqlArea.copy();
            sqlArea.deselect();
        });
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close, copy, toEditor);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 装载

    private void loadTables() {
        context.queryService().submit(() -> session.tables(schema))
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "读取表清单失败", error);
                        return;
                    }
                    tableList.setItems(FXCollections.observableArrayList(
                            list.stream().map(TableInfo::name).toList()));
                }));
    }

    private void addSelectedTables() {
        List<String> picked = new ArrayList<>(tableList.getSelectionModel().getSelectedItems());
        if (picked.isEmpty()) {
            return;
        }
        context.queryService().submit(() -> {
            List<String> names = new ArrayList<>();
            sources.forEach(s -> names.add(s.table()));
            names.addAll(picked);
            return ModelService.reverse(session.connection(), schema, names);
        }).whenComplete((model, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "读取表结构失败", error);
                return;
            }
            rebuild(model);
        }));
    }

    /**
     * 按最新的模型重建来源、连接和字段。
     *
     * <p>连接直接取模型里的关系：外键是确定的，按命名推测的也一并给出，
     * 但在提示里说明有几条是推测——用错的连接查出来的数是错的，而且看不出来。
     */
    private void rebuild(ModelService.Model model) {
        sources.clear();
        joins.clear();
        allColumns.clear();
        Set<String> taken = new LinkedHashSet<>();

        for (ModelService.Entity entity : model.entities()) {
            String alias = QueryPlan.aliasFor(entity.name(), taken);
            taken.add(alias);
            sources.add(new QueryPlan.Source(entity.name(), alias));
            allColumns.addAll(entity.columns());
        }

        long guessed = 0;
        for (ModelService.Relation relation : model.relations()) {
            String left = aliasOf(relation.fromTable());
            String right = aliasOf(relation.toTable());
            if (left == null || right == null) {
                continue;
            }
            joins.add(new QueryPlan.Join(QueryPlan.JoinType.INNER,
                    left, relation.fromColumns().get(0),
                    right, relation.toColumns().get(0)));
            if (!relation.confirmed()) {
                guessed++;
            }
        }
        joinHint.setText(joins.isEmpty()
                ? "没有推导出连接，可能需要手写 SQL"
                : joins.size() + " 条" + (guessed > 0 ? "（其中 " + guessed + " 条按命名推测，请核对）" : ""));

        List<FieldRow> rows = new ArrayList<>();
        for (ModelService.Entity entity : model.entities()) {
            String alias = aliasOf(entity.name());
            for (ColumnInfo column : entity.columns()) {
                FieldRow row = new FieldRow(alias, entity.name(), column.name());
                // 默认全不输出：一股脑 SELECT 出几十列，用户还得一个个去掉
                row.output = false;
                rows.add(row);
            }
        }
        fieldTable.setItems(FXCollections.observableArrayList(rows));
        pickedList.setItems(FXCollections.observableArrayList(
                sources.stream().map(s -> s.table() + "  " + s.alias()).toList()));
        refresh();
    }

    private String aliasOf(String table) {
        for (QueryPlan.Source source : sources) {
            if (source.table().equalsIgnoreCase(table)) {
                return source.alias();
            }
        }
        return null;
    }

    private void refresh() {
        joinTable.setItems(FXCollections.observableArrayList(joins));
        fieldTable.refresh();

        List<QueryPlan.Field> fields = new ArrayList<>();
        for (FieldRow row : fieldTable.getItems()) {
            fields.add(new QueryPlan.Field(row.alias, row.column, row.outputName,
                    row.aggregate, false, row.output, row.sort, row.condition));
        }
        sqlArea.setText(QueryPlan.toSql(session.connection().dialect(), schema,
                sources, joins, fields));
    }
}
