package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.ui.FileDialogs;
import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import com.plainly.core.db.Connections;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;

/** 新建 / 编辑连接。 */
public class ConnectionDialog {

    private final AppContext context;
    private final ConnectionConfig config;
    private final boolean isNew;

    private final TextField nameField = new TextField();
    private final ComboBox<DbType> typeBox = new ComboBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField databaseField = new TextField();
    private final TextField fileField = new TextField();
    private final Button browseButton = UiUtils.toolButton("浏览", null);
    private final ComboBox<String> timezoneBox = new ComboBox<>();
    private final ComboBox<String> charsetBox = new ComboBox<>();
    private final CheckBox savePasswordBox = new CheckBox("保存密码");
    private final CheckBox readOnlyBox = new CheckBox("只读模式");
    private final ComboBox<ConnectionColor> colorBox = new ComboBox<>();
    /**
     * 分组名。可编辑：既能从已有的分组里挑，也能直接敲一个新的。
     *
     * <p>做成可编辑的下拉而不是纯输入框，是因为分组靠<b>名字完全相同</b>来归拢——
     * 敲成「生产库」和「生产 库」就是两个分组，而用户在树上看到的是两个长得几乎
     * 一样的目录，还找不出原因。列出已有的名字，让「选」比「敲」更省事。
     */
    private final ComboBox<String> groupBox = new ComboBox<>();

    private final CheckBox sshBox = new CheckBox("经 SSH 跳板机连接");
    private final TextField sshHost = new TextField();
    private final TextField sshPort = new TextField("22");
    private final TextField sshUser = new TextField();
    private final PasswordField sshPassword = new PasswordField();
    private final TextField sshKeyPath = new TextField();
    private final CheckBox sshAcceptHost = new CheckBox("首次连接自动接受主机密钥");
    private final Label credentialHint = UiUtils.label("", "hint");
    private final HBox banner = new HBox(9);
    private final Label bannerText = UiUtils.label("");

    private final VBox serverRows = new VBox(15);
    private final HBox fileRow;
    private final ScrollPane formScroll = new ScrollPane();

    /** 「默认库」那一格的标签。各家填的东西不是一回事，标签得跟着类型变。 */
    private final Label databaseLabel = UiUtils.label("默认库", "form-label");
    private final Label typeHint = UiUtils.label("", "hint");

    private Stage stage;
    private ConnectionConfig saved;

    public ConnectionDialog(AppContext context, ConnectionConfig existing) {
        this.context = context;
        this.isNew = existing == null;
        this.config = existing == null ? new ConnectionConfig() : existing.copy();
        this.fileRow = UiUtils.row(8, fileField, browseButton);
        HBox.setHgrow(fileField, Priority.ALWAYS);
    }

    public Optional<ConnectionConfig> showAndWait(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(isNew ? "新建连接" : "编辑连接");

        // 表单套在滚动区里。这不是可有可无的：勾上 SSH 之后表单会长出五行，
        // 之前是直接把下面的内容连同「保存」按钮一起挤出窗口——不报错，就是看不见，
        // 于是这个连接根本存不下来。
        formScroll.setContent(buildForm());
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.getStyleClass().add("dialog-scroll");

        VBox root = new VBox(formScroll, buildBanner(), buildFoot());
        VBox.setVgrow(formScroll, Priority.ALWAYS);

        // 不勾 SSH 时整张表单正好放得下；勾了就交给滚动条。
        // 上限收在屏幕高度里，笔记本上不至于窗口比屏幕还高
        double height = Math.min(760,
                javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() - 80);
        Scene scene = new Scene(root, 580, height);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        loadConfig();
        stage.showAndWait();
        return Optional.ofNullable(saved);
    }

