package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.store.ConnectionPortability;
import com.plainly.driver.ConnectionConfig;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接配置的导出与导入。
 *
 * <h2>为什么口令要单独说一遍</h2>
 * 本机存的口令密文绑定当前 Windows 账户，换台机器解不开。所以导出时只有两条路：
 * 不带口令，或者用一个新口令重新加密。两条都摆在界面上，默认是<b>不带</b>——
 * 把配置发给同事是这个功能最常见的用法，而口令不该跟着在聊天工具里流转。
 *
 * <h2>导入为什么默认新建而不是覆盖</h2>
 * 覆盖是不可逆的：同名的那条连接一旦被文件里的内容盖掉，原来的主机、端口、
 * 口令就都找不回来了。而多出一条重名的连接，用户自己删掉就是了。
 */
public class ConnectionPortDialog {

    private final AppContext context;
    private Stage stage;
    /** 关窗之后要做的事。导入完了左树得刷新，而窗口是非模态的，不能在 show() 之后就刷。 */
    private Runnable onClosed = () -> { };

    public ConnectionPortDialog(AppContext context) {
        this.context = context;
    }

    public ConnectionPortDialog setOnClosed(Runnable action) {
        this.onClosed = action == null ? () -> { } : action;
        return this;
    }

    /** 一行：勾选状态 + 一条连接。导出和导入两边共用。 */
    public static final class Entry {
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);
        private final ConnectionConfig config;
        /** 导入时这条会不会撞上已有的同名连接。导出时恒为 null。 */
        private final String clash;

        Entry(ConnectionConfig config, String clash) {
            this.config = config;
            this.clash = clash;
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        public ConnectionConfig config() {
            return config;
        }
    }

    // ------------------------------------------------------------------ 导出

    public void showExport(Window owner) {
        stage = newStage(owner, "导出连接配置");

        TableView<Entry> table = buildTable(false);
        List<Entry> entries = new ArrayList<>();
        for (ConnectionConfig c : context.registry().listAll()) {
            entries.add(new Entry(c, null));
        }
        table.setItems(FXCollections.observableArrayList(entries));

        CheckBox withPassword = new CheckBox("把口令一起导出（用下面这个口令加密）");
        PasswordField pass1 = new PasswordField();
        PasswordField pass2 = new PasswordField();
        pass1.setPromptText("加密口令");
        pass2.setPromptText("再输一遍");
        pass1.setPrefWidth(200);
        pass2.setPrefWidth(200);
        pass1.setDisable(true);
        pass2.setDisable(true);
        withPassword.setOnAction(e -> {
            pass1.setDisable(!withPassword.isSelected());
            pass2.setDisable(!withPassword.isSelected());
        });

        Label note = UiUtils.label("""
                本机保存的口令是用 Windows DPAPI 加密的，绑定当前账户——原样带走，到别的机器上解不开。
                所以要么不带口令（对方导入后自己补一次），要么在这里用一个口令重新加密。""", "hint");
        note.setWrapText(true);

        VBox body = UiUtils.column(10,
                UiUtils.row(10, UiUtils.label("选择要导出的连接", "section-label"),
                        UiUtils.hSpacer(), selectAllButton(table), selectNoneButton(table)),
                table,
                withPassword,
                UiUtils.row(8, UiUtils.label("口令", "hint"), pass1, pass2),
                note);
        body.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button go = UiUtils.toolButton("导出到文件…", Icons.export("#ffffff", 12), "primary");
        go.setOnAction(e -> {
            List<ConnectionConfig> picked = selectedConfigs(table);
            if (picked.isEmpty()) {
                UiUtils.showInfo(stage, "没有选中任何连接", "先勾上要导出的那几条。");
                return;
            }
            String passphrase = null;
            if (withPassword.isSelected()) {
                if (pass1.getText().isEmpty()) {
                    UiUtils.showInfo(stage, "还没输入加密口令", "不想带口令的话，把上面那个勾去掉。");
                    return;
                }
                if (!pass1.getText().equals(pass2.getText())) {
                    UiUtils.showInfo(stage, "两次输入的口令不一样",
                            "导出文件解不开就等于没导出，所以这里必须输两遍对上。");
                    return;
                }
                passphrase = pass1.getText();
            }

            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出连接配置");
            chooser.setInitialFileName("plainly-connections.json");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
            File file = FileDialogs.save(stage, context.uiState(), chooser);
            if (file == null) {
                return;
            }
            try {
                ConnectionPortability.export(picked, file.toPath(), passphrase,
                        context.registry());
                UiUtils.showInfo(stage, "已导出 " + picked.size() + " 条连接",
                        file.getAbsolutePath()
                                + System.lineSeparator()
                                + (passphrase == null
                                        ? "文件里不含口令，对方导入后需要自己补一次密码。"
                                        : "口令已加密。把加密口令另行告知对方——"
                                                + "和文件走同一个渠道就失去意义了。"));
                stage.close();
            } catch (RuntimeException ex) {
                UiUtils.showError(stage, "导出失败", ex);
            }
        });

        finish(body, go);
    }

