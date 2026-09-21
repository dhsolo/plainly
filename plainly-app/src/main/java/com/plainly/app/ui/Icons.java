package com.plainly.app.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * 线性图标。
 *
 * <p>用 SVGPath 而不是图片或 emoji：能随主题改色、任意缩放不糊，
 * 也不引入字体图标那一层依赖。全部按 16×16 网格绘制。
 */
public final class Icons {

    public static final String ACCENT = "#0f6f70";
    public static final String NEUTRAL = "#4a4a45";
    public static final String MUTED = "#7a8a9a";
    public static final String FAINT = "#a8a8a1";

    private static List<Image> appIcons;

    private Icons() {
    }

    // ------------------------------------------------------------------ 主题

    /*
     * 图标怎么跟着主题换色。
     *
     * 图标的颜色是调用方以字符串传进来的（Icons.table(Icons.MUTED, 12)），而这些
     * 字符串在编译期就被内联成了字面量——换主题时去改那几个常量是没有用的，
     * 而且已经画出来的图标本来也不会因为常量变了而重画。
     *
     * 所以这里换一条路：把这些<b>颜色值本身</b>当成键，映射到一个可观察的颜色属性，
     * 画的时候<b>绑定</b>过去而不是取一个瞬时值。换主题只要给这些属性重新赋值，
     * 界面上每一个已经画出来的图标都会立刻跟着变——不需要重启，也不需要重建界面。
     *
     * 认不出的颜色照旧按字面量画。调用方传一个一次性的颜色进来仍然成立。
     */
    private static final java.util.Map<String, javafx.beans.property.ObjectProperty<Color>> PALETTE =
            new java.util.HashMap<>();

    /** 亮色主题下的取值，同时也是调用方写在代码里的那些字面量。 */
    private static final String[][] LIGHT_DARK = {
            // 键（= 亮色值）      暗色值
            {"#0f6f70", "#3fa9aa"},   // ACCENT
            {"#4a4a45", "#c9c9c2"},   // NEUTRAL
            {"#7a8a9a", "#8fa3b5"},   // MUTED
            {"#a8a8a1", "#7d7d76"},   // FAINT
            {"#a0402a", "#e07a5f"},   // 危险
            {"#8a6d1f", "#d4a541"},   // 警告
            {"#a8781a", "#d4a541"},
            {"#8a7a4a", "#bdae7e"},
            {"#5a7a8a", "#89aec2"},   // 视图
            {"#4d7a4d", "#7fb37f"},   // 成功
            {"#3a6b3a", "#7fb37f"},
            {"#8a3d6b", "#d089ae"},   // 关键字
            {"#2a5d8f", "#7aa9d8"},   // 函数
            {"#6a6a62", "#a5a59d"},
            {"#ffffff", "#ffffff"},   // 按钮上的反白图标，两种主题下都是白的
    };

    static {
        for (String[] pair : LIGHT_DARK) {
            PALETTE.put(pair[0], new javafx.beans.property.SimpleObjectProperty<>(
                    Color.web(pair[0])));
        }
    }

    /**
     * 切换图标配色。
     *
     * <p>可以在任何时候调用：已经画出来的图标绑在这些属性上，会当场跟着变。
     */
    public static void applyTheme(boolean dark) {
        for (String[] pair : LIGHT_DARK) {
            PALETTE.get(pair[0]).set(Color.web(dark ? pair[1] : pair[0]));
        }
    }

    /** 给一条路径上色：认得的颜色绑上去，认不得的按字面量画。 */
    private static void paintStroke(SVGPath path, String color) {
        var prop = PALETTE.get(color);
        if (prop != null) {
            path.strokeProperty().bind(prop);
        } else {
            path.setStroke(Color.web(color));
        }
    }

    private static void paintFill(javafx.scene.shape.Shape path, String color) {
        var prop = PALETTE.get(color);
        if (prop != null) {
            path.fillProperty().bind(prop);
        } else {
            path.setFill(Color.web(color));
        }
    }