    private VBox buildForm() {
        typeBox.getItems().addAll(DbType.MYSQL, DbType.POSTGRESQL, DbType.ORACLE, DbType.DM,
                DbType.REDIS, DbType.MONGODB,
                DbType.KINGBASE, DbType.OCEANBASE, DbType.GAUSSDB,
                DbType.SQLITE, DbType.H2);
        typeBox.setMaxWidth(Double.MAX_VALUE);
        // 下拉里也用树上那套标记：用户在这儿选过一次，回头在树上看到同一个色块就认得
        typeBox.setCellFactory(v -> new TypeCell());
        typeBox.setButtonCell(new TypeCell());
        typeBox.valueProperty().addListener((obs, old, type) -> onTypeChanged(type));

        timezoneBox.getItems().addAll("Asia/Shanghai", "UTC", "Asia/Tokyo", "America/New_York");
        timezoneBox.setEditable(true);
        timezoneBox.setMaxWidth(Double.MAX_VALUE);

        charsetBox.getItems().addAll("utf8mb4", "utf8", "latin1", "gbk");
        charsetBox.setEditable(true);
        charsetBox.setMaxWidth(Double.MAX_VALUE);

        portField.setPrefWidth(84);
        portField.setMaxWidth(84);

        browseButton.setOnAction(e -> chooseFile());

        savePasswordBox.setSelected(true);
        colorBox.setItems(FXCollections.observableArrayList(ConnectionColor.values()));
        colorBox.setValue(ConnectionColor.NONE);
        colorBox.setCellFactory(v -> new ColorCell());
        colorBox.setButtonCell(new ColorCell());
        groupBox.setEditable(true);
        groupBox.setItems(FXCollections.observableArrayList(existingGroups()));
        groupBox.setMaxWidth(Double.MAX_VALUE);
        credentialHint.setText(context.credentialStore().describe());
        credentialHint.setWrapText(true);
        typeHint.setWrapText(true);

        serverRows.getChildren().addAll(
                formRow("主机", UiUtils.row(12, growing(hostField),
                        UiUtils.label("端口", "form-label"), portField)),
                formRow("用户名", userField),
                formRow("密码", passwordField),
                indented(UiUtils.column(2, savePasswordBox, credentialHint)),
                formRow(databaseLabel, databaseField));

        VBox form = new VBox(15,
                formRow("连接名", nameField),
                formRow("数据库", typeBox),
                indented(typeHint),
                separator(),
                serverRows,
                formRow("库文件", fileRow),
                separator(),
                formRow("时区", timezoneBox),
                formRow("字符集", charsetBox),
                formRow("标记色", colorBox),
                // 这句说的是上面那个标记色，得挨着它——原先掉到 SSH 那一堆下面去了
                indented(hint("生产库标红：树上、标签页上都会带这个颜色，"
                        + "让人一眼看出自己连在哪个库上")),
                formRow("分组", groupBox),
                indented(hint("填了分组，树上就把同组的连接收进一个可折叠的目录；"
                        + "留空则留在最外层")),
                separator(),
                indented(sshBox),
                sshRows(),
                separator(),
                indented(UiUtils.row(9, readOnlyBox,
                        UiUtils.label("拦截本连接上的所有写操作", "hint"))));
        form.setPadding(new Insets(20, 20, 8, 20));
        return form;
    }

