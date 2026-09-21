import com.plainly.core.db.Connections;
import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 导出到底写出了什么——把文件读回来看。
 *
 * <p>这是唯一能证明「导的是完整值」的办法：对话框上写着什么不算数，
 * 网格里显示什么也不算数，落到磁盘上的那些字节才算。
 *
 * <p>用一台本进程的假 Redis，里面放几个专门用来暴露问题的键：
 * 一个 500 字节的字符串（预览只取 200）、一个哈希（预览是给人看的摘要）、
 * 一个值里带逗号和引号的键（CSV 转义）。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/RedisExportCheck.java</pre>
 */
public class RedisExportCheck {

    private static final Path OUT = Paths.get(
            "C:/Users/root/AppData/Local/Temp/claude/D--dh/"
            + "f23050bd-2c01-43ba-af2d-0c1cf3d6fac6/scratchpad/redis-export.csv");

    public static void main(String[] args) throws Exception {
        try (TinyRedis server = new TinyRedis()) {
            ConnectionConfig config = new ConnectionConfig()
                    .setName("export-check")
                    .setType(DbType.REDIS)
                    .setHost("127.0.0.1")
                    .setPort(server.port())
                    .setDatabase("db0");

            try (DbConnection conn = Connections.open(config)) {
                System.out.println("网格看到的（预览）：");
                dump(conn.executeQuery(new com.plainly.driver.SqlDialect.PreparedSql(
                        conn.dialect().selectPage("db0", "k", com.plainly.driver.query.FilterSpec
                                .empty(), List.of(), 100, 0).sql(), List.of()), List.of(), 100));

                System.out.println("\n导出写出的（完整）：");
                RowSource source = RowSource.ofTable(conn, "db0", "k", null, 1000, -1);
                ExportOptions options = new ExportOptions()
                        .setFormat(ExportOptions.Format.CSV)
                        .setIncludeHeader(true)
                        .setTableName("k")
                        .setTarget(OUT);
                long written = Exporters.export(source, options, n -> { });
                System.out.println("写出 " + written + " 行 → " + OUT);
                for (String line : Files.readAllLines(OUT, StandardCharsets.UTF_8)) {
                    System.out.println("   " + (line.length() > 240
                            ? line.substring(0, 240) + "…（本行共 " + line.length() + " 字符）"
                            : line));
                }
            }
        }
    }

    private static void dump(com.plainly.driver.QueryResult r) {
        System.out.println("   列：" + r.columns().stream().map(c -> c.label()).toList());
        for (com.plainly.driver.Row row : r.rows()) {
            String value = row.get(4) == null ? "" : row.get(4);
            System.out.println("   " + row.get(0) + "  [" + row.get(1) + "]  "
                    + (value.length() > 60 ? value.substring(0, 60) + "…（" + value.length()
                            + " 字符）" : value));
        }
    }

    // ================================================================ 假 Redis

    static final class TinyRedis implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private volatile boolean running = true;

        TinyRedis() throws IOException {
            data.put("k:long", "A".repeat(500).getBytes(StandardCharsets.UTF_8));
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("name", "张三");
            hash.put("note", "带,逗号 和\"引号\"");
            data.put("k:hash", hash);
            data.put("k:list", new ArrayList<>(List.of("a", "b", "c")));
            data.put("k:num", "9223372036854775807".getBytes(StandardCharsets.UTF_8));

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
                    return raw("+OK\r\n");
                case "INFO":
                    return bulk("# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n");
                case "CONFIG":
                    return raw("*2\r\n$9\r\ndatabases\r\n$2\r\n16\r\n");
                case "SCAN": {
                    List<String> keys = new ArrayList<>(data.keySet());
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
                case "STRLEN":
                    return raw(":" + bytesOf(args.get(1)).length + "\r\n");
                case "HLEN":
                    return raw(":" + mapOf(args.get(1)).size() + "\r\n");
                case "LLEN":
                    return raw(":" + listOf(args.get(1)).size() + "\r\n");
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
                case "LRANGE": {
                    List<String> all = listOf(args.get(1));
                    int to = Integer.parseInt(args.get(3));
                    int end = to < 0 ? all.size() + to : Math.min(to, all.size() - 1);
                    return arrayOf(all.subList(0, Math.max(0, end + 1)));
                }
                default:
                    return raw("-ERR unknown command '" + cmd + "'\r\n");
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> mapOf(String key) {
            Object v = data.get(key);
            return v instanceof Map ? (Map<String, String>) v : Map.of();
        }

        @SuppressWarnings("unchecked")
        private List<String> listOf(String key) {
            Object v = data.get(key);
            return v instanceof List ? (List<String>) v : List.of();
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
            if (v instanceof List) {
                return "list";
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
