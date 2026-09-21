import com.plainly.app.view.ValueViewerDialog;
import com.plainly.core.search.DataSearch;
import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnRef;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 这一轮新东西的取样。
 *
 * <p>做两件事：把值查看器真的画出来看一眼排版有没有塌；在 demo 库上真跑一次
 * 跨表找值和列名搜索，确认它们在一个真实的库上（而不是测试里那张造出来的表上）
 * 也能给出结果。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SearchPreview.java</pre>
 */
public class SearchPreview extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private static final String HUGE = "12345678901234567890.1234567890";

    @Override
    public void start(Stage stage) {
        probeSearch();

        ColumnMeta amount = new ColumnMeta("AMOUNT", "AMOUNT", "DECIMAL",
                TypeCategory.EXACT_NUMERIC, 38, 10, true, "PUBLIC", "ORDERS", false, false);

        ValueViewerDialog dialog = new ValueViewerDialog(amount, HUGE, 3, true);
        dialog.show(null);

        PauseTransition p = new PauseTransition(Duration.seconds(1.2));
        p.setOnFinished(e -> {
            Stage top = (Stage) javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof Stage && w.isShowing())
                    .findFirst().orElse(null);
            if (top != null) {
                write(top.getScene().snapshot(null), "value-viewer");
            } else {
                System.out.println("没有可见窗口，取不到图");
            }
            Platform.exit();
        });
        p.play();
    }

    /** 在 demo 库上真跑一次，看看两个搜索给不给得出东西。 */
    private void probeSearch() {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("preview").setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa").setPassword("");
        cfg.setId("preview-search");
        try (DbConnection conn = JdbcConnections.open(cfg)) {
            List<ColumnRef> columns = conn.searchColumns("PUBLIC", "amo", 20);
            System.out.println("列名里含 amo 的（小写输入，库里存的是大写）：");
            columns.forEach(c -> System.out.println("   " + c.table() + "." + c.column()
                    + "  " + c.nativeType()));

            List<String> tables = conn.listTables("PUBLIC").stream()
                    .filter(t -> t.kind() == com.plainly.driver.meta.DbObjects.ObjectKind.TABLE)
                    .map(com.plainly.driver.meta.DbObjects.TableInfo::name).toList();
            System.out.println("库里的表：" + tables);

            DataSearch.Result r = DataSearch.run(conn, "PUBLIC", tables, "a",
                    DataSearch.Mode.CONTAINS, 20, true, name -> { }, () -> false);
            System.out.println("跨表找 a：命中 " + r.matches().size() + " 处，涉及 "
                    + r.matchedTables() + " 张表，用时 " + r.elapsedMillis() + " ms");
            r.matches().stream().limit(6).forEach(m -> System.out.println(
                    "   " + m.table() + "." + m.column() + " = " + preview(m.value())
                            + "   [" + m.locator() + "]"));
            r.tables().stream().filter(t -> t.skipped() != null).forEach(t ->
                    System.out.println("   跳过 " + t.table() + "：" + t.skipped()));
            r.tables().stream().filter(t -> t.error() != null).forEach(t ->
                    System.out.println("   出错 " + t.table() + "：" + t.error()));
        } catch (RuntimeException e) {
            System.out.println("探针失败：" + e);
        }
    }

    private static String preview(String v) {
        if (v == null) {
            return "(NULL)";
        }
        String flat = v.replace('\n', ' ');
        return flat.length() > 40 ? flat.substring(0, 40) + "…" : flat;
    }

    private static void write(Image img, String name) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", new File(OUT + name + ".png"));
            System.out.println("→ " + name + ".png  " + w + "×" + h);
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(SearchPreview.class, args);
        System.exit(0);
    }
}