    /** 已经用过的分组名，去重并排序，供下拉列出。 */
    private java.util.List<String> existingGroups() {
        return context.registry().listAll().stream()
                .map(ConnectionConfig::group)
                .filter(g -> g != null && !g.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 预设的几个标记色。给自由取色反而让人纠结，几个够用的就行。 */
    public enum ConnectionColor {
        NONE("不标记", ""),
        RED("红 · 生产", "#c0392b"),
        ORANGE("橙 · 预发", "#d68910"),
        GREEN("绿 · 测试", "#1e8449"),
        BLUE("蓝 · 开发", "#2471a3"),
        PURPLE("紫 · 其它", "#6c3483");

        private final String label;
        private final String hex;

        ConnectionColor(String label, String hex) {
            this.label = label;
            this.hex = hex;
        }

        public String label() {
            return label;
        }

        public String hex() {
            return hex;
        }

        public static ConnectionColor of(String hex) {
            for (ConnectionColor c : values()) {
                if (c.hex.equalsIgnoreCase(hex == null ? "" : hex)) {
                    return c;
                }
            }
            return NONE;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 数据库种类那一栏：色块加名字，和左侧树上的标记是同一套。 */
    private static class TypeCell extends javafx.scene.control.ListCell<DbType> {
        @Override
        protected void updateItem(DbType item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.displayName());
            setGraphic(com.plainly.app.ui.DbMarks.mark(item, 14, false));
        }
    }

    /** 下拉里画个色块，光有文字看不出深浅。 */
    private static class ColorCell extends javafx.scene.control.ListCell<ConnectionColor> {
        @Override
        protected void updateItem(ConnectionColor item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.label());
            if (item == ConnectionColor.NONE) {
                setGraphic(null);
                return;
            }
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
            dot.setFill(javafx.scene.paint.Color.web(item.hex()));
            setGraphic(dot);
        }
    }

    /**
     * SSH 那几行。
     *
     * <p>不勾就整块收起来（{@code managed=false} 才是真的不占位置），
     * 免得一个直连的库也要面对六个跳板机字段。
     */
    private VBox sshRows() {
        sshHost.setPromptText("跳板机地址");
        // 跟上面「主机 / 端口」那行用同一个宽度，两个端口框才对得齐
        sshPort.setPrefWidth(84);
        sshPort.setMaxWidth(84);
        sshUser.setPromptText("SSH 用户名");
        sshPassword.setPromptText("SSH 密码（用密钥时留空）");
        sshKeyPath.setPromptText("私钥路径，如 C:\\Users\\me\\.ssh\\id_ed25519");

        Button browse = UiUtils.toolButton("选私钥", null);
        browse.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择私钥文件");
            java.io.File file = FileDialogs.open(stage, context.uiState(), chooser);
            if (file != null) {
                sshKeyPath.setText(file.getAbsolutePath());
            }
        });

        // 行距、标签宽度、输入框撑不撑满，都跟上半张表单保持一致；
        // growing() 少一个，那一行的输入框就缩成半截——「主机」是撑满的，
        // 「跳板机」不撑满，看着就像两张表单拼起来的
        VBox box = UiUtils.column(15,
                formRow("跳板机", UiUtils.row(12, growing(sshHost),
                        UiUtils.label("端口", "form-label"), sshPort)),
                formRow("SSH 用户", sshUser),
                formRow("SSH 密码", sshPassword),
                formRow("私钥", UiUtils.row(8, growing(sshKeyPath), browse)),
                indented(UiUtils.column(2, sshAcceptHost,
                        hint("不勾则要求本机 known_hosts 里已有该主机的密钥。"
                                + "自动接受等于放弃 SSH 对中间人攻击的防护，只在可信网络里用。"))));
        box.setPadding(new Insets(3, 0, 0, 0));
        box.setVisible(false);
        box.setManaged(false);
        sshBox.setOnAction(e -> {
            box.setVisible(sshBox.isSelected());
            box.setManaged(sshBox.isSelected());
            if (sshBox.isSelected()) {
                // 刚长出来的五行可能在视野下面。等这一轮布局跑完再滚，
                // 不然拿到的还是展开前的坐标
                Platform.runLater(() -> scrollIntoView(sshBox));
            }
        });
        return box;
    }

    /**
     * 一句说明。<b>一定要能换行</b>：说明里往往写着「不勾会怎样」这种要紧话，
     * 不换行就被截成「……对中间人…」，等于没写。
     */
    private static Label hint(String text) {
        Label l = UiUtils.label(text, "hint");
        l.setWrapText(true);
        return l;
    }

