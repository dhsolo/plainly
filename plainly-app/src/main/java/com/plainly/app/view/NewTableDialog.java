package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.TypeNames;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableDiff;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.converter.DefaultStringConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 新建表。
 *
 * <p>建表语句是现拼的，但拼的是同一段代码：{@code SqlDialect.createTableDdl} 既用于
 * 「照着现有表生成 DDL」，也用于这里的「照着草稿建表」。两处各写各的迟早会长歪。
 *
 * <p>下方一直显示将要执行的语句。建表这种事没有撤销，执行前让人看清楚它究竟要干什么，
 * 比事后解释便宜得多。
 */
public class NewTableDialog {

    private final AppContext context;
    private final DbSession session;
    private final String schema;

    private final TextField nameField = new TextField();
    private final ObservableList<ColumnDraft> drafts = FXCollections.observableArrayList();
    private final TableView<ColumnDraft> fields = new TableView<>();
    private final TextArea preview = new TextArea();
    private final Label problemLabel = UiUtils.label("", "status-error");
    private final Button createButton = UiUtils.toolButton("创建", null, "primary");

    private Stage stage;
    private Runnable onCreated = () -> { };
    private String pendingSql;

    public NewTableDialog(AppContext context, DbSession session, String schema) {
        this.context = context;
        this.session = session;
        this.schema = schema;
    }

    /** 建好之后通知外面刷新树。 */
    public void setOnCreated(Runnable handler) {
        this.onCreated = handler;
    }

