import com.plainly.app.ui.Icons;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.sql.SqlRisk;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 这次加的几样东西，画出来看一眼。
 *
 * <p>为什么必须真画：样式表出过一次事——一处写错让<b>整张</b> plainly.css 作废，
 * 界面悄悄退回 JavaFX 默认皮肤。那次是靠截图发现的，代码本身编译、运行都没问题。
 * 所以凡是动过 CSS 的东西，都得看像素，不能只看代码。
 *
 * <p>拍两张：影响面确认框（新样式类 risk-item / risk-sql），
 * 以及带分组目录和行数的连接树（新样式类 tree-row-folder / tree-folder）。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/NewFeaturePreview.java &lt;输出目录&gt;</pre>
 */
public class NewFeaturePreview extends Application {

    private static String out = ".";
    private Stage main;

    public static void main(String[] args) {
        if (args.length > 0) {
            out = args[0];
        }
        launch(NewFeaturePreview.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        main = stage;
        stage.setTitle("新功能预览");
        stage.setScene(new Scene(treePreview(), 300, 420));
        stage.getScene().getStylesheets().add(
                NewFeaturePreview.class.getResource("/com/plainly/app/plainly.css")
                        .toExternalForm());
        stage.show();

        at(0.8, () -> write(stage.getScene().snapshot(null), "tree-groups"));
        at(1.4, this::captureRiskDialog);
        at(1.6, this::showRiskDialog);
        at(3.4, Platform::exit);
    }

    // ------------------------------------------------------------ 连接树

    /** 一棵和真界面同构的树：分组目录 → 连接 → 库 → 分组 → 表（带行数）。 */
    private VBox treePreview() {
        TreeItem<Row> root = new TreeItem<>();

        TreeItem<Row> prod = folder("线上", 2);
        prod.getChildren().addAll(
                connection("订单库 生产", "#c0392b"),
                connection("会员库 生产", "#c0392b"));

        TreeItem<Row> test = folder("测试", 1);
        TreeItem<Row> conn = connection("本地 MySQL", "");
        TreeItem<Row> schema = row(new Row("shop", "tree-row-schema", "tree-schema", "默认"));
        TreeItem<Row> group = row(new Row("表", "tree-row-group", "tree-group", "128"));
        group.getChildren().addAll(
                table("orders", "1283万", "订单主表"),
                table("order_item", "4.2亿", "订单明细"),
                table("users", "9,860", "会员"),
                table("coupon", "312", ""),
                table("audit_log", "0", "审计"));
        group.setExpanded(true);
        schema.getChildren().add(group);
        schema.setExpanded(true);
        conn.getChildren().add(schema);
        conn.setExpanded(true);
        test.getChildren().add(conn);

        root.getChildren().addAll(prod, test);

        TreeView<Row> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new RowCell());
        VBox box = new VBox(UiUtils.label("连接", "section-label"), tree);
        box.getStyleClass().add("sidebar");
        VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    /** 一行要显示的东西：文字、整行样式类、文字样式类、右边那个小角标。 */
    private record Row(String text, String rowClass, String textClass, String badge) {
    }

    private TreeItem<Row> row(Row r) {
        return new TreeItem<>(r);
    }

    private TreeItem<Row> folder(String name, int count) {
        TreeItem<Row> item = row(new Row(name, "tree-row-folder", "tree-folder",
                count + " 个连接"));
        item.setExpanded(true);
        return item;
    }

    private TreeItem<Row> connection(String name, String color) {
        return row(new Row(name, "", "hint-strong", color.isEmpty() ? "" : "在线"));
    }

    private TreeItem<Row> table(String name, String rows, String comment) {
        return row(new Row(name, "", "", rows + (comment.isEmpty() ? "" : "  " + comment)));
    }

    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("tree-row-folder", "tree-row-schema", "tree-row-group");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (!item.rowClass().isEmpty()) {
                getStyleClass().add(item.rowClass());
            }
            Label name = new Label(item.text());
            if (!item.textClass().isEmpty()) {
                name.getStyleClass().add(item.textClass());
            }
            HBox box = UiUtils.row(6, Icons.table(Icons.MUTED, 12), name);
            if (!item.badge().isEmpty()) {
                box.getChildren().add(UiUtils.label(item.badge(), "tree-count"));
            }
            setText(null);
            setGraphic(box);
        }
    }

    // ------------------------------------------------------------ 确认框

    /** 和 SqlEditorPane.confirmWideReach 里搭的是同一个正文，样式类也一样。 */
    private void showRiskDialog() {
        List<SqlRisk.Risk> risks = SqlRisk.scan(List.of(
                "UPDATE orders SET status = 1",
                "SELECT 1",
                "DELETE FROM order_item",
                "DROP DATABASE shop_tmp"));
        System.out.println("认出 " + risks.size() + " 条：");
        risks.forEach(r -> System.out.println("    " + r.describe()));

        VBox body = UiUtils.column(8);
        body.getChildren().add(UiUtils.label("下面的语句不限定范围，执行之后没有撤销可按：", "hint"));
        for (SqlRisk.Risk risk : risks) {
            Label line = UiUtils.label(risk.describe(), "risk-item");
            line.setWrapText(true);
            body.getChildren().add(line);
            Label detail = UiUtils.label(risk.sql(), "risk-sql");
            detail.setWrapText(true);
            body.getChildren().add(detail);
        }
        body.getChildren().add(new CheckBox("本次运行不再提示"));

        UiUtils.confirm(main, risks.size() == 1 ? risks.get(0).kind().headline()
                : "有 " + risks.size() + " 条语句会一次性影响全部数据", body, "仍然执行");
    }

    private void captureRiskDialog() {
        at(0.9, () -> {
            Window dialog = null;
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w != main) {
                    dialog = w;
                }
            }
            if (dialog == null) {
                System.out.println("没找到弹窗");
                return;
            }
            write(dialog.getScene().snapshot(null), "risk-confirm");
            dialog.hide();
        });
    }

    // ------------------------------------------------------------ 工具

    private static void write(Image img, String name) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            File f = new File(out, name + ".png");
            ImageIO.write(buf, "png", f);
            System.out.println("→ " + f.getAbsolutePath() + "  " + w + "x" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    /** 动画回调里不能直接 showAndWait，再 runLater 一跳跳出这一轮 pulse。 */
    private void at(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> Platform.runLater(action));
        p.play();
    }
}
