package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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

import java.util.List;

/**
 * 新建一个库。
 *
 * <h2>字符集为什么摆在这儿</h2>
 * MySQL 上这不是可选项而是必选项：它的 {@code utf8} 是最多三字节的阉割版，
 * 存不下 emoji 和一部分汉字。建库时选错，要等某天某条插入失败才发现，
 * 而那时候库里已经有数据了，改字符集就成了一件麻烦事。所以默认给
 * {@code utf8mb4}，并把这句话写在界面上。
 *
 * <p>不支持建库的家（SQLite 一个文件就是一个库）直接说清楚，不给一个填了也没用的框。
 */
public class NewSchemaDialog {

    /** MySQL 上常用的几个。utf8mb4 排第一，因为它才是真正的 UTF-8。 */
    private static final List<String> CHARSETS =
            List.of("utf8mb4", "utf8mb3", "latin1", "gbk", "binary");

    private static final List<String> COLLATIONS = List.of(
            "（服务端默认）", "utf8mb4_general_ci", "utf8mb4_unicode_ci",
            "utf8mb4_0900_ai_ci", "utf8mb4_bin");

    private final AppContext context;
    private final DbSession session;

    private final TextField nameField = new TextField();
    private final ComboBox<String> charset = new ComboBox<>();
    private final ComboBox<String> collation = new ComboBox<>();
    private final TextArea preview = new TextArea();

    private Stage stage;
    private Runnable onCreated = () -> { };

    public NewSchemaDialog(AppContext context, DbSession session) {
        this.context = context;
        this.session = session;
    }

    public void setOnCreated(Runnable handler) {
        this.onCreated = handler;
    }

    public void show(Window owner) {
        String refusal = session.connection().dialect().schemaCreationUnsupportedReason();
        if (refusal != null) {
            UiUtils.showInfo(owner, "这个数据库不能新建库", refusal);
            return;
        }

        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("新建库");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 640, 400);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        refreshPreview();
        stage.show();
        Platform.runLater(nameField::requestFocus);
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.folder("#8a7a4a", 14),
                UiUtils.label(session.config().name(), "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        nameField.setPromptText("库名");
        nameField.setPrefWidth(240);
        nameField.textProperty().addListener((o, was, is) -> refreshPreview());

        boolean withCharset = session.connection().dialect().supportsSchemaCharset();

        charset.getItems().addAll(CHARSETS);
        charset.setValue("utf8mb4");
        charset.setPrefWidth(160);
        charset.valueProperty().addListener((o, was, is) -> refreshPreview());

        collation.getItems().addAll(COLLATIONS);
        collation.setValue(COLLATIONS.get(0));
        collation.setPrefWidth(200);
        collation.valueProperty().addListener((o, was, is) -> refreshPreview());

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(3);

        VBox box = UiUtils.column(10,
                UiUtils.row(8, UiUtils.label("库名", "form-label"), nameField));

        if (withCharset) {
            box.getChildren().add(UiUtils.row(8,
                    UiUtils.label("字符集", "form-label"), charset,
                    UiUtils.label("排序规则", "form-label"), collation));
            Label note = UiUtils.label(
                    "MySQL 的 utf8 最多三字节，存不下 emoji 和一部分汉字——utf8mb4 才是完整的 UTF-8。"
                            + "建完之后再改字符集，已有数据要跟着转，很麻烦。", "hint");
            note.setWrapText(true);
            box.getChildren().add(note);
        }

        box.getChildren().addAll(
                UiUtils.row(10, UiUtils.label("将要执行", "section-label")), preview);
        box.setPadding(new Insets(12));
        VBox.setVgrow(preview, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        Button create = UiUtils.toolButton("创建", null, "primary");
        create.setOnAction(e -> create());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, create);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    /** 界面上先把语句摆出来：建库是 DDL，让人在按下去之前看清要执行什么。 */
    private void refreshPreview() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            preview.setText("-- 先填库名");
            return;
        }
        preview.setText(ddl(name) + ";");
    }

    private String ddl(String name) {
        boolean withCharset = session.connection().dialect().supportsSchemaCharset();
        String cs = withCharset ? charset.getValue() : null;
        String co = withCharset && !COLLATIONS.get(0).equals(collation.getValue())
                ? collation.getValue() : null;
        return session.connection().dialect().createSchemaDdl(name, cs, co);
    }

    private void create() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            UiUtils.showInfo(stage, "还差点东西", "填一个库名。");
            return;
        }
        String ddl = ddl(name);
        context.queryService()
                .submit(() -> session.connection().executeDdlBatch(List.of(ddl)))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "建库失败", error);
                        return;
                    }
                    // 新库要在树上出现，得先把缓存的库列表作废
                    session.invalidate();
                    onCreated.run();
                    stage.close();
                }));
    }
}