    /** 把某个控件滚到视野靠上的位置——它下面通常还跟着一串要填的东西。 */
    private void scrollIntoView(javafx.scene.Node node) {
        javafx.scene.Node content = formScroll.getContent();
        if (content == null || formScroll.getViewportBounds() == null) {
            return;
        }
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewHeight = formScroll.getViewportBounds().getHeight();
        double scrollable = contentHeight - viewHeight;
        if (scrollable <= 0) {
            return; // 全放得下，本来就看得见
        }
        double top = content.sceneToLocal(node.localToScene(node.getBoundsInLocal())).getMinY();
        double value = (top - 16) / scrollable;
        formScroll.setVvalue(Math.max(0, Math.min(1, value)));
    }

    private HBox formRow(String label, javafx.scene.Node field) {
        return formRow(UiUtils.label(label, "form-label"), field);
    }

    private HBox formRow(Label l, javafx.scene.Node field) {
        l.setMinWidth(78);
        l.setPrefWidth(78);
        l.setAlignment(Pos.CENTER_RIGHT);
        HBox row = UiUtils.row(12, l, field);
        if (field instanceof javafx.scene.layout.Region r) {
            HBox.setHgrow(r, Priority.ALWAYS);
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return row;
    }

    private TextField growing(TextField f) {
        HBox.setHgrow(f, Priority.ALWAYS);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    private VBox indented(javafx.scene.Node node) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(-6, 0, 0, 90));
        return box;
    }