    public void showAndWait(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("新建表");

        buildFieldTable();
        // 一张空表没法建，先给一个 id 主键——十有八九就是要它。
        // 类型必须按库来：Oracle 没有 BIGINT（ORA-00902），
        // SQLite 的自增只认正好是 INTEGER 的主键。原来这里写的是
        // 「? "BIGINT" : "BIGINT"」——两个分支一模一样，等于没分支
        ColumnDraft id = ColumnDraft.added("id");
        id.setNativeType(com.plainly.driver.TypeNames.defaultKeyType(session.config().type()));
        id.setNullable(false);
        id.setPrimaryKey(true);
        id.setAutoIncrement(true);
        drafts.add(id);

        nameField.setPromptText("表名");
        nameField.setPrefWidth(240);
        nameField.textProperty().addListener((o, was, is) -> recompute());

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(7);

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        recompute();
        Platform.runLater(nameField::requestFocus);
        stage.showAndWait();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.table(Icons.ACCENT, 14),
                UiUtils.label(schema, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        Button add = UiUtils.toolButton("添加字段", Icons.plus(Icons.NEUTRAL, 11));
        add.setOnAction(e -> {
            drafts.add(ColumnDraft.added("column_" + (drafts.size() + 1)));
            recompute();
        });
        Button drop = UiUtils.toolButton("删除字段", Icons.minus(Icons.NEUTRAL, 11));
        drop.setOnAction(e -> {
            ColumnDraft selected = fields.getSelectionModel().getSelectedItem();
            if (selected != null) {
                drafts.remove(selected);
                recompute();
            }
        });

        HBox nameBar = UiUtils.row(10, UiUtils.label("表名", "form-label"), nameField);
        nameBar.setPadding(new Insets(12, 12, 4, 12));
        nameBar.setAlignment(Pos.CENTER_LEFT);

        HBox tools = UiUtils.row(6, add, drop, UiUtils.hSpacer(),
                UiUtils.label("类型可以直接敲库里特有的写法", "hint"));
        tools.getStyleClass().add("grid-toolbar");

        VBox.setVgrow(fields, Priority.ALWAYS);
        VBox box = UiUtils.column(0, nameBar, tools, fields,
                UiUtils.column(4,
                        UiUtils.label("将要执行", "section-label"),
                        preview));
        box.setPadding(new Insets(0, 12, 8, 12));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        createButton.setOnAction(e -> create());

        HBox foot = UiUtils.row(8, problemLabel, UiUtils.hSpacer(), cancel, createButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 字段表

    private void buildFieldTable() {
        fields.setItems(drafts);
        fields.setEditable(true);
        fields.getStyleClass().add("data-grid");
        fields.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        fields.setPlaceholder(UiUtils.label("还没有字段", "hint"));

        fields.getColumns().addAll(
                text("字段名", 170, ColumnDraft::name, ColumnDraft::setName),
                typeColumn(),
                number("长度", 72, ColumnDraft::precision, ColumnDraft::setPrecision,
                        d -> TypeNames.takesLength(d.nativeType())),
                number("小数位", 70, ColumnDraft::scale, ColumnDraft::setScale,
                        d -> TypeNames.takesScale(d.nativeType())),
                check("非空", 60, d -> !d.nullable(), (d, v) -> d.setNullable(!v)),
                check("主键", 58, ColumnDraft::primaryKey, ColumnDraft::setPrimaryKey),
                check("自增", 58, ColumnDraft::autoIncrement, ColumnDraft::setAutoIncrement),
                text("默认值", 140, d -> d.defaultValue() == null ? "" : d.defaultValue(),
                        ColumnDraft::setDefaultValue),
                text("注释", 200, ColumnDraft::comment, ColumnDraft::setComment));
    }

    private TableColumn<ColumnDraft, String> text(String title, double width,
                                                  Function<ColumnDraft, String> getter,
                                                  BiConsumer<ColumnDraft, String> setter) {
        TableColumn<ColumnDraft, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                getter.apply(c.getValue()) == null ? "" : getter.apply(c.getValue())));
        col.setCellFactory(c -> new DraftFieldCells.TextCell(
                new DefaultStringConverter(), DraftFieldCells.NO_STYLE));
        col.setOnEditCommit(e -> {
            setter.accept(e.getRowValue(), e.getNewValue());
            recompute();
            fields.refresh();
        });
        return col;
    }

    /** 类型列给常用类型，也允许直接敲——各家总有自己的类型名。 */
    private TableColumn<ColumnDraft, String> typeColumn() {
        TableColumn<ColumnDraft, String> col = new TableColumn<>("数据类型");
        col.setPrefWidth(150);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nativeType()));
        ObservableList<String> types = FXCollections.observableArrayList(
                TypeNames.catalogFor(session.config().type()));
        col.setCellFactory(c -> new DraftFieldCells.TypeCell(types, DraftFieldCells.NO_STYLE));
        col.setOnEditCommit(e -> {
            e.getRowValue().setNativeType(e.getNewValue());
            recompute();
            fields.refresh();
        });
        return col;
    }

    /**
     * 数字列。
     *
     * <p>用普通文本框收，自己解析成整数——绝不用会替我们做数值转换的输入控件，
     * 精度这件事在这个项目里没有例外。
     */
    private TableColumn<ColumnDraft, String> number(String title, double width,
                                                    Function<ColumnDraft, Integer> getter,
                                                    java.util.function.ObjIntConsumer<ColumnDraft> setter,
                                                    Predicate<ColumnDraft> applicable) {
        TableColumn<ColumnDraft, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> {
            ColumnDraft d = c.getValue();
            return new SimpleStringProperty(
                    !applicable.test(d) || getter.apply(d) <= 0 ? "" : String.valueOf(getter.apply(d)));
        });
        col.setCellFactory(c -> new DraftFieldCells.TextCell(
                new DefaultStringConverter(), DraftFieldCells.NO_STYLE));
        col.setOnEditCommit(e -> {
            String raw = e.getNewValue() == null ? "" : e.getNewValue().trim();
            int value = 0;
            if (!raw.isEmpty()) {
                try {
                    value = Integer.parseInt(raw);
                } catch (NumberFormatException ex) {
                    value = 0;
                }
            }
            setter.accept(e.getRowValue(), Math.max(0, value));
            recompute();
            fields.refresh();
        });
        return col;
    }

    private TableColumn<ColumnDraft, Boolean> check(String title, double width,
                                                    Function<ColumnDraft, Boolean> getter,
                                                    BiConsumer<ColumnDraft, Boolean> setter) {
        TableColumn<ColumnDraft, Boolean> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleBooleanProperty(getter.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    ColumnDraft row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        setter.accept(row, box.isSelected());
                        recompute();
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
        });
        return col;
    }

    // ------------------------------------------------------------------ 生成

    private void recompute() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        List<String> problems = new ArrayList<>();
        if (name.isEmpty()) {
            problems.add("还没填表名");
        } else if (!name.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            problems.add("表名 " + name + " 含有需要引号包裹的字符，建议改成字母、数字与下划线");
        }
        problems.addAll(TableDiff.validate(new ArrayList<>(drafts)));

        if (!problems.isEmpty()) {
            problemLabel.setText(String.join("；", problems));
            preview.setText("");
            pendingSql = null;
            createButton.setDisable(true);
            return;
        }

        List<String> pk = drafts.stream().filter(ColumnDraft::primaryKey)
                .map(ColumnDraft::name).toList();
        try {
            pendingSql = session.connection().dialect()
                    .createTableDdl(schema, name, new ArrayList<>(drafts), pk);
        } catch (RuntimeException e) {
            problemLabel.setText(UiUtils.rootMessage(e));
            preview.setText("");
            pendingSql = null;
            createButton.setDisable(true);
            return;
        }

        problemLabel.setText(pk.isEmpty()
                ? "没有主键：这张表建好之后不能在网格里改数据" : "");
        preview.setText(pendingSql + ";");
        createButton.setDisable(false);
    }

    private void create() {
        if (pendingSql == null) {
            return;
        }
        String sql = pendingSql;
        createButton.setDisable(true);
        context.queryService()
                .submit(() -> session.executeDdl(schema, List.of(sql)))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "建表失败", error);
                        createButton.setDisable(false);
                        return;
                    }
                    session.invalidate();
                    onCreated.run();
                    stage.close();
                }));
    }
}
