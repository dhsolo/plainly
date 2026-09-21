package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.sync.StructureSyncService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DdlBatchException;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构同步。
 *
 * <h2>方向不可调换</h2>
 * 界面上「源」在左、「目标」在右，目标一侧用红点和红底标出，
 * 并且没有「交换方向」按钮——要反向就重新选一次，逼用户再确认一遍。
 * 把测试库的结构刷到生产库上，是这类工具最容易造成的事故。
 */
public class StructureSyncDialog {

    private final AppContext context;
    private final StructureSyncService service = new StructureSyncService();

    private final ComboBox<ConnectionConfig> sourceConn = new ComboBox<>();
    private final ComboBox<String> sourceSchema = new ComboBox<>();
    private final ComboBox<ConnectionConfig> targetConn = new ComboBox<>();
    private final ComboBox<String> targetSchema = new ComboBox<>();

    private final TableView<SchemaChange> changeTable = new TableView<>();
    private final Set<SchemaChange> selected = new LinkedHashSet<>();
    private final TextArea detail = new TextArea();
    private final TextArea sqlPreview = new TextArea();
    private final Label summary = UiUtils.label("尚未比对", "hint");
    private final Label blocker = UiUtils.label("");
    private final Label atomicNote = UiUtils.label("", "hint");

    private final Button compareButton;
    private final Button applyButton;
    private final Button exportButton;

    private StructureSyncService.SyncPlan plan;
    private Stage stage;

    public StructureSyncDialog(AppContext context) {
        this.context = context;
        compareButton = UiUtils.toolButton("开始比对", null, "primary");
        applyButton = UiUtils.toolButton("应用到目标库", null);
        exportButton = UiUtils.toolButton("仅导出脚本", null);
        applyButton.setDisable(true);
        exportButton.setDisable(true);
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("结构同步");

        VBox root = new VBox(buildHead(), buildEndpoints(), buildBody(), buildFooter());
        Scene scene = new Scene(root, 1180, 820);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        loadConnections();
        stage.show();
    }

