package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.model.ModelService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 数据模型图：由现有库逆向生成。
 *
 * <p>图上有两条线索。其一，精确数值列用琥珀色标出来——金额链路上的每一列都在这儿，
 * 一眼能看出两端的精度约定是否一致（{@code discount_rate} 与 {@code discount}
 * 都是 DECIMAL(8,6) 才对得上）。其二，虚线是按命名推测的关系，库里没有对应外键约束；
 * 常见于历史库，值得确认是有意为之还是漏建。
 */
public class ErDiagramDialog {

    private static final double BOX_WIDTH = 230;
    private static final double ROW_HEIGHT = 19;
    private static final double HEADER_HEIGHT = 34;
    private static final double GAP_X = 120;
    private static final double GAP_Y = 70;

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private final Pane canvas = new Pane();
    private final Group canvasGroup = new Group(canvas);
    private final Slider zoom = new Slider(0.4, 1.6, 1.0);
    private final Label summary = UiUtils.label("尚未逆向", "hint");
    private final VBox relationList = new VBox(3);

    private final Map<String, VBox> boxes = new LinkedHashMap<>();
    private ModelService.Model model;
    private Stage stage;

    public ErDiagramDialog(AppContext context) {
        this.context = context;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("数据模型");

        BorderPane root = new BorderPane();
        root.setTop(new VBox(buildHead(), buildToolbar()));
        root.setCenter(buildBody());
        root.setBottom(buildFoot());

        Scene scene = new Scene(root, 1240, 820);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(javafx.collections.FXCollections.observableArrayList(
                context.registry().listAll()));
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.plan(Icons.ACCENT, 14),
                UiUtils.label("由现有库逆向生成", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildToolbar() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(220);
        connBox.valueProperty().addListener((o, was, is) -> loadSchemas(is));
        schemaBox.setPrefWidth(180);

        Button reverse = UiUtils.toolButton("重新逆向", Icons.refresh(Icons.NEUTRAL, 12));
        reverse.setOnAction(e -> reverse());
        Button relayout = UiUtils.toolButton("自动布局", Icons.plan(Icons.NEUTRAL, 12));
        relayout.setOnAction(e -> layout());
        Button png = UiUtils.toolButton("导出 PNG", Icons.export(Icons.NEUTRAL, 12));
        png.setOnAction(e -> exportPng());
        Button script = UiUtils.toolButton("生成建库脚本", Icons.file(Icons.NEUTRAL, 12));
        script.setOnAction(e -> showScript());

        Button snapshot = UiUtils.toolButton("存快照", Icons.file(Icons.NEUTRAL, 12));
        snapshot.setOnAction(e -> saveSnapshot());
        Button compare = UiUtils.toolButton("与快照比对", Icons.plan(Icons.NEUTRAL, 12));
        compare.setOnAction(e -> compareSnapshot());

        zoom.setPrefWidth(120);
        zoom.valueProperty().addListener((o, was, is) -> {
            canvas.setScaleX(is.doubleValue());
            canvas.setScaleY(is.doubleValue());
        });

        HBox bar = UiUtils.row(8, connBox, schemaBox, reverse, UiUtils.vSeparator(),
                relayout, png, script, snapshot, compare, UiUtils.hSpacer(),
                UiUtils.label("缩放", "form-label"), zoom);
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private HBox buildBody() {
        canvas.setStyle("-fx-background-color: -sx-panel-3;");
        ScrollPane scroll = new ScrollPane(canvasGroup);
        scroll.setPannable(true);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        VBox side = UiUtils.column(8,
                UiUtils.label("关系", "section-label"),
                relationList,
                UiUtils.vSpacer(),
                UiUtils.label("读这张图的两条线索", "section-label"),
                UiUtils.label("琥珀底色是精确数值列。金额链路上的每一列都在这里，"
                        + "一眼能看出两端的精度约定是否一致。", "hint"),
                UiUtils.label("虚线是按命名推测的关系，库里没有对应外键约束。"
                        + "常见于历史库，值得确认是有意为之还是漏建。", "hint"));
        side.setPadding(new Insets(10));
        side.setPrefWidth(300);
        side.setMinWidth(300);
        side.setStyle("-fx-background-color:-sx-sidebar;"
                + "-fx-border-color: #e4e4e0 transparent transparent transparent;");
        relationList.setFillWidth(true);

        return new HBox(scroll, side);
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, summary, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 逆向

    private void loadSchemas(ConnectionConfig config) {
        schemaBox.getItems().clear();
        if (config == null) {
            return;
        }
        context.queryService().submit(() -> context.openSession(config).schemas())
                .whenComplete((schemas, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "连接失败", error);
                        return;
                    }
                    schemaBox.setItems(javafx.collections.FXCollections.observableArrayList(
                            schemas.stream().map(SchemaInfo::name).toList()));
                    schemas.stream().filter(SchemaInfo::isDefault).findFirst()
                            .ifPresent(s -> schemaBox.setValue(s.name()));
                }));
    }

