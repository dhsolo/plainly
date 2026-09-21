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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * 「新建键 / 改值 / 重命名 / 删除键」在真界面上长什么样、点得动点不动。
 *
 * <p><b>跑在一台本进程的假 Redis 上</b>，不碰用户任何真实数据——写入这件事
 * 在别人的库上试，就算加了前缀、就算跑完删干净，也不该由我来决定。
 * 写入逻辑本身由 {@code RedisWriteTest} 那 18 条用例守着；这里验的是界面接没接上。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RedisWriteUiCheck.java</pre>
 */
public class RedisWriteUiCheck extends Application {

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
        context = new AppContext();
        ConnectionConfig config = context.registry().save(new ConnectionConfig()
                .setName("探针·写入检查")
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
        stage.setTitle("Redis 写入检查");
        stage.show();

        step(() -> {
            for (Node n : window.lookupAll(".tree-view")) {
                if (n instanceof TreeView<?> tv) {
                    tree = tv;
                }
            }
            expand("探针·写入检查");
        });
        step(() -> expand("db0"));
        step(() -> expandStartingWith("GroupNode"));
        step(() -> openKeyspace("（全部键）"));
        step(() -> { });
        step(() -> {
            System.out.println("工具条按钮：" + toolbarButtons());
            System.out.println("网格行数：" + gridRows());
            write(scene.snapshot(null), "redis-write-toolbar");

            // 选中第一行，再点「改值」——这条路要能把完整的值读回来
            selectFirstRow();
        });
        step(() -> clickToolbar("改值"));
        step(() -> {
            Stage dialog = findDialog();
            if (dialog == null) {
                System.out.println("！「改值」没打开对话框");
                return;
            }
            System.out.println("\n改值框里的内容：");
            for (String t : texts(dialog.getScene().getRoot())) {
                System.out.println("   " + (t.length() > 90
                        ? t.substring(0, 90) + "…（共 " + t.length() + " 字符）" : t));
            }
            write(dialog.getScene().snapshot(null), "redis-edit-value");
            dialog.close();
        });
        step(() -> {
            System.out.println();
            System.out.println("在搜索框里敲 1001");
            typeSearch("1001");
        });
        step(() -> { });
        step(() -> {
            System.out.println("搜到的键：" + gridKeys());
            System.out.println("分页器：" + pagerText());
            write(scene.snapshot(null), "redis-key-search");
        });
        step(() -> {
            System.out.println();
            System.out.println("改成通配 user:*");
            typeSearch("user:*");
        });
        step(() -> { });
        step(() -> System.out.println("搜到的键：" + gridKeys()));
        step(() -> clickToolbar("新建键"));
        step(() -> {
            Stage dialog = findDialog();
            if (dialog == null) {
                System.out.println("！「新建键」没打开对话框");
                return;
            }
            write(dialog.getScene().snapshot(null), "redis-new-key");
            System.out.println("\n新建框标题：" + texts(dialog.getScene().getRoot()).get(0));
            dialog.close();
        });
        step(this::cleanUp);
    }

    private void cleanUp() {
        try {
            if (savedId != null) {
                context.registry().delete(savedId);
                System.out.println("\n临时连接已删除");
            }
            context.close();
        } finally {
            server.close();
            Platform.exit();
        }
    }

    /** 往搜索框里敲字并回车，走的是用户真会走的那条路。 */
    private void typeSearch(String text) {
        for (Node n : window.lookupAll(".text-field")) {
            if (n instanceof javafx.scene.control.TextField f
                    && f.getPromptText() != null && f.getPromptText().startsWith("搜索键名")) {
                f.setText(text);
                f.fireEvent(new javafx.event.ActionEvent());
                return;
            }
        }
        System.out.println("！没找到搜索框");
    }

