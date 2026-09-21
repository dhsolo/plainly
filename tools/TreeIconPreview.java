import com.plainly.app.ui.Icons;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
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
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.DoubleFunction;

/**
 * 左树里「数据库」和「表」两个图标的备选，摆在一起对着看。
 *
 * <h2>这两个图标真正的工作尺寸是 11 到 13px</h2>
 * 树上的对象图标是 12px，分组图标 11px，库是 13px——比工具栏上那些小得多。
 * 线条图标到这个尺寸，多一条内部线就多一片糊。所以这里按真实尺寸画，
 * 而且并排放一行「模拟的树行」：图标旁边就是文字，大小配不配、重不重，
 * 单看图标是看不出来的。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/TreeIconPreview.java
 * </pre>
 */
public class TreeIconPreview extends Application {

    /** 树上实际用的尺寸在前，后面几档是放大看细节用的。 */
    private static final int[] SIZES = {11, 12, 13, 16, 24, 40};

    private static final String SCHEMA_COLOR = "#8a7a4a";
    private static final String TABLE_COLOR = "#7a8a9a";

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(0);
        root.setPadding(new Insets(16));
        root.setBackground(new Background(new BackgroundFill(
                Color.web("#f5f5f3"), null, null)));

        root.getChildren().addAll(
                heading("数据库（树上的库节点，13px）"),
                row("现在 · 文件夹", "user_center",
                        px -> Icons.folder(SCHEMA_COLOR, px)),
                row("D1 · 圆柱（现成的 Icons.database）", "user_center",
                        px -> Icons.database(SCHEMA_COLOR, px)),
                row("D2 · 圆柱·只留一道腰线", "user_center",
                        px -> stroke(SCHEMA_COLOR, px,
                                "M13 4.2a5 2.2 0 1 0-10 0a5 2.2 0 1 0 10 0",
                                "M3 4.2v7.6c0 1.2 2.2 2.2 5 2.2s5-1 5-2.2V4.2",
                                "M3 8.2c0 1.2 2.2 2.2 5 2.2s5-1 5-2.2")),
                row("D3 · 圆柱·实心盖", "user_center",
                        px -> mixed(SCHEMA_COLOR, px,
                                new String[]{"M13 4a5 2.1 0 1 0-10 0a5 2.1 0 1 0 10 0"},
                                new String[]{"M3 4v8c0 1.2 2.2 2.1 5 2.1s5-.9 5-2.1V4",
                                        "M3 8.2c0 1.2 2.2 2.1 5 2.1s5-.9 5-2.1"})),

                heading("表（树上的对象节点，12px）"),
                row("现在 · 竖线偏左", "t_order_item",
                        px -> Icons.table(TABLE_COLOR, px)),
                row("T1 · 竖线居中", "t_order_item",
                        px -> stroke(TABLE_COLOR, px,
                                "M2.2 3h11.6v10H2.2z",
                                "M2.2 6.2h11.6M8 6.2V13")),
                row("T2 · 实心表头 + 居中竖线", "t_order_item",
                        px -> mixed(TABLE_COLOR, px,
                                new String[]{"M2.2 3h11.6v3.2H2.2z"},
                                new String[]{"M2.2 3h11.6v10H2.2z", "M8 6.2V13"})),
                row("T3 · 实心表头 + 两行（不画竖线）", "t_order_item",
                        px -> mixed(TABLE_COLOR, px,
                                new String[]{"M2.2 3h11.6v3.2H2.2z"},
                                new String[]{"M2.2 3h11.6v10H2.2z", "M2.2 9.6h11.6"})),
                row("T4 · 收窄一点 + 实心表头", "t_order_item",
                        px -> mixed(TABLE_COLOR, px,
                                new String[]{"M2.8 2.6h10.4v3H2.8z"},
                                new String[]{"M2.8 2.6h10.4v10.8H2.8z", "M8 5.6v7.8"})),
                row("T5 · 全实心 · 对齐像素", "t_order_item",
                        px -> snappedTable(TABLE_COLOR, px)),

                heading("全实心圆柱：柱宽 / 盖高 / 总高 / 缝位置"),
                row("D4 · 0.32 / 0.11 / 0.72 / 42%", "user_center",
                        px -> cyl(SCHEMA_COLOR, px, 0.32, 0.11, 0.72, 0.42, true)),
                row("D5 · 0.30 / 0.13 / 0.78 / 40%", "user_center",
                        px -> cyl(SCHEMA_COLOR, px, 0.30, 0.13, 0.78, 0.40, true)),
                row("D6 · 0.30 / 0.13 / 0.78 / 无缝", "user_center",
                        px -> cyl(SCHEMA_COLOR, px, 0.30, 0.13, 0.78, 0.40, false)),
                row("D7 · 0.28 / 0.14 / 0.80 / 34%（缝靠上）", "user_center",
                        px -> cyl(SCHEMA_COLOR, px, 0.28, 0.14, 0.80, 0.34, true)));

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("TreeIconPreview");
        stage.show();

