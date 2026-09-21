package com.plainly.app.view;

import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.query.FilterSpec;
import com.plainly.driver.query.FilterSpec.Combiner;
import com.plainly.driver.query.FilterSpec.Condition;
import com.plainly.driver.query.FilterSpec.Operator;
import com.plainly.driver.query.FilterSpec.Sort;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 筛选与排序。
 *
 * <p>筛选在数据库里执行，不是在已加载的那一页里过滤——页上只有当前这几百行，
 * 在上面筛出来的结果对整张表没有意义。这件事在界面上直说，因为它决定了用户
 * 该怎么理解看到的行数。
 *
 * <p>下方一直显示将要下推的片段和参数。值是绑定的、不进 SQL 文本：
 * 长数值拼进语句会被驱动按 double 解析或被数据库按字面量推断类型，两条路都丢精度。
 */
public class FilterDialog {

    private final DbSession session;
    private final String schema;
    private final String table;
    private final List<ColumnInfo> columns;

    private final VBox conditionRows = new VBox(6);
    private final VBox sortRows = new VBox(6);
    private final TextArea preview = new TextArea();

    private Stage stage;
    private FilterSpec result;

    public FilterDialog(DbSession session, String schema, String table,
                        List<ColumnInfo> columns) {
        this.session = session;
        this.schema = schema;
        this.table = table;
        this.columns = columns;
    }

    /** @return 用户点了「应用」时给出新的筛选，取消则为空 */
    public Optional<FilterSpec> showAndWait(Window owner, FilterSpec current) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("筛选与排序");

