package com.plainly.app.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 启动画面。
 *
 * <h2>为什么要有它</h2>
 * 双击图标之后，要先打开本机配置库、读凭据、搭主窗口，才有东西可看。
 * 这几步原来全在界面线程上同步做完，期间屏幕上什么都没有——
 * 慢一点的机器上用户会以为没点中，再双击一次，于是开出两个实例。
 *
 * <p>这里先摆一张卡片出来，告诉用户「起来了，在做什么」，
 * 重活挪到后台去做，卡片上的进度条就一直在动。
 *
 * <h2>为什么不用 JVM 的 -splash</h2>
 * 那个只能放一张图，不能跟主题，也不能报进度；而且它由 AWT 画，
 * 和后面 JavaFX 的窗口衔接时会闪一下。
 */
public final class Splash {

    /** 卡片至少露这么久。机器快的时候一闪而过，看着像界面出了毛病。 */
    private static final Duration MIN_VISIBLE = Duration.millis(600);

    private static final double TRACK_WIDTH = 200;
    private static final double SEGMENT_WIDTH = 56;

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final Label status = new Label();
    private final VBox card;
    private final Timeline sweep;
    private final long shownAt;

    private Splash() {
        // 配置库还没打开，先按上次的主题配色；之后 Theme.restore 会以配置库里的为准
        Theme.apply(Theme.lastKnownDark());

        Label name = new Label("Plainly");
        name.getStyleClass().add("splash-name");
        status.getStyleClass().add("splash-status");

        // 进度条：一段青色在轨道里来回滑。不知道还要多久，就不画假的百分比
        Rectangle track = new Rectangle(TRACK_WIDTH, 3);
        track.setArcWidth(3);
        track.setArcHeight(3);
        track.getStyleClass().add("splash-track");
        Rectangle segment = new Rectangle(SEGMENT_WIDTH, 3);
        segment.setArcWidth(3);
        segment.setArcHeight(3);
        segment.getStyleClass().add("splash-segment");
        Pane bar = new Pane(track, segment);
        bar.setPrefSize(TRACK_WIDTH, 3);
        bar.setMaxSize(TRACK_WIDTH, 3);
        // 段落滑出轨道两头的部分不画，看上去是从里面流过去的
        bar.setClip(new Rectangle(TRACK_WIDTH, 3));
        sweep = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(segment.translateXProperty(), -SEGMENT_WIDTH)),
                new KeyFrame(Duration.millis(1100),
                        new KeyValue(segment.translateXProperty(), TRACK_WIDTH,
                                Interpolator.EASE_BOTH)));
        sweep.setCycleCount(Timeline.INDEFINITE);

        card = new VBox(14, Icons.appMark(56), name, bar, status);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("splash-card");
        card.setPrefSize(340, 230);
        card.setMaxSize(340, 230);

        // 外面留一圈给阴影，否则阴影被窗口边缘切掉
        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: transparent; -fx-padding: 24;");

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
                Splash.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.getIcons().setAll(Icons.appIcons());
        stage.setTitle("Plainly");
        Theme.register(scene);

        status("正在启动…");
        card.setOpacity(0);
        card.setScaleX(0.96);
        card.setScaleY(0.96);
        stage.show();
        stage.centerOnScreen();
        sweep.play();

        FadeTransition fade = new FadeTransition(Duration.millis(220), card);
        fade.setToValue(1);
        ScaleTransition grow = new ScaleTransition(Duration.millis(220), card);
        grow.setToX(1);
        grow.setToY(1);
        grow.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, grow).play();

        shownAt = System.nanoTime();
    }

    /** 弹出启动画面。只能在界面线程上调。 */
    public static Splash show() {
        return new Splash();
    }

    /** 卡片底下那行字：现在在做什么。 */
    public void status(String text) {
        status.setText(text);
    }

    /**
     * 收起启动画面，同时把主窗口淡入。
     *
     * <p>主窗口要在调这里之前 {@code setOpacity(0)} 再 {@code show()}：
     * 先把它摆好、只是看不见，淡入时它已经排版完毕，不会出现半成品的一帧。
     *
     * @param then 两边都收尾之后再做的事（比如弹「恢复上次的标签页」）
     */
    public void handOver(Stage main, Runnable then) {
        double shownMillis = (System.nanoTime() - shownAt) / 1_000_000.0;
        PauseTransition hold = new PauseTransition(
                Duration.millis(Math.max(0, MIN_VISIBLE.toMillis() - shownMillis)));
        hold.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(200), card);
            out.setToValue(0);
            Timeline in = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(main.opacityProperty(), 0)),
                    new KeyFrame(Duration.millis(260),
                            new KeyValue(main.opacityProperty(), 1, Interpolator.EASE_OUT)));
            ParallelTransition both = new ParallelTransition(out, in);
            both.setOnFinished(done -> {
                close();
                main.toFront();
                // 不能在这里直接跑：then 里常常要弹确认框（恢复上次的标签页），
                // 而 showAndWait 不许在动画回调里调用，会抛 IllegalStateException，
                // 那个询问就悄悄没了。排到下一轮，动画已经彻底结束
                javafx.application.Platform.runLater(then);
            });
            both.play();
        });
        hold.play();
    }

    /** 直接关掉，不要动画。启动失败、要弹错误框时用。 */
    public void close() {
        sweep.stop();
        stage.close();
    }
}
