package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.ddl.TableCopy;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * 照着一张表建一张新表。
 *
 * <h2>为什么值得有</h2>
 * 「改表结构之前先留一份」是每天都在做的事，而在没有这个功能的时候，做法是：
 * 打开 DDL 页、复制建表语句、粘到查询页、把表名改掉、执行、再手写一条
 * {@code INSERT ... SELECT}。六步里有两步要手改文本，改错了不会报错——
 * 只会建出一张名字对、内容错的表。
 *
 * <h2>不复制什么，以及为什么要写在界面上</h2>
 * 外键、触发器、表级注释、自增当前值都不复制。它们各自的麻烦不一样：
 * 外键指向的表未必也复制了；触发器复制过去会对着新表继续写日志，
 * 而那多半不是用户想要的。与其替他猜，不如不做——但必须说出来，
 * 否则用户会以为拿到了一张一模一样的表，然后在某个依赖外键的地方栽跟头。
 *
 * <h2>只在同一条连接内</h2>
 * 跨连接复制是另一件事（要建连接、要传数据、要处理类型差异），
 * 那是「数据传输」的职责。这里只做同连接内的复制，所以能用一条
 * {@code INSERT ... SELECT} 让数据完全不经过本机。
 */
public class CopyTableDialog {

    private final AppContext context;
    private final DbSession session;
    private final String sourceSchema;
    private final TableInfo source;

    private final TextField nameField = new TextField();
    private final ComboBox<String> schemaBox = new ComboBox<>();
    private final ToggleGroup contentGroup = new ToggleGroup();
    private final RadioButton structureOnly = new RadioButton("仅结构");
    private final RadioButton withData = new RadioButton("结构和数据");
    private final CheckBox withIndexes = new CheckBox("一并建立索引");
    private final TextArea preview = new TextArea();
    private final Label warning = UiUtils.label("", "status-error");

    private Stage stage;
    private Button copyButton;
    private TableStructure structure;
    private Runnable onCopied = () -> { };

    public CopyTableDialog(AppContext context, DbSession session,
                           String schema, TableInfo table) {
        this.context = context;
        this.session = session;
        this.sourceSchema = schema;
        this.source = table;
    }