    private javafx.scene.layout.Region separator() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(1);
        r.setMaxHeight(1);
        r.setStyle("-fx-background-color:-sx-chrome-2;");
        return r;
    }

    private HBox buildBanner() {
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(0, 20, 0, 20));
        banner.getChildren().addAll(bannerText);
        banner.setVisible(false);
        banner.setManaged(false);
        return banner;
    }

    private HBox buildFoot() {
        Button test = UiUtils.toolButton("测试连接", null);
        Button cancel = UiUtils.toolButton("取消", null);
        Button save = UiUtils.toolButton("保存", null, "primary");

        test.setOnAction(e -> testConnection(test));
        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> save());

        HBox foot = UiUtils.row(9, test, UiUtils.hSpacer(), cancel, save);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    // ------------------------------------------------------------------ 行为

    private void onTypeChanged(DbType type) {
        if (type == null) {
            return;
        }
        boolean fileBased = type.isFileBased();
        serverRows.setVisible(!fileBased);
        serverRows.setManaged(!fileBased);
        fileRow.getParent().setVisible(fileBased);
        fileRow.getParent().setManaged(fileBased);
        if (!fileBased && (portField.getText().isBlank() || isDefaultPortOfAnyType(portField.getText()))) {
            portField.setText(String.valueOf(type.defaultPort()));
        }
        charsetBox.getParent().setVisible(type == DbType.MYSQL);
        charsetBox.getParent().setManaged(type == DbType.MYSQL);

        // 时区是给「服务端怎么解释这个时间戳」用的。Redis 没有时间类型，
        // 存进去什么就是什么，摆一个时区选择框只会让人以为它有用
        boolean hasTimezone = type != DbType.REDIS && type != DbType.MONGODB;
        timezoneBox.getParent().setVisible(hasTimezone);
        timezoneBox.getParent().setManaged(hasTimezone);

        databaseLabel.setText(type.databaseLabel());
        typeHint.setText(hintFor(type));
    }

    /**
     * 选中这一类之后该说的话。
     *
     * <p>两件事必须说：<b>「库」那一格到底填什么</b>（Oracle 填的是服务名，
     * 填成模式名会连不上，而报错信息看着像网络问题），
     * 以及<b>这一家的精度有没有验证过</b>——本项目给数据库放行的门槛是
     * 跑通精度一致性测试，没跑过的就得说没跑过。
     */
    private static String hintFor(DbType type) {
        switch (type) {
            case ORACLE:
                return "默认按服务名连接。要按 SID 连就在上面写 SID:ORCL；"
                        + "整段 (DESCRIPTION=...) 描述符也可以直接粘进来。"
                        + "方言和类型映射已写好，但尚未在真实 Oracle 上跑过精度一致性测试，"
                        + "首次连上真库时建议先核对一下大数值列。";
            case DM:
                return "达梦按 Oracle 兼容语法接入，「模式」这一格填登录后要切到的模式，"
                        + "留空则用该用户的默认模式。"
                        + "同样尚未在真实达梦实例上跑过精度一致性测试。";
            case KINGBASE:
                return "人大金仓 KingBase ES V8，基于 PostgreSQL，方言整套沿用 PG。"
                        + "尚未在真实实例上跑过精度一致性测试，首次连上真库时"
                        + "建议先核对一下大数值列。";
            case OCEANBASE:
                return "按 MySQL 兼容模式接入——那是默认模式。"
                        + "租户如果建成 Oracle 模式，这里生成的语句会对不上。"
                        + "租户信息拼在用户名里：用户@租户#集群。"
                        + "同样尚未在真实实例上验证。";
            case GAUSSDB:
                return "GaussDB / openGauss，PostgreSQL 血统，方言沿用 PG。"
                        + "用的是 openGauss 官方驱动的 -og 变体（类名 org.opengauss.Driver），"
                        + "不会和 PostgreSQL 驱动抢同一个类名。同样尚未在真实实例上验证。";
            case MONGODB:
                return "文档库，不走 JDBC，用官方驱动接入。"
                        + "「主机」那一格可以直接粘一整条 mongodb:// 连接串——"
                        + "副本集、TLS、authSource 这些只能靠连接串表达。"
                        + "注意用户是建在某个库里的，本工具默认拿 admin 库认证，"
                        + "账号建在别处时请用连接串并带 ?authSource=库名。"
                        + "集合没有固定结构，网格里的列是抽样文档凑出来的。";
            case REDIS:
                return "键值库，不走 JDBC，协议（RESP）由本工具自己实现。"
                        + "「用户名」只有 Redis 6 起的 ACL 用得上，用全局口令的话留空。"
                        + "键可以直接在界面上增删改；工具条上还有频道监听（发布/订阅）、"
                        + "队列监听（list 与 stream，只看不取）和分布式锁视图。"
                        + "任意命令可以在 SQL 标签页里发。";
            default:
                return "经 JDBC 驱动连接，读取路径由 CellReader 统一把关";
        }
    }

    private boolean isDefaultPortOfAnyType(String text) {
        for (DbType t : DbType.values()) {
            if (String.valueOf(t.defaultPort()).equals(text.trim())) {
                return true;
            }
        }
        return false;
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 SQLite 数据库文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("数据库文件", "*.db", "*.sqlite", "*.sqlite3", "*.mv.db"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        File file = FileDialogs.open(stage, context.uiState(), chooser);
        if (file != null) {
            fileField.setText(file.getAbsolutePath());
            if (nameField.getText().isBlank()) {
                nameField.setText(file.getName());
            }
        }
    }

    private void loadConfig() {
        nameField.setText(nz(config.name()));
        typeBox.setValue(config.type());
        hostField.setText(nz(config.host()));
        portField.setText(String.valueOf(config.port() > 0 ? config.port() : config.type().defaultPort()));
        userField.setText(nz(config.user()));
        databaseField.setText(nz(config.database()));
        fileField.setText(nz(config.filePath()));
        timezoneBox.setValue(config.timezone() == null || config.timezone().isBlank()
                ? "Asia/Shanghai" : config.timezone());
        charsetBox.setValue(config.charset() == null || config.charset().isBlank()
                ? "utf8mb4" : config.charset());
        savePasswordBox.setSelected(config.savePassword());
        readOnlyBox.setSelected(config.readOnly());
        colorBox.setValue(ConnectionColor.of(config.color()));
        groupBox.setValue(nz(config.group()));
        loadSavedPassword();

        java.util.Map<String, String> extras = config.extraProperties();
        boolean ssh = "true".equalsIgnoreCase(extras.getOrDefault("ssh.enabled", "false"));
        sshBox.setSelected(ssh);
        sshHost.setText(extras.getOrDefault("ssh.host", ""));
        sshPort.setText(extras.getOrDefault("ssh.port", "22"));
        sshUser.setText(extras.getOrDefault("ssh.user", ""));
        sshPassword.setText(extras.getOrDefault("ssh.password", ""));
        sshKeyPath.setText(extras.getOrDefault("ssh.keyPath", ""));
        sshAcceptHost.setSelected(
                "true".equalsIgnoreCase(extras.getOrDefault("ssh.acceptUnknownHost", "false")));
        sshBox.fireEvent(new javafx.event.ActionEvent());
        onTypeChanged(config.type());
    }

    /**
     * 把已保存的口令填回密码框。
     *
     * <h2>为什么原来是空的</h2>
     * 注册表交出来的 {@code ConnectionConfig} 口令字段<b>恒为 null</b>——那是有意的，
     * 口令不跟着配置对象在界面各处流转，只在建连接那一刻解密。
     * 但这个对话框是唯一一个「用户就是来看和改口令的」地方，
     * 空着的密码框说不清到底是「没存过口令」还是「存了但不给你看」。
     *
     * <p>而且它会误导人去重新输一遍：不输的话，{@code collect} 那边判空之后
     * 不动已存的口令——功能上没问题，但用户不知道，只能凭猜。
     *
     * <p>解密是本机 DPAPI，不走网络，所以同步做没有问题。
     * 解不开（换了机器或换了 Windows 用户）就留空，凭据存储那行提示会说明情况。
     */
    private void loadSavedPassword() {
        if (isNew || !config.savePassword() || config.id() == null) {
            return;
        }
        try {
            String plain = context.registry().resolvePassword(config).password();
            if (plain != null && !plain.isEmpty()) {
                passwordField.setText(plain);
            }
        } catch (RuntimeException e) {
            // 解不开就空着。为了回显失败而弹一个错误框，是把主次弄反了——
            // 用户还可以自己重输一个
            passwordField.setText("");
        }
    }

    private ConnectionConfig collect() {
        ConnectionConfig c = config.copy();
        c.setName(nameField.getText().isBlank() ? "未命名连接" : nameField.getText().trim());
        c.setType(typeBox.getValue());
        c.setHost(hostField.getText().trim());
        try {
            c.setPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            c.setPort(c.type().defaultPort());
        }
        c.setUser(userField.getText().trim());
        c.setDatabase(databaseField.getText().trim());
        c.setFilePath(fileField.getText().trim());
        c.setTimezone(nz(timezoneBox.getValue()));
        c.setCharset(nz(charsetBox.getValue()));
        c.setSavePassword(savePasswordBox.isSelected());
        c.setReadOnly(readOnlyBox.isSelected());
        c.setColor(colorBox.getValue() == null ? "" : colorBox.getValue().hex());
        // 可编辑下拉：用户敲了字但没敲回车时 getValue() 还是旧的，
        // 真正在输入框里的那串在 editor 上。取错就会把刚敲的分组名丢掉
        c.setGroup(groupBox.isEditable() && groupBox.getEditor() != null
                ? groupBox.getEditor().getText() : groupBox.getValue());

        c.extraProperties().putAll(com.plainly.driver.jdbc.SshTunnel.toExtras(
                sshBox.isSelected(), sshHost.getText().trim(),
                parseInt(sshPort.getText(), 22), sshUser.getText().trim(),
                sshPassword.getText(), sshKeyPath.getText().trim(),
                sshAcceptHost.isSelected()));
        /*
         * 口令<b>无条件</b>取框里的内容，包括空串。
         *
         * 原来这里判了非空才设，是因为框里从来是空的（见 loadSavedPassword），
         * 空就只能理解成「没改」。现在框里回显了已存的口令，语义就变了：
         * 框里是什么，口令就是什么——清空它就是「不要口令了」。
         *
         * 注册表那边据此区分：null 表示「这次不碰口令」（改分组、改颜色那些路径），
         * 空串表示「明确要清掉」。
         */
        c.setPassword(passwordField.getText());
        return c;
    }

    private void testConnection(Button trigger) {
        ConnectionConfig probe = collect();
        if (probe.password() == null && probe.id() != null) {
            // 编辑既有连接、又没重输密码时，用已保存的那份去测
            probe = context.registry().resolvePassword(probe);
        }
        trigger.setDisable(true);
        showBanner(false, "正在连接…", null);

        ConnectionConfig finalProbe = probe;
        context.queryService().submit(() -> Connections.test(finalProbe))
                .whenComplete((info, error) -> Platform.runLater(() -> {
                    trigger.setDisable(false);
                    if (error != null) {
                        showBanner(true, UiUtils.rootMessage(error), error);
                    } else {
                        showBanner(false, "连接成功 · " + info, null);
                    }
                }));
    }

    /**
     * 对话框顶上那条结果横幅。
     *
     * <h2>长报错只放摘要，全文交给详情框</h2>
     * 数据库连不上时的报错常常很长：openGauss、Oracle 的驱动会把主机、端口、
     * SSL 协商、认证方式一路拼进去，几百个字符。整段塞进这条横幅，
     * 要么把对话框撑得没法看，要么被裁掉——而<b>最要紧的那半句
     * （到底是连不上、还是密码不对）往往在后半段</b>。
     *
     * <p>所以这里只显示第一段，旁边给一个「详情」按钮，打开可复制、可拉大的错误框。
     */
    private void showBanner(boolean isError, String message, Throwable error) {
        banner.setVisible(true);
        banner.setManaged(true);
        banner.getStyleClass().setAll(isError ? "banner-error" : "banner-ok");

        String summary = summarize(message);
        bannerText.setText(summary);
        bannerText.setWrapText(true);

        banner.getChildren().setAll(
                isError ? Icons.warn("#a0402a", 14) : Icons.check("#4d7a4d", 14),
                bannerText);

        // 摘要之外还有内容时才给按钮——没被截断的短报错，再点一下只会看到同一句话
        if (error != null && !summary.equals(message)) {
            Button detail = UiUtils.toolButton("详情", null);
            detail.setOnAction(e -> UiUtils.showError(stage, "连接失败", error));
            banner.getChildren().addAll(UiUtils.hSpacer(), detail);
        }
    }

    /**
     * 取报错的第一段，并限制长度。
     *
     * <p>驱动喜欢把整个连接串、SSL 参数、重试过程都拼进一条消息里。
     * 第一段通常就是结论（{@code Connection refused}、
     * {@code password authentication failed}），后面是佐证。
     */
    private static String summarize(String message) {
        if (message == null) {
            return "(无详细信息)";
        }
        String first = message.split(String.valueOf((char) 10))[0].trim();
        if (first.isEmpty()) {
            first = message.trim();
        }
        return first.length() <= 160 ? first : first.substring(0, 160) + "…";
    }

    private void save() {
        ConnectionConfig toSave = collect();
        if (toSave.type().isFileBased() && toSave.filePath().isBlank()) {
            showBanner(true, "请选择 SQLite 数据库文件", null);
            return;
        }
        if (!toSave.type().isFileBased() && toSave.host().isBlank()) {
            showBanner(true, "请填写主机地址", null);
            return;
        }
        try {
            saved = context.registry().save(toSave);
            stage.close();
        } catch (RuntimeException e) {
            UiUtils.showError(stage, "保存连接失败", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
