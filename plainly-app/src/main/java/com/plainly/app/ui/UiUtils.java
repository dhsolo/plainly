package com.plainly.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** 界面构造的小工具。 */
public final class UiUtils {

    private UiUtils() {
    }

    public static Button toolButton(String text, Node graphic, String... extraClasses) {
        Button b = new Button(text);
        if (graphic != null) {
            b.setGraphic(graphic);
            b.setGraphicTextGap(5);
        }
        b.getStyleClass().add("tool-button");
        b.getStyleClass().addAll(extraClasses);
        noEllipsis(b);
        return b;
    }

    /**
     * 下拉式工具按钮，外观和 {@link #toolButton} 一致。
     *
     * <p>工具条上塞十几个按钮，窗口一窄就全被截成「...」。把不那么高频的几组收进
     * 带名字的下拉里，比让用户对着一排「...」猜要好——名字本身就说明了里面是什么，
     * 而且位置固定，不会随窗口宽度跳来跳去。
     */
    public static MenuButton toolMenu(String text, Node graphic, MenuItem... items) {
        MenuButton m = new MenuButton(text);
        if (graphic != null) {
            m.setGraphic(graphic);
            m.setGraphicTextGap(5);
        }
        m.getStyleClass().add("tool-button");
        m.getItems().addAll(items);
        noEllipsis(m);
        return m;
    }