        current.conditions().forEach(this::addConditionRow);
        current.sorts().forEach(this::addSortRow);
        if (current.conditions().isEmpty()) {
            addConditionRow(null);
        }

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(5);

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 860, 620);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        refreshPreview();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.filter(Icons.ACCENT, 14),
                UiUtils.label(schema + "." + table, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        Button addCondition = UiUtils.toolButton("添加条件", Icons.plus(Icons.NEUTRAL, 11));
        addCondition.setOnAction(e -> {
            addConditionRow(null);
            refreshPreview();
        });
        Button addSort = UiUtils.toolButton("添加排序", Icons.plus(Icons.NEUTRAL, 11));
        addSort.setOnAction(e -> {
            addSortRow(null);
            refreshPreview();
        });

        VBox conditions = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("筛选条件", "section-label"), addCondition),
                conditionRows);
        VBox sorts = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("排序", "section-label"), addSort),
                sortRows);

        VBox previewBox = UiUtils.column(4,
                UiUtils.row(10, UiUtils.label("将下推数据库执行的片段", "section-label"),
                        UiUtils.label("值以参数绑定，不拼进 SQL", "hint")),
                preview);
        VBox.setVgrow(preview, Priority.ALWAYS);

        VBox body = UiUtils.column(16, conditions, sorts, previewBox);
        body.setPadding(new Insets(14, 14, 10, 14));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private HBox buildFoot() {
        Label note = UiUtils.label("筛选在数据库执行，不是在已加载的这一页里过滤", "hint");

        Button clear = UiUtils.toolButton("清空", null);
        clear.setOnAction(e -> {
            conditionRows.getChildren().clear();
            sortRows.getChildren().clear();
            addConditionRow(null);
            refreshPreview();
        });
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        Button apply = UiUtils.toolButton("应用", null, "primary");
        apply.setOnAction(e -> {
            result = collect();
            stage.close();
        });

        HBox foot = UiUtils.row(8, note, UiUtils.hSpacer(), clear, cancel, apply);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 条件行

    private void addConditionRow(Condition seed) {
        ComboBox<Combiner> combiner = new ComboBox<>();
        combiner.getItems().addAll(Combiner.values());
        combiner.setValue(seed == null ? Combiner.AND : seed.combiner());
        combiner.setPrefWidth(78);

        ComboBox<String> column = new ComboBox<>();
        columns.forEach(c -> column.getItems().add(c.name()));
        column.setValue(seed != null ? seed.column()
                : (columns.isEmpty() ? null : columns.get(0).name()));
        column.setPrefWidth(180);

        ComboBox<Operator> operator = new ComboBox<>();
        operator.getItems().addAll(Operator.values());
        operator.setValue(seed == null ? Operator.EQ : seed.operator());
        operator.setPrefWidth(120);

        TextField value = new TextField(seed == null ? "" : nz(seed.value()));
        value.setPromptText("值");
        HBox.setHgrow(value, Priority.ALWAYS);
        TextField value2 = new TextField(seed == null ? "" : nz(seed.value2()));
        value2.setPromptText("到");
        value2.setPrefWidth(150);

        Label typeHint = UiUtils.label("", "hint");

        Button remove = UiUtils.toolButton("", Icons.minus(Icons.NEUTRAL, 10));
        HBox row = UiUtils.row(6, combiner, column, operator, value, value2, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox holder = UiUtils.column(2, row, typeHint);

        Runnable sync = () -> {
            int arity = operator.getValue() == null ? 1 : operator.getValue().arity();
            value.setVisible(arity >= 1);
            value.setManaged(arity >= 1);
            value2.setVisible(arity >= 2);
            value2.setManaged(arity >= 2);
            typeHint.setText(describeColumn(column.getValue()));
            refreshPreview();
        };
        combiner.setOnAction(e -> refreshPreview());
        column.setOnAction(e -> sync.run());
        operator.setOnAction(e -> sync.run());
        value.textProperty().addListener((o, was, is) -> refreshPreview());
        value2.textProperty().addListener((o, was, is) -> refreshPreview());
        remove.setOnAction(e -> {
            conditionRows.getChildren().remove(holder);
            refreshPreview();
        });

        // 第一行的连接词不参与拼装，摆着只会让人以为它有用
        combiner.setDisable(conditionRows.getChildren().isEmpty());
        conditionRows.getChildren().add(holder);
        sync.run();
    }

    /** 精确数值列要说清楚：这里输入的是文本，一位都不会在比较前被改写。 */
    private String describeColumn(String name) {
        ColumnInfo info = lookup(name);
        if (info == null) {
            return "";
        }
        String base = info.nativeType();
        if (info.category().isExact()) {
            return base + " · 值按原文绑定为 BigDecimal，不经 double 参与比较";
        }
        return base;
    }

    // ------------------------------------------------------------------ 排序行

    private void addSortRow(Sort seed) {
        ComboBox<String> column = new ComboBox<>();
        columns.forEach(c -> column.getItems().add(c.name()));
        column.setValue(seed != null ? seed.column()
                : (columns.isEmpty() ? null : columns.get(0).name()));
        column.setPrefWidth(200);

        CheckBox desc = new CheckBox("降序");
        desc.setSelected(seed != null && seed.descending());

        Button remove = UiUtils.toolButton("", Icons.minus(Icons.NEUTRAL, 10));
        HBox row = UiUtils.row(8, column, desc, remove);
        row.setAlignment(Pos.CENTER_LEFT);

        column.setOnAction(e -> refreshPreview());
        desc.setOnAction(e -> refreshPreview());
        remove.setOnAction(e -> {
            sortRows.getChildren().remove(row);
            refreshPreview();
        });
        sortRows.getChildren().add(row);
    }

    // ------------------------------------------------------------------ 汇总

    private FilterSpec collect() {
        List<Condition> conditions = new ArrayList<>();
        for (javafx.scene.Node node : conditionRows.getChildren()) {
            HBox row = (HBox) ((VBox) node).getChildren().get(0);
            @SuppressWarnings("unchecked")
            ComboBox<Combiner> combiner = (ComboBox<Combiner>) row.getChildren().get(0);
            @SuppressWarnings("unchecked")
            ComboBox<String> column = (ComboBox<String>) row.getChildren().get(1);
            @SuppressWarnings("unchecked")
            ComboBox<Operator> operator = (ComboBox<Operator>) row.getChildren().get(2);
            TextField value = (TextField) row.getChildren().get(3);
            TextField value2 = (TextField) row.getChildren().get(4);

            if (column.getValue() == null || operator.getValue() == null) {
                continue;
            }
            // 需要值却没填的行直接丢掉：空条件下推过去只会筛出空结果
            if (operator.getValue().arity() >= 1 && value.getText().isBlank()) {
                continue;
            }
            if (operator.getValue().arity() >= 2 && value2.getText().isBlank()) {
                continue;
            }
            conditions.add(new Condition(combiner.getValue(), column.getValue(),
                    operator.getValue(), value.getText(), value2.getText()));
        }

        List<Sort> sorts = new ArrayList<>();
        for (javafx.scene.Node node : sortRows.getChildren()) {
            HBox row = (HBox) node;
            @SuppressWarnings("unchecked")
            ComboBox<String> column = (ComboBox<String>) row.getChildren().get(0);
            CheckBox desc = (CheckBox) row.getChildren().get(1);
            if (column.getValue() != null) {
                sorts.add(new Sort(column.getValue(), desc.isSelected()));
            }
        }
        return new FilterSpec(conditions, sorts);
    }

    private void refreshPreview() {
        FilterSpec spec = collect();
        if (spec.isEmpty()) {
            preview.setText("-- 没有条件，取全表");
            return;
        }
        SqlDialect dialect = session.connection().dialect();
        SqlDialect.PreparedSql sql = dialect.selectPage(schema, table, spec, columns, 200, 0);
        List<String> values = spec.conditions().stream()
                .flatMap(c -> c.values().stream()).toList();

        StringBuilder sb = new StringBuilder(sql.sql());
        if (!values.isEmpty()) {
            sb.append("\n-- 参数：");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(values.get(i) == null ? "NULL" : values.get(i));
            }
        }
        preview.setText(sb.toString());
    }

    private ColumnInfo lookup(String name) {
        for (ColumnInfo c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
