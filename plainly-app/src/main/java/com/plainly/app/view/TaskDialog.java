package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.task.ScheduledTask;
import com.plainly.core.task.TaskRunner;
import com.plainly.core.task.TaskStore;
import com.plainly.driver.ConnectionConfig;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
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
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划任务。
 *
 * <p>有一件事必须先说清楚：任务在应用进程内运行，应用关掉就不会触发。
 * 想要真正的无人值守，得把任务交给 Windows 计划任务——界面上给出可直接粘贴的命令，
 * 而且那条命令走的是同一个执行器，真能跑。
 *
 * <p>假装能在关机后运行是不诚实的：用户会以为备份每天都在做，直到某天需要它的时候。
 */
public class TaskDialog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AppContext context;
    private final TaskStore store;

    private final TableView<ScheduledTask> taskTable = new TableView<>();
    private final TableView<TaskStore.Run> runTable = new TableView<>();
    private final TableView<ScheduledTask.Step> stepTable = new TableView<>();

    private final TextField nameField = new TextField();
    private final CheckBox enabledBox = new CheckBox("启用");
    private final ComboBox<ScheduledTask.Trigger> triggerBox = new ComboBox<>();
    private final TextField timeField = new TextField("03:00");
    private final ComboBox<DayOfWeek> dayBox = new ComboBox<>();
    private final TextField intervalField = new TextField("60");
    private final TextField retryField = new TextField("0");
    private final Label nextRuns = UiUtils.label("", "hint");

    private final ComboBox<ScheduledTask.StepType> stepType = new ComboBox<>();
    private final ComboBox<ConnectionConfig> stepConn = new ComboBox<>();
    private final TextField stepSchema = new TextField();
    private final TextArea stepPayload = new TextArea();
    private final TextField stepTarget = new TextField();

    private ScheduledTask editing = new ScheduledTask();
    private Stage stage;

    public TaskDialog(AppContext context) {
        this.context = context;
        this.store = context.taskStore();
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("计划任务");

        VBox root = new VBox(buildHead(), buildWarning(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 1180, 820);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        reload();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.refresh(Icons.ACCENT, 14),
                UiUtils.label("把执行 SQL、导出数据串成可定时重放的流程", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    /** 这条提示不折叠、不淡化：它决定了用户能不能指望这些任务。 */
    private HBox buildWarning() {
        HBox bar = UiUtils.row(6, Icons.warn("#8a6d1f", 12),
                UiUtils.label("任务在应用进程内运行，应用关掉就不会触发。"
                        + "需要真正的无人值守，请用「导出为命令行」把它交给 Windows 计划任务。",
                        "readonly-text"));
        bar.getStyleClass().add("readonly-bar");
        return bar;
    }

    private SplitPane buildBody() {
        buildTaskTable();
        buildRunTable();
        buildStepTable();

        VBox left = UiUtils.column(6,
                UiUtils.label("任务", "section-label"), taskTable,
                UiUtils.label("最近运行", "section-label"), runTable);
        left.setPadding(new Insets(10));
        left.setPrefWidth(420);
        VBox.setVgrow(taskTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, buildEditor());
        split.setDividerPositions(0.36);
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void buildTaskTable() {
        taskTable.getStyleClass().add("data-grid");
        taskTable.setPlaceholder(UiUtils.label("还没有任务", "hint"));
        taskTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ScheduledTask, String> name = new TableColumn<>("任务");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));

        TableColumn<ScheduledTask, String> schedule = new TableColumn<>("调度");
        schedule.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().describeSchedule()));

        TableColumn<ScheduledTask, String> next = new TableColumn<>("下次运行");
        next.setCellValueFactory(c -> {
            LocalDateTime at = c.getValue().nextRunAfter(LocalDateTime.now());
            return new SimpleStringProperty(at == null ? "已停用" : at.format(STAMP));
        });

        taskTable.getColumns().addAll(name, schedule, next);
        taskTable.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is != null) {
                edit(is);
            }
        });
    }

    private void buildRunTable() {
        runTable.getStyleClass().add("data-grid");
        runTable.setPlaceholder(UiUtils.label("还没有运行记录", "hint"));
        runTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        runTable.setPrefHeight(180);

        TableColumn<TaskStore.Run, String> when = new TableColumn<>("时间");
        when.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().startedAt().format(STAMP)));

        TableColumn<TaskStore.Run, String> status = new TableColumn<>("结果");
        status.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().succeeded() ? "成功" : "失败"));

        TableColumn<TaskStore.Run, String> detail = new TableColumn<>("说明");
        detail.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().message() + " · " + c.getValue().elapsedMillis() + " ms"));

        runTable.getColumns().addAll(when, status, detail);
    }

    private void buildStepTable() {
        stepTable.getStyleClass().add("data-grid");
        stepTable.setPlaceholder(UiUtils.label("还没有步骤", "hint"));
        stepTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        stepTable.setPrefHeight(150);

        TableColumn<ScheduledTask.Step, String> order = new TableColumn<>("#");
        order.setMaxWidth(40);
        order.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(stepTable.getItems().indexOf(c.getValue()) + 1)));

        TableColumn<ScheduledTask.Step, String> what = new TableColumn<>("步骤");
        what.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().describe()));

        stepTable.getColumns().addAll(order, what);
    }

    private VBox buildEditor() {
        triggerBox.getItems().addAll(ScheduledTask.Trigger.values());
        triggerBox.setValue(ScheduledTask.Trigger.DAILY);
        triggerBox.valueProperty().addListener((o, was, is) -> refreshNextRuns());
        dayBox.getItems().addAll(DayOfWeek.values());
        dayBox.setValue(DayOfWeek.MONDAY);
        dayBox.valueProperty().addListener((o, was, is) -> refreshNextRuns());
        timeField.setPrefWidth(80);
        timeField.textProperty().addListener((o, was, is) -> refreshNextRuns());
        intervalField.setPrefWidth(70);
        intervalField.textProperty().addListener((o, was, is) -> refreshNextRuns());
        retryField.setPrefWidth(60);
        nameField.setPrefWidth(240);

        HBox line1 = UiUtils.row(10, UiUtils.label("任务名", "form-label"), nameField, enabledBox);
        HBox line2 = UiUtils.row(10, UiUtils.label("调度", "form-label"), triggerBox,
                UiUtils.label("于", "form-label"), timeField, dayBox,
                UiUtils.label("间隔（分钟）", "form-label"), intervalField);
        HBox line3 = UiUtils.row(10, UiUtils.label("失败后重试", "form-label"), retryField,
                UiUtils.label("次", "form-label"));

        stepType.getItems().addAll(ScheduledTask.StepType.values());
        stepType.setValue(ScheduledTask.StepType.RUN_SQL);
        stepType.valueProperty().addListener((o, was, is) -> syncStepFields());
        stepConn.setItems(FXCollections.observableArrayList(context.registry().listAll()));
        stepConn.setCellFactory(v -> new ConnectionCell());
        stepConn.setButtonCell(new ConnectionCell());
        stepConn.setPrefWidth(200);
        stepSchema.setPromptText("库名");
        stepSchema.setPrefWidth(140);
        stepPayload.setPrefRowCount(4);
        stepPayload.getStyleClass().add("ddl-area");
        stepTarget.setPromptText("导出到的文件路径");

        Button addStep = UiUtils.toolButton("添加步骤", Icons.plus(Icons.NEUTRAL, 11));
        addStep.setOnAction(e -> addStep());
        Button dropStep = UiUtils.toolButton("删除步骤", Icons.minus(Icons.NEUTRAL, 11));
        dropStep.setOnAction(e -> {
            ScheduledTask.Step selected = stepTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                stepTable.getItems().remove(selected);
                stepTable.refresh();
            }
        });

        VBox stepBox = UiUtils.column(6,
                UiUtils.row(10, UiUtils.label("步骤", "section-label"),
                        UiUtils.label("按顺序执行，任一步失败即中止", "hint")),
                stepTable,
                UiUtils.row(10, stepType, stepConn, stepSchema),
                stepPayload, stepTarget,
                UiUtils.row(6, addStep, dropStep));

        VBox box = UiUtils.column(12, line1, line2, line3, nextRuns, stepBox);
        box.setPadding(new Insets(12));
        VBox.setVgrow(stepBox, Priority.ALWAYS);
        syncStepFields();
        refreshNextRuns();
        return box;
    }

    /**
     * 三种步骤要填的东西不一样，用不上的就收起来。
     *
     * <p>{@code payload} 这一栏在三种步骤下装的是三样东西：SQL 脚本、表名、
     * 备份保留份数。提示文字必须跟着变——不变的话，用户会往「保留份数」里填表名。
     */
    private void syncStepFields() {
        ScheduledTask.StepType type = stepType.getValue();
        boolean sql = type == ScheduledTask.StepType.RUN_SQL;
        boolean backup = type == ScheduledTask.StepType.BACKUP;

        stepPayload.setPromptText(sql ? "要执行的 SQL"
                : backup ? "保留最近几份（留空按 " + ScheduledTask.DEFAULT_KEEP + " 份）"
                : "要导出的表名");
        stepPayload.setPrefRowCount(sql ? 4 : 1);
        stepTarget.setPromptText(backup ? "备份文件放到哪个目录" : "导出到的文件路径");
        stepTarget.setVisible(!sql);
        stepTarget.setManaged(!sql);
    }

    private HBox buildFoot() {
        Button newTask = UiUtils.toolButton("新建任务", Icons.plus(Icons.NEUTRAL, 12));
        newTask.setOnAction(e -> {
            editing = new ScheduledTask();
            edit(editing);
        });
        Button delete = UiUtils.toolButton("删除", Icons.minus("#a0402a", 12), "danger");
        delete.setOnAction(e -> deleteTask());
        Button runNow = UiUtils.toolButton("立即运行一次", Icons.play(Icons.NEUTRAL, 11));
        runNow.setOnAction(e -> runNow());
        Button exportCmd = UiUtils.toolButton("导出为命令行", Icons.export(Icons.NEUTRAL, 12));
        exportCmd.setOnAction(e -> exportCommand());

        Button mail = UiUtils.toolButton("通知设置", Icons.file(Icons.NEUTRAL, 12));
        mail.setOnAction(e -> mailSettings());
        Button save = UiUtils.toolButton("保存任务", null, "primary");
        save.setOnAction(e -> save());

        HBox foot = UiUtils.row(8, newTask, delete, UiUtils.hSpacer(),
                mail, runNow, exportCmd, save);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 行为

    private void reload() {
        taskTable.setItems(FXCollections.observableArrayList(store.listAll()));
        runTable.setItems(FXCollections.observableArrayList(store.recentRuns(null, 20)));
    }

    private void edit(ScheduledTask task) {
        editing = task;
        nameField.setText(task.name());
        enabledBox.setSelected(task.enabled());
        triggerBox.setValue(task.trigger());
        timeField.setText(task.atTime().toString());
        dayBox.setValue(task.onDay());
        intervalField.setText(String.valueOf(task.intervalMinutes()));
        retryField.setText(String.valueOf(task.retries()));
        stepTable.setItems(FXCollections.observableArrayList(task.steps()));
        refreshNextRuns();
        if (task.id() != null) {
            runTable.setItems(FXCollections.observableArrayList(store.recentRuns(task.id(), 20)));
        }
    }

    private void addStep() {
        ScheduledTask.StepType type = stepType.getValue();
        boolean backup = type == ScheduledTask.StepType.BACKUP;
        // 备份的 payload 是「保留份数」，留空就是默认值，不算没填
        if (stepConn.getValue() == null || stepSchema.getText().isBlank()
                || (!backup && stepPayload.getText().isBlank())) {
            UiUtils.showInfo(stage, "还差点东西", "连接、库和内容都要填。");
            return;
        }
        if (type != ScheduledTask.StepType.RUN_SQL && stepTarget.getText().isBlank()) {
            UiUtils.showInfo(stage, "还差点东西",
                    backup ? "备份步骤要指定放文件的目录。" : "导出步骤要指定目标文件。");
            return;
        }
        stepTable.getItems().add(new ScheduledTask.Step(
                type, stepConn.getValue().id(), stepSchema.getText().trim(),
                backup && stepPayload.getText().isBlank()
                        ? String.valueOf(ScheduledTask.DEFAULT_KEEP)
                        : stepPayload.getText().trim(),
                type == ScheduledTask.StepType.RUN_SQL ? null : stepTarget.getText().trim()));
        stepPayload.clear();
        stepTarget.clear();
    }

    private ScheduledTask collect() {
        editing.setName(nameField.getText().trim())
                .setEnabled(enabledBox.isSelected())
                .setTrigger(triggerBox.getValue())
                .setOnDay(dayBox.getValue())
                .setRetries(parse(retryField.getText(), 0))
                .setIntervalMinutes(parse(intervalField.getText(), 60))
                .setSteps(new ArrayList<>(stepTable.getItems()));
        try {
            editing.setAtTime(LocalTime.parse(timeField.getText().trim()));
        } catch (RuntimeException e) {
            editing.setAtTime(LocalTime.of(3, 0));
        }
        return editing;
    }

    private void save() {
        if (nameField.getText().isBlank()) {
            UiUtils.showInfo(stage, "还差个名字", "任务得有个名字，命令行也是按名字找它的。");
            return;
        }
        store.save(collect());
        reload();
        UiUtils.showInfo(stage, "已保存", "任务已保存。");
    }

    private void deleteTask() {
        ScheduledTask selected = taskTable.getSelectionModel().getSelectedItem();
        if (selected == null || selected.id() == null) {
            return;
        }
        if (!UiUtils.confirm(stage, "删除任务", "确定删除「" + selected.name() + "」？")) {
            return;
        }
        store.delete(selected.id());
        editing = new ScheduledTask();
        edit(editing);
        reload();
    }

    private void runNow() {
        ScheduledTask task = collect();
        if (task.steps().isEmpty()) {
            UiUtils.showInfo(stage, "没有步骤", "先加一个步骤。");
            return;
        }
        LocalDateTime started = LocalDateTime.now();
        context.queryService()
                .submit(() -> TaskRunner.run(task, context.registry(), line -> { }))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "运行失败", error);
                        return;
                    }
                    if (task.id() != null) {
                        store.recordRun(task.id(), started, result.elapsedMillis(),
                                result.succeeded(), result.message());
                    }
                    reload();
                    UiUtils.showInfo(stage, result.succeeded() ? "运行成功" : "运行失败",
                            String.join("\n", result.log()));
                }));
    }

    /**
     * 给出交给 Windows 计划任务的命令。
     *
     * <p>命令里跑的是 {@code TaskRunner} 的 main，和界面上「立即运行一次」走的是同一段代码——
     * 不是照着界面另写一套，那样两边迟早不一致。
     */
    private void exportCommand() {
        if (nameField.getText().isBlank()) {
            UiUtils.showInfo(stage, "先起个名字", "命令行是按任务名找它的。");
            return;
        }
        String home = System.getProperty("user.dir");
        String command = "javaw -cp \"" + home + "\\plainly-app\\target\\plainly.jar;"
                + home + "\\plainly-app\\target\\deps\\*\" "
                + "com.plainly.core.task.TaskRunner \"" + nameField.getText().trim() + "\"";

        TextArea area = new TextArea(command
                + "\n\n把上面这行交给 Windows 计划任务：\n"
                + "1. 打开「任务计划程序」→ 创建基本任务\n"
                + "2. 触发器按需要设置（这里的调度只在应用开着时有效）\n"
                + "3. 操作选「启动程序」，把上面整行粘进去\n\n"
                + "命令跑的是同一个执行器，行为和「立即运行一次」一致。");
        area.setEditable(false);
        area.getStyleClass().add("ddl-area");
        area.setPrefRowCount(12);

        Stage sub = new Stage();
        UiUtils.brand(sub);
        sub.initOwner(stage);
        sub.initModality(Modality.WINDOW_MODAL);
        sub.setTitle("导出为命令行");

        Button copy = UiUtils.toolButton("复制命令", null, "primary");
        copy.setOnAction(e -> {
            area.selectRange(0, command.length());
            area.copy();
            area.deselect();
        });
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), copy);
        foot.getStyleClass().add("dialog-foot");

        VBox box = new VBox(area, foot);
        VBox.setVgrow(area, Priority.ALWAYS);
        Scene scene = new Scene(box, 820, 380);
        scene.getStylesheets().addAll(stage.getScene().getStylesheets());
        sub.setScene(scene);
        sub.show();
    }

    /**
     * 邮件通知设置。
     *
     * <p>默认只在失败时发。每天一封「成功」的邮件，两周之后就没人看了，
     * 真出事那封也会被一起忽略——所以「每次都发」是个要主动打开的选项。
     */
    private void mailSettings() {
        TextField host = new TextField(store.setting("mail.host", ""));
        TextField port = new TextField(store.setting("mail.port", "587"));
        TextField user = new TextField(store.setting("mail.user", ""));
        javafx.scene.control.PasswordField password =
                new javafx.scene.control.PasswordField();
        password.setText(store.setting("mail.password", ""));
        TextField from = new TextField(store.setting("mail.from", ""));
        TextField to = new TextField(store.setting("mail.to", ""));
        CheckBox tls = new CheckBox("STARTTLS");
        tls.setSelected("true".equalsIgnoreCase(store.setting("mail.starttls", "true")));
        CheckBox always = new CheckBox("每次都发（默认只在失败时发）");
        always.setSelected("true".equalsIgnoreCase(store.setting("mail.always", "false")));

        port.setPrefWidth(80);
        host.setPromptText("smtp.example.com");
        to.setPromptText("收件人，多个用逗号分隔");

        Stage sub = new Stage();
        UiUtils.brand(sub);
        sub.initOwner(stage);
        sub.initModality(Modality.WINDOW_MODAL);
        sub.setTitle("通知设置");

        Runnable persist = () -> {
            store.setSetting("mail.host", host.getText().trim());
            store.setSetting("mail.port", port.getText().trim());
            store.setSetting("mail.user", user.getText().trim());
            store.setSetting("mail.password", password.getText());
            store.setSetting("mail.from", from.getText().trim());
            store.setSetting("mail.to", to.getText().trim());
            store.setSetting("mail.starttls", String.valueOf(tls.isSelected()));
            store.setSetting("mail.always", String.valueOf(always.isSelected()));
        };

        Button test = UiUtils.toolButton("发一封试试", null);
        test.setOnAction(e -> {
            persist.run();
            context.queryService().submit(() -> {
                com.plainly.core.task.MailNotifier.test(
                        new com.plainly.core.task.MailNotifier.Config(
                                host.getText().trim(), parse(port.getText(), 587),
                                tls.isSelected(), user.getText().trim(), password.getText(),
                                from.getText().trim(), to.getText().trim()));
                return true;
            }).whenComplete((ok, error) -> Platform.runLater(() -> {
                if (error != null) {
                    UiUtils.showError(sub, "发信失败", error);
                    return;
                }
                UiUtils.showInfo(sub, "已发出", "去收件箱看看有没有收到。");
            }));
        });
        Button save = UiUtils.toolButton("保存", null, "primary");
        save.setOnAction(e -> {
            persist.run();
            sub.close();
        });

        VBox box = UiUtils.column(10,
                UiUtils.row(10, UiUtils.label("SMTP", "form-label"), host,
                        UiUtils.label("端口", "form-label"), port, tls),
                UiUtils.row(10, UiUtils.label("用户名", "form-label"), user),
                UiUtils.row(10, UiUtils.label("密码", "form-label"), password),
                UiUtils.row(10, UiUtils.label("发件人", "form-label"), from),
                UiUtils.row(10, UiUtils.label("收件人", "form-label"), to),
                always);
        box.setPadding(new Insets(14));

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), test, save);
        foot.getStyleClass().add("dialog-foot");

        VBox root = new VBox(box, foot);
        Scene scene = new Scene(root, 620, 340);
        scene.getStylesheets().addAll(stage.getScene().getStylesheets());
        sub.setScene(scene);
        sub.show();
    }

    private void refreshNextRuns() {
        ScheduledTask preview = new ScheduledTask()
                .setEnabled(true)
                .setTrigger(triggerBox.getValue() == null
                        ? ScheduledTask.Trigger.DAILY : triggerBox.getValue())
                .setOnDay(dayBox.getValue() == null ? DayOfWeek.MONDAY : dayBox.getValue())
                .setIntervalMinutes(parse(intervalField.getText(), 60));
        try {
            preview.setAtTime(LocalTime.parse(timeField.getText().trim()));
        } catch (RuntimeException e) {
            nextRuns.setText("时间格式应当是 HH:mm");
            return;
        }
        List<LocalDateTime> runs = preview.nextRuns(LocalDateTime.now(), 3);
        List<String> parts = new ArrayList<>();
        runs.forEach(r -> parts.add(r.format(STAMP)));
        nextRuns.setText("接下来三次：" + String.join(" · ", parts));
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.name());
        }
    }
}