    public static MenuItem menuItem(String text, Node graphic, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (graphic != null) {
            item.setGraphic(graphic);
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    /**
     * 别把按钮文字截成「...」。
     *
     * <p>JavaFX 在 HBox 放不下时会平摊着压缩每个孩子，按钮于是一起变成「...」——
     * 三个不同的按钮显示成同一个样子，比工具条溢出糟糕得多：溢出至少还看得见其中几个，
     * 截断是全都认不出来。锁死最小宽度之后，压缩压力会转到真正可伸缩的东西上
     * （搜索框、弹性空白），那才是该让步的一方。
     */
    private static void noEllipsis(Control control) {
        control.setMinWidth(Region.USE_PREF_SIZE);
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region vSpacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region vSeparator() {
        Region r = new Region();
        r.setMinWidth(1);
        r.setMaxWidth(1);
        r.setMinHeight(18);
        r.setStyle("-fx-background-color: -sx-border;");
        return r;
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox column(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    /**
     * 给窗口挂上应用图标。
     *
     * <p>不挂就是 JavaFX 自带的那个默认图标：任务栏、标题栏、Alt-Tab 里全都露馅，
     * 而且每个对话框都得各挂各的——Stage 不会从 owner 继承图标。
     */
    public static void brand(Stage stage) {
        stage.getIcons().setAll(Icons.appIcons());
        // 主题也在这里挂：每个对话框都会经过 brand()，挂钩放在这儿，
        // 新开的窗口自然就带上当前主题，不必在二十个对话框里各写一遍。
        // 用 follow 而不是直接挂类——这时候 stage 多半还没有 scene
        Theme.follow(stage);
        // 全局 Ctrl+C 也挂在这儿，理由同上：每个窗口都经过 brand()。
        // 场景是后设的，所以等它出现再挂
        stage.sceneProperty().addListener((o, was, is) -> {
            if (is != null) {
                CopySelection.install(is);
            }
        });
    }

    /**
     * 给 Alert 套上本应用的皮。
     *
     * <p>Alert 用的是独立的 {@code DialogPane}，样式表得单独挂——不挂就一直是
     * JavaFX 的系统默认外观，和整套界面完全脱节。
     *
     * <p>头部整个自己接管，而不是 {@code setGraphic} 换图标：modena 用
     * {@code .alert.error.dialog-pane { -fx-graphic: url("dialog-error.png") }}
     * 指着一张 PNG，样式表的优先级高于代码里设的值，程序设进去的图标会被刷回去。
     * 一旦 {@code setHeader} 给了自己的节点，那条规则连同它的图标就都不参与了。
     */
    /**
     * 给对话框套上本应用的皮。
     *
     * <p>参数是 {@code Dialog<?>} 而不是 {@code Alert}：输入框对话框
     * （{@code TextInputDialog}）不是 Alert，但该长成一个样子。
     */
    private static void dress(javafx.scene.control.Dialog<?> alert, Node icon) {
        DialogPane pane = alert.getDialogPane();
        pane.getStylesheets().add(
                UiUtils.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        pane.getStyleClass().add("plainly-alert");
        // Alert 的场景根节点就是这个 DialogPane，主题的类得挂在它自己身上
        Theme.applyToDialog(pane);

        String headerText = alert.getHeaderText();
        alert.setHeaderText(null);
        Label title = label(headerText == null ? "" : headerText, "alert-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox header = row(11, icon, title);
        header.getStyleClass().add("alert-head");
        pane.setHeader(header);

        Stage stage = (Stage) pane.getScene().getWindow();
        /*
         * 尊重调用方的决定，而不是一律钉死成不可调整。
         *
         * 原来这里写的是 setResizable(false)，而它跑在调用方设置之后——
         * 于是错误框里那句 setResizable(true) 被<b>无声地覆盖掉</b>了，
         * 代码看着写了、实际没生效。
         *
         * 提示框和确认框的内容长度可控，固定大小更整齐（默认就是 false）；
         * 错误框不行——报错长度没有上限，不让人拉大就只能滚动着看。
         */
        stage.setResizable(alert.isResizable());
        brand(stage);
    }

    /**
     * 错误提示。
     *
     * <p>正文用可选中的 TextArea 而不是普通 Label：数据库报错常常又长又关键，
     * 用户需要能复制出来搜索，不能只让他看着。
     */
    public static void showError(Window owner, String header, Throwable error) {
        // 堆栈写进日志。
        //
        // 界面上只显示根因那一句——那是给用户看的，堆栈对他没有意义。但对排查有：
        // 这条路吞掉堆栈之后，凡是被 whenComplete 接住的异常都<b>只存在于那个对话框里</b>，
        // 关掉就没了。曾经有一个 ConcurrentModificationException 就是这样：
        // 用户看得见，日志里一个字都没有，查了好几轮才靠读代码定位到。
        if (error != null) {
            System.err.println("[" + java.time.LocalDateTime.now().withNano(0) + "] " + header);
            error.printStackTrace();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("出错了");
        alert.setHeaderText(header);

        String detail = error == null ? "(无详细信息)" : rootMessage(error);
        int columns = 64;

        TextArea area = new TextArea(detail);
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("alert-detail");
        area.setPrefColumnCount(columns);
        area.setPrefRowCount(wrappedRows(detail, columns));

        // 让用户能把整段拿走。数据库的报错常常要贴到搜索框或者发给别人，
        // 而在只读文本框里先全选再复制，多一步也多一次出错的机会
        Button copy = toolButton("复制全部", null);
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content =
                    new javafx.scene.input.ClipboardContent();
            content.putString(detail);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText("已复制");
        });
        HBox tools = row(8, hSpacer(), copy);
        tools.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox content = column(6, area, tools);
        javafx.scene.layout.VBox.setVgrow(area, javafx.scene.layout.Priority.ALWAYS);

        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(620);
        // 报错长度没有上限，窗口就不能是固定的。JavaFX 的 Alert 默认不可调整大小，
        // 遇到一条特别长的报错时，用户除了滚动没有别的办法
        alert.setResizable(true);
        dress(alert, Icons.warn("#a0402a", 22));
        alert.showAndWait();
    }

    public static void showInfo(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("提示");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setPrefWidth(480);
        dress(alert, Icons.check(Icons.ACCENT, 22));
        alert.showAndWait();
    }

    /**
     * 问用户要一行文字。取消或者留空返回 null。
     *
     * <p>和 {@link #confirm} 相反，这里<b>不</b>把默认按钮改成取消：
     * 输入框里敲回车就是「填好了」，这是所有人对输入框的预期，
     * 而这个框本身不做任何不可逆的事——真正不可逆的那一步由调用方另外确认。
     */
    public static String prompt(Window owner, String header, String message, String initial) {
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(initial == null ? "" : initial);
        dialog.initOwner(owner);
        dialog.setTitle(header);
        dialog.setHeaderText(header);
        dialog.setContentText(message);
        dialog.getDialogPane().setPrefWidth(480);
        dress(dialog, Icons.file(Icons.ACCENT, 22));

        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (ok != null) {
            ok.setText("确定");
            ok.getStyleClass().add("alert-primary");
        }
        if (cancel != null) {
            cancel.setText("取消");
        }
        return dialog.showAndWait().map(String::trim).filter(t -> !t.isEmpty()).orElse(null);
    }

    public static boolean confirm(Window owner, String header, String message) {
        return confirm(owner, header, new Label(message) {
            {
                setWrapText(true);
            }
        }, "确定");
    }

    /**
     * 确认框，正文由调用方给。
     *
     * <p>存在的理由：有些确认要摆出一张清单（哪几条语句会作用于整张表），
     * 还要带一个「不再提示」的勾选框——一行 {@code contentText} 装不下这些。
     *
     * @param okText 确认按钮上的字。写「确定」是最弱的说明；写「仍然执行」，
     *               用户不必回头再读一遍标题就知道自己按下去会发生什么
     */
    public static boolean confirm(Window owner, String header, Node content, String okText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("确认");
        alert.setHeaderText(header);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);
        dress(alert, Icons.warn("#a8781a", 22));

        // 默认按钮改成「取消」：确认框大多出现在不可逆操作前，
        // 让手滑敲回车的后果是「什么都没发生」而不是「已经删了」
        Button ok = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
        Button cancel = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (ok != null) {
            ok.setText(okText);
            ok.setDefaultButton(false);
            ok.getStyleClass().add("alert-primary");
        }
        if (cancel != null) {
            cancel.setText("取消");
            cancel.setDefaultButton(true);
        }

        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /**
     * 层层 cause 里真正有用的那几句话。
     *
     * <p>做三件事：
     *
     * <ul>
     *   <li>剥掉 {@code CompletionException} 这类搬运工。它的 {@code getMessage()} 就是内层异常的
     *       {@code toString()}，于是「com.plainly.driver.DbException:」这样的类名会原样进到用户眼里；</li>
     *   <li>去掉消息开头的异常类名——那是给日志看的，不是给人看的；</li>
     *   <li>只留具体的那句。内层的话被外层说过就不再重复，最多再补一条真正不同的，
     *       同一句错误连说三遍，用户还得自己分辨这是三个问题还是一个。</li>
     * </ul>
     */
    /**
     * 这段文字在给定列宽下会占多少行。
     *
     * <h2>为什么不能直接数换行符</h2>
     * 文本框开了自动折行，一条很长的单行会被折成十几个视觉行。
     * 按换行符数出来的是 1，于是框子只有三行高，长报错被压在里面——
     * 用户看到的是开头半句加一条滚动条，而最要紧的信息（比如
     * 「connection refused」还是「password authentication failed」）
     * 往往在后半段。
     *
     * <p>openGauss、Oracle 这些驱动的报错动辄几百字符，这一条在它们身上最明显。
     *
     * <p>估算按字符数除以列宽，中文字符宽度算两倍——不精确，但方向是对的：
     * 宁可框子大一点，也不要让人看不到内容。上限 24 行，再长就交给滚动条，
     * 那时候屏幕也放不下了。
     */
    public static int wrappedRows(String text, int columns) {
        if (text == null || text.isBlank()) {
            return 3;
        }
        int rows = 0;
        for (String line : text.split("\n", -1)) {
            int width = 0;
            for (int i = 0; i < line.length(); i++) {
                // 汉字、全角标点在等宽字体里占两个字符的位置
                width += line.charAt(i) > 0x2E80 ? 2 : 1;
            }
            rows += Math.max(1, (width + columns - 1) / columns);
        }
        return Math.min(24, Math.max(3, rows + 1));
    }

    public static String rootMessage(Throwable t) {
        if (t == null) {
            return "(无详细信息)";
        }
        List<String> parts = new ArrayList<>();
        Throwable cur = unwrap(t);
        int depth = 0;
        while (cur != null && depth < 5 && parts.size() < 2) {
            String message = stripTypeName(cur.getMessage());
            if (message != null && !message.isBlank()
                    && parts.stream().noneMatch(seen -> seen.contains(message))) {
                parts.add(message);
            }
            cur = cur.getCause();
            depth++;
        }
        return parts.isEmpty() ? stripTypeName(t.toString()) : String.join("\n\n起因：", parts);
    }

    /** 只负责转交、自己不带信息的那几层包装。 */
    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur.getCause() != null && depth < 5) {
            boolean carrier = cur instanceof CompletionException
                    || cur instanceof ExecutionException
                    || cur instanceof InvocationTargetException
                    || (cur.getMessage() != null && cur.getMessage().equals(cur.getCause().toString()));
            if (!carrier) {
                break;
            }
            cur = cur.getCause();
            depth++;
        }
        return cur;
    }

    /** 去掉「com.foo.BarException: 」这样的类名前缀。 */
    private static String stripTypeName(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceFirst(
                "^(?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error|Throwable): ", "").trim();
    }

    /** 数字千分位，仅用于展示计数，绝不用于数据值。 */
    public static String groupDigits(long value) {
        return String.format("%,d", value);
    }

    /**
     * 概数：给一眼看数量级用的短写法。
     *
     * <p>侧栏只有两百多像素宽，{@code 12,834,591} 这样的完整数字会把表名挤出去。
     * 而在这个位置上，用户要的本来就不是准确值——是「这张表是空的、几百行、
     * 还是上千万行」，那决定了他敢不敢直接点开、要不要先加个条件。
     *
     * <p>一万以下照常写完整：那个范围里每一位都有意义，也不占地方。
     */
    public static String approxCount(long value) {
        if (value < 0) {
            return "";
        }
        if (value < 10000) {
            return groupDigits(value);
        }
        if (value < 100000000L) {
            return scaled(value / 10000.0) + "万";
        }
        return scaled(value / 100000000.0) + "亿";
    }

    /**
     * 一位小数，但只在它还带信息的时候留。
     *
     * <p>1.2 万和 1.3 万是有区别的；1283.5 万和 1284 万没有——量级一样，
     * 而那个小数点在两百像素宽的侧栏里要占掉一个字的位置。
     * 末尾的 .0 一律去掉：它一个信息也不带。
     */
    private static String scaled(double value) {
        if (value >= 100) {
            return String.valueOf(Math.round(value));
        }
        String text = String.format("%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
