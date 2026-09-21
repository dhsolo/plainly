package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.SqlScript;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
 * 视图 / 函数 / 触发器的编辑。
 *
 * <p>这三样在数据库里都是「一段定义」，不像表那样能拆成字段逐个改。
 * 所以这里给的就是一个定义编辑器：读出原来的定义、改、整体替换回去。
 * 硬做成图形化的表单反而会限制能写的东西——存储过程里什么都可能有。
 *
 * <p>视图用 {@code CREATE OR REPLACE}：先 DROP 再 CREATE 会在两条语句之间
 * 留下一段「视图不存在」的窗口，那期间别人的查询会直接报错。
 */
public class ObjectEditorDialog {

    /** 编辑的是哪一类对象。 */
    public enum Kind {
        VIEW("视图"), ROUTINE("函数 / 存储过程"), TRIGGER("触发器");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final Kind kind;
    private final String objectName;

    private final TextField nameField = new TextField();
    private final TextArea body = new TextArea();
    private final Label hint = UiUtils.label("", "hint");

    private Stage stage;
    private Runnable onApplied = () -> { };

    public ObjectEditorDialog(AppContext context, DbSession session, String schema,
                              Kind kind, String objectName) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.kind = kind;
        this.objectName = objectName;
    }

    public void setOnApplied(Runnable handler) {
        this.onApplied = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(objectName == null ? "新建" + kind.label() : kind.label());

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 880, 620);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        if (objectName != null) {
            nameField.setText(objectName);
            nameField.setDisable(kind == Kind.VIEW);
            load();
        }
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.view(Icons.ACCENT, 14),
                UiUtils.label(schema, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        nameField.setPromptText("名称");
        nameField.setPrefWidth(240);
        body.getStyleClass().add("ddl-area");
        body.setPromptText(promptFor());

        VBox box = UiUtils.column(10,
                UiUtils.row(10, UiUtils.label("名称", "form-label"), nameField),
                UiUtils.label(kind == Kind.VIEW ? "视图定义（只写 SELECT 部分）" : "完整定义",
                        "section-label"),
                body, hint);
        box.setPadding(new Insets(12));
        VBox.setVgrow(body, Priority.ALWAYS);
        return box;
    }

    private String promptFor() {
        return switch (kind) {
            case VIEW -> "SELECT ...";
            case ROUTINE -> "CREATE FUNCTION ... / CREATE PROCEDURE ...";
            case TRIGGER -> "CREATE TRIGGER ...";
        };
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        Button drop = UiUtils.toolButton("删除", Icons.minus("#a0402a", 12), "danger");
        drop.setOnAction(e -> dropObject());
        drop.setDisable(objectName == null || kind != Kind.VIEW);
        Button apply = UiUtils.toolButton("应用", null, "primary");
        apply.setOnAction(e -> apply());

        HBox foot = UiUtils.row(8, drop, UiUtils.hSpacer(), cancel, apply);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 读写

    private void load() {
        if (kind != Kind.VIEW) {
            hint.setText("函数与触发器的定义请粘贴完整语句；本工具不改写它，原样执行。");
            return;
        }
        String sql = session.connection().dialect().viewDefinitionQuery(schema, objectName);
        context.queryService().submit(() -> session.connection().execute(sql, 1))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null || result.rows().isEmpty()) {
                        hint.setText("读不到原定义，可以直接写一份新的覆盖上去");
                        return;
                    }
                    String definition = result.rows().get(0).get(0);
                    body.setText(definition == null ? "" : definition);
                    hint.setText("改完点「应用」，走 CREATE OR REPLACE，不会出现视图短暂消失的窗口");
                }));
    }

    private void apply() {
        String name = nameField.getText().trim();
        String text = body.getText().trim();
        if (name.isEmpty() || text.isEmpty()) {
            UiUtils.showInfo(stage, "还差点东西", "名称和定义都要填。");
            return;
        }

        List<String> statements;
        if (kind == Kind.VIEW) {
            statements = List.of(session.connection().dialect()
                    .createOrReplaceViewDdl(schema, name, text));
        } else {
            // 函数和触发器的定义体里正常带分号，整段原样发过去，不切
            statements = List.of(text);
        }

        if (!UiUtils.confirm(stage, "应用" + kind.label(),
                "将执行 " + statements.size() + " 条语句。")) {
            return;
        }
        context.queryService().submit(() -> session.executeDdl(schema, statements))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "应用失败", error);
                        return;
                    }
                    session.invalidate();
                    onApplied.run();
                    UiUtils.showInfo(stage, "已应用",
                            kind.label() + " " + nameField.getText() + " 已更新。");
                    stage.close();
                }));
    }

    private void dropObject() {
        if (objectName == null || !UiUtils.confirm(stage, "删除" + kind.label(),
                "确定删除 " + schema + "." + objectName + "？")) {
            return;
        }
        String ddl = session.connection().dialect().dropViewDdl(schema, objectName);
        context.queryService().submit(() -> session.executeDdl(schema, List.of(ddl)))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "删除失败", error);
                        return;
                    }
                    session.invalidate();
                    onApplied.run();
                    stage.close();
                }));
    }

    /** 供外部检查脚本能不能切开——函数体里的分号是最常见的坑。 */
    static int statementCount(String script, boolean mysql) {
        return SqlScript.split(script, mysql).size();
    }
}
