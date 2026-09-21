package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.meta.DbObjects.TriggerEvent;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import com.plainly.driver.meta.DbObjects.TriggerTiming;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 触发器的新建与修改。
 *
 * <h2>为什么单独一个窗口，而不是塞进索引外键那个</h2>
 * 索引和外键能用结构化表单表达（选几列、挑个规则），触发器不能——它的主体是一段
 * <b>过程代码</b>。硬做成表单只会做出一个处处受限的编辑器，还不如给一个像样的文本框。
 *
 * <h2>为什么「改」是删了重建</h2>
 * 只有极少数库有 {@code CREATE OR REPLACE TRIGGER}。这里统一走「先 DROP 再 CREATE」，
 * 并且把两条语句都摆在待执行清单里。<b>这中间有一段时间触发器是不存在的</b>——
 * 如果这张表上正有写入，那些写入不会被触发器处理。这句话必须写在界面上，
 * 不能等用户自己发现。
 *
 * <h2>按「时机 + 事件」分类</h2>
 * 时机（BEFORE / AFTER）和事件（INSERT / UPDATE / DELETE）用两个下拉选，
 * 外面那圈 {@code CREATE TRIGGER ... FOR EACH ROW} 由方言按选择拼出来，
 * 用户只写触发器体。这六种组合在各家的写法差异（MySQL 直接跟体，
 * PostgreSQL 得先建函数再挂，Oracle 能 CREATE OR REPLACE），没有理由让用户去背。
 *
 * <h2>SQLite 是例外</h2>
 * 它的元数据里存的就是整条建触发器语句，没有单独的时机、事件两栏
 * （见 {@code SqlDialect.triggerActionIsFullStatement}）。所以在它上面，
 * 文本框里放的是整条语句，下拉只用来生成起手模板。
 */
public class TriggerEditorDialog {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;
    /** 要改的触发器；null 表示新建。 */
    private final TriggerInfo existing;

    private final TextField nameField = new TextField();
    private final ComboBox<TriggerTiming> timingBox = new ComboBox<>();
    private final ComboBox<TriggerEvent> eventBox = new ComboBox<>();
    private final TextArea body = new TextArea();
    private final Label note = UiUtils.label("", "hint");
    private final Label preview = UiUtils.label("", "mono", "hint");

    /** 名字是否还是工具按分类推的。用户自己改过就不再跟着分类变。 */
    private boolean nameAuto = true;

    private Stage stage;
    private Runnable onApplied = () -> { };

