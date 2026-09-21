import com.plainly.app.ui.CopySelection;
import com.plainly.app.ui.UiUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * 全局 Ctrl+C：该复制的复制得出来，<b>不该抢的一个也别抢</b>。
 *
 * <h2>第二条是重点</h2>
 * 这个功能最大的风险不是复制不出来，是把已有的复制行为顶掉：
 * 数据网格那套「按格式复制」、文本框那套「按选区复制」，
 * 如果被一个通用实现抢在前面，用户不会看到任何报错，
 * 只会发现复制出来的东西变了样——而且很难联想到是哪次改动造成的。
 *
 * <p>所以这里专门造一个「自己已经处理了 Ctrl+C 的控件」，
 * 确认全局那层<b>没有</b>插手。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/CopySelectionProbe.java
 * </pre>
 */
public class CopySelectionProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    private record Row(String name, String value) { }

    @Override
    public void start(Stage stage) {
        // 一、表格
        TableView<Row> table = new TableView<>(FXCollections.observableArrayList(
                new Row("甲", "1"), new Row("乙", "2"), new Row("丙", "3")));
        table.getColumns().addAll(List.of(
                col("名称", Row::name), col("值", Row::value)));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 二、列表，条目是领域对象，靠 cell factory 渲染
        ListView<Row> list = new ListView<>(FXCollections.observableArrayList(
                new Row("消息A", "内容A"), new Row("消息B", "内容B")));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " → " + item.value());
            }
        });

        // 三、树，同样是领域对象
        TreeItem<Row> root = new TreeItem<>(new Row("根", ""));
        TreeItem<Row> child = new TreeItem<>(new Row("子节点", "x"));
        root.getChildren().add(child);
        root.setExpanded(true);
        TreeView<Row> tree = new TreeView<>(root);
        tree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "【" + item.name() + "】");
            }
        });

        // 四、一个自己已经处理 Ctrl+C 的控件（模拟数据网格）
        TableView<Row> guarded = new TableView<>(FXCollections.observableArrayList(
                new Row("不该被抢", "z")));
        guarded.getColumns().add(col("名称", Row::name));
        guarded.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                put("我自己处理的");
                e.consume();
            }
        });

        TextField text = new TextField("文本框里的内容");

        VBox root2 = new VBox(8, table, list, tree, guarded, text);
        Scene scene = new Scene(root2, 640, 700);
        CopySelection.install(scene);
        stage.setScene(scene);
        stage.show();

        delay(700, () -> {
            run(scene, table, list, tree, guarded, text);
            System.out.print(LOG);
            System.out.println();
            System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
            Platform.exit();
        });
    }

    private void run(Scene scene, TableView<Row> table, ListView<Row> list,
                     TreeView<Row> tree, TableView<Row> guarded, TextField text) {
        /*
         * 断言落在 textOf() 上，不落在剪贴板上。
         *
         * 剪贴板是进程外的共享资源，读写随时可能被别的程序干扰——上一版就是这样：
         * 第一次读到了，后面连自己刚写进去的值都读回 null，
         * 于是四条检查全红，而逻辑其实是好的。拿一个不可靠的东西当断言的落点，
         * 得到的是时有时无的结果，比没有测试更浪费时间。
         */
        /*
         * 端到端只做一次，而且<b>这一条会偶发失败</b>。
         *
         * 剪贴板是进程外的共享资源：别的程序（输入法、剪贴板管理器、远程桌面）
         * 随时可能在这一瞬间占着它，读回来就是 null。实测同一份代码连跑两次，
         * 一次通过一次失败。
         *
         * 所以真正的判据在上面那些 textOf() 的检查上——那一半是确定的。
         * 这一条只是确认「链路是通的」，红了先重跑一次再说。
         */
        // 端到端只做一次：真的往剪贴板写一回。
        // requestFocus 不能省——处理器读的是 scene.getFocusOwner()，
        // 焦点没给它就取不到选区，于是什么也不写（上一版就漏了这一句）
        table.requestFocus();
        table.getSelectionModel().selectIndices(0);
        press(scene, table);
        String clip = Clipboard.getSystemClipboard().getString();
        LOG.append("端到端（剪贴板）→ ").append(show(clip)).append(NL);
        check("端到端：真的写进了剪贴板",
                clip != null && clip.contains("甲"), show(clip));

        table.getSelectionModel().clearSelection();
        table.getSelectionModel().selectIndices(0, 2);
        String got = CopySelection.textOf(table);
        LOG.append("表格 → ").append(show(got)).append(NL);
        check("表格按列取值、制表符分隔、多行",
                got != null && got.contains("甲") && got.contains("丙")
                        && got.contains(String.valueOf((char) 9)), show(got));
        check("没把没选中的那行也带上", got != null && !got.contains("乙"), show(got));

        list.getSelectionModel().select(0);
        got = CopySelection.textOf(list);
        LOG.append("列表 → ").append(show(got)).append(NL);
        check("列表取的是屏幕上那行字，不是 Row[name=...] 那种",
                got != null && got.contains("消息A → 内容A") && !got.contains("Row["), show(got));

        tree.getSelectionModel().select(1);
        got = CopySelection.textOf(tree);
        LOG.append("树 → ").append(show(got)).append(NL);
        check("树取的是屏幕上那行字",
                got != null && got.contains("【子节点】") && !got.contains("Row["), show(got));

        table.getSelectionModel().clearSelection();
        check("没有选中内容时返回 null（于是不会动剪贴板）",
                CopySelection.textOf(table) == null, show(CopySelection.textOf(table)));

        check("焦点不在这几种控件上时返回 null",
                CopySelection.textOf(text) == null, show(CopySelection.textOf(text)));

        /*
         * 「不抢已有行为」这一条只能看事件有没有走到场景那一层。
         *
         * 给受保护的控件挂一个过滤器（和数据网格一样），它消费掉事件；
         * 再看场景上的处理器有没有被触发。触发了就说明全局那层会顶掉别人。
         */
        boolean[] sceneSaw = {false};
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                sceneSaw[0] = true;
            }
        });
        guarded.getSelectionModel().select(0);
        press(scene, guarded);
        check("事件被控件自己消费后，不会再走到场景那一层（所以抢不走）",
                !sceneSaw[0], "场景那层仍然收到了事件");

        // 事件没人管的时候，场景那层要收得到
        sceneSaw[0] = false;
        press(scene, list);
        check("没人处理时，场景那一层收得到", sceneSaw[0], "场景那层没收到");

    }

    // ------------------------------------------------------------------ 辅助

    private static <T> TableColumn<Row, String> col(String title,
                                                    java.util.function.Function<Row, String> get) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setCellValueFactory(x -> new SimpleStringProperty(get.apply(x.getValue())));
        return c;
    }

    /** 直接往目标控件上发 Ctrl+C，让它按真实路径冒泡到场景。 */
    private static void press(Scene scene, javafx.scene.Node target) {
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                false, true, false, false);
        javafx.event.Event.fireEvent(target, e);
    }

    private static void put(String s) {
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    private static String show(String s) {
        return s == null ? "(null)"
                : "[" + s.replace(String.valueOf((char) 10), " ⏎ ")
                        .replace(String.valueOf((char) 9), " ⇥ ") + "]";
    }

    private static void delay(int millis, Runnable then) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            LOG.append("  ").append(what).append(NL);
        } else {
            fail++;
            LOG.append("× ").append(what).append("   实际：").append(detail).append(NL);
        }
    }

    public static void main(String[] args) {
        launch(CopySelectionProbe.class, args);
    }
}
