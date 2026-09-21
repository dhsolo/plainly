import com.plainly.app.view.sql.LineNumbers;
import com.plainly.app.view.sql.SqlHighlighter;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * SQL 编辑器：拖水平滚动条为什么卡，以及某些行为什么和别的行差几像素。
 *
 * <h2>三处不这么做就测不准</h2>
 * <ul>
 *   <li>编辑器要包在 {@link VirtualizedScrollPane} 里——用户拖的那根横向滚动条
 *       就是它的。裸 CodeArea 扔进 StackPane 测出来明显偏低，第一版就是这样，
 *       报「通过」而问题明明在；</li>
 *   <li>要滚到文档最顶上——个位数行号和两位数行号只有在那里才同屏出现，
 *       而这正是要比的两种行。停在中间那一屏全是两位数，比了个寂寞；</li>
 *   <li>帧耗时要靠 {@link AnimationTimer} 量真实脉冲，<b>不能自己调 layout()</b>
 *       （那量的是「完整重排一次多久」，会高估），更不能在 FX 线程上自旋等待——
 *       那会把脉冲整个堵死，测出一串 0。</li>
 * </ul>
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SqlEditorProbe.java [列数]
 * </pre>
 */
public class SqlEditorProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static int pass;
    private static int fail;

    /** 一行里有多少列。用来看耗时随 SQL 变长怎么涨。 */
    private static int columns = 40;

    private final CodeArea editor = new CodeArea();
    private String sql;

    /** 照着用户那份 SQL 的样子造：很多列的 INSERT，一行好几百字符。 */
    private static String sample() {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < columns; i++) {
            if (i > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append("columnName").append(i);
            vals.append('\'').append("value").append(i).append('\'');
        }
        StringBuilder sb = new StringBuilder();
        for (int line = 0; line < 40; line++) {
            sb.append("INSERT INTO ele_ana_type (").append(cols)
                    .append(") VALUES (").append(vals).append(");\n");
        }
        return sb.toString();
    }

    @Override
    public void start(Stage stage) {
        sql = sample();

        editor.getStyleClass().add("code-area");
        editor.setParagraphGraphicFactory(LineNumbers.factory(editor));
        editor.replaceText(sql);
        editor.setStyleSpans(0, SqlHighlighter.computeHighlighting(sql));

        Scene scene = new Scene(new StackPane(new VirtualizedScrollPane<>(editor)), 900, 400);
        java.net.URL css = SqlEditorProbe.class.getResource("/com/plainly/app/plainly.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
        editor.showParagraphAtTop(0);

        after(700, () -> {
            measureAlignment();
            measureScroll(() -> {
                System.out.print(LOG);
                System.out.println();
                System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
                Platform.exit();
            });
        });
    }

    // -------------------------------------------------------------- 对齐

    /**
     * 行号那一格的宽度，和每一行文字实际的起点。
     *
     * <p>行号是右对齐补空格的。等宽字体下空格和数字一样宽，各行的行号格就一样宽；
     * 比例字体下空格只有数字一半，个位数的那些行会窄一截，正文跟着往左挪——
     * 等宽正文下一眼看得出来。
     */
    private void measureAlignment() {
        List<Node> linenos = new ArrayList<>();
        collectByClass(editor, "lineno", linenos);
        List<TextFlow> flows = new ArrayList<>();
        collect(editor, flows);

        check("行号格取到了（取不到下面就什么都没验）", !linenos.isEmpty(), "0 个");
        if (linenos.isEmpty()) {
            return;
        }

        Font font = linenos.get(0) instanceof Label lb ? lb.getFont() : Font.getDefault();
        double space = widthOf(font, " ");
        double digit = widthOf(font, "9");
        LOG.append("行号字体 ").append(font.getFamily()).append(' ').append(font.getSize())
                .append("   空格宽 ").append(fmt(space))
                .append("   数字宽 ").append(fmt(digit)).append(NL);
        // 这一条是根因：补位用的是空格，空格比数字窄就一定歪
        check("行号字体是等宽的（空格和数字一样宽）", Math.abs(space - digit) < 0.2,
                "空格 " + fmt(space) + " vs 数字 " + fmt(digit));

        List<Double> widths = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        int n = Math.min(linenos.size(), flows.size());
        for (int i = 0; i < n; i++) {
            widths.add(linenos.get(i).getLayoutBounds().getWidth());
            Text first = firstText(flows.get(i));
            if (first != null) {
                xs.add(first.localToScene(first.getBoundsInLocal()).getMinX());
            }
        }
        check("行号格宽度一致", spread(widths) < 0.5, "相差 " + fmt(spread(widths)) + " 像素");
        check("每一行文字起点一致", spread(xs) < 0.5, "相差 " + fmt(spread(xs)) + " 像素");
    }

    // -------------------------------------------------------------- 滚动

    /**
     * 拖水平滚动条的代价，以及它和着色跨度数量的关系。
     *
     * <p>同一份文本量两遍：一遍是现在这套按 token 着色，一遍是整篇一段。
     * 两者的差就是<b>跨度数量</b>带来的代价——不这么比只能猜「大概是着色太碎」，
     * 而猜出来的结论正是这套探针要消灭的东西。
     */
    private void measureScroll(Runnable done) {
        StyleSpans<Collection<String>> fine = SqlHighlighter.computeHighlighting(sql);
        StyleSpansBuilder<Collection<String>> plain = new StyleSpansBuilder<>();
        plain.add(Collections.emptyList(), sql.length());
        StyleSpans<Collection<String>> coarse = plain.create();

        // 自动换行下还卡不卡：换行之后没有横向滚动条，每个可见行也短，
        // 需要排版的文字量被视口框住了。如果这条路快很多，那就值得给用户一个开关
        timeWrapped(wrapMs -> timeScroll(fine, fineMs -> timeScroll(coarse, coarseMs -> {
            LOG.append(NL)
                    .append("一行 ").append(sql.indexOf((char) 10)).append(" 字符 · 着色 ")
                    .append(fine.getSpanCount() / 40).append(" 段/行").append(NL)
                    .append("  按 token 着色   每帧 ").append(fmt(fineMs)).append(" 毫秒").append(NL)
                    .append("  整篇一段（对照） 每帧 ").append(fmt(coarseMs)).append(" 毫秒").append(NL)
                    .append("  着色的代价       ").append(fmt(fineMs - coarseMs))
                    .append(" 毫秒/帧").append(NL);
            LOG.append("  自动换行时（纵向滚动） 每帧 ").append(fmt(wrapMs))
                    .append(" 毫秒").append(NL);
            // 横向滚动这一项<b>不设断言</b>，只如实记下来。
            //
            // 大头是 RichTextFX 把整行交给 TextFlow 排版——行有多长排多长，
            // 哪怕只看得见一百来个字符。那一层在组件内部，我们改不动，
            // 摆一条永远红的断言只会变成噪音，还会盖住真正回归的那一天。
            LOG.append("  （横向这一项不设断言：大头在组件内部，我们改不动）").append(NL);
            // 绝对毫秒数在负载下抖得厉害（同一份文本测出过 10 到 20 毫秒），
            // 拿它当断言只会得到一条时灵时不灵的红线。要断言就断言<b>比值</b>：
            // 两边在同一次运行里、同一台机器上测出来，负载对两边一样
            check("自动换行比横向滚动快数倍（这才是那个开关的意义）",
                    fineMs > wrapMs * 3,
                    "横向 " + fmt(fineMs) + " vs 换行 " + fmt(wrapMs));
            done.run();
        })));
    }

    /** 开了自动换行之后纵向滚动的帧耗时。 */
    private void timeWrapped(DoubleConsumer then) {
        editor.setWrapText(true);
        editor.setStyleSpans(0, SqlHighlighter.computeHighlighting(sql));
        editor.showParagraphAtTop(0);
        List<Double> frames = new ArrayList<>();
        AnimationTimer timer = new AnimationTimer() {
            private long last;
            private int count;

            @Override
            public void handle(long now) {
                if (last > 0) {
                    frames.add((now - last) / 1_000_000.0);
                }
                last = now;
                editor.scrollYBy(30);
                if (++count >= 90) {
                    stop();
                    editor.setWrapText(false);
                    frames.sort(Double::compareTo);
                    then.accept(frames.isEmpty() ? 0 : frames.get(frames.size() / 2));
                }
            }
        };
        timer.start();
    }

    /**
     * 量真实帧间隔：每一帧挪一点，让 JavaFX 自己走脉冲。
     *
     * <p>取中位数——头几帧带着预热噪声，平均值会被它们拉偏。
     */
    private void timeScroll(StyleSpans<Collection<String>> spans, DoubleConsumer then) {
        editor.setStyleSpans(0, spans);
        editor.scrollXToPixel(0);
        double before = editor.getEstimatedScrollX();

        List<Double> frames = new ArrayList<>();
        AnimationTimer timer = new AnimationTimer() {
            private long last;
            private int count;

            @Override
            public void handle(long now) {
                if (last > 0) {
                    frames.add((now - last) / 1_000_000.0);
                }
                last = now;
                editor.scrollXBy(30);
                if (++count >= 90) {
                    stop();
                    double moved = editor.getEstimatedScrollX() - before;
                    check("这一轮真的滚动了（否则耗时没有意义）", moved > 500,
                            "只走了 " + fmt(moved) + " 像素");
                    frames.sort(Double::compareTo);
                    then.accept(frames.isEmpty() ? 0 : frames.get(frames.size() / 2));
                }
            }
        };
        timer.start();
    }

    // -------------------------------------------------------------- 辅助

    private static double widthOf(Font font, String s) {
        Text t = new Text(s);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static double spread(List<Double> values) {
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return max - min;
    }

    private static Text firstText(TextFlow flow) {
        for (Node n : flow.getChildrenUnmodifiable()) {
            if (n instanceof Text t && !t.getText().isEmpty()) {
                return t;
            }
        }
        return null;
    }

    private static void collectByClass(Parent parent, String styleClass, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.getStyleClass().contains(styleClass)) {
                out.add(child);
            }
            if (child instanceof Parent p) {
                collectByClass(p, styleClass, out);
            }
        }
    }

    private static void collect(Parent parent, List<TextFlow> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof TextFlow tf) {
                out.add(tf);
            }
            if (child instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    private static void after(int millis, Runnable then) {
        PauseTransition wait = new PauseTransition(Duration.millis(millis));
        wait.setOnFinished(e -> then.run());
        wait.play();
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            LOG.append("  ").append(what).append(NL);
        } else {
            fail++;
            LOG.append("X ").append(what).append("   实际：").append(detail).append(NL);
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            columns = Integer.parseInt(args[0]);
        }
        launch(SqlEditorProbe.class, args);
    }
}