    // ------------------------------------------------------------------ 顶部

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.export(Icons.ACCENT, 14),
                UiUtils.label("比对两个库的结构，生成把目标改成源的 DDL", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildEndpoints() {
        configureConnectionCombo(sourceConn, sourceSchema);
        configureConnectionCombo(targetConn, targetSchema);

        sourceSchema.setMaxWidth(Double.MAX_VALUE);
        targetSchema.setMaxWidth(Double.MAX_VALUE);
        sourceConn.setMaxWidth(Double.MAX_VALUE);
        targetConn.setMaxWidth(Double.MAX_VALUE);

        VBox source = UiUtils.column(4,
                UiUtils.label("源 · 以它为准", "section-label"),
                UiUtils.row(6, grow(sourceConn), fixed(sourceSchema, 170)));

        VBox target = UiUtils.column(4,
                targetLabel(),
                UiUtils.row(6, grow(targetConn), fixed(targetSchema, 170)));
        targetConn.setStyle("-fx-border-color:-sx-red-border;");

        compareButton.setOnAction(e -> compare());

        HBox.setHgrow(source, Priority.ALWAYS);
        HBox.setHgrow(target, Priority.ALWAYS);

        HBox bar = UiUtils.row(12, source,
                Icons.export(Icons.ACCENT, 18), target, compareButton);
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setPadding(new Insets(12, 14, 12, 14));
        bar.setStyle("-fx-border-color: transparent transparent -sx-border-light transparent;"
                + "-fx-border-width: 0 0 1 0;");
        return bar;
    }

    private Label targetLabel() {
        Label l = UiUtils.label("目标 · 将被修改", "section-label");
        l.setStyle("-fx-text-fill:-sx-red;");
        return l;
    }

    private ComboBox<ConnectionConfig> grow(ComboBox<ConnectionConfig> box) {
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private ComboBox<String> fixed(ComboBox<String> box, double width) {
        box.setPrefWidth(width);
        box.setMinWidth(width);
        return box;
    }

    private void configureConnectionCombo(ComboBox<ConnectionConfig> connBox,
                                          ComboBox<String> schemaBox) {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.valueProperty().addListener((o, was, is) -> loadSchemas(is, schemaBox));
    }

    /**
     * 下拉里的一条连接。
     *
     * <h2>为什么用 setText 而不是把名字塞进 setGraphic 的 HBox 里</h2>
     * 原来的写法是 {@code setText(null)} 加一个装着图标和名字的 HBox。
     * 结果：下拉框宽 339px，里面的按钮单元格只有 <b>18px</b>——正好一个图标的宽度，
     * 连接名被压没了，选完之后只看得见一个数据库图标。
     *
     * <p>{@code ListCell} 继承自 {@code Labeled}，它的皮肤只按 text + graphic 这一对
     * 去算宽度；把内容藏进 graphic 里的一个容器，皮肤量不到里面那个 Label 该占多宽。
     * 图标留在 graphic 里（那正是 graphic 的用途），文字交给 text，
     * 皮肤就按它本来的方式排版了。
     */
    private class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.name() + "  ·  " + item.type().displayName()
                    + (item.readOnly() ? "  （只读）" : ""));
            setGraphic(Icons.database(
                    context.isConnected(item.id()) ? Icons.ACCENT : Icons.MUTED, 12));
            setGraphicTextGap(7);
        }
    }

    // ------------------------------------------------------------------ 主体

    private VBox buildBody() {
        buildChangeTable();

        detail.setEditable(false);
        detail.setStyle("-fx-font-family:'Cascadia Mono',Consolas,monospace;-fx-font-size:11.5px;");
        detail.setPrefHeight(150);

        HBox listHead = UiUtils.row(10, UiUtils.label("差异对象", "section-label"), summary,
                UiUtils.hSpacer(),
                UiUtils.label("删表与删索引默认不勾选", "hint"));
        listHead.getStyleClass().add("grid-toolbar");

        HBox detailHead = UiUtils.row(10, UiUtils.label("变更明细", "section-label"));
        detailHead.getStyleClass().add("grid-toolbar");

        sqlPreview.setEditable(false);
        sqlPreview.setStyle("-fx-font-family:'Cascadia Mono',Consolas,monospace;-fx-font-size:12px;");
        sqlPreview.setPrefHeight(180);

        Button copy = UiUtils.toolButton("复制 SQL", null);
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(sqlPreview.getText());
            Clipboard.getSystemClipboard().setContent(c);
        });
        HBox sqlHead = UiUtils.row(10, UiUtils.label("同步脚本", "section-label"),
                atomicNote, UiUtils.hSpacer(), copy);
        sqlHead.getStyleClass().add("grid-toolbar");

        blocker.setWrapText(true);
        blocker.setVisible(false);
        blocker.setManaged(false);

        VBox.setVgrow(changeTable, Priority.ALWAYS);
        VBox body = UiUtils.column(0, listHead, changeTable, detailHead, detail,
                sqlHead, blocker, sqlPreview);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private void buildChangeTable() {
        changeTable.getStyleClass().add("data-grid");
        changeTable.setEditable(true);
        changeTable.setPlaceholder(UiUtils.label("选择源与目标后点「开始比对」", "hint"));
        changeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<SchemaChange, Boolean> pick = new TableColumn<>("");
        pick.setPrefWidth(38);
        pick.setMaxWidth(38);
        pick.setCellValueFactory(cd -> {
            SchemaChange change = cd.getValue();
            SimpleBooleanProperty p = new SimpleBooleanProperty(selected.contains(change));
            p.addListener((o, was, is) -> {
                if (is) {
                    selected.add(change);
                } else {
                    selected.remove(change);
                }
                refreshPlanForSelection();
            });
            return p;
        });
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));

        TableColumn<SchemaChange, String> object = new TableColumn<>("对象");
        object.setPrefWidth(240);
        object.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().objectName()));

        TableColumn<SchemaChange, String> desc = new TableColumn<>("变更");
        desc.setPrefWidth(520);
        desc.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().describe()));

        TableColumn<SchemaChange, String> risk = new TableColumn<>("风险");
        risk.setPrefWidth(130);
        risk.setCellValueFactory(cd -> new SimpleStringProperty(riskLabel(cd.getValue().risk())));
        risk.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                SchemaChange row = getTableRow() == null ? null : getTableRow().getItem();
                setStyle(row == null ? "" : "-fx-text-fill:" + riskColor(row.risk()) + ";");
            }
        });

        changeTable.getColumns().addAll(pick, object, desc, risk);
        changeTable.getSelectionModel().selectedItemProperty()
                .addListener((o, was, is) -> showDetail(is));
    }

    private static String riskLabel(TableChange.Risk risk) {
        switch (risk) {
            case DESTRUCTIVE:
                return "会丢数据";
            case DATA_DEPENDENT:
                return "取决于既有数据";
            default:
                return "安全";
        }
    }

    /**
     * 风险色。返回的是样式表里的<b>令牌名</b>而不是十六进制值。
     *
     * <p>写死颜色的话，暗色主题下这三个点仍然是给亮底调的深色，
     * 在深色背景上几乎看不见——而它们正是这一页最该被看见的东西。
     */
    private static String riskColor(TableChange.Risk risk) {
        switch (risk) {
            case DESTRUCTIVE:
                return "-sx-red";
            case DATA_DEPENDENT:
                return "-sx-amber";
            default:
                return "-sx-green";
        }
    }

    private void showDetail(SchemaChange change) {
        if (change == null) {
            detail.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(change.describe()).append("\n\n");
        if (change instanceof SchemaChange.AlterTable alter) {
            for (TableChange c : alter.changes()) {
                sb.append("  · ").append(c.describe())
                        .append("   [").append(riskLabel(c.risk())).append("]\n");
            }
        } else if (change instanceof SchemaChange.CreateTable ct) {
            ct.table().columns().forEach(c -> sb.append("  · ").append(c.name())
                    .append(' ').append(c.displayType())
                    .append(c.nullable() ? "" : " NOT NULL")
                    .append(c.primaryKey() ? "  [主键]" : "").append('\n'));
        }
        detail.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 底部

    private HBox buildFooter() {
        applyButton.setOnAction(e -> apply());
        exportButton.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(sqlPreview.getText());
            Clipboard.getSystemClipboard().setContent(c);
            UiUtils.showInfo(stage, "已复制", "同步脚本已复制到剪贴板。");
        });

        HBox foot = UiUtils.row(12, UiUtils.hSpacer(), exportButton, applyButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 数据

    private void loadConnections() {
        List<ConnectionConfig> all = context.registry().listAll();
        sourceConn.setItems(FXCollections.observableArrayList(all));
        targetConn.setItems(FXCollections.observableArrayList(all));
    }

    private void loadSchemas(ConnectionConfig config, ComboBox<String> schemaBox) {
        schemaBox.getItems().clear();
        if (config == null) {
            return;
        }
        schemaBox.setPromptText("读取中…");
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return session.schemas();
        }).whenComplete((schemas, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "连接 " + config.name() + " 失败", error);
                schemaBox.setPromptText("连接失败");
                return;
            }
            schemaBox.setItems(FXCollections.observableArrayList(
                    schemas.stream().map(SchemaInfo::name).toList()));
            schemas.stream().filter(SchemaInfo::isDefault).findFirst()
                    .ifPresent(s -> schemaBox.setValue(s.name()));
            if (schemaBox.getValue() == null && !schemaBox.getItems().isEmpty()) {
                schemaBox.setValue(schemaBox.getItems().get(0));
            }
        }));
    }

    private void compare() {
        ConnectionConfig src = sourceConn.getValue();
        ConnectionConfig tgt = targetConn.getValue();
        if (src == null || tgt == null
                || sourceSchema.getValue() == null || targetSchema.getValue() == null) {
            UiUtils.showInfo(stage, "还差一点", "请把源与目标的连接和库都选上。");
            return;
        }
        if (src.id().equals(tgt.id()) && sourceSchema.getValue().equals(targetSchema.getValue())) {
            UiUtils.showInfo(stage, "源与目标相同", "选了同一个库，没有可比的内容。");
            return;
        }

        compareButton.setDisable(true);
        summary.setText("正在读取结构…");
        changeTable.setItems(FXCollections.observableArrayList());
        selected.clear();

        String srcSchema = sourceSchema.getValue();
        String tgtSchema = targetSchema.getValue();

        context.queryService().submit(() -> {
            DbSession s = context.openSession(src);
            DbSession t = context.openSession(tgt);
            return service.plan(s.connection(), srcSchema, t.connection(), tgtSchema,
                    name -> Platform.runLater(() -> summary.setText("正在读取 " + name + " …")));
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            compareButton.setDisable(false);
            if (error != null) {
                UiUtils.showError(stage, "比对失败", error);
                summary.setText("比对失败");
                return;
            }
            plan = result;
            presentPlan();
        }));
    }

    private void presentPlan() {
        changeTable.setItems(FXCollections.observableArrayList(plan.changes()));

        // 默认勾选规则：不可逆的操作不替用户做决定。
        // 源里没有不等于该删——很可能只是源库还没同步过来。
        selected.clear();
        for (SchemaChange c : plan.changes()) {
            boolean irreversible = c instanceof SchemaChange.DropTable
                    || c instanceof SchemaChange.DropIndex;
            if (!irreversible) {
                selected.add(c);
            }
        }
        changeTable.refresh();

        if (plan.isEmpty()) {
            summary.setText("两库结构一致，没有差异");
        } else {
            long destructive = plan.changes().stream()
                    .filter(c -> c.risk() == TableChange.Risk.DESTRUCTIVE).count();
            summary.setText(plan.changes().size() + " 项差异，已选 " + selected.size()
                    + (destructive > 0 ? " · 其中 " + destructive + " 项会丢数据" : ""));
        }
        refreshPlanForSelection();
    }

    /** 勾选变化后重新生成脚本。预览与执行始终来自同一份计划。 */
    private void refreshPlanForSelection() {
        if (plan == null) {
            return;
        }
        ConnectionConfig tgt = targetConn.getValue();
        DbSession session = tgt == null ? null : context.sessionFor(tgt.id());
        if (session == null) {
            return;
        }

        StructureSyncService.SyncPlan restricted =
                service.restrictTo(plan, session.connection(), targetSchema.getValue(), selected);

        boolean atomic = StructureSyncService.isAtomic(session.connection());
        atomicNote.setText(atomic
                ? "整批在一个事务内执行，失败回滚"
                : "该数据库的 DDL 会隐式提交，无法回滚；失败时会告知已执行到第几条");
        atomicNote.setStyle(atomic ? "" : "-fx-text-fill:-sx-amber;");

        if (!restricted.blockers().isEmpty()) {
            blocker.setText("以下变更当前数据库做不到：\n· "
                    + String.join("\n· ", restricted.blockers()));
            blocker.getStyleClass().setAll("banner-error");
            blocker.setVisible(true);
            blocker.setManaged(true);
            sqlPreview.setText("-- 存在无法生成的变更，见上方说明");
        } else {
            blocker.setVisible(false);
            blocker.setManaged(false);
            sqlPreview.setText(restricted.sql().isEmpty()
                    ? "-- 未选中任何变更"
                    : String.join(";\n", restricted.sql()) + ";");
        }

        boolean canApply = restricted.canApply() && !session.config().readOnly();
        applyButton.setDisable(!canApply);
        exportButton.setDisable(restricted.sql().isEmpty());
        applyButton.getStyleClass().setAll("tool-button");
        applyButton.getStyleClass().add(restricted.hasDestructive() ? "danger" : "primary");
        applyButton.setText("应用到 " + (tgt == null ? "目标" : tgt.name()));
    }

    private void apply() {
        ConnectionConfig tgt = targetConn.getValue();
        DbSession session = context.sessionFor(tgt.id());
        StructureSyncService.SyncPlan restricted =
                service.restrictTo(plan, session.connection(), targetSchema.getValue(), selected);

        boolean atomic = StructureSyncService.isAtomic(session.connection());
        StringBuilder prompt = new StringBuilder("将在 ").append(tgt.name())
                .append(" 上执行 ").append(restricted.sql().size()).append(" 条语句。");
        if (restricted.hasDestructive()) {
            prompt.append("\n\n其中包含会永久丢失数据的操作。");
        }
        if (!atomic) {
            prompt.append("\n\n该数据库的 DDL 无法回滚。若中途失败，前面已执行的变更会保留。");
        }
        if (!UiUtils.confirm(stage, "应用结构同步", prompt.toString())) {
            return;
        }

        applyButton.setDisable(true);
        summary.setText("正在应用…");

        context.queryService().submit(() -> service.apply(session.connection(), restricted))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        Throwable root = error instanceof java.util.concurrent.CompletionException
                                ? error.getCause() : error;
                        boolean partial = root instanceof DdlBatchException dbe
                                && dbe.leftPartialState();
                        UiUtils.showError(stage,
                                partial ? "同步失败，且目标库已被改了一半" : "同步失败", error);
                        // 目标库可能已经变了，重新比一次让用户看到真实状态
                        session.invalidate();
                        compare();
                        return;
                    }
                    summary.setText("已应用 " + count + " 条语句");
                    session.invalidate();
                    // 重新比对：成功的话应当归零，这也是给用户的一次确认
                    compare();
                }));
    }
}
