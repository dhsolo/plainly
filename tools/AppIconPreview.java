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
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * 应用图标的几个备选，摆在一起对着看。
 *
 * <h2>为什么要摆出来看，而不是直接改</h2>
 * 图标最要命的尺寸是 16px——任务栏、标题栏、文件关联都用它。
 * 一个在 256px 上好看的图案，缩到 16px 常常糊成一团色块，而这件事
 * 光看代码、甚至光看大图都判断不出来。所以每个备选都按真实的那几档尺寸画一遍，
 * 而且深浅两种底色各摆一排——浅色任务栏和深色任务栏上的观感不是一回事。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/AppIconPreview.java
 * </pre>
 */
public class AppIconPreview extends Application {

    private static final String ACCENT = "#0f6f70";
    private static final Color WHITE = Color.web("#ffffff");

    /** 真实会被系统挑走的那几档。128 和 256 只在大图标视图里出现，各放一个就够。 */
    private static final int[] SIZES = {128, 64, 48, 32, 24, 20, 16};

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(0);
        root.setPadding(new Insets(16));
        root.setBackground(new Background(new BackgroundFill(
                Color.web("#f5f5f3"), null, null)));

        root.getChildren().addAll(
                // 第一行永远是当前在用的那个：改完 appMark 跑一遍这个工具，
                // 小尺寸有没有糊、深色底上够不够亮，一眼就看出来
                candidate("定稿 · Icons.appMark（数据库圆柱）", Icons::appMark),
                candidate("比例对照 · 更瘦：0.26 / 0.64 / 缝在 40%",
                        px -> tuned(px, 0.26, 0.095, 0.64, 0.40, 0.045)),
                candidate("比例对照 · 更胖：0.31 / 0.56 / 缝在 45%",
                        px -> tuned(px, 0.31, 0.110, 0.56, 0.45, 0.055)),
                candidate("落选 · 表格（48px 以上最好看，16px 缝会并掉）",
                        AppIconPreview::grid),
                candidate("落选 · 叠盘（小尺寸退化成三条杠，老毛病还在）",
                        AppIconPreview::discs));

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("AppIconPreview");
        stage.show();