    // ------------------------------------------------------------------ 导入

    public void showImport(Window owner) {
        stage = newStage(owner, "导入连接配置");

        TableView<Entry> table = buildTable(true);
        table.setPlaceholder(UiUtils.label("先选一个导出文件", "hint"));

        TextField pathField = new TextField();
        pathField.setEditable(false);
        pathField.setPrefWidth(360);
        pathField.setPromptText("还没选文件");

        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("文件带加密口令时才需要");
        passphrase.setPrefWidth(200);

        ChoiceBox<String> onClash = new ChoiceBox<>(FXCollections.observableArrayList(
                "同名时新建一条（推荐）", "同名时覆盖已有的那条"));
        onClash.getSelectionModel().selectFirst();

        Label problems = UiUtils.label("", "status-error");
        problems.setWrapText(true);

        Button pick = UiUtils.toolButton("选文件…", Icons.file(Icons.NEUTRAL, 12));
        Runnable reload = () -> {
            if (pathField.getText().isEmpty()) {
                return;
            }
            try {
                ConnectionPortability.ImportResult result = ConnectionPortability.read(
                        Path.of(pathField.getText()),
                        passphrase.getText().isEmpty() ? null : passphrase.getText());
                List<String> existing = context.registry().listAll().stream()
                        .map(ConnectionConfig::name).toList();
                List<Entry> entries = new ArrayList<>();
                for (ConnectionConfig c : result.configs()) {
                    entries.add(new Entry(c,
                            existing.contains(c.name()) ? "已有同名连接" : ""));
                }
                table.setItems(FXCollections.observableArrayList(entries));
                problems.setText(String.join(System.lineSeparator(), result.problems()));
            } catch (RuntimeException ex) {
                table.setItems(FXCollections.observableArrayList());
                problems.setText(UiUtils.rootMessage(ex));
            }
        };
        pick.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择连接配置文件");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
            File file = FileDialogs.open(stage, context.uiState(), chooser);
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
                reload.run();
            }
        });
        // 口令是后输的：先选文件、看到「需要口令」那句话，再回头输——所以输完要重读一遍
        passphrase.setOnAction(e -> reload.run());

        VBox body = UiUtils.column(10,
                UiUtils.row(8, UiUtils.label("文件", "hint"), pathField, pick),
                UiUtils.row(8, UiUtils.label("解密口令", "hint"), passphrase,
                        UiUtils.label("输完按回车重新读一遍", "hint")),
                UiUtils.row(10, UiUtils.label("文件里的连接", "section-label"),
                        UiUtils.hSpacer(), selectAllButton(table), selectNoneButton(table)),
                table,
                UiUtils.row(8, UiUtils.label("重名怎么办", "hint"), onClash),
                problems);
        body.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button go = UiUtils.toolButton("导入", Icons.check("#ffffff", 12), "primary");
        go.setOnAction(e -> {
            List<ConnectionConfig> picked = selectedConfigs(table);
            if (picked.isEmpty()) {
                UiUtils.showInfo(stage, "没有选中任何连接", "先勾上要导入的那几条。");
                return;
            }
            boolean overwrite = onClash.getSelectionModel().getSelectedIndex() == 1;
            if (overwrite && !UiUtils.confirm(stage, "确认覆盖",
                    "同名的连接会被文件里的内容整条盖掉，原来的主机、端口、口令都找不回来。"
                            + System.lineSeparator() + "确定继续？")) {
                return;
            }
            try {
                int saved = doImport(picked, overwrite);
                UiUtils.showInfo(stage, "已导入 " + saved + " 条连接",
                        "在左侧连接树上刷新一下就能看到。"
                                + System.lineSeparator()
                                + "没有口令的那些，第一次连接时会提示输入密码。");
                stage.close();
            } catch (RuntimeException ex) {
                UiUtils.showError(stage, "导入失败", ex);
            }
        });

        finish(body, go);
    }

    /**
     * 真正落库。
     *
     * <p>id 一律重新生成：文件里的 id 可能和本机已有的某条撞上，
     * 那样「新建一条」就会静静变成「盖掉那一条」——正是用户明确没选的行为。
     */
    private int doImport(List<ConnectionConfig> configs, boolean overwrite) {
        List<ConnectionConfig> existing = context.registry().listAll();
        int count = 0;
        for (ConnectionConfig c : configs) {
            ConnectionConfig target = c.copy();
            target.setId(null);
            if (overwrite) {
                existing.stream()
                        .filter(e -> e.name().equals(c.name()))
                        .findFirst()
                        .ifPresent(e -> target.setId(e.id()));
            } else {
                target.setName(uniqueName(existing, c.name()));
            }
            // 没带口令的那些，password 是 null——注册表把 null 理解成「这次不碰口令」，
            // 对一条全新的连接来说正好就是「没有口令」
            context.registry().save(target);
            count++;
        }
        return count;
    }

    private static String uniqueName(List<ConnectionConfig> existing, String name) {
        List<String> taken = existing.stream().map(ConnectionConfig::name).toList();
        if (!taken.contains(name)) {
            return name;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = name + " (" + i + ")";
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return name + " (导入)";
    }

    // ------------------------------------------------------------------ 公共部件

    private TableView<Entry> buildTable(boolean showClash) {
        TableView<Entry> table = new TableView<>();
        table.getStyleClass().add("data-grid");
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(UiUtils.label("一条连接也没有", "hint"));

        TableColumn<Entry, Boolean> check = new TableColumn<>("");
        check.setPrefWidth(36);
        check.setCellValueFactory(c -> c.getValue().selectedProperty());
        check.setCellFactory(CheckBoxTableCell.forTableColumn(check));
        check.setEditable(true);

        table.getColumns().add(check);
        table.getColumns().add(text("名称", e -> e.config.name(), 160));
        table.getColumns().add(text("类型", e -> e.config.type().displayName(), 100));
        table.getColumns().add(text("目标", e -> e.config.describe(), 240));
        table.getColumns().add(text("分组", e -> e.config.group(), 90));
        if (showClash) {
            table.getColumns().add(text("提示", e -> e.clash == null ? "" : e.clash, 110));
        }
        return table;
    }

    private TableColumn<Entry, String> text(String title,
                                           java.util.function.Function<Entry, String> getter,
                                           double width) {
        TableColumn<Entry, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        return c;
    }

    private Button selectAllButton(TableView<Entry> table) {
        Button b = UiUtils.toolButton("全选", null);
        b.setOnAction(e -> table.getItems().forEach(i -> i.selectedProperty().set(true)));
        return b;
    }

    private Button selectNoneButton(TableView<Entry> table) {
        Button b = UiUtils.toolButton("全不选", null);
        b.setOnAction(e -> table.getItems().forEach(i -> i.selectedProperty().set(false)));
        return b;
    }

    private static List<ConnectionConfig> selectedConfigs(TableView<Entry> table) {
        List<ConnectionConfig> out = new ArrayList<>();
        for (Entry e : table.getItems()) {
            if (e.selectedProperty().get()) {
                out.add(e.config());
            }
        }
        return out;
    }

    private Stage newStage(Window owner, String title) {
        Stage s = new Stage();
        UiUtils.brand(s);
        s.initOwner(owner);
        s.initModality(Modality.WINDOW_MODAL);
        s.setTitle(title);
        return s;
    }

    private void finish(VBox body, Button primary) {
        HBox head = UiUtils.row(8, Icons.export(Icons.ACCENT, 14),
                UiUtils.label("换机器、给同事一份配置，不必一条条重敲", "hint"));
        head.getStyleClass().add("dialog-head");

        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, primary);
        foot.getStyleClass().add("dialog-foot");

        VBox root = new VBox(head, body, foot);
        VBox.setVgrow(body, Priority.ALWAYS);
        Scene scene = new Scene(root, 820, 560);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> onClosed.run());
        stage.show();
    }
}