    private void reverse() {
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        if (config == null || schema == null) {
            UiUtils.showInfo(stage, "还差点东西", "先选连接和库。");
            return;
        }
        summary.setText("正在逆向…");
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            // 本机标注的虚拟外键一并算进来：很多线上库刻意不建物理外键，
            // 光看约束画出来是一堆孤岛
            java.util.List<com.plainly.driver.meta.DbObjects.ForeignKeyInfo> virtual =
                    context.virtualKeys().listInSchema(config.id(), schema).stream()
                            .map(com.plainly.core.store.VirtualKeyStore.VirtualKey::toForeignKey)
                            .toList();
            // 表太多图就没法看了，先取前 24 张
            return ModelService.reverse(session.connection(), schema, 24, virtual);
        }).whenComplete((m, error) -> Platform.runLater(() -> {
            if (error != null) {
                summary.setText("逆向失败");
                UiUtils.showError(stage, "逆向失败", error);
                return;
            }
            model = m;
            render();
        }));
    }

    // ------------------------------------------------------------------ 绘制

    private void render() {
        canvas.getChildren().clear();
        boxes.clear();
        relationList.getChildren().clear();
        if (model == null) {
            return;
        }

        for (ModelService.Entity entity : model.entities()) {
            VBox box = entityBox(entity);
            boxes.put(entity.name().toLowerCase(Locale.ROOT), box);
            canvas.getChildren().add(box);
        }
        layout();

        for (ModelService.Relation relation : model.relations()) {
            Node line = relationLine(relation);
            if (line != null) {
                canvas.getChildren().add(0, line);
            }
            relationList.getChildren().add(relationRow(relation));
        }

        if (model.relations().isEmpty()) {
            relationList.getChildren().add(UiUtils.label(
                    "没有关系：库里没有外键约束，按命名也没推出来", "hint"));
        }
        summary.setText(model.entities().size() + " 个实体 · " + model.relations().size()
                + " 条关系" + (model.guessed() > 0 ? "（其中 " + model.guessed() + " 条为推测）" : ""));
    }

    /** 一张表画成一个盒子：表头 + 每列一行。 */
    private VBox entityBox(ModelService.Entity entity) {
        Label title = UiUtils.label(entity.name(), "er-title");
        HBox header = UiUtils.row(6, Icons.table(Icons.ACCENT, 11), title);
        header.getStyleClass().add("er-header");
        header.setPrefHeight(HEADER_HEIGHT);

        VBox box = new VBox(header);
        box.getStyleClass().add("er-box");
        box.setPrefWidth(BOX_WIDTH);
        box.setMinWidth(BOX_WIDTH);

        for (ColumnInfo column : entity.columns()) {
            boolean pk = entity.primaryKey().stream()
                    .anyMatch(k -> k.equalsIgnoreCase(column.name()));
            Label marker = UiUtils.label(pk ? "◆" : " ", "er-marker");
            marker.setMinWidth(12);
            Label name = UiUtils.label(column.name(), "er-column");
            Label type = UiUtils.label(shortType(column), "er-type");

            HBox row = UiUtils.row(4, marker, name, UiUtils.hSpacer(), type);
            row.setPrefHeight(ROW_HEIGHT);
            row.getStyleClass().add("er-row");
            // 精确数值列单独标出来：金额链路一眼可辨
            if (column.category() == com.plainly.driver.TypeCategory.EXACT_NUMERIC) {
                row.getStyleClass().add("er-exact");
                type.getStyleClass().add("er-type-exact");
            }
            box.getChildren().add(row);
        }
        return box;
    }

    private static String shortType(ColumnInfo column) {
        String type = column.nativeType();
        if (column.precision() > 0 && column.scale() > 0) {
            return type + "(" + column.precision() + "," + column.scale() + ")";
        }
        if (column.precision() > 0) {
            return type + "(" + column.precision() + ")";
        }
        return type;
    }

    /**
     * 网格布局。
     *
     * <p>没做力导向：那种布局每次结果都不一样，看图的人会失去方位感。
     * 固定网格至少是稳定的，用户还能自己拖。
     */
    private void layout() {
        int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(boxes.size())));
        List<VBox> all = new ArrayList<>(boxes.values());

        // 每一排的高度按这排最高的那个盒子算。用固定行高的话，
        // 两列字段的表和二十列字段的表会占同样的位置，图上到处是空洞
        double y = 30;
        int index = 0;
        while (index < all.size()) {
            double rowHeight = 0;
            for (int col = 0; col < perRow && index + col < all.size(); col++) {
                VBox box = all.get(index + col);
                box.setLayoutX(30 + col * (BOX_WIDTH + GAP_X));
                box.setLayoutY(y);
                makeDraggable(box);
                rowHeight = Math.max(rowHeight, boxHeight(box));
            }
            y += rowHeight + GAP_Y;
            index += perRow;
        }
        canvas.setPrefSize(30 + perRow * (BOX_WIDTH + GAP_X), y + 30);
    }

    /** 盒子高度按内容算：布局这一步还没做过测量，问不到真实高度。 */
    private static double boxHeight(VBox box) {
        return HEADER_HEIGHT + Math.max(0, box.getChildren().size() - 1) * ROW_HEIGHT + 8;
    }

    private void makeDraggable(VBox box) {
        final double[] offset = new double[2];
        box.setOnMousePressed(e -> {
            offset[0] = e.getSceneX() - box.getLayoutX();
            offset[1] = e.getSceneY() - box.getLayoutY();
        });
        box.setOnMouseDragged(e -> {
            box.setLayoutX(e.getSceneX() - offset[0]);
            box.setLayoutY(e.getSceneY() - offset[1]);
        });
    }

    /** 关系线。虚线表示这条关系只是推测出来的。 */
    private Node relationLine(ModelService.Relation relation) {
        VBox from = boxes.get(relation.fromTable().toLowerCase(Locale.ROOT));
        VBox to = boxes.get(relation.toTable().toLowerCase(Locale.ROOT));
        if (from == null || to == null) {
            return null;
        }
        Line line = new Line();
        line.startXProperty().bind(from.layoutXProperty().add(BOX_WIDTH / 2));
        line.startYProperty().bind(from.layoutYProperty().add(HEADER_HEIGHT / 2));
        line.endXProperty().bind(to.layoutXProperty().add(BOX_WIDTH / 2));
        line.endYProperty().bind(to.layoutYProperty().add(HEADER_HEIGHT / 2));
        line.getStyleClass().add(relation.confirmed() ? "er-line" : "er-line-guessed");
        if (!relation.confirmed()) {
            line.getStrokeDashArray().addAll(5.0, 5.0);
        }
        return line;
    }

    private HBox relationRow(ModelService.Relation relation) {
        Label text = UiUtils.label(relation.fromTable() + " → " + relation.toTable(), "hint");
        Label badge = UiUtils.label(relation.confirmed() ? "外键约束" : "按命名推测",
                relation.confirmed() ? "tree-badge" : "badge-warn");
        HBox row = UiUtils.row(6, text, UiUtils.hSpacer(), badge);
        javafx.scene.control.Tooltip.install(row,
                new javafx.scene.control.Tooltip(relation.note()));
        return row;
    }

    // ------------------------------------------------------------------ 导出

    private void exportPng() {
        if (model == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出模型图");
        chooser.setInitialFileName(model.schema() + "-model.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = FileDialogs.save(stage, context.uiState(), chooser);
        if (file == null) {
            return;
        }
        try {
            Image shot = canvas.snapshot(null, null);
            int w = (int) shot.getWidth();
            int h = (int) shot.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader px = shot.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setRGB(x, y, px.getArgb(x, y));
                }
            }
            ImageIO.write(out, "png", file);
            UiUtils.showInfo(stage, "已导出", file.getAbsolutePath());
        } catch (Exception e) {
            UiUtils.showError(stage, "导出失败", e);
        }
    }

    private void showScript() {
        if (model == null) {
            return;
        }
        context.queryService().submit(() -> {
            DbSession session = context.openSession(connBox.getValue());
            return ModelService.buildScript(session.connection(), model);
        }).whenComplete((script, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "生成脚本失败", error);
                return;
            }
            TextArea area = new TextArea(String.join(";\n\n", script) + ";");
            area.setEditable(false);
            area.getStyleClass().add("ddl-area");
            area.setPrefRowCount(24);

            Stage sub = new Stage();
            UiUtils.brand(sub);
            sub.initOwner(stage);
            sub.initModality(Modality.WINDOW_MODAL);
            sub.setTitle("建库脚本");

            Button copy = UiUtils.toolButton("复制", null, "primary");
            copy.setOnAction(e -> {
                area.selectAll();
                area.copy();
                area.deselect();
            });
            HBox foot = UiUtils.row(8,
                    UiUtils.label("推测出来的关系不写进脚本——那是线索，不是库里真有的约束", "hint"),
                    UiUtils.hSpacer(), copy);
            foot.getStyleClass().add("dialog-foot");

            VBox box = new VBox(area, foot);
            VBox.setVgrow(area, Priority.ALWAYS);
            Scene scene = new Scene(box, 780, 560);
            scene.getStylesheets().addAll(stage.getScene().getStylesheets());
            sub.setScene(scene);
            sub.show();
        }));
    }

    /** 把当前库结构存成一份快照文件，以后能拿它比。 */
    private void saveSnapshot() {
        if (connBox.getValue() == null || schemaBox.getValue() == null) {
            UiUtils.showInfo(stage, "先选连接和库", "存快照要知道存的是哪个库。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存模型快照");
        chooser.setInitialFileName(schemaBox.getValue() + "-"
                + java.time.LocalDate.now().toString().replace("-", "") + ".snapshot");
        File file = FileDialogs.save(stage, context.uiState(), chooser);
        if (file == null) {
            return;
        }
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        context.queryService().submit(() -> {
            com.plainly.core.model.ModelSnapshot.save(
                    context.openSession(config).connection(), schema, file.toPath());
            return file.getAbsolutePath();
        }).whenComplete((path, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "存快照失败", error);
                return;
            }
            UiUtils.showInfo(stage, "已保存", path
                    + "\n\n这是一份文本文件，可以进版本库，也能直接用 diff 工具看。");
        }));
    }

    /** 拿当前库和一份旧快照比，看结构改了什么。 */
    private void compareSnapshot() {
        if (connBox.getValue() == null || schemaBox.getValue() == null) {
            UiUtils.showInfo(stage, "先选连接和库", "比对要知道拿哪个库去比。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要比对的快照");
        File file = FileDialogs.open(stage, context.uiState(), chooser);
        if (file == null) {
            return;
        }
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        context.queryService().submit(() -> {
            var conn = context.openSession(config).connection();
            var before = com.plainly.core.model.ModelSnapshot.load(file.toPath());
            var now = com.plainly.core.model.ModelSnapshot.read(conn, schema);
            return com.plainly.core.model.ModelSnapshot.compare(now, before);
        }).whenComplete((changes, error) -> Platform.runLater(() -> {
            if (error != null) {
                UiUtils.showError(stage, "比对失败", error);
                return;
            }
            if (changes.isEmpty()) {
                UiUtils.showInfo(stage, "没有差异", "当前结构和这份快照一致。");
                return;
            }
            StringBuilder sb = new StringBuilder("相对这份快照，当前库有 ")
                    .append(changes.size()).append(" 处结构变化：\n");
            changes.forEach(c -> sb.append("\n· ").append(c.describe()));
            UiUtils.showInfo(stage, "结构有变化", sb.toString());
        }));
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null
                    : item.name() + "  ·  " + item.type().displayName());
        }
    }
}
