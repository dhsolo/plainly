package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.kv.KeyValueStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 新建一个键，或者改一个已有的键。
 *
 * <h2>为什么改值要开一个框，而不是在网格里双击那一格</h2>
 * 网格里那一列是<b>预览</b>：字符串只取前 200 字节，集合只取前 5 个元素。
 * 在截断过的内容上编辑再保存，等于把用户剩下的数据抹掉——而且他看不出来，
 * 保存也会显示成功。所以改值一律走这里，进来的第一件事是把<b>完整的值</b>重新读回来。
 *
 * <h2>集合为什么用 JSON</h2>
 * 哈希、列表、集合的元素本身可能带换行、逗号、等号。任何没有转义规则的格式
 * （比如「一行一个」「a=b」）都会在某个真实的值上切错，而且切错了不报错。
 * JSON 也正是导出用的格式，所以「导出→改→贴回来」这条路是通的。
 *
 * <p>保存前先解析：格式不对就在这里拦下，那时候库里的旧值还一点没动。
 */
public class RedisKeyDialog {

    private final AppContext context;
    private final DbSession session;
    private final KeyValueStore store;
    private final String schema;

    /** 编辑已有键时是它的名字；新建时为 null。 */
    private final String editingKey;

    private final TextField keyField = new TextField();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final TextField ttlField = new TextField();
    private final TextArea valueArea = new TextArea();
    private final Label shapeHint = UiUtils.label("", "hint");
    private final Label errorLabel = UiUtils.label("", "status-error");

    private Stage stage;
    private Runnable onSaved = () -> { };

    public RedisKeyDialog(AppContext context, DbSession session, String schema, String editingKey) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.editingKey = editingKey;
        this.store = (KeyValueStore) session.connection();
    }

    public void setOnSaved(Runnable handler) {
        this.onSaved = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(editingKey == null ? "新建键" : "改值");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 640, 520);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        if (editingKey != null) {
            load();
        } else {
            typeBox.setValue("string");
            Platform.runLater(keyField::requestFocus);
        }
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                UiUtils.label(schema + (editingKey == null ? "" : " · " + editingKey), "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        typeBox.getItems().addAll(store.valueTypes());
        typeBox.setMaxWidth(Double.MAX_VALUE);
        typeBox.valueProperty().addListener((o, was, is) -> updateShapeHint(is));

        keyField.setPromptText("键名，例如 user:1001");
        ttlField.setPromptText("留空表示永不过期");
        ttlField.setPrefWidth(160);
        ttlField.setMaxWidth(160);

        valueArea.setStyle("-fx-font-family:'Consolas','Microsoft YaHei',monospace;"
                + "-fx-font-size:12.5px;");
        valueArea.setWrapText(true);
        VBox.setVgrow(valueArea, Priority.ALWAYS);

        shapeHint.setWrapText(true);
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        VBox body = new VBox(14,
                row("键名", keyField),
                row("类型", typeBox),
                row("过期", UiUtils.row(8, ttlField, UiUtils.label("秒", "form-label"),
                        persistButton())),
                UiUtils.column(5, UiUtils.label("值", "section-label"), shapeHint, valueArea),
                errorLabel);
        body.setPadding(new Insets(16, 20, 12, 20));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    /** 「设为永不过期」：把过期时间那格清空。摆一个按钮比让人猜「留空是什么意思」直接。 */
    private Button persistButton() {
        Button b = UiUtils.toolButton("永不过期", null);
        b.setOnAction(e -> ttlField.clear());
        return b;
    }

    private HBox row(String label, javafx.scene.Node field) {
        Label l = UiUtils.label(label, "form-label");
        l.setMinWidth(52);
        l.setPrefWidth(52);
        l.setAlignment(Pos.CENTER_RIGHT);
        HBox box = UiUtils.row(12, l, field);
        if (field instanceof Region r) {
            HBox.setHgrow(r, Priority.ALWAYS);
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return box;
    }

    /**
     * 每种类型的值该怎么写。
     *
     * <p>直接把该有的形状写出来，比一句「集合请用 JSON」有用得多——
     * 有序集合的成员和分数谁在前，光说「JSON」是猜不到的。
     */
    private void updateShapeHint(String type) {
        String hint;
        switch (type == null ? "string" : type) {
            case "hash":
                hint = "JSON 对象，字段和值都是字符串：{\"name\":\"张三\",\"city\":\"杭州\"}";
                break;
            case "list":
                hint = "JSON 数组，顺序就是列表的顺序：[\"job-1\",\"job-2\"]";
                break;
            case "set":
                hint = "JSON 数组，重复的成员会被合并：[\"红\",\"黄\"]";
                break;
            case "zset":
                hint = "JSON 数组，每项是 [成员, 分数]：[[\"张三\",\"100\"],[\"李四\",\"90\"]]";
                break;
            default:
                hint = "原样存进去，包括换行和引号。不用加引号，也不做任何转义";
        }
        shapeHint.setText(hint);
    }

    /** 打开已有的键：重新读一次完整的值。 */
    private void load() {
        keyField.setText(editingKey);
        keyField.setDisable(true);
        keyField.setTooltip(new javafx.scene.control.Tooltip(
                "改名请用工具条上的「重命名」——那是一条独立的操作，和改值不是一回事"));

        context.queryService().submit(() -> store.read(schema, editingKey))
                .whenComplete((entry, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        showError(UiUtils.rootMessage(error));
                        return;
                    }
                    if (entry == null) {
                        showError("这个键已经不在了——可能刚被别人删掉，或者已经过期");
                        return;
                    }
                    typeBox.setValue(entry.type());
                    valueArea.setText(entry.value());
                    ttlField.setText(entry.persistent() ? "" : String.valueOf(entry.ttlSeconds()));
                    updateShapeHint(entry.type());
                }));
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        Button save = UiUtils.toolButton("保存", null, "primary");
        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> save(save));

        HBox foot = UiUtils.row(9,
                UiUtils.label("整体替换：保存后这个键就是这里写的内容", "hint"),
                UiUtils.hSpacer(), cancel, save);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private void save(Button trigger) {
        String key = keyField.getText() == null ? "" : keyField.getText().trim();
        if (key.isEmpty()) {
            showError("键名不能为空");
            return;
        }
        long ttl;
        try {
            String text = ttlField.getText() == null ? "" : ttlField.getText().trim();
            ttl = text.isEmpty() ? -1 : Long.parseLong(text);
            if (!text.isEmpty() && ttl <= 0) {
                showError("过期时间要是个正整数（秒）。想让它不过期就把这一格清空");
                return;
            }
        } catch (NumberFormatException e) {
            showError("过期时间要填秒数，例如 3600");
            return;
        }

        // 新建时撞名要问一句：写下去就把人家原来的值整个换掉了
        if (editingKey == null && store.exists(schema, key)
                && !UiUtils.confirm(stage, "这个键已经存在",
                        "「" + key + "」已经在 " + schema + " 里了。"
                        + "保存会把它原来的内容整个替换掉，且不能撤销。确定继续？")) {
            return;
        }

        KeyValueStore.Entry entry = new KeyValueStore.Entry(
                key, typeBox.getValue(), valueArea.getText(), ttl);

        trigger.setDisable(true);
        hideError();
        context.queryService().submit(() -> {
            store.write(schema, entry);
            return null;
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            trigger.setDisable(false);
            if (error != null) {
                showError(UiUtils.rootMessage(error));
                return;
            }
            onSaved.run();
            stage.close();
        }));
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
