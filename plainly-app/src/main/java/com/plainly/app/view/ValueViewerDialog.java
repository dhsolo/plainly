package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.util.JsonText;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.TypeCategory;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 单元格的完整值。
 *
 * <p>网格一行就那么高，一段两千字的备注、一坨压成一行的 JSON、一个末尾多了个空格的编号，
 * 在格子里全都长得差不多。这个窗口的存在就是为了把「看不清」这件事解决掉。
 *
 * <h2>三个视图各自的边界</h2>
 * <ul>
 *   <li><b>原文</b>——数据库里存的是什么就是什么，可编辑，改完写回编辑缓冲，
 *       和在格子里手动改是同一条路径（仍然要按保存才落库）。</li>
 *   <li><b>格式化 JSON</b>——只是排版好看，<b>只读</b>。它不会改动任何数字的写法
 *       （见 {@link JsonText}），但也不该让人误以为「看了一眼就把库里的格式改了」。</li>
 *   <li><b>十六进制</b>——两个「看起来一样」的值对不上时，差别通常是尾随空格、
 *       不间断空格、BOM 或者 CRLF。只有摊成字节才看得见。</li>
 * </ul>
 */
public class ValueViewerDialog {

    /** 十六进制最多转储多少字节。再多，界面上也不是用来读的。 */
    private static final int HEX_LIMIT = 64 * 1024;

    private final ColumnMeta meta;
    private final String original;
    private final int rowNumber;
    private final boolean editable;

    private final TextArea area = new TextArea();
    private final Label info = UiUtils.label("", "hint");
    private final ToggleGroup views = new ToggleGroup();

    private Consumer<String> onApply = v -> { };
    private Stage stage;
    private boolean isNull;

    /**
     * @param rowNumber 页内第几行，从 1 起；只用于标题，不参与定位
     * @param editable  网格是否可编辑。不可编辑时这里也只读——
     *                  在一个改不了的结果集上摆个能打字的框，是在骗人
     */
    public ValueViewerDialog(ColumnMeta meta, String value, int rowNumber, boolean editable) {
        this.meta = meta;
        this.original = value;
        this.rowNumber = rowNumber;
        this.editable = editable;
        this.isNull = value == null;
    }

    /** 点「应用」时回调，参数是新值；{@code null} 表示置为 SQL NULL。 */
    public void setOnApply(Consumer<String> handler) {
        this.onApply = handler;
    }

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("单元格 · " + meta.label());

        area.getStyleClass().add("mono");
        area.setWrapText(true);
        area.setEditable(editable);
        VBox.setVgrow(area, Priority.ALWAYS);

