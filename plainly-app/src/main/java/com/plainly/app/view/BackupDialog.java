package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.backup.BackupService;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDate;

/**
 * 备份与还原。
 *
 * <p>这是逻辑备份：一份建表语句 + 数据的 SQL 脚本。界面上直说它不含什么——
 * 用户以为备份了权限和存储过程，等到要还原的那天才发现没有，那才是真正的灾难。
 */
public class BackupDialog {

    private final AppContext context;

    private final ComboBox<ConnectionConfig> connBox = new ComboBox<>();
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private final TextField fileField = new TextField();
    private final CheckBox includeData = new CheckBox("包含数据（不勾则只备份结构）");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = UiUtils.label("", "hint");

    private Stage stage;

    private ConnectionConfig initialConfig;
    private String initialSchema;

    public BackupDialog(AppContext context) {
        this.context = context;
    }

    /**
     * 预选连接和库。
     *
     * <p>从左树上某个库的右键菜单点「备份这个库」进来时，目标是明确的，
     * 不该再让用户在两个下拉框里重选一遍。
     */
    public void preselect(ConnectionConfig config, String schema) {
        this.initialConfig = config;
        this.initialSchema = schema;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("备份与还原");

        VBox root = new VBox(buildHead(), buildLimits(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 760, 460);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        connBox.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        includeData.setSelected(true);
        if (initialConfig != null) {
            connBox.getSelectionModel().select(initialConfig);
        }
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.database(Icons.ACCENT, 14),
                UiUtils.label("导出为一份可直接执行的 SQL 脚本", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    /** 说清楚边界，比事后解释便宜得多。 */
    private HBox buildLimits() {
        HBox bar = UiUtils.row(6, Icons.warn("#8a6d1f", 12),
                UiUtils.label("这是逻辑备份：只含表结构与数据，不含用户、权限、存储过程，"
                        + "也不是一致性快照。要这些请用 mysqldump / pg_dump。", "readonly-text"));
        bar.getStyleClass().add("readonly-bar");
        return bar;
    }

    private VBox buildBody() {
        connBox.setCellFactory(v -> new ConnectionCell());
        connBox.setButtonCell(new ConnectionCell());
        connBox.setPrefWidth(240);
        connBox.valueProperty().addListener((o, was, is) -> loadSchemas(is));
        schemaBox.setPrefWidth(180);
        schemaBox.valueProperty().addListener((o, was, is) -> suggestFile());

        Button browse = UiUtils.toolButton("浏览", null);
        browse.setOnAction(e -> chooseFile());
        HBox.setHgrow(fileField, Priority.ALWAYS);

        VBox box = UiUtils.column(12,
                UiUtils.row(10, UiUtils.label("连接", "form-label"), connBox,
                        UiUtils.label("库", "form-label"), schemaBox),
                UiUtils.row(10, UiUtils.label("文件", "form-label"), fileField, browse),
                includeData,
                progress, status);
        box.setPadding(new Insets(14));
        progress.setVisible(false);
        progress.setPrefWidth(Double.MAX_VALUE);
        return box;
    }

    private HBox buildFoot() {
        Button restore = UiUtils.toolButton("从文件还原", null);
        restore.setOnAction(e -> restore());
        Button backup = UiUtils.toolButton("开始备份", null, "primary");
        backup.setOnAction(e -> backup());
        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close, restore, backup);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

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
                    schemaBox.setItems(FXCollections.observableArrayList(
                            schemas.stream().map(SchemaInfo::name).toList()));
                    // 调用方指定的库优先于数据库自己报的「默认库」：他刚在树上点过它
                    if (initialSchema != null
                            && schemaBox.getItems().contains(initialSchema)) {
                        schemaBox.setValue(initialSchema);
                    } else {
                        schemas.stream().filter(SchemaInfo::isDefault).findFirst()
                                .ifPresent(s -> schemaBox.setValue(s.name()));
                    }
                }));
    }

    private void suggestFile() {
        if (schemaBox.getValue() == null) {
            return;
        }
        String home = System.getProperty("user.home");
        fileField.setText(home + "\\Documents\\" + schemaBox.getValue()
                + "_" + LocalDate.now().toString().replace("-", "") + ".sql");
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("备份到");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
        File file = FileDialogs.save(stage, context.uiState(), chooser);
        if (file != null) {
            fileField.setText(file.getAbsolutePath());
        }
    }

    private void backup() {
        if (connBox.getValue() == null || schemaBox.getValue() == null
                || fileField.getText().isBlank()) {
            UiUtils.showInfo(stage, "还差点东西", "连接、库和文件路径都要选好。");
            return;
        }
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        boolean data = includeData.isSelected();
        java.nio.file.Path target = Paths.get(fileField.getText().trim());

        busy(true);
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return BackupService.backup(session.connection(), schema, target, data,
                    line -> Platform.runLater(() -> status.setText(line)));
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            busy(false);
            if (error != null) {
                UiUtils.showError(stage, "备份失败", error);
                return;
            }
            status.setText("完成");
            StringBuilder msg = new StringBuilder(result.describe())
                    .append('\n').append(result.file()).append('\n')
                    .append(result.bytes() < 0 ? "" : (result.bytes() / 1024) + " KB");
            // 有东西没备进去就必须说出来。一个备份最危险的失败方式是
            // 「少了点什么但没人提」——等到还原时才发现视图或存储过程不见了，
            // 那时候原库多半已经没了
            if (!result.skipped().isEmpty()) {
                msg.append("\n\n以下内容没有备份进去：");
                for (String one : result.skipped()) {
                    msg.append("\n· ").append(one);
                }
            }
            UiUtils.showInfo(stage, result.skipped().isEmpty() ? "备份完成" : "备份完成，但有遗漏",
                    msg.toString());
        }));
    }

    private void restore() {
        if (connBox.getValue() == null || schemaBox.getValue() == null) {
            UiUtils.showInfo(stage, "还差点东西", "先选要还原到哪个连接和库。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择备份文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
        File file = FileDialogs.open(stage, context.uiState(), chooser);
        if (file == null) {
            return;
        }
        ConnectionConfig config = connBox.getValue();
        String schema = schemaBox.getValue();
        if (!UiUtils.confirm(stage, "还原到 " + config.name() + " / " + schema,
                "脚本会逐条执行。建表撞名会失败，已存在的表请先自行处理。\n"
                        + "中途失败会停下来并告诉你停在第几条——前面几条已经生效。")) {
            return;
        }

        busy(true);
        context.queryService().submit(() -> {
            DbSession session = context.openSession(config);
            return BackupService.restore(session.connection(), schema, file.toPath(),
                    line -> Platform.runLater(() -> status.setText(line)));
        }).whenComplete((count, error) -> Platform.runLater(() -> {
            busy(false);
            if (error != null) {
                UiUtils.showError(stage, "还原失败", error);
                return;
            }
            UiUtils.showInfo(stage, "还原完成", "执行了 " + count + " 条语句。");
        }));
    }

    private void busy(boolean busy) {
        progress.setVisible(busy);
        progress.setProgress(busy ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        if (!busy) {
            status.setText("");
        }
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
