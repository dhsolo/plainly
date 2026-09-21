package com.plainly.app.view;

import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * 键盘和鼠标能做的事，一页列全。
 *
 * <h2>为什么需要这一页</h2>
 * 这个工具里有一批只能靠按键或右键触发的操作：Shift+空格选整行、Ctrl+V 粘进网格、
 * 点列头排序、Ctrl+Enter 只跑光标那一条。它们都<b>没有对应的按钮</b>——
 * 加按钮会把工具条挤爆，而不加按钮就意味着：除非有人告诉你，否则你永远不会知道它存在。
 *
 * <p>菜单项里顺手标注快捷键只能覆盖菜单里有的那几项。剩下的（网格上的、编辑器里的）
 * 无处可标，只能有这么一页。
 *
 * <h2>为什么不做成可配置</h2>
 * 让用户改键位，就要处理冲突检测、导入导出、以及「改过之后这页说的还算不算数」。
 * 而这套键位本身没有争议（F5 执行、Ctrl+C 复制都是通行做法）。
 * 先把「有什么」说清楚，比先做「能改成什么」有用得多。
 */
public class ShortcutsDialog {

    /** 一条：按什么键，做什么事。 */
    private record Entry(String keys, String what) {
    }

    /** 一组。 */
    private record Group(String title, List<Entry> entries) {
    }

    /**
     * 全部快捷键。
     *
     * <p>写死在这里，而不是从各处的绑定反射出来：反射出来的是 {@code KeyCode.SPACE}，
     * 而用户要看的是「Shift+空格 选中整行」——后半句代码里根本没有。
     * 代价是改了键位要记得改这里，所以每一处绑定旁边都指向本类。
     */
    private static final List<Group> GROUPS = List.of(
            new Group("全局", List.of(
                    new Entry("Ctrl + P", "定位对象：在侧栏里搜连接、库、表、列"),
                    new Entry("Ctrl + Shift + F", "在库中查找数据：跨表搜一个值"),
                    new Entry("F1", "打开这一页"))),

            new Group("SQL 编辑器", List.of(
                    new Entry("F5", "执行。选中了一段就只执行选中的那一段"),
                    new Entry("Ctrl + Enter", "只执行光标所在的那一条语句"),
                    new Entry("Ctrl + D", "把光标所在行复制到下一行。"
                            + "圈中了几行就整段复制这几行，光标跟到复制出来的那一行——"
                            + "连按几下就是连着往下复制几行"),
                    new Entry("Ctrl + 空格", "唤出补全"),
                    new Entry("Tab / Enter", "采用当前候选（补全打开时）"),
                    new Entry("↑ / ↓", "在候选之间移动（补全打开时）"),
                    new Entry("Esc", "收起补全"))),

            new Group("数据网格", List.of(
                    new Entry("双击单元格", "就地改值。改完按「保存」才写库"),
                    new Entry("Shift + 空格", "把当前格所在的整行选中"),
                    new Entry("Ctrl + C", "复制选中的内容，可选复制成 INSERT 语句"),
                    new Entry("Ctrl + V", "从剪贴板粘贴，只进编辑缓冲，按「保存」才落库"),
                    new Entry("点列头", "按这一列排序：升序 → 降序 → 不排。排序下推给数据库，"
                            + "不是只排当前这一页"))),

            new Group("到处都能用", List.of(
                    new Entry("Ctrl + C", "复制当前选中的内容——左侧树、各个对话框里的表格"
                            + "和列表都认。数据网格和文本框有自己的复制规则，"
                            + "它们优先"))),

            new Group("标签页与左侧树", List.of(
                    new Entry("右键标签页", "保存、收藏、关闭当前 / 左侧 / 右侧 / 全部"),
                    new Entry("双击连接", "连上并展开"),
                    new Entry("双击表", "打开数据"),
                    new Entry("右键库", "新建表 / 视图 / 触发器、导入向导、备份"),
                    new Entry("右键表", "复制表、生成测试数据、清空、删除"))));

    private Stage stage;

    public void show(Window owner) {
        stage = new Stage();
        UiUtils.brand(stage);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("快捷键");

        ScrollPane scroll = new ScrollPane(buildBody());
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("dialog-scroll");

        VBox root = new VBox(buildHead(), scroll, buildFoot());
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Scene scene = new Scene(root, 560, 640);
        scene.getStylesheets().add(
                getClass().getResource("/com/plainly/app/plainly.css").toExternalForm());
        // Esc 关掉：这一页是查完就走的，没必要非去够右下角那个按钮
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.show();
    }

    private HBox buildHead() {
        HBox head = UiUtils.row(8,
                Icons.file(Icons.ACCENT, 14),
                UiUtils.label("按 Esc 关闭", "hint"));
        head.getStyleClass().add("dialog-head");
        return head;
    }

    private VBox buildBody() {
        VBox box = UiUtils.column(0);
        for (Group group : GROUPS) {
            Label title = UiUtils.label(group.title(), "section-label");
            title.setPadding(new Insets(14, 0, 6, 0));
            box.getChildren().add(title);
            for (Entry entry : group.entries()) {
                box.getChildren().add(row(entry));
            }
        }
        box.setPadding(new Insets(4, 16, 16, 16));
        return box;
    }

    private HBox row(Entry entry) {
        Label keys = UiUtils.label(entry.keys(), "shortcut-keys");
        // 固定宽度让所有键位左对齐成一列：宽窄不一时眼睛要在两列之间来回找
        keys.setMinWidth(126);
        keys.setPrefWidth(126);

        Label what = UiUtils.label(entry.what(), "shortcut-what");
        what.setWrapText(true);
        HBox.setHgrow(what, Priority.ALWAYS);
        what.setMaxWidth(Double.MAX_VALUE);

        HBox row = UiUtils.row(12, keys, what);
        row.setPadding(new Insets(4, 0, 4, 0));
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return row;
    }

    private HBox buildFoot() {
        Button close = UiUtils.toolButton("关闭", null, "primary");
        close.setOnAction(e -> stage.close());
        HBox foot = UiUtils.row(8, UiUtils.hSpacer(), close);
        foot.getStyleClass().add("dialog-foot");
        return foot;
    }
}