    private List<String> gridKeys() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t && !t.getColumns().isEmpty()) {
                for (Object row : t.getItems()) {
                    out.add(String.valueOf(cellOf(t, row)));
                }
                return out;
            }
        }
        return out;
    }

    private static <S> Object cellOf(javafx.scene.control.TableView<S> t, Object row) {
        @SuppressWarnings("unchecked")
        javafx.scene.control.TableColumn<S, ?> col =
                (javafx.scene.control.TableColumn<S, ?>) t.getColumns().get(0);
        @SuppressWarnings("unchecked")
        S typed = (S) row;
        return col.getCellObservableValue(typed) == null
                ? "?" : col.getCellObservableValue(typed).getValue();
    }

    private String pagerText() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".pager")) {
            out.addAll(texts(n));
        }
        return String.join(" ", out);
    }

    private void clickToolbar(String label) {
        for (Node n : window.lookupAll(".tool-button")) {
            if (n instanceof javafx.scene.control.Button b && label.equals(b.getText())) {
                System.out.println("点「" + label + "」");
                Platform.runLater(b::fire);
                return;
            }
        }
        System.out.println("！工具条上没有「" + label + "」");
    }

    private void selectFirstRow() {
        for (Node n : window.lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t && !t.getColumns().isEmpty()
                    && !t.getItems().isEmpty()) {
                selectFirstCell(t);
                return;
            }
        }
    }

    /** 网格开的是单元格选择，得连列一起给，泛型只能在这儿收口。 */
    private static <S> void selectFirstCell(javafx.scene.control.TableView<S> table) {
        table.getSelectionModel().clearAndSelect(0, table.getColumns().get(0));
    }

    private static Stage findDialog() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null
                    && s.getScene().getRoot().lookup(".dialog-title") != null
                    && !"Redis 写入检查".equals(s.getTitle())) {
                return s;
            }
        }
        return null;
    }

    private static List<String> texts(Node node) {
        List<String> out = new ArrayList<>();
        walk(node, out);
        return out;
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof javafx.scene.control.Labeled l
                && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof javafx.scene.control.TextInputControl t
                && t.getText() != null && !t.getText().isBlank()) {
            out.add(t.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out));
        }
    }

    private List<String> toolbarButtons() {
        List<String> out = new ArrayList<>();
        for (Node n : window.lookupAll(".tool-button")) {
            if (n instanceof javafx.scene.control.Button b && b.getText() != null
                    && !b.getText().isBlank()) {
                out.add(b.getText());
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

    private void expand(String needle) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).contains(needle)) {
                item.setExpanded(true);
                return;
            }
        }
        System.out.println("树上没有含「" + needle + "」的行");
    }

    private void expandStartingWith(String prefix) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            if (String.valueOf(item.getValue()).startsWith(prefix)) {
                item.setExpanded(true);
                return;
            }
        }
    }

    private void openKeyspace(String name) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<?> item = tree.getTreeItem(i);
            String text = String.valueOf(item.getValue());
            if (text.startsWith("TableNode") && text.contains("name=" + name)) {
                tree.getSelectionModel().select(i);
                javafx.event.Event.fireEvent(tree, new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, true, false, false, null));
                return;
            }
        }
        System.out.println("树上没找到键空间 " + name);
    }

    private void step(Runnable action) {
        at += 1.7;
        PauseTransition p = new PauseTransition(Duration.seconds(at));
        p.setOnFinished(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                System.out.println("！步骤失败：" + ex);
            }
        });
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
        launch(RedisWriteUiCheck.class, args);
        System.exit(0);
    }

    // ================================================================ 假 Redis

    /** 够本探针用的一台 Redis。读命令齐全，写命令够走通一次保存。 */
    static final class TinyRedis implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private volatile boolean running = true;

        TinyRedis() throws IOException {
            data.put("user:1001", "很长的一段值".repeat(40).getBytes(StandardCharsets.UTF_8));
            data.put("user:1002", "李四".getBytes(StandardCharsets.UTF_8));
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("name", "张三");
            hash.put("city", "杭州");
            data.put("user:profile", hash);
            data.put("user:1003", "王五".getBytes(StandardCharsets.UTF_8));
            data.put("order:1001", "订单".getBytes(StandardCharsets.UTF_8));
            data.put("session:abc", "token".getBytes(StandardCharsets.UTF_8));

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
                // 无所谓
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
                // 断了
            }
        }

        private byte[] handle(List<String> args) {
            String cmd = args.get(0).toUpperCase(Locale.ROOT);
            switch (cmd) {
                case "PING":
                    return raw("+PONG\r\n");
                case "SELECT":
                case "MULTI":
                case "EXEC":
                    return raw("+OK\r\n");
                case "INFO":
                    return bulk("# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n");
                case "CONFIG":
                    return raw("*2\r\n$9\r\ndatabases\r\n$2\r\n16\r\n");
                case "SCAN": {
                    // MATCH 必须真的实现：不实现的话，这台假服务端会把所有键都回回来，
                    // 而驱动正是靠服务端来筛的（它只在本地补一道前缀过滤）。
                    // 探针就会显示成「搜索没生效」，而真 Redis 上其实是好的
                    String pattern = "*";
                    for (int i = 2; i + 1 < args.size(); i++) {
                        if (args.get(i).equalsIgnoreCase("MATCH")) {
                            pattern = args.get(i + 1);
                        }
                    }
                    java.util.regex.Pattern re = globToRegex(pattern);
                    List<String> keys = new ArrayList<>();
                    for (String k : data.keySet()) {
                        if (re.matcher(k).matches()) {
                            keys.add(k);
                        }
                    }
                    StringBuilder sb = new StringBuilder("*2\r\n$1\r\n0\r\n*" + keys.size() + "\r\n");
                    keys.forEach(k -> sb.append('$')
                            .append(k.getBytes(StandardCharsets.UTF_8).length)
                            .append("\r\n").append(k).append("\r\n"));
                    return raw(sb.toString());
                }
                case "TYPE":
                    return raw("+" + typeOf(data.get(args.get(1))) + "\r\n");
                case "TTL":
                    return raw(":-1\r\n");
                case "EXISTS":
                    return raw(":" + (data.containsKey(args.get(1)) ? 1 : 0) + "\r\n");
                case "STRLEN":
                    return raw(":" + bytesOf(args.get(1)).length + "\r\n");
                case "HLEN":
                    return raw(":" + mapOf(args.get(1)).size() + "\r\n");
                case "GET":
                    return bulkBytes(bytesOf(args.get(1)));
                case "GETRANGE": {
                    byte[] v = bytesOf(args.get(1));
                    int to = Math.min(Integer.parseInt(args.get(3)), v.length - 1);
                    return bulkBytes(v.length == 0 ? v : java.util.Arrays.copyOfRange(v, 0, to + 1));
                }
                case "HGETALL":
                case "HSCAN": {
                    List<String> flat = new ArrayList<>();
                    mapOf(args.get(1)).forEach((k, v) -> {
                        flat.add(k);
                        flat.add(v);
                    });
                    byte[] arr = arrayOf(flat);
                    if (cmd.equals("HSCAN")) {
                        byte[] head = raw("*2\r\n$1\r\n0\r\n");
                        byte[] both = new byte[head.length + arr.length];
                        System.arraycopy(head, 0, both, 0, head.length);
                        System.arraycopy(arr, 0, both, head.length, arr.length);
                        return both;
                    }
                    return arr;
                }
                case "SET":
                    data.put(args.get(1), args.get(2).getBytes(StandardCharsets.UTF_8));
                    return raw("+OK\r\n");
                case "DEL": {
                    int n = 0;
                    for (int i = 1; i < args.size(); i++) {
                        if (data.remove(args.get(i)) != null) {
                            n++;
                        }
                    }
                    return raw(":" + n + "\r\n");
                }
                default:
                    return raw("-ERR unknown command '" + cmd + "'\r\n");
            }
        }

        /** 够用的 glob → 正则。字面字符一律 quote，免得点号变成任意字符。 */
        private static java.util.regex.Pattern globToRegex(String glob) {
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    re.append(".*");
                } else if (c == '?') {
                    re.append('.');
                } else if (c == '\\' && i + 1 < glob.length()) {
                    re.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(++i))));
                } else {
                    re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                }
            }
            return java.util.regex.Pattern.compile(re.toString(), java.util.regex.Pattern.DOTALL);
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> mapOf(String key) {
            Object v = data.get(key);
            return v instanceof Map ? (Map<String, String>) v : Map.of();
        }

        private byte[] bytesOf(String key) {
            Object v = data.get(key);
            return v instanceof byte[] b ? b : new byte[0];
        }

        private static String typeOf(Object v) {
            if (v instanceof byte[]) {
                return "string";
            }
            if (v instanceof Map) {
                return "hash";
            }
            return "none";
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
                in.read();
                int len = Integer.parseInt(line(in));
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
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

        private static byte[] bulkBytes(byte[] d) {
            byte[] head = raw("$" + d.length + "\r\n");
            byte[] out = new byte[head.length + d.length + 2];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(d, 0, out, head.length, d.length);
            out[out.length - 2] = '\r';
            out[out.length - 1] = '\n';
            return out;
        }

        private static byte[] arrayOf(List<String> items) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.writeBytes(raw("*" + items.size() + "\r\n"));
            items.forEach(i -> out.writeBytes(bulkBytes(i.getBytes(StandardCharsets.UTF_8))));
            return out.toByteArray();
        }
    }
}