        delay(700, () -> {
            shoot(scene, "tools/tree-icons.png");
            Platform.exit();
        });
    }

    // ------------------------------------------------------------------ 画法

    /**
     * 描边图标。和 {@link Icons} 里那个私有的 {@code stroke} 同一套参数——
     * 线宽 1.35 在缩放前给出，缩放后仍是 1.35 个物理像素。
     */
    private static Group stroke(String color, double size, String... paths) {
        Group g = new Group();
        double scale = size / 16.0;
        for (String d : paths) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.setFill(Color.TRANSPARENT);
            p.setStroke(Color.web(color));
            p.setStrokeWidth(1.35 / scale);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(StrokeLineJoin.ROUND);
            g.getChildren().add(p);
        }
        g.setScaleX(scale);
        g.setScaleY(scale);
        return g;
    }

    /** 一部分实心、一部分描边。实心那几条先画，描边压在上面。 */
    private static Group mixed(String color, double size, String[] fills, String[] strokes) {
        Group g = new Group();
        double scale = size / 16.0;
        for (String d : fills) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.setFill(Color.web(color));
            p.setStroke(Color.TRANSPARENT);
            g.getChildren().add(p);
        }
        for (String d : strokes) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.setFill(Color.TRANSPARENT);
            p.setStroke(Color.web(color));
            p.setStrokeWidth(1.35 / scale);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(StrokeLineJoin.ROUND);
            g.getChildren().add(p);
        }
        g.setScaleX(scale);
        g.setScaleY(scale);
        return g;
    }

    /**
     * 表：一条实心表头加下面 2×2 四个实心格，缝隙留空。
     *
     * <p>不用描边：1.35px 的线在 12px 的图标上压不到像素边界，抗锯齿一摊，
     * 整个图标变成一团浅灰（放大看一眼就知道）。实心块的边界可以取整，
     * 于是每条边都是实的。
     *
     * <p>缝隙是<b>透明</b>的，不是用底色画出来的——树行有普通、悬停、选中三种底色，
     * 拿任何一种去填缝，换个状态就露馅。
     */
    private static Group snappedTable(String color, double size) {
        double s = Math.max(8, Math.round(size));
        Group g = new Group();
        g.getChildren().add(box(s));

        double gap = Math.max(1, Math.round(s * 0.07));
        double colW = Math.max(2, Math.round(s * 0.33));
        double headH = Math.max(2, Math.round(s * 0.17));
        double rowH = Math.max(2, Math.round(s * 0.19));
        double w = colW * 2 + gap;
        double h = headH + gap + rowH + gap + rowH;
        double x0 = Math.round((s - w) / 2);
        double y0 = Math.round((s - h) / 2);

        g.getChildren().add(rect(x0, y0, w, headH, color));
        for (int r = 0; r < 2; r++) {
            double y = y0 + headH + gap + r * (rowH + gap);
            for (int c = 0; c < 2; c++) {
                g.getChildren().add(rect(x0 + c * (colW + gap), y, colW, rowH, color));
            }
        }
        return g;
    }

    /** 数据库：实心圆柱，腰上留一道透明的缝。理由同 {@link #snappedTable}。 */
    private static Group cyl(String color, double size, double rxRatio, double ryRatio,
                             double heightRatio, double seamAt, boolean seam) {
        double s = Math.max(8, Math.round(size));
        Group g = new Group();
        g.getChildren().add(box(s));

        double rx = Math.round(s * rxRatio);
        double ry = Math.max(1, Math.round(s * ryRatio));
        double total = Math.round(s * heightRatio);
        double bodyH = Math.max(2, total - ry * 2);
        double y0 = Math.round((s - total) / 2);
        double top = y0 + ry;
        double bottom = top + bodyH;
        double cx = Math.round(s / 2);

        javafx.scene.shape.Ellipse capTop = new javafx.scene.shape.Ellipse(cx, top, rx, ry);
        capTop.setFill(Color.web(color));
        javafx.scene.shape.Ellipse capBottom =
                new javafx.scene.shape.Ellipse(cx, bottom, rx, ry);
        capBottom.setFill(Color.web(color));
        g.getChildren().add(capBottom);

        if (seam) {
            double seamH = Math.max(1, Math.round(s * 0.07));
            double seamY = top + Math.max(1, Math.round(bodyH * seamAt));
            // 柱身拆成上下两段，中间空出来的就是那道缝
            g.getChildren().addAll(
                    rect(cx - rx, top, rx * 2, seamY - top, color),
                    rect(cx - rx, seamY + seamH, rx * 2, bottom - seamY - seamH, color));
        } else {
            g.getChildren().add(rect(cx - rx, top, rx * 2, bodyH, color));
        }
        g.getChildren().add(capTop);
        return g;
    }

    /** 一个透明的 s×s 外框，让实心图标和别的图标在一行里对齐。 */
    private static javafx.scene.shape.Rectangle box(double s) {
        javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle(s, s);
        r.setFill(Color.TRANSPARENT);
        return r;
    }

    private static javafx.scene.shape.Rectangle rect(double x, double y,
                                                     double w, double h, String color) {
        javafx.scene.shape.Rectangle r =
                new javafx.scene.shape.Rectangle(x, y, w, Math.max(0, h));
        r.setFill(Color.web(color));
        return r;
    }

    // ------------------------------------------------------------------ 排版

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 14));
        label.setPadding(new Insets(14, 0, 6, 0));
        return label;
    }

    /** 一行：名字 + 一条模拟树行 + 各档尺寸。 */
    private static HBox row(String name, String sample, DoubleFunction<Node> icon) {
        Label label = new Label(name);
        label.setFont(Font.font("System", 12));
        label.setMinWidth(210);

        HBox sizes = new HBox(16);
        sizes.setAlignment(Pos.CENTER_LEFT);
        for (int px : SIZES) {
            sizes.getChildren().add(icon.apply(px));
        }

        HBox box = new HBox(14, label, treeRow(sample, icon),
                magnified(icon, 12, 7), magnified(icon, 11, 7), sizes);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(6, 10, 6, 10));
        box.setBackground(new Background(new BackgroundFill(
                Color.web("#f0f0ed"), null, null)));
        return box;
    }

    /**
     * 把某一档尺寸放大，逐像素看。
     *
     * <p>12px 的图标在截图里只有 12 个点，糊没糊、哪条线被抗锯齿吃成了灰，
     * 肉眼在原尺寸上根本分辨不出来。先按真实尺寸渲染成位图，再用最近邻放大——
     * 放大的是<b>已经渲染好的那 12 个像素</b>，所以看到的就是用户实际看到的东西，
     * 而不是重新按大尺寸画一遍的理想图形。
     */
    private static Node magnified(DoubleFunction<Node> icon, int px, int factor) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Group holder = new Group(icon.apply(px));
        new Scene(new javafx.scene.layout.Pane(holder));
        WritableImage shot = holder.snapshot(params, null);

        javafx.scene.image.ImageView viewer = new javafx.scene.image.ImageView(shot);
        viewer.setSmooth(false);   // 最近邻：要的就是看见每一个像素
        viewer.setFitWidth(px * factor);
        viewer.setPreserveRatio(true);

        Label tag = new Label(px + "px ×" + factor);
        tag.setFont(Font.font("System", 9));
        tag.setTextFill(Color.web("#8a8a84"));
        VBox box = new VBox(2, viewer, tag);
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(px * factor + 8);
        return box;
    }

    /** 模拟一条树行：图标 + 名字，字号和侧栏一致。 */
    private static HBox treeRow(String text, DoubleFunction<Node> icon) {
        Label name = new Label(text);
        name.setFont(Font.font("System", 12));
        // 库节点 13px、对象节点 12px，这里统一按对象节点那档摆，差别看得出来
        HBox box = new HBox(6, icon.apply(12), name);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(160);
        box.setPadding(new Insets(3, 8, 3, 8));
        box.setBackground(new Background(new BackgroundFill(
                Color.web("#ffffff"), null, null)));
        return box;
    }

    // ------------------------------------------------------------------ 截图

    private void shoot(Scene scene, String path) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#f5f5f3"));
        WritableImage image = scene.getRoot().snapshot(params, null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
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
        launch(TreeIconPreview.class, args);
    }
}
