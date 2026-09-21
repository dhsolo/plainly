import com.plainly.app.ui.DbMarks;
import com.plainly.driver.DbType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * 把所有种类的标记画出来，看它们到底分不分得开。
 *
 * <h2>为什么要出图</h2>
 * {@code DbMarksTest} 能保证字母两两不同，保证不了「在 15px 上看着不像」。
 * 后者只有真画出来才知道——而这正是用户报的那个问题：
 * MySQL 和 MongoDB 当时都是 M，测试全绿，界面上分不出来。
 *
 * <p>顺带画一遍灰度：颜色是辅助，去掉颜色之后应当仍然分得开。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/DbMarkPreview.java
 * </pre>
 */
public class DbMarkPreview extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        VBox root = new VBox(14);
        root.setStyle("-fx-background-color: white; -fx-padding: 16;");

        root.getChildren().add(row("树上的实际尺寸（15px）", 15, false));
        root.getChildren().add(row("放大看清楚（40px）", 40, false));
        root.getChildren().add(row("未连接时置灰（15px）", 15, true));

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.show();

        WritableImage shot = scene.snapshot(null);
        File out = new File(System.getProperty("plainly.home", ".") + "/tools/db-marks.png");
        ImageIO.write(toBuffered(shot), "png", out);
        System.out.println("已写出 " + out);
        Platform.exit();
    }

    /**
     * WritableImage 转 BufferedImage，自己逐像素拷。
     *
     * <p>不用 {@code SwingFXUtils}：那个类在 javafx-swing 模块里，
     * 而这个项目没引它。为一张预览图多拉一个模块进来不划算，
     * 而逐像素拷贝就这么几行。
     */
    private static java.awt.image.BufferedImage toBuffered(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    private HBox row(String caption, double size, boolean dim) {
        HBox marks = new HBox(10);
        marks.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        for (DbType type : DbType.values()) {
            VBox one = new VBox(4);
            one.setAlignment(javafx.geometry.Pos.CENTER);
            one.getChildren().add(DbMarks.mark(type, size, dim));
            Label name = new Label(type.name());
            name.setFont(javafx.scene.text.Font.font(9));
            name.setTextFill(Color.web("#666"));
            one.getChildren().add(name);
            marks.getChildren().add(one);
        }
        Label head = new Label(caption);
        head.setFont(javafx.scene.text.Font.font("Microsoft YaHei", 11));
        head.setTextFill(Color.web("#333"));
        VBox box = new VBox(6, head, marks);
        return new HBox(box);
    }

    public static void main(String[] args) {
        launch(DbMarkPreview.class, args);
    }
}