        VBox root = new VBox(buildHead(), buildViewBar(), area, buildInfoBar(), buildFoot());
        Scene scene = new Scene(root, 760, 560);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);

        showRaw();
        stage.show();
    }

    private HBox buildHead() {
        String where = (meta.tableName() == null || meta.tableName().isBlank()
                ? "" : meta.tableName() + ".") + meta.name();
        HBox head = UiUtils.row(8,
                Icons.column(Icons.ACCENT, 14),
                UiUtils.label(where, "dialog-title"),
                UiUtils.label(meta.displayType() + " · 第 " + rowNumber + " 行"
                        + (meta.nullable() ? "" : " · NOT NULL"), "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private HBox buildViewBar() {
        ToggleButton raw = viewToggle("原文", true);
        raw.setOnAction(e -> showRaw());

        ToggleButton json = viewToggle("格式化 JSON", false);
        json.setOnAction(e -> showJson());

        ToggleButton hex = viewToggle("十六进制", false);
        hex.setOnAction(e -> showHex());

        Label note = UiUtils.label(
                editable ? "原文可编辑；另两个视图只是换个样子看，不改值" : "结果集只读，这里也只读",
                "hint");

        HBox bar = UiUtils.row(8, raw, json, hex, UiUtils.hSpacer(), note);
        bar.getStyleClass().add("grid-toolbar");
        return bar;
    }

    private ToggleButton viewToggle(String text, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(views);
        b.getStyleClass().add("tool-button");
        b.setSelected(selected);
        if (selected) {
            b.getStyleClass().add("primary");
        }
        b.selectedProperty().addListener((o, was, is) -> {
            b.getStyleClass().removeIf("primary"::equals);
            if (is) {
                b.getStyleClass().add("primary");
            }
        });
        // 不允许把三个都取消掉：那样就没有任何视图了
        b.setOnMouseClicked(e -> b.setSelected(true));
        return b;
    }

    private HBox buildInfoBar() {
        HBox bar = UiUtils.row(8, info);
        bar.getStyleClass().add("inspector-head");
        return bar;
    }

    private HBox buildFoot() {
        Button copy = UiUtils.toolButton("复制当前视图", null);
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Button setNull = UiUtils.toolButton("置为 NULL", null);
        setNull.setDisable(!editable || !meta.nullable());
        if (editable && !meta.nullable()) {
            setNull.setTooltip(new javafx.scene.control.Tooltip(
                    "这一列是 NOT NULL，置空会被数据库拒绝"));
        }
        setNull.setOnAction(e -> {
            onApply.accept(null);
            stage.close();
        });

        Button apply = UiUtils.toolButton("应用到单元格", Icons.check("#ffffff", 11), "primary");
        apply.setDisable(!editable);
        apply.setOnAction(e -> {
            if (!isRawView()) {
                // 在十六进制视图上点「应用」会把那一屏十六进制当成值写回去。
                // 与其防御性地猜他想干什么，不如直接把他送回原文视图
                showRaw();
                return;
            }
            onApply.accept(area.getText());
            stage.close();
        });

        Button close = UiUtils.toolButton("关闭", null);
        close.setOnAction(e -> stage.close());

        HBox foot = UiUtils.row(8, copy, setNull, UiUtils.hSpacer(), close, apply);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }

    private boolean isRawView() {
        ToggleButton selected = (ToggleButton) views.getSelectedToggle();
        return selected != null && "原文".equals(selected.getText());
    }

    // ------------------------------------------------------------------ 三个视图

    private void showRaw() {
        views.getToggles().stream()
                .filter(t -> "原文".equals(((ToggleButton) t).getText()))
                .findFirst().ifPresent(t -> t.setSelected(true));
        area.setEditable(editable);
        area.setText(original == null ? "" : original);
        info.setText(describe());
    }

    private void showJson() {
        area.setEditable(false);
        if (original == null) {
            area.setText("");
            info.setText("值是 NULL，没有可格式化的内容");
            return;
        }
        if (!JsonText.looksLikeJson(original)) {
            area.setText(original);
            info.setText("这个值不是以 { 或 [ 开头，按原文显示——没当成 JSON 硬排");
            return;
        }
        String pretty = JsonText.pretty(original);
        area.setText(pretty);
        info.setText(pretty.equals(original)
                ? describe() + " · 原本就是这个排版"
                : describe() + " · 已重新缩进；数字一律照抄原文，没有解析成浮点");
    }

    private void showHex() {
        area.setEditable(false);
        if (original == null) {
            area.setText("");
            info.setText("值是 NULL，没有字节");
            return;
        }
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
        area.setText(JsonText.hexDump(bytes, HEX_LIMIT));
        info.setText("按 UTF-8 编码 · " + bytes.length + " 字节"
                + (bytes.length > HEX_LIMIT ? "（只转储前 " + HEX_LIMIT + " 字节）" : ""));
    }

    /** 底部那行事实：多少字、多少字节，以及精确数值的位数形状。 */
    private String describe() {
        if (original == null) {
            isNull = true;
            return "SQL NULL —— 和空字符串是两回事";
        }
        isNull = false;
        int bytes = original.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        sb.append(original.length()).append(" 字符 · ").append(bytes).append(" 字节(UTF-8)");
        if (original.isEmpty()) {
            sb.append(" · 空字符串，不是 NULL");
        }
        if (!original.isEmpty() && !original.strip().equals(original)) {
            // 首尾空白是查不出来的老问题，专门点一句
            sb.append(" · 首尾有空白字符（切到十六进制能看清是哪个）");
        }
        if (meta.category() == TypeCategory.EXACT_NUMERIC
                || meta.category() == TypeCategory.INTEGER) {
            try {
                BigDecimal d = new BigDecimal(original.strip());
                sb.append(" · precision=").append(d.precision())
                        .append(", scale=").append(d.scale());
            } catch (NumberFormatException ignored) {
                // 不是合法数字就不显示形状，不必报错
            }
        }
        return sb.toString();
    }

    /** 打开时值是不是 NULL。留给调用方决定要不要提示。 */
    public boolean valueWasNull() {
        return isNull;
    }
}