    public TriggerEditorDialog(AppContext context, DbSession session, String schema,
                               String table, TriggerInfo existing) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        this.existing = existing;
    }

    public void setOnApplied(Runnable handler) {
        this.onApplied = handler;
    }

    public void show(Window owner) {
        String refusal = session.connection().dialect().triggerUnsupportedReason();
        if (refusal != null) {
            // 灰掉按钮却不说原因，用户只会以为软件坏了
            UiUtils.showInfo(owner, "这个库不能在这里写触发器", refusal);
            return;
        }

        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? "新建触发器" : "修改触发器");

        VBox root = new VBox(buildHead(), buildBody(), buildFoot());
        Scene scene = new Scene(root, 820, 600);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        fill();
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.plan(Icons.ACCENT, 14),
                UiUtils.label(schema + "." + table, "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        nameField.setPromptText("触发器名");
        nameField.setPrefWidth(240);
        nameField.textProperty().addListener((o, was, is) -> {
            if (!Objects.equals(is, suggestedName())) {
                nameAuto = false;
            }
        });

        timingBox.getItems().addAll(TriggerTiming.values());
        timingBox.setValue(TriggerTiming.AFTER);
        eventBox.getItems().addAll(TriggerEvent.values());
        eventBox.setValue(TriggerEvent.INSERT);
        timingBox.setOnAction(e -> classificationChanged());
        eventBox.setOnAction(e -> classificationChanged());

        body.getStyleClass().add("ddl-area");
        body.setPrefRowCount(16);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox box = UiUtils.column(8,
                UiUtils.row(8, UiUtils.label("名称", "form-label"), nameField,
                        UiUtils.label("时机", "form-label"), timingBox,
                        UiUtils.label("事件", "form-label"), eventBox),
                UiUtils.row(10, UiUtils.label("触发器体", "section-label"), note),
                body,
                UiUtils.row(8, UiUtils.label("将执行", "form-label"), preview));
        box.setPadding(new Insets(10));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** 分类换了：名字（没被手改过的话）和模板都跟着换，下面那行预览也重画。 */
    private void classificationChanged() {
        if (nameAuto) {
            String suggested = suggestedName();
            nameField.setText(suggested);
            nameAuto = true;   // setText 会触发监听器，这里把它扳回来
        }
        if (existing == null && !fullStatementMode()) {
            body.setText(session.connection().dialect().triggerBodyTemplate(eventBox.getValue()));
        }
        refreshPreview();
    }

    /** 按分类推一个名字：{@code 表名_before_insert}，和 Navicat 的习惯一致。 */
    private String suggestedName() {
        return table.toLowerCase() + "_" + timingBox.getValue().name().toLowerCase()
                + "_" + eventBox.getValue().name().toLowerCase();
    }

    /** 这一家的触发器元数据是不是整条语句（SQLite）。是的话文本框里放的就是整条。 */
    /**
     * 这个对话框此刻是不是「整条语句」模式：文本框里放的是完整的 CREATE 语句。
     *
     * <h2>新建和修改问的不是同一件事</h2>
     * <ul>
     *   <li><b>修改</b>已有触发器时，文本框里放的是从元数据读回来的东西。
     *       那份是不是整条语句，由 {@code triggerActionIsFullStatement()} 说了算
     *       ——达梦的 {@code TRIGGER_BODY} 存的就是整条；</li>
     *   <li><b>新建</b>时，文本框里放什么由我们决定，
     *       取决于这一家能不能让本工具替它拼出 CREATE 语句
     *       （{@code composesFullStatement()}）。达梦能，所以新建时只写触发器体。</li>
     * </ul>
     *
     * <p>两者共用一个标志的代价：新建时文本框里是<b>把名字写死在里面</b>的整条语句，
     * 上面那个「名称」输入框就成了摆设——改了名字点创建，执行的仍是语句里那个旧名字。
     * 用户看到的是「新建的触发器没出现」，真相是又把同名的那个覆盖了一遍。
     */
    private boolean fullStatementMode() {
        var dialect = session.connection().dialect();
        return existing == null
                ? dialect.composesFullStatement()
                : dialect.triggerActionIsFullStatement();
    }

    private void refreshPreview() {
        if (fullStatementMode()) {
            preview.setText("这一家（" + session.config().type().displayName()
                    + "）的触发器按整条语句保存，上面写什么就执行什么");
            return;
        }
        String ddl = generateDdl();
        if (ddl == null || ddl.isBlank()) {
            preview.setText("");
            return;
        }
        // 按 10（换行符）数，不能用 System.lineSeparator()：方言生成的语句里是 LF，
        // 而这台机器上的行分隔符是 CRLF，拿它去切根本切不开——切不开就成了
        // 「整段 DDL 挤在预览行里，后面还写着『共 1 行』」
        int lines = 1 + (int) ddl.chars().filter(c -> c == 10).count();
        int firstBreak = ddl.indexOf(10);
        String head = firstBreak < 0 ? ddl : ddl.substring(0, firstBreak);
        preview.setText(head + " …（共 " + lines + " 行）");
    }

    /** 把分类和触发器体拼成整条建触发器语句。 */
    private String generateDdl() {
        return session.connection().dialect().createTriggerDdl(schema, table,
                nameField.getText() == null ? "" : nameField.getText().trim(),
                timingBox.getValue(), eventBox.getValue(), body.getText().trim());
    }

    private HBox buildFoot() {
        Button cancel = UiUtils.toolButton("取消", null);
        cancel.setOnAction(e -> stage.close());

        Button apply = UiUtils.toolButton(
                existing == null ? "创建" : "应用修改（删了重建）", null, "primary");
        apply.setOnAction(e -> apply());

        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), cancel, apply);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private void fill() {
        if (existing == null) {
            nameField.setText(suggestedName());
            nameAuto = true;
            body.setText(fullStatementMode()
                    ? session.connection().dialect()
                            .createTriggerTemplate(schema, table, suggestedName())
                    : session.connection().dialect().triggerBodyTemplate(eventBox.getValue()));
            note.setText(fullStatementMode()
                    ? "整条语句都在这儿，按需要改"
                    : "只写触发器体，外面那圈 CREATE 由上面选的时机与事件拼");
            refreshPreview();
            return;
        }

        nameField.setText(existing.name());
        nameField.setDisable(true); // 改名等于换一个触发器，走「新建」更清楚
        nameAuto = false;
        // 分类按元数据回填。认不出来（SQLite 那种只存整条语句的）就保持默认，
        // 由用户自己在语句里改——替他猜一个错的更糟
        TriggerTiming timing = existing.timingOf();
        TriggerEvent event = existing.eventOf();
        if (timing != null) {
            timingBox.setValue(timing);
        }
        if (event != null) {
            eventBox.setValue(event);
        }
        // 去掉首尾空白：PostgreSQL 的 prosrc 前后各带一个换行
        //（函数体是写在 $$ 和 $$ 之间的，那两个换行属于包装不属于内容）。
        // 不去掉的话，每存一次就多出一圈空行，改几次之后编辑框顶上全是空白
        body.setText(existing.action() == null ? "" : existing.action().strip());
        note.setText(fullStatementMode()
                ? "会先 DROP，再执行上面这条整语句"
                : "改时机或事件也可以，会按新的分类重建");
        refreshPreview();
    }

    private void apply() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isBlank() || body.getText().isBlank()) {
            UiUtils.showInfo(stage, "还差点东西", "名称和定义都要填。");
            return;
        }

        String ddl = fullStatementMode() ? body.getText().trim() : generateDdl();
        if (ddl == null || ddl.isBlank()) {
            UiUtils.showInfo(stage, "这个库不能在这里写触发器",
                    session.connection().dialect().triggerUnsupportedReason());
            return;
        }

        // 整条语句模式下，真正生效的名字写在语句里，上面那个输入框只是个说明。
        // 两处不一致时不能替用户挑一个——挑错了就是覆盖掉另一个触发器
        if (fullStatementMode()) {
            String inStatement = com.plainly.core.sql.TriggerNames.parse(ddl);
            if (inStatement != null && !inStatement.equalsIgnoreCase(name)) {
                UiUtils.showInfo(stage, "名称和语句里的对不上",
                        "上面填的是 " + name + "，而下面这条语句建的是 " + inStatement + "。"
                                + System.lineSeparator()
                                + "这一家的触发器按整条语句执行，真正生效的是语句里那个。"
                                + System.lineSeparator()
                                + "把两处改成一致再继续——照着执行的话，"
                                + "你会以为建的是 " + name + "，实际动的是 " + inStatement + "。");
                return;
            }
        }

        List<String> statements = new ArrayList<>();
        // Oracle 那种 CREATE OR REPLACE 不必先删：先删再建反而多出一段
        // 「触发器不存在」的窗口，期间的写入不会被它处理
        boolean replaces = replacesSilently(ddl);
        if (existing != null && !replaces) {
            statements.add(session.connection().dialect()
                    .dropTriggerDdl(schema, table, existing.name()));
        }
        // 拆不拆由方言自己决定，外面不按分号切。
        //
        // 切是错的：MySQL 触发器的 BEGIN ... ; ... ; END 里本来就有分号，
        // 按分号切会把它剁成几段互不成立的碎片（mysql 命令行要写 DELIMITER
        // 就是为了绕开这件事，而 JDBC 根本不需要）。
        //
        // 但也不能一律整段发：PostgreSQL 那份是「先建函数再挂触发器」两条语句，
        // pgjdbc 肯替我们拆，openGauss 的驱动不肯，会报
        // 「cannot insert multiple commands into a prepared statement」。
        // 缝在哪里只有生成它的那个方言知道，所以问它。
        statements.addAll(session.connection().dialect().triggerStatements(ddl));

        String warning = statements.size() > 1
                ? System.lineSeparator() + "先删后建，中间这段时间触发器不生效。" : "";

        // 撞名检查要发一次查询，不能在界面线程上做。所以整条路串成后台链：
        // 先问库里有没有同名的，回到界面线程处理，再发 DDL
        context.queryService()
                .submit(() -> findConflict(name))
                .whenComplete((clash, lookupError) -> Platform.runLater(() -> {
                    // 查不出来不该拦住用户建触发器——退回原来的行为，让数据库自己说话
                    if (lookupError == null && clash != null && !passesConflict(name, clash)) {
                        return;
                    }
                    if (!UiUtils.confirm(stage, existing == null ? "创建触发器" : "重建触发器",
                            timingBox.getValue().name() + " " + eventBox.getValue().name()
                                    + " 触发器，将执行 " + statements.size() + " 条语句。" + warning)) {
                        return;
                    }
                    execute(statements);
                }));
    }

    /**
     * 撞名了怎么办：能覆盖的问一句，不能覆盖的直接拦下。
     *
     * <h2>为什么不能一律「问一句要不要继续」</h2>
     * MySQL、PostgreSQL 没有 {@code CREATE OR REPLACE TRIGGER}，撞名<b>必然</b>被拒。
     * 明知会失败还摆一个「确定继续」，等于请用户去撞一堵墙，然后把数据库的原始报错
     * 甩给他——而那句报错里没有他真正需要的信息：这个名字已经被<b>哪张表</b>占了。
     *
     * <p>Oracle、达梦则相反：它们发的是 {@code CREATE OR REPLACE}，撞名不报错而是
     * <b>静默覆盖</b>。那种情况必须问，因为损失是不可逆的、而且不问就完全看不见。
     *
     * @return 是否可以继续往下走
     */
    private boolean passesConflict(String name, Conflict clash) {
        String where = clash.sameTable()
                ? "这张表上"
                : "同一个库的 " + clash.table() + " 表上";
        if (!replacesSilently()) {
            // 注定被拒，不给「继续」这个选项——它唯一的作用是让用户挨一句原始报错
            UiUtils.showInfo(stage, "这个名字已经被占了",
                    where + "已经有一个叫 " + name + " 的触发器。"
                            + System.lineSeparator()
                            + session.config().type().displayName()
                            + " 的触发器名在整个库里唯一，同名再建一定会被拒绝。"
                            + System.lineSeparator()
                            + "换一个名字再创建。");
            return false;
        }
        return UiUtils.confirm(stage, "已经有同名触发器",
                where + "已经有一个叫 " + name + " 的触发器。"
                        + System.lineSeparator()
                        + "这个数据库用的是 CREATE OR REPLACE，继续会把原来那个覆盖掉，"
                        + "原来的定义找不回来。"
                        + System.lineSeparator()
                        + "想两个都留着的话，先取消，换一个名字。"
                        + System.lineSeparator() + "确定继续？");
    }

    /** 撞上的那个同名触发器挂在哪张表上。{@code sameTable} 为 true 就是当前这张。 */
    private record Conflict(String table, boolean sameTable) {
    }

    /**
     * 库里已经有同名触发器了吗；没有返回 null。只在<b>新建</b>时查——
     * 改的时候撞的是它自己。
     *
     * <h2>为什么非查不可</h2>
     * 两种后果，方向完全相反，但都得在发语句<b>之前</b>知道：
     * <ul>
     *   <li>Oracle、达梦发的是 {@code CREATE OR REPLACE}，撞名<b>静默覆盖</b>。
     *       新建对话框默认推的名字是 {@code 表名_时机_事件}，用默认设置连着建两次
     *       名字必然一样，第二次就把第一次悄悄冲掉了——用户看到的是
     *       「列表里还是一条，我新建的那个没出现」；</li>
     *   <li>MySQL、PostgreSQL 撞名<b>必然被拒</b>。不预先查的话，用户拿到的是数据库的
     *       原始报错，里面没有他真正需要的那条信息：这个名字被<b>哪张表</b>占了。</li>
     * </ul>
     *
     * <h2>查的范围要按家</h2>
     * 触发器名的作用域各家不同（见 {@code SqlDialect.triggerNameConflictQuery}）：
     * MySQL / Oracle / 达梦是整个库唯一，PostgreSQL 是每张表唯一。
     * 一律只查当前表，就会在前一类库上漏掉跨表撞名。
     */
    private Conflict findConflict(String name) {
        if (existing != null) {
            return null;
        }
        String sql = session.connection().dialect().triggerNameConflictQuery(schema, name);
        if (sql != null) {
            var result = session.connection().execute(sql, 10);
            if (result.rows().isEmpty()) {
                return null;
            }
            String owner = result.rows().get(0).get(1);
            return new Conflict(owner, owner != null && owner.equalsIgnoreCase(table));
        }
        // 名字只在表内唯一的库（PostgreSQL）：查当前表就够了
        boolean hit = session.connection().listTriggers(schema, table).stream()
                .anyMatch(t -> t.name() != null && t.name().equalsIgnoreCase(name));
        return hit ? new Conflict(table, true) : null;
    }

    private boolean replacesSilently() {
        return replacesSilently(fullStatementMode() ? body.getText().trim() : generateDdl());
    }

    /** 判据见 {@link com.plainly.core.sql.TriggerNames#replacesExisting}。 */
    private static boolean replacesSilently(String ddl) {
        return com.plainly.core.sql.TriggerNames.replacesExisting(ddl);
    }

    private void execute(List<String> statements) {
        context.queryService()
                .submit(() -> session.executeDdl(schema, statements))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(stage, "执行失败", error);
                        return;
                    }
                    onApplied.run();
                    stage.close();
                }));
    }
}