        delay(700, () -> {
            shoot(scene, "tools/app-icon.png");
            Platform.exit();
        });
    }

    /** 一个备选一行：左边写名字，右边深浅两条底色上各摆一遍全部尺寸。 */
    private static VBox candidate(String name, DoubleFunction<Group> mark) {
        Label title = new Label(name);
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        title.setPadding(new Insets(0, 0, 6, 0));

        HBox light = strip(mark, Color.web("#f0f0ed"));
        HBox dark = strip(mark, Color.web("#232320"));

        VBox box = new VBox(0, title, light, dark);
        box.setPadding(new Insets(10, 0, 14, 0));
        return box;
    }

    private static HBox strip(DoubleFunction<Group> mark, Color background) {
        HBox row = new HBox(14);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setBackground(new Background(new BackgroundFill(background, null, null)));
        for (int px : SIZES) {
            row.getChildren().add(mark.apply(px));
        }
        return row;
    }

    // ------------------------------------------------------------------ 备选

    /** 所有备选共用的圆角底板。 */
    private static Group plate(double s) {
        Group g = new Group();
        Rectangle bg = new Rectangle(s, s);
        double corner = Math.max(2, Math.round(s * 0.28));
        bg.setArcWidth(corner * 2);
        bg.setArcHeight(corner * 2);
        bg.setFill(Color.web(ACCENT));
        g.getChildren().add(bg);
        return g;
    }

    /**
     * A · 数据库圆柱。
     *
     * <p>上下两个椭圆盖 + 中间一段柱身，腰上用底色开一道缝表示分层。
     * 这是数据库最通用的那个符号，16px 上也还认得出来——轮廓只有一个闭合外形，
     * 不像多条平行线那样一缩就并成一团。
     */
    private static Group cylinder(double s) {
        Group g = plate(s);
        g.getChildren().addAll(cylinderParts(s, s * 0.60));
        return g;
    }

    /** D · 不要底板，直接一个实心圆柱。任务栏上更轻，但没有色块那么抢眼。 */
    private static Group bareCylinder(double s) {
        Group g = new Group();
        Rectangle spacer = new Rectangle(s, s);
        spacer.setFill(Color.TRANSPARENT);
        g.getChildren().add(spacer);
        for (Node node : cylinderParts(s, s * 0.86)) {
            if (node instanceof javafx.scene.shape.Shape shape
                    && WHITE.equals(shape.getFill())) {
                shape.setFill(Color.web(ACCENT));
            } else if (node instanceof javafx.scene.shape.Shape shape) {
                // 分层缝在无底板的版本里要透明，否则那道缝是实心的强调色
                shape.setFill(Color.TRANSPARENT);
            }
            g.getChildren().add(node);
        }
        return g;
    }

    private static List<Node> cylinderParts(double s, double height) {
        return parts(s, 0.30, 0.105, height / s, 0.48, 0.050);
    }

    /** 带底板的一个比例组合，用来横向比。 */
    private static Group tuned(double s, double rxRatio, double ryRatio,
                               double heightRatio, double seamAt, double seamRatio) {
        Group g = plate(s);
        g.getChildren().addAll(parts(s, rxRatio, ryRatio, heightRatio, seamAt, seamRatio));
        return g;
    }

    private static List<Node> parts(double s, double rxRatio, double ryRatio,
                                    double heightRatio, double seamAt, double seamRatio) {
        double rx = Math.round(s * rxRatio);
        double ry = Math.max(1.5, Math.round(s * ryRatio));
        double total = Math.round(s * heightRatio);
        double bodyH = Math.max(2, total - ry * 2);
        double top = Math.round((s - total) / 2) + ry;
        double bottom = top + bodyH;
        double cx = Math.round(s / 2);

        Rectangle body = new Rectangle(cx - rx, top, rx * 2, bodyH);
        body.setFill(WHITE);
        Ellipse capBottom = new Ellipse(cx, bottom, rx, ry);
        capBottom.setFill(WHITE);
        Ellipse capTop = new Ellipse(cx, top, rx, ry);
        capTop.setFill(WHITE);

        // 腰上那道缝。小尺寸下只留一道，两道会糊在一起
        double seamH = Math.max(1, Math.round(s * seamRatio));
        double seamY = Math.round(top + bodyH * seamAt);
        Rectangle seam = new Rectangle(cx - rx, seamY, rx * 2, seamH);
        seam.setFill(Color.web(ACCENT));

        return List.of(body, capBottom, capTop, seam);
    }

    /**
     * B · 表格。
     *
     * <p>一条表头加两行两列，正是这个软件天天在显示的东西。
     * 风险在 16px：格子只剩两三个像素，缝隙可能并掉。
     */
    private static Group grid(double s) {
        Group g = plate(s);
        double inset = Math.round(s * 0.24);
        double w = s - inset * 2;
        double gap = Math.max(1, Math.round(s * 0.055));
        double headH = Math.max(2, Math.round(w * 0.26));
        double rowH = Math.max(2, Math.floor((w - headH - gap * 2) / 2));
        double colW = Math.max(2, Math.floor((w - gap) / 2));

        Rectangle head = new Rectangle(inset, inset, colW * 2 + gap, headH);
        head.setFill(WHITE);
        g.getChildren().add(head);

        for (int r = 0; r < 2; r++) {
            double y = inset + headH + gap + r * (rowH + gap);
            for (int c = 0; c < 2; c++) {
                Rectangle cell = new Rectangle(inset + c * (colW + gap), y, colW, rowH);
                cell.setFill(WHITE);
                g.getChildren().add(cell);
            }
        }
        return g;
    }

    /**
     * C · 叠盘。
     *
     * <p>现在那三条杠的直接改法：把矩形换成椭圆，立刻从「菜单」变成「一摞盘片」。
     * 改动最小，但仍然是三个分开的形状，小尺寸下容易并。
     */
    private static Group discs(double s) {
        Group g = plate(s);
        double rx = Math.round(s * 0.28);
        double ry = Math.max(1.2, Math.round(s * 0.085));
        double gap = Math.max(1, Math.round(s * 0.075));
        double step = ry * 2 + gap;
        double cx = Math.round(s / 2);
        double first = Math.round((s - (step * 2 + ry * 2)) / 2) + ry;
        for (int i = 0; i < 3; i++) {
            Ellipse disc = new Ellipse(cx, first + i * step, rx, ry);
            disc.setFill(WHITE);
            g.getChildren().add(disc);
        }
        return g;
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
        launch(AppIconPreview.class, args);
    }
}