    private static Group stroke(String color, double size, String... paths) {
        Group g = new Group(spacer(16));
        double scale = size / 16.0;
        for (String d : paths) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.setFill(Color.TRANSPARENT);
            paintStroke(p, color);
            p.setStrokeWidth(1.35 / scale);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(StrokeLineJoin.ROUND);
            g.getChildren().add(p);
        }
        return sized(g, scale);
    }

    private static Group filled(String color, double size, String... paths) {
        Group g = new Group(spacer(16));
        double scale = size / 16.0;
        for (String d : paths) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            paintFill(p, color);
            g.getChildren().add(p);
        }
        return sized(g, scale);
    }

    /**
     * 缩放，并让它在布局里<b>真的</b>只占那么大。
     *
     * <h2>为什么要多套一层 Group</h2>
     * {@code Group.layoutBounds} 是子节点的并集，<b>不含 Group 自己的变换</b>。
     * 所以直接 {@code setScaleX} 之后，一个 11px 的图标画出来是 11px，
     * 在布局里占的却仍然是 16px——量出来是「11px 和 16px 的图标一样宽」。
     *
     * <p>后果不是某一处难看，而是<b>图标后面的文字对不齐</b>：树上表行和视图行
     * 用的图标路径不一样，各自空出来的那一截也不一样，两行的文字于是差着两三个像素起跑。
     * 再套一层 Group，外层的 layoutBounds 取的是内层的 boundsInParent，那个是含缩放的。
     *
     * <p>配合内层那个 16×16 的透明方框，每个图标在布局里正好占 {@code size} 见方，
     * 与路径画到哪儿无关。
     */
    private static Group sized(Group art, double scale) {
        art.setScaleX(scale);
        art.setScaleY(scale);
        return new Group(art);
    }

    /*
     * 树上这两个图标（库、表）和别处的图标画法不一样：它们是<b>实心 + 对齐像素</b>的，
     * 别的还是描边。
     *
     * 原因是工作尺寸。工具栏上的图标 11 到 16px 都有，但树上的库是 13px、对象 12px、
     * 分组 11px，而描边的线宽是 1.35px——那条线几乎永远压在两个像素的中间，
     * 抗锯齿一摊，一条实线变成两条半透明的灰线。把 12px 的图标放大七倍看一眼就明白：
     * 原来的表图标在那个尺寸上根本没有一条实边，整体是一团浅灰
     * （对照图见 tools/TreeIconPreview.java，那里有逐像素的放大）。
     *
     * 实心块的边界可以取整，于是每条边都落在像素边界上，小尺寸下才是干净的。
     * 代价是圆柱的两个椭圆盖仍然是曲线，抗锯齿躲不掉——但那是轮廓的一部分，
     * 糊一点仍然认得出是个罐子；而内部的细线糊掉就真的没有了。
     */

    /**
     * 数据库：实心圆柱，腰上留一道缝。
     *
     * <p>那道缝是<b>透明</b>的，不是拿底色画出来的——树行有普通、悬停、选中三种底色，
     * 用任何一种去填缝，换个状态就露馅。所以柱身拆成上下两段，中间那块空着。
     */
    public static Group database(String color, double size) {
        double s = Math.max(8, Math.round(size));
        Group g = new Group(spacer(s));

        double rx = Math.round(s * 0.30);
        // 盖子的短半轴至少 1px：再扁就只剩一条线，圆柱看着像一段方管
        double ry = Math.max(1, Math.round(s * 0.13));
        double total = Math.round(s * 0.78);
        double bodyH = Math.max(2, total - ry * 2);
        double top = Math.round((s - total) / 2) + ry;
        double bottom = top + bodyH;
        double cx = Math.round(s / 2);
        double seamH = Math.max(1, Math.round(s * 0.07));
        double seamY = top + Math.max(1, Math.round(bodyH * 0.40));

        g.getChildren().addAll(
                ellipse(cx, bottom, rx, ry, color),
                rect(cx - rx, top, rx * 2, seamY - top, color),
                rect(cx - rx, seamY + seamH, rx * 2, bottom - seamY - seamH, color),
                ellipse(cx, top, rx, ry, color));
        return g;
    }

    public static Group folder(String color, double size) {
        return stroke(color, size, "M2 4.2h4.2l1.2 1.5H14v7.1H2z");
    }

    /**
     * 表：一条实心表头，下面 2×2 四个格子，缝隙留空。
     *
     * <p>上一版是描边的方框加两条内部线，而且那条竖线<b>没有居中</b>
     * （方框 2.2–13.8，竖线却在 6.2，偏左将近两个单位）——放大之后一眼就看得出歪。
     *
     * <p>格子宽度先定，外框宽度由它推出来（{@code colW * 2 + gap}），
     * 不是反过来先定外框再除以二：先定外框的话，{@code (w - gap) / 2} 往往除不尽，
     * 两列就差一个像素，看着像没对齐。
     */
    public static Group table(String color, double size) {
        double s = Math.max(8, Math.round(size));
        Group g = new Group(spacer(s));

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

    /**
     * 一个透明的 s×s 方框。
     *
     * <p>实心图标是按真实像素直接画的，没有缩放，所以它的包围盒就是图形本身——
     * 比描边图标窄一截（那些是在 16 单位的网格上画完再缩放的，而 Group 的
     * layoutBounds 不含自身缩放，于是它们在布局里占的一直是十四五个单位）。
     * 两者摆在同一棵树里，图标后面的文字会差出两三个像素，看着像没对齐。
     * 补一个透明方框，占位就稳定了。
     */
    private static javafx.scene.shape.Rectangle spacer(double s) {
        javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle(s, s);
        r.setFill(Color.TRANSPARENT);
        return r;
    }

    private static javafx.scene.shape.Rectangle rect(double x, double y, double w, double h,
                                                     String color) {
        javafx.scene.shape.Rectangle r =
                new javafx.scene.shape.Rectangle(x, y, w, Math.max(0, h));
        paintFill(r, color);
        return r;
    }

    private static Ellipse ellipse(double cx, double cy, double rx, double ry, String color) {
        Ellipse e = new Ellipse(cx, cy, rx, ry);
        paintFill(e, color);
        return e;
    }

    public static Group view(String color, double size) {
        return stroke(color, size,
                "M1.6 8s2.4-4.2 6.4-4.2S14.4 8 14.4 8s-2.4 4.2-6.4 4.2S1.6 8 1.6 8z",
                "M8 9.9a1.9 1.9 0 1 0 0-3.8a1.9 1.9 0 1 0 0 3.8");
    }

    /** 复制：后面一张露出边角，前面压着一张完整的。 */
    public static Group copy(String color, double size) {
        return stroke(color, size,
                "M10.2 4.4V2.4H2.6v8.4h2.2",
                "M5.4 5.2h8v8.4h-8z");
    }

    public static Group file(String color, double size) {
        return stroke(color, size, "M4 2.2h5l3 3v8.6H4z", "M6 8.4h4M6 10.8h4");
    }

    public static Group plus(String color, double size) {
        return stroke(color, size, "M8 3.2v9.6M3.2 8h9.6");
    }

    public static Group minus(String color, double size) {
        return stroke(color, size, "M3.4 8h9.2");
    }

    public static Group check(String color, double size) {
        return stroke(color, size, "M3.4 8.4 6.4 11.4l6.2-6.6");
    }

    public static Group refresh(String color, double size) {
        return stroke(color, size, "M13 8a5 5 0 1 1-1.6-3.7", "M13.2 2.6v2.8h-2.8");
    }

    public static Group search(String color, double size) {
        return stroke(color, size, "M11.4 7a4.4 4.4 0 1 0-8.8 0a4.4 4.4 0 1 0 8.8 0",
                "M10.4 10.4 13.6 13.6");
    }

    public static Group filter(String color, double size) {
        return stroke(color, size, "M3 4h10M5 8h6M7 12h2");
    }

    public static Group play(String color, double size) {
        return filled(color, size, "M4.5 3 12.5 8l-8 5z");
    }

    public static Group playLine(String color, double size) {
        return stroke(color, size, "M4.5 3 12 8l-7.5 5z", "M13.5 3v10");
    }

    public static Group stop(String color, double size) {
        return filled(color, size, "M4 4h8v8H4z");
    }

    public static Group format(String color, double size) {
        return stroke(color, size, "M3 4h10M3 8h6M3 12h8");
    }

    public static Group plan(String color, double size) {
        return stroke(color, size, "M8 2.4v3.2M4.6 7.2 8 5.6l3.4 1.6M4.6 7.2v3.4L8 12.2l3.4-1.6V7.2");
    }

    public static Group export(String color, double size) {
        return stroke(color, size, "M8 2.6v7.6M5 7.4 8 10.4l3-3M3 12.6h10");
    }

    /** 导入：箭头朝上，和 {@link #export} 的朝下正好相反，一眼分得开方向。 */
    public static Group imports(String color, double size) {
        return stroke(color, size, "M8 10.2V2.6M5 5.6 8 2.6l3 3M3 12.6h10");
    }

    public static Group warn(String color, double size) {
        return stroke(color, size, "M8 2.2 14.4 13.4H1.6z", "M8 6.4v3.2M8 11.4v.6");
    }

    public static Group column(String color, double size) {
        return stroke(color, size, "M2.5 2.5h11v11h-11z", "M2.5 6.5h11");
    }

    public static Group key(String color, double size) {
        return stroke(color, size,
                "M10.2 2.6a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4",
                "M8 7.8 2.6 13.2v1.2h2v-1.6h1.6v-1.6h1.4z");
    }

    public static Group lock(String color, double size) {
        return stroke(color, size,
                "M3.6 7.2h8.8a1 1 0 0 1 1 1v4.4a1 1 0 0 1-1 1H3.6a1 1 0 0 1-1-1V8.2a1 1 0 0 1 1-1z",
                "M5.4 7.2V5.2a2.6 2.6 0 0 1 5.2 0v2");
    }

    public static Group disconnect(String color, double size) {
        return stroke(color, size, "M6 10 3.4 12.6M10 6l2.6-2.6",
                "M5.4 5.4 2.8 8a2.6 2.6 0 0 0 3.7 3.7l2.6-2.6",
                "M10.6 10.6 13.2 8a2.6 2.6 0 0 0-3.7-3.7L6.9 6.9");
    }

    // ------------------------------------------------------------ 应用标记

    /**
     * 应用标记：青绿圆角方块 + 一个白色数据库圆柱。
     *
     * <h2>为什么不再是三条横条</h2>
     * 上一版是三条从宽到窄的白横条（产品还叫 Strata「地层」时画的）。
     * 问题不在小尺寸糊不糊，而在<b>它读起来像汉堡菜单</b>——三条平行横线
     * 在今天的界面里已经被那个含义占死了，再怎么调长短也扳不回来。
     *
     * <p>换成圆柱还有一个实打实的好处：<b>轮廓是一个闭合外形</b>。
     * 多条平行线一缩小，线与线之间的缝先被抗锯齿吃掉，三条并成一团；
     * 而圆柱缩到 16px 仍然是「一个立着的罐子中间一道缝」，认得出来。
     * 几个比例方案的对照图见 {@code tools/AppIconPreview.java}，
     * 最后取的是柱宽 0.28、总高 0.62、缝在柱身 42% 处那一组。
     *
     * <p>这里唯一的实心图标。窗口图标要在 16px 的任务栏和标题栏里还认得出，
     * 线条到那个尺寸只会糊成一团灰。
     *
     * <p>所有几何量都按整像素取整，理由和上一版一样：16px 下那道缝本该高 0.72px，
     * 压在像素缝里被抗锯齿一摊薄，剩不到一半的不透明度，远看就没有缝了——
     * 圆柱于是变成一个白色的圆角块。取整之后每条边都落在像素边界上，
     * 小尺寸才是干干净净的实边。
     */
    public static Group appMark(double size) {
        double s = Math.max(8, Math.round(size));
        Group g = new Group();

        Rectangle bg = new Rectangle(s, s);
        double corner = Math.max(2, Math.round(s * 0.28));
        bg.setArcWidth(corner * 2);
        bg.setArcHeight(corner * 2);
        bg.setFill(Color.web(ACCENT));
        g.getChildren().add(bg);

        double rx = Math.round(s * 0.28);
        // 盖子的短半轴至少 1.5px：再扁就只剩一条线，圆柱看着像一段方管
        double ry = Math.max(1.5, Math.round(s * 0.10));
        double total = Math.round(s * 0.62);
        double bodyH = Math.max(2, total - ry * 2);
        // 竖直居中：总高含上下两个盖子，所以柱身顶端要再往下挪一个 ry
        double top = Math.round((s - total) / 2) + ry;
        double cx = Math.round(s / 2);

        Rectangle body = new Rectangle(cx - rx, top, rx * 2, bodyH);
        body.setFill(Color.web("#ffffff"));
        Ellipse capBottom = new Ellipse(cx, top + bodyH, rx, ry);
        capBottom.setFill(Color.web("#ffffff"));
        Ellipse capTop = new Ellipse(cx, top, rx, ry);
        capTop.setFill(Color.web("#ffffff"));
        g.getChildren().addAll(body, capBottom, capTop);

        // 腰上那道缝：用底色在白柱身上开一条，表示分层。
        // 只开一道——两道在 16px 上会并成一片灰
        double seamH = Math.max(1, Math.round(s * 0.045));
        Rectangle seam = new Rectangle(
                cx - rx, Math.round(top + bodyH * 0.42), rx * 2, seamH);
        seam.setFill(Color.web(ACCENT));
        g.getChildren().add(seam);

        return g;
    }

    /**
     * 窗口图标，多个尺寸备好让系统自己挑。
     *
     * <p>直接把矢量标记快照成位图，省掉往仓库里塞 .ico/.png 这类二进制资源；
     * 结果缓存起来——每开一个对话框都重画一遍太浪费。
     *
     * <p>尺寸表里有 20 和 40：Windows 在 125% / 200% 缩放下要的正是这两个，
     * 备不齐它就拿 16 去拉，拉出来必然是糊的。
     */
    public static List<Image> appIcons() {
        if (appIcons == null) {
            List<Image> list = new ArrayList<>();
            for (int px : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) {
                list.add(renderAppIcon(px));
            }
            appIcons = List.copyOf(list);
        }
        return appIcons;
    }

    /**
     * 画出来，再编码成 PNG 解回来。
     *
     * <p>多这一道不是没事找事：{@code snapshot} 出来的 {@code WritableImage}
     * 装进 {@code Stage.getIcons()} 之后，标题栏依旧是系统那个通用图标——
     * 拿 {@code WM_GETICON} 直接问系统，回答是「没有图标」；
     * 同一张图写成 PNG 再读进来就挂得上（对照实验见 tools/IconAbProbe.java）。
     * 所以走一遍 JavaFX 自己的图片解码器，让它拿到一张能交给系统的图。
     *
     * <p>这类事情不会报错，只会让图标默默不出现，所以宁可多这一步。
     */
    private static Image renderAppIcon(int px) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Image shot = appMark(px).snapshot(params, null);

        BufferedImage buffer = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = shot.getPixelReader();
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                buffer.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(buffer, "png", bytes);
            return new Image(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("生成窗口图标失败", e);
        }
    }
}
