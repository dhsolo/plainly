import com.plainly.app.ui.Icons;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把左树的几种行按真实字号、真实缩进摆一遍，几套配色并排比。
 *
 * <h2>为什么单看图标不够</h2>
 * 一个图标好不好，在树里取决于三件事：和旁边的文字比是不是一样重、
 * 几种行的图标摆在一起能不能一眼分开、图标后面的文字有没有对齐。
 * 这三件事全都要把行摆在一起才看得出来——尤其是配色：单看一个图标说不上淡，
 * 十几行摞在一起才发现整列都糊在背景里。
 *
 * <p>深色底那一排同样重要：调色板给每个颜色都配了暗色主题的取值，
 * 而「亮色下刚好」的颜色到深色底上常常太暗。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/TreeRowPreview.java
 * </pre>
 */
public class TreeRowPreview extends Application {

    /**
     * 一套配色。
     *
     * @param groupFade 分组行图标的不透明度——分组是容器，该比它装的东西轻一点，
     *                  但不该轻到看不见
     */
    private record Scheme(String name, String schema, String table, String view,
                          String sequence, String folder, double groupFade) { }

    private static final Scheme CURRENT = new Scheme("现状",
            "#8a7a4a", "#7a8a9a", "#5a7a8a", "#5a7a8a", "#6a6a62", 1.0);

    /** 一：只把饱和度提上来，色相不动，改动最小。 */
    private static final Scheme WARM = new Scheme("一 · 提饱和度",
            "#a8781a", "#2a5d8f", "#5a7a8a", "#4d7a4d", "#6a6a62", 0.55);

    /**
     * 定稿：每一类一个色相，靠颜色分类型，不用去认形状。
     *
     * <p>这几个值要和 {@code ConnectionTreePane} 里那几个 COLOR_* 常量对上——
     * 改了那边记得回来改这边，否则这个工具画出来的就不是线上的样子了。
     */
    private static final Scheme HUED = new Scheme("定稿 · 按类型分色",
            "#a8781a", "#2a5d8f", "#8a3d6b", "#4d7a4d", "#6a6a62", 0.55);

    /** 三：库用主题色，最抢眼；表还是蓝。 */
    private static final Scheme ACCENTED = new Scheme("三 · 库用主题青",
            "#0f6f70", "#2a5d8f", "#8a3d6b", "#4d7a4d", "#6a6a62", 0.55);

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(14));
        root.setBackground(new Background(new BackgroundFill(
                Color.web("#f5f5f3"), null, null)));

        root.getChildren().addAll(
                heading("浅色主题"),
                new HBox(12, tree(HUED, false), tree(CURRENT, false),
                        tree(WARM, false), tree(ACCENTED, false)),
                heading("深色主题（同一套调色板的暗色取值）"),
                new HBox(12, tree(HUED, true), tree(CURRENT, true),
                        tree(WARM, true), tree(ACCENTED, true)));

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("TreeRowPreview");
        stage.show();

        delay(700, () -> {
            shoot(scene, "tools/tree-rows.png");
            Platform.exit();
        });
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 13));
        return label;
    }

    /**
     * 一棵模拟的树。
     *
     * <p>{@code dark} 只切图标的调色板和底色——树的文字颜色跟着一起换，
     * 否则深色底上一片黑字，图标好不好看根本判断不出来。
     */
    private static VBox tree(Scheme s, boolean dark) {
        Icons.applyTheme(dark);
        Color text = Color.web(dark ? "#e8e8e2" : "#1c1c1a");
        Color background = Color.web(dark ? "#1f1f1d" : "#f0f0ed");

        VBox box = new VBox(0);
        box.setPadding(new Insets(8, 10, 10, 6));
        box.setBackground(new Background(new BackgroundFill(background, null, null)));
        box.setMinWidth(230);

        Label title = new Label(s.name());
        title.setFont(Font.font("System", FontWeight.BOLD, 11));
        title.setTextFill(Color.web(dark ? "#8f8f88" : "#8a8a84"));
        title.setPadding(new Insets(0, 0, 6, 6));
        box.getChildren().add(title);

        box.getChildren().addAll(
                row(0, Icons.folder(s.folder(), 13), "生产环境", text, true),
                row(1, Icons.database(s.schema(), 13), "user_center", text, false),
                row(2, fade(Icons.table(s.table(), 11), s.groupFade()), "表", text, false),
                row(3, Icons.table(s.table(), 12), "t_user", text, false),
                row(3, Icons.table(s.table(), 12), "t_order_item", text, false),
                row(2, fade(Icons.view(s.view(), 11), s.groupFade()), "视图", text, false),
                row(3, Icons.view(s.view(), 12), "v_user_summary", text, false),
                row(2, fade(Icons.plus(s.sequence(), 11), s.groupFade()), "序列", text, false),
                row(3, Icons.plus(s.sequence(), 12), "seq_order_id", text, false),
                row(1, Icons.database(s.schema(), 13), "order_center", text, false));

        // 画完再切回亮色，免得下一棵树拿到的是上一棵留下的状态
        Icons.applyTheme(false);
        return box;
    }

    private static Node fade(Node node, double opacity) {
        node.setOpacity(opacity);
        return node;
    }

    /** 一行：按层级缩进，图标 + 名字，字号和侧栏一致。 */
    private static HBox row(int depth, Node icon, String label, Color color, boolean bold) {
        Label name = new Label(label);
        name.setFont(bold ? Font.font("System", FontWeight.BOLD, 12) : Font.font("System", 12));
        name.setTextFill(color);
        HBox box = new HBox(6, icon, name);
        box.setAlignment(Pos.CENTER_LEFT);
        // 树的缩进量，照 JavaFX 默认的 18px 一级
        box.setPadding(new Insets(4, 8, 4, 8 + depth * 18));
        return box;
    }

    private void shoot(Scene scene, String path) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#f5f5f3"));
        WritableImage image = scene.getRoot().snapshot(params, null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        // 放大两倍存一份：树行里的图标只有十几个像素，原尺寸截图看不出差异
        BufferedImage out = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h * 2; y++) {
            for (int x = 0; x < w * 2; x++) {
                out.setRGB(x, y, pixels.getArgb(x / 2, y / 2));
            }
        }
        try {
            File file = new File(path);
            javax.imageio.ImageIO.write(out, "png", file);
            System.out.println("截图：" + file.getAbsolutePath());
        } catch (java.io.IOException e) {
            System.out.println("截图存不下来：" + e);
        }
    }

    private static void delay(int millis, Runnable action) {
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        wait.setOnFinished(e -> action.run());
        wait.play();
    }

    public static void main(String[] args) {
        launch(TreeRowPreview.class, args);
    }
}
