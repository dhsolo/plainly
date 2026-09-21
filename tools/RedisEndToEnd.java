import com.plainly.app.AppContext;
import com.plainly.app.view.MainWindow;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * Redis 从头走到尾：起一台假 Redis，让应用连上去，展开树，打开一个键空间。
 *
 * <p>验的是那条<b>跨越所有层</b>的链路——连接分发（Redis 不走 JDBC）、
 * RESP 编解码、键按前缀分组、树上那一层叫「键空间」而不是「表」、
 * 表页上少显示五个页签、网格里真的出来了键和值。
 * 这些单元测试各测一段，只有真跑起来才知道接不接得上。
 *
 * <p>会往连接列表里临时写一条，跑完删掉。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RedisEndToEnd.java</pre>
 */
public class RedisEndToEnd extends Application {

    private static final String OUT =
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/";

    private double at;
    private AppContext context;
    private MainWindow window;
    private TreeView<?> tree;
    private String savedId;
    private TinyRedis server;

    @Override
    public void start(Stage stage) throws Exception {
        server = new TinyRedis();
        System.out.println("假 Redis 起在 " + server.port() + " 端口");

        context = new AppContext();
        ConnectionConfig config = context.registry().save(new ConnectionConfig()
                .setName("探针·假Redis")
                .setType(DbType.REDIS)
                .setHost("127.0.0.1")
                .setPort(server.port())
                .setDatabase("db0"));
        savedId = config.id();

        window = new MainWindow(context);
        Scene scene = new Scene(window, 1280, 760);
        scene.getStylesheets().add(
                AppContext.class.getResource("/com/plainly/app/plainly.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Redis 端到端");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
            expandTo("探针·假Redis");
        });
        step(() -> expandTo("db0"));
        step(() -> expandTo("键空间"));
        step(() -> {
            System.out.println("\n树上这几行：");
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                TreeItem<?> item = tree.getTreeItem(i);
                System.out.println("   " + "  ".repeat(depth(item)) + item.getValue());
            }
        });
        step(() -> {
            // 双击「user」那一组，把它打开
            int row = findRowIndex("user");
            if (row < 0) {
                System.out.println("树上没找到 user 这一组");
                return;
            }
            tree.getSelectionModel().select(row);
            tree.scrollTo(row);
            javafx.event.Event.fireEvent(tree, new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 2,
                    false, false, false, false, true, false, false, true, false, false, null));
        });
        step(() -> { });
        step(() -> {
            System.out.println("\n表页上的视图页签：" + viewTabs());
            System.out.println("网格里的列：" + gridColumns());
            System.out.println("网格里的行数：" + gridRows());
            write(scene.snapshot(null), "redis-end-to-end");
        });
        step(this::cleanUp);
    }

    private void cleanUp() {
        try {
            // 顺序要紧：先删记录，再关上下文。反过来的话 registry 底下那个 SQLite
            // 已经关了，删除会抛「database connection closed」，
            // 探针连接就留在用户的连接列表里——下次再跑还会先撞上那条死的
            if (savedId != null) {
                context.registry().delete(savedId);
                System.out.println("临时连接已删除");
            }
            context.close();
        } finally {
            server.close();
            Platform.exit();
        }
    }

    private static int depth(TreeItem<?> item) {
        int d = 0;
        for (TreeItem<?> p = item.getParent(); p != null; p = p.getParent()) {
            d++;
        }
        return d;
    }

    private void expandTo(String label) {
        TreeItem<?> item = findRow(label);
        if (item == null) {
            System.out.println("树上没有「" + label + "」");
            return;
        }
        item.setExpanded(true);
    }

    private int findRowIndex(String label) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (item != null && String.valueOf(item.getValue()).contains(label)) {
                return i;
            }
        }
        return -1;
    }

    private TreeItem<?> findRow(String label) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (item != null && String.valueOf(item.getValue()).contains(label)) {
                return item;
            }
        }
        return null;
    }

    private List<String> viewTabs() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".view-tab")) {
            if (n instanceof javafx.scene.control.ToggleButton b) {
                out.add(b.getText());
            }
        }
        return out;
    }

    private List<String> gridColumns() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t && !t.getColumns().isEmpty()) {
                t.getColumns().forEach(c -> out.add(c.getText()));
                return out;
            }
        }
        return out;
    }

    private int gridRows() {
        for (Node n : window.lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t && !t.getColumns().isEmpty()) {
                return t.getItems().size();
            }
        }
        return -1;
    }

    private void step(Runnable action) {
        at += 1.5;
        PauseTransition p = new PauseTransition(Duration.seconds(at));
        p.setOnFinished(e -> action.run());
        p.play();
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
            System.out.println("→ " + name + ".png");
        } catch (Exception e) {
            System.out.println("写图失败：" + e);
        }
    }

    public static void main(String[] args) {
        launch(RedisEndToEnd.class, args);
        System.exit(0);
    }

    // ================================================================ 假 Redis

    /** 只够本探针用的一台 Redis，按字节说 RESP。 */
    static final class TinyRedis implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Map<String, byte[]> data = new LinkedHashMap<>();
        private volatile boolean running = true;

        TinyRedis() throws IOException {
            data.put("user:1001", "张三".getBytes(StandardCharsets.UTF_8));
            data.put("user:1002", "李四".getBytes(StandardCharsets.UTF_8));
            data.put("user:1003", "王五".getBytes(StandardCharsets.UTF_8));
            data.put("session:abc123", "token".getBytes(StandardCharsets.UTF_8));
            data.put("order:9001", "9223372036854775807".getBytes(StandardCharsets.UTF_8));
            data.put("cache_hot", "x".getBytes(StandardCharsets.UTF_8));

            serverSocket = new ServerSocket(0);
            Thread t = new Thread(this::acceptLoop, "tiny-redis");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 关不上也没别的可做
            }
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread t = new Thread(() -> serve(socket), "tiny-redis-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket;
                 InputStream in = new BufferedInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {
                while (running) {
                    List<String> args = read(in);
                    if (args == null) {
                        return;
                    }
                    out.write(handle(args));
                    if (in.available() == 0) {
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 断了就断了
            }
        }

        private byte[] handle(List<String> args) {
            String command = args.get(0).toUpperCase(Locale.ROOT);
            switch (command) {
                case "PING":
                    return raw("+PONG\r\n");
                case "SELECT":
                    return raw("+OK\r\n");
                case "INFO":
                    return bulk(args.size() > 1 && args.get(1).equalsIgnoreCase("keyspace")
                            ? "# Keyspace\r\ndb0:keys=" + data.size() + ",expires=0\r\n"
                            : "# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n");
                case "CONFIG":
                    return raw("*2\r\n$9\r\ndatabases\r\n$2\r\n16\r\n");
                case "SCAN": {
                    String pattern = "*";
                    for (int i = 2; i + 1 < args.size(); i++) {
                        if (args.get(i).equalsIgnoreCase("MATCH")) {
                            pattern = args.get(i + 1);
                        }
                    }
                    String regex = pattern.replace("\\", "").replace("*", ".*");
                    List<String> hits = new ArrayList<>();
                    data.keySet().forEach(k -> {
                        if (k.matches(regex)) {
                            hits.add(k);
                        }
                    });
                    StringBuilder sb = new StringBuilder("*2\r\n$1\r\n0\r\n*" + hits.size() + "\r\n");
                    hits.forEach(k -> sb.append('$')
                            .append(k.getBytes(StandardCharsets.UTF_8).length)
                            .append("\r\n").append(k).append("\r\n"));
                    return raw(sb.toString());
                }
                case "TYPE":
                    return raw(data.containsKey(args.get(1)) ? "+string\r\n" : "+none\r\n");
                case "TTL":
                    return raw(args.get(1).startsWith("session") ? ":300\r\n" : ":-1\r\n");
                case "STRLEN":
                    return raw(":" + value(args.get(1)).length + "\r\n");
                case "GETRANGE":
                    return bulkBytes(value(args.get(1)));
                case "GET":
                    return data.containsKey(args.get(1))
                            ? bulkBytes(value(args.get(1))) : raw("$-1\r\n");
                case "DBSIZE":
                    return raw(":" + data.size() + "\r\n");
                default:
                    return raw("-ERR unknown command '" + command + "'\r\n");
            }
        }

        private byte[] value(String key) {
            byte[] v = data.get(key);
            return v == null ? new byte[0] : v;
        }

        private static List<String> read(InputStream in) throws IOException {
            int marker = in.read();
            if (marker < 0) {
                return null;
            }
            if (marker != '*') {
                throw new IOException("不是 RESP 数组");
            }
            int count = Integer.parseInt(line(in));
            List<String> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (in.read() != '$') {
                    throw new IOException("参数不是批量字符串");
                }
                int length = Integer.parseInt(line(in));
                byte[] buf = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(buf, read, length - read);
                    if (n < 0) {
                        throw new IOException("读到一半断了");
                    }
                    read += n;
                }
                in.read();
                in.read();
                args.add(new String(buf, StandardCharsets.UTF_8));
            }
            return args;
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r') {
                    in.read();
                    return sb.toString();
                }
                sb.append((char) c);
            }
            throw new IOException("读行时断了");
        }

        private static byte[] raw(String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }

        private static byte[] bulk(String s) {
            return bulkBytes(s.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] bulkBytes(byte[] data) {
            byte[] head = raw("$" + data.length + "\r\n");
            byte[] out = new byte[head.length + data.length + 2];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(data, 0, out, head.length, data.length);
            out[out.length - 2] = '\r';
            out[out.length - 1] = '\n';
            return out;
        }
    }
}