    /** 复制完成后回调，用于刷新树。 */
    public void setOnCopied(Runnable handler) {
        this.onCopied = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("复制表");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 700, 560);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        loadStructure();
        Platform.runLater(() -> {
            nameField.requestFocus();
            // 选中「_copy」之前那一段：多数人要改的是主体名字，后缀留着就行
            nameField.selectRange(0, source.name().length());
        });
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.table(Icons.ACCENT, 14),
                UiUtils.label(sourceSchema + "." + source.name(), "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        nameField.setText(source.name() + "_copy");
        nameField.setPrefWidth(260);
        nameField.textProperty().addListener((o, was, is) -> refreshPreview());

        List<SchemaInfo> schemas = session.schemas();
        schemas.forEach(s -> schemaBox.getItems().add(s.name()));
        schemaBox.setValue(sourceSchema);
        schemaBox.setPrefWidth(200);
        schemaBox.valueProperty().addListener((o, was, is) -> onTargetSchemaChanged(is));

        structureOnly.setToggleGroup(contentGroup);
        withData.setToggleGroup(contentGroup);
        structureOnly.setSelected(true);
        contentGroup.selectedToggleProperty().addListener((o, was, is) -> refreshPreview());

        withIndexes.setSelected(true);
        withIndexes.selectedProperty().addListener((o, was, is) -> refreshPreview());

        preview.setEditable(false);
        preview.getStyleClass().add("ddl-area");
        preview.setPrefRowCount(10);

        warning.setWrapText(true);
        warning.setManaged(false);
        warning.setVisible(false);

        Label omitted = UiUtils.label(
                "不复制：外键、触发器、表级注释、自增当前值。"
                        + "需要这些的话，改在新表上单独建——把它们一起搬过来往往不是想要的结果"
                        + "（比如触发器会对着新表继续写日志）。", "hint");
        omitted.setWrapText(true);

        VBox box = UiUtils.column(10,
                UiUtils.row(8, UiUtils.label("新表名", "form-label"), nameField,
                        UiUtils.label("建到", "form-label"), schemaBox),
                UiUtils.row(12, UiUtils.label("内容", "form-label"),
                        structureOnly, withData, withIndexes),
                omitted,
                warning,
                UiUtils.row(10, UiUtils.label("将要执行", "section-label")),
                preview);
        box.setPadding(new Insets(12));
        VBox.setVgrow(preview, Priority.ALWAYS);
        return box;
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());
        copyButton = UiUtils.toolButton("开始复制", null, "primary");
        copyButton.setOnAction(e -> copy());
        copyButton.setDisable(true);

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, copyButton);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    /**
     * 取源表结构。
     *
     * <p>放在后台：几百列的表读一次元数据要几百毫秒，卡在界面线程上，
     * 对话框会顶着一片空白弹出来。
     */
    private void loadStructure() {
        preview.setText("-- 正在读取 " + source.name() + " 的结构…");
        context.queryService()
                .submit(() -> session.structure(sourceSchema, source.name()))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        preview.setText("-- 读不到表结构");
                        UiUtils.showError(stage, "读取 " + source.name() + " 结构失败", error);
                        return;
                    }
                    structure = result;
                    refreshPreview();
                }));
    }

    private String newName() {
        return nameField.getText() == null ? "" : nameField.getText().trim();
    }

    private TableCopy.Options options() {
        return new TableCopy.Options(schemaBox.getValue(), newName(),
                withIndexes.isSelected(), withData.isSelected());
    }

    /**
     * 刷新预览，顺带做落库前的检查。
     *
     * <p>检查放在这里而不是按下按钮之后：撞名是最常见的一种失败，
     * 而它在按下去之前就能知道。等报错再说，用户已经白等了一次往返，
     * 还得自己从数据库的英文报错里读出「原来是重名了」。
     */
    private void refreshPreview() {
        if (structure == null) {
            return;
        }
        String problem = check();
        // 拦截优先于提醒：两句一起摆出来，用户分不清哪一句让按钮灰着
        showWarning(problem != null ? problem : autoIncrementNote());
        copyButton.setDisable(problem != null);
        if (problem != null && newName().isEmpty()) {
            preview.setText("-- 先填新表名");
            return;
        }
        preview.setText(String.join(";" + System.lineSeparator() + System.lineSeparator(),
                TableCopy.build(session.connection().dialect(), sourceSchema, structure, options())
                        .all()) + ";");
    }

    /**
     * 这一家写不出自增，而源表恰好有一列是自增——提醒一句。
     *
     * <p>这不是拦截，是提醒：复制出来的表照样能用，只是往里插数据时得自己给主键值。
     * 不说的话，用户要到第一条 INSERT 报「主键不能为空」时才发现，
     * 而那已经离复制这一步很远了。
     */
    private String autoIncrementNote() {
        String reason = session.connection().dialect().autoIncrementUnsupportedReason();
        if (reason == null || structure == null) {
            return null;
        }
        boolean hasAutoIncrement = structure.columns().stream().anyMatch(c -> c.autoIncrement());
        return hasAutoIncrement ? reason + "。" : null;
    }

    /** 拦得住的问题，返回一句话；没问题返回 null。 */
    private String check() {
        String name = newName();
        if (name.isEmpty()) {
            return "填一个新表名。";
        }
        String target = schemaBox.getValue();
        if (name.equalsIgnoreCase(source.name()) && target.equals(sourceSchema)) {
            return "新表名和原表相同。改个名字，或者把「建到」换成别的库。";
        }
        /*
         * 只看缓存里的表清单。
         *
         * check() 是<b>每敲一个字符</b>都要跑一遍的（refreshPreview 挂在名字框上）。
         * 原来这里写的是 session.tables(target)，它在未命中缓存时会真的去查库——
         * 也就是每一次击键一次网络往返。源库那份是热的（树上展开过），
         * 但用户一旦把「建到」换成别的库，就全是未命中。
         *
         * 所以这里只读缓存，读不到就不下「撞名」的结论；那份清单由
         * onTargetSchemaChanged 在后台捂热，回来之后再刷一次预览。
         */
        List<TableInfo> known = session.cachedTables(target);
        if (known == null) {
            return null; // 还不知道那个库里有什么，先不拦
        }
        boolean exists = known.stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
        if (exists) {
            return target + " 里已经有 " + name + " 了。这里不会覆盖它——换个名字。";
        }
        return null;
    }

    /**
     * 换了目标库：先刷一次预览，再把那个库的表清单捂热。
     *
     * <p>捂热是为了撞名检查——它只读缓存（见 {@link #check}）。读回来之后
     * 再刷一次，那时候撞名才拦得住。这中间的一两秒里按下「开始复制」，
     * 数据库自己会拒绝重名，不会真的覆盖谁。
     */
    private void onTargetSchemaChanged(String target) {
        refreshPreview();
        if (target == null || session.cachedTables(target) != null) {
            return;
        }
        context.queryService().submit(() -> session.tables(target))
                .whenComplete((tables, error) -> Platform.runLater(this::refreshPreview));
    }

    private void showWarning(String text) {
        // 提醒和拦截长得不一样：拦截是红的（按钮灰着，必须处理），提醒是灰的
        warning.getStyleClass().setAll(check() == null ? "hint" : "status-error");
        warning.setText(text == null ? "" : text);
        warning.setManaged(text != null);
        warning.setVisible(text != null);
    }

    private void copy() {
        String problem = check();
        if (problem != null) {
            showWarning(problem);
            return;
        }
        TableCopy.Options options = options();
        TableCopy.Plan plan = TableCopy.build(
                session.connection().dialect(), sourceSchema, structure, options);

        copyButton.setDisable(true);
        copyButton.setText("复制中…");
        context.queryService()
                .submit(() -> run(plan, options))
                .whenComplete((outcome, error) -> Platform.runLater(() -> {
                    copyButton.setDisable(false);
                    copyButton.setText("开始复制");
                    if (error != null) {
                        // DDL 多半已经生效了一部分（多数库的 DDL 不参与事务），
                        // 这一点由 DdlBatchException 自己说清楚，这里不再猜
                        UiUtils.showError(stage, "复制 " + source.name() + " 失败", error);
                        return;
                    }
                    // 新表要在树上出现，缓存的表清单得作废
                    session.invalidate();
                    onCopied.run();
                    UiUtils.showInfo(stage, "已复制", describe(outcome, options));
                    stage.close();
                }));
    }

    /**
     * 一次复制做完之后的情况。
     *
     * @param rows        灌进去的行数；只复制结构时为 -1
     * @param counterNote 自增计数器那一步的问题；顺利时为 null
     */
    private record Outcome(int rows, String counterNote) {
    }

    /** 真正跑语句的那一段，在后台线程上。 */
    private Outcome run(TableCopy.Plan plan, TableCopy.Options options) {
        session.executeDdl(sourceSchema, plan.ddl());
        if (plan.insert() == null) {
            return new Outcome(-1, null);
        }
        int rows = session.connection().execute(plan.insert(), 0).updateCount();
        return new Outcome(rows, advanceCounter(options));
    }

    /**
     * 把新表的自增计数器推到已有数据之后。
     *
     * <p>数据是连同主键值一起显式插进去的，多数库的计数器不会因此前移——不推的话，
     * 复制出来的表看着完全正常，直到有人往里插第一条新数据，撞主键。
     *
     * <p>这一步失败不算复制失败：表和数据都已经好了，只是计数器没动。
     * 所以返回一句话交给完成提示，而不是把整次复制报成失败——
     * 后者会让用户以为要重来一次，而重来会撞上「表已存在」。
     */
    private String advanceCounter(TableCopy.Options options) {
        String column = TableCopy.autoIncrementColumn(structure);
        if (column == null) {
            return null;
        }
        try {
            String max = session.connection().scalar("SELECT MAX("
                    + session.connection().dialect().quote(column) + ") FROM "
                    + session.connection().dialect()
                            .qualify(options.targetSchema(), options.newName()));
            if (max == null || max.isBlank()) {
                return null; // 空表，计数器本来就在起点上
            }
            String ddl = TableCopy.restartAutoIncrement(session.connection().dialect(),
                    structure, options, Long.parseLong(max.trim()));
            if (ddl == null) {
                return "这个数据库的自增计数器要自己调，否则下一条插入会撞主键。";
            }
            session.executeDdl(sourceSchema, List.of(ddl));
            return null;
        } catch (RuntimeException e) {
            return "自增计数器没能推上去（" + UiUtils.rootMessage(e)
                    + "），下一条插入可能撞主键，请手动调整。";
        }
    }

    private String describe(Outcome outcome, TableCopy.Options options) {
        StringBuilder sb = new StringBuilder(options.newName()).append(" 建好了");
        sb.append(outcome.rows() >= 0
                ? "，灌入 " + UiUtils.groupDigits(outcome.rows()) + " 行。" : "。");
        sb.append(System.lineSeparator()).append("外键、触发器和表级注释没有跟过来。");
        if (outcome.counterNote() != null) {
            sb.append(System.lineSeparator()).append(outcome.counterNote());
        }
        return sb.toString();
    }
}
