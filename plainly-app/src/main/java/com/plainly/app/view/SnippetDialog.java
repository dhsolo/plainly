package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.SnippetStore.Snippet;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
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

import java.util.function.Consumer;

/**
 * SQL 代码片段的管理。
 *
 * <h2>为什么不和「收藏」合并</h2>
 * 收藏留的是<b>一整页</b>，带着连接和库——「上次那个排查脚本」。
 * 片段留的是<b>一段写法</b>，和库无关——「分页该怎么写」。
 * 合成一个之后，两边的检索方式立刻打架：前者按连接找，后者按写法找。
 *
 * <p>片段真正的入口不在这个窗口里，而是编辑器的补全：敲前缀就能补出整段。
 * 这个窗口负责的是「攒」和「改」。
 */
public class SnippetDialog {

    private final AppContext context;

    private final TableView<Snippet> table = new TableView<>();
    private final TextField name = new TextField();
    private final TextField prefix = new TextField();
    private final TextField note = new TextField();
    private final TextArea body = new TextArea();
    private final Label status = UiUtils.label("", "hint");

    private Stage stage;
    private Consumer<String> onUse = s -> { };
    private Runnable onClosed = () -> { };
    /** 正在编辑的那条；0 表示这是一条还没存过的新片段。 */
    private long editingId;

    public SnippetDialog(AppContext context) {
        this.context = context;
    }

    /** 「插到编辑器里」按的是什么。没设的话那个按钮不出现。 */
    public SnippetDialog setOnUse(Consumer<String> action) {
        this.onUse = action == null ? s -> { } : action;
        return this;
    }

    public SnippetDialog setOnClosed(Runnable action) {
        this.onClosed = action == null ? () -> { } : action;
        return this;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("SQL 代码片段");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 900, 580);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> onClosed.run());
        stage.show();

        reload();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8, Icons.file(Icons.ACCENT, 14),
                UiUtils.label("在编辑器里敲下面这个「前缀」，补全里就会出现整段", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private SplitPane buildBody() {
        table.getStyleClass().add("data-grid");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(UiUtils.label("一条片段也没有", "hint"));
        table.getColumns().add(col("名称", Snippet::name, 150));
        table.getColumns().add(col("前缀", s -> s.prefix() == null ? "" : s.prefix(), 90));
        table.getColumns().add(col("说明", s -> s.note() == null ? "" : s.note(), 200));
        table.getSelectionModel().selectedItemProperty()
                .addListener((o, was, is) -> showInEditor(is));

        Button newOne = UiUtils.toolButton("新建", Icons.plus(Icons.ACCENT, 12));
        newOne.setOnAction(e -> clearEditor());

        Button remove = UiUtils.toolButton("删除", Icons.minus("#a0402a", 12), "danger");
        remove.setOnAction(e -> {
            Snippet picked = table.getSelectionModel().getSelectedItem();
            if (picked == null) {
                status.setText("先在左边选一条");
                return;
            }
            if (!UiUtils.confirm(stage, "删除片段",
                    "删掉「" + picked.name() + "」？这条撤不回来。")) {
                return;
            }
            context.snippets().delete(picked.id());
            clearEditor();
            reload();
            status.setText("已删除");
        });

        VBox left = UiUtils.column(6,
                UiUtils.row(8, UiUtils.label("片段", "section-label"),
                        UiUtils.hSpacer(), newOne, remove),
                table);
        left.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        name.setPromptText("名称，如「分页查询」");
        prefix.setPromptText("前缀，如 page");
        note.setPromptText("说明（可选）");
        body.setPromptText("片段内容。${xxx} 这样的占位符会原样插进去，插完自己替掉");
        body.getStyleClass().add("mono");
        body.setWrapText(false);

        Button save = UiUtils.toolButton("保存", Icons.check(Icons.ACCENT, 12), "accent");
        save.setOnAction(e -> save());

        Button use = UiUtils.toolButton("插到编辑器里", Icons.export(Icons.NEUTRAL, 12));
        use.setOnAction(e -> {
            if (body.getText().isBlank()) {
                status.setText("内容是空的，没什么可插的");
                return;
            }
            onUse.accept(body.getText());
            stage.close();
        });

        VBox right = UiUtils.column(8,
                UiUtils.row(8, UiUtils.label("名称", "hint"), name,
                        UiUtils.label("前缀", "hint"), prefix),
                UiUtils.row(8, UiUtils.label("说明", "hint"), note),
                UiUtils.label("内容", "section-label"),
                body,
                UiUtils.row(8, status, UiUtils.hSpacer(), use, save));
        right.setPadding(new Insets(10));
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox.setHgrow(note, Priority.ALWAYS);
        prefix.setPrefWidth(110);
        VBox.setVgrow(body, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.4);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private TableColumn<Snippet, String> col(String title,
                                             java.util.function.Function<Snippet, String> getter,
                                             double width) {
        TableColumn<Snippet, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    private void reload() {
        table.setItems(FXCollections.observableArrayList(context.snippets().list()));
    }

    private void showInEditor(Snippet snippet) {
        if (snippet == null) {
            return;
        }
        editingId = snippet.id();
        name.setText(snippet.name());
        prefix.setText(snippet.prefix() == null ? "" : snippet.prefix());
        note.setText(snippet.note() == null ? "" : snippet.note());
        body.setText(snippet.body());
        status.setText("");
    }

    private void clearEditor() {
        editingId = 0;
        name.clear();
        prefix.clear();
        note.clear();
        body.clear();
        table.getSelectionModel().clearSelection();
        status.setText("新片段");
    }

    private void save() {
        try {
            long id = context.snippets().save(new Snippet(editingId, name.getText().trim(),
                    prefix.getText().trim(), body.getText(), note.getText().trim()));
            editingId = id;
            reload();
            status.setText("已保存");
        } catch (RuntimeException e) {
            status.setText(UiUtils.rootMessage(e));
        }
    }
}
