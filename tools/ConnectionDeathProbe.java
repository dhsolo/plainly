import com.plainly.app.AppContext;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 连接在空闲中被掐断之后，程序看到的是什么。
 *
 * <h2>要回答的问题</h2>
 * 用户报的是「长时间断开连接之后，右键『连接』没反应」。可能的原因有好几个，
 * 但它们要求完全不同的修法，所以得先分清楚：
 * <ol>
 *   <li>{@code Connection.isClosed()} 认不认得出对端已经没了？
 *       认不出的话，界面会一直以为自己连着——「连接」那一项就是灰的，
 *       点它当然没反应；</li>
 *   <li>{@code isValid()} 认得出吗？认得出的话就有一个便宜的判据；</li>
 *   <li>在死掉的连接上发一条语句，是<b>立刻报错</b>还是<b>一直卡着</b>？
 *       卡着的话，界面上那一行会永远停在「连接中…」，而且
 *       {@code deferExpand} 的去重集合里那一项永远不会被移除——
 *       此后每一次展开都会被当成「已经在路上了」直接忽略。</li>
 * </ol>
 *
 * <h2>怎么造出这个场景</h2>
 * 不去动用户的服务器。本地起一个转发端口，JDBC 连它、它连真库；
 * 连上之后把转发整个关掉，等价于「会话在空闲期间被服务端掐掉」。
 * 两种断法都试：
 * <ul>
 *   <li><b>RST</b>——socket 直接关掉，对端立刻收到复位。服务端主动 KILL 或重启是这样；</li>
 *   <li><b>黑洞</b>——不再转发也不关闭，什么都不回。网络中断、防火墙丢包是这样，
 *       而这一种最难受：没有任何信号，只能等 TCP 自己超时。</li>
 * </ul>
 *
 * <h2>探活并不总是救得了场</h2>
 * 线程转储证实：语句卡在 socket 读上时，它<b>握着连接对象的锁</b>
 * （{@code locked ConnectionImpl}），而 {@code isValid()} 要先拿同一把锁
 * （{@code waiting to lock}）。于是探活会跟着一起卡死，它那个超时参数
 * 压根没走到网络那一层。
 *
 * <p>结论：探活只能用来判断「闲着的连接还通不通」。已经卡住的连接救不回来，
 * 只能把引用丢掉、另建一条——这也是「重新连接」必须先 closeSession 的原因。
 *
 * <p><b>只读。</b>对真库只发 {@code SELECT 1} 和读元数据。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ConnectionDeathProbe.java</pre>
 */
public class ConnectionDeathProbe {

    /** 黑洞那一轮最多等多久。真的没有超时的话，等下去也只是白等。 */
    private static final long BLACKHOLE_WAIT_MS = 90_000;

    public static void main(String[] args) throws Exception {
        try (AppContext ctx = new AppContext()) {
            ConnectionConfig saved = ctx.registry().listAll().stream()
                    .filter(c -> c.type() == DbType.MYSQL)
                    .filter(c -> args.length == 0 || c.name().contains(args[0]))
                    .findFirst().orElse(null);
            if (saved == null) {
                System.out.println("没有可用的 MySQL 连接");
                return;
            }
            ConnectionConfig real = ctx.registry().resolvePassword(saved);
            System.out.println("目标 " + real.host() + ":" + real.port());

            run(real, Break.RESET);
            run(real, Break.BLACKHOLE);
            isValidUnderBlackhole(real);
        }
    }

    private enum Break {
        /** 把 socket 关掉：对端立刻收到 RST。 */
        RESET("对端直接断开（RST）"),
        /** 不转发也不关闭：对端收不到任何东西。 */
        BLACKHOLE("对端变成黑洞（不回也不断）");

        final String label;

        Break(String label) {
            this.label = label;
        }
    }

    private static void run(ConnectionConfig real, Break how) throws Exception {
        System.out.println();
        System.out.println("=== " + how.label + " ===");

        Proxy proxy = new Proxy(real.host(), real.port());
        proxy.start();
        ConnectionConfig viaProxy = real.copy()
                .setHost("127.0.0.1")
                .setPort(proxy.localPort())
                .withPassword(real.password());

        DbConnection conn = Connections.open(viaProxy);
        try {
            System.out.println("  连上了：" + conn.serverVersion());
            System.out.println("  断开前 SELECT 1 -> " + conn.scalar("SELECT 1"));

            if (how == Break.RESET) {
                proxy.hardClose();
            } else {
                proxy.blackhole();
            }
            System.out.println("  已" + how.label);

            // 一、程序判断「还连着吗」用的就是这两个
            System.out.println("  isClosed()        = " + conn.isClosed()
                    + "   ← 界面就是拿它判断「连接」那一项要不要灰掉");

            // 二、真发一条语句会怎样。
            //
            // 顺序有讲究：isValid() 探测失败之后，MySQL 驱动会把这条连接
            // 标记成已关闭，此后任何语句都立刻抛「connection closed」。
            // 先探再发，就永远测不出「发语句会不会卡住」——而那正是要测的东西。
            System.out.println("  发一条 SELECT 1…");
            long t = System.nanoTime();
            Thread worker = new Thread(() -> {
                try {
                    String v = conn.scalar("SELECT 1");
                    System.out.println("    居然回来了：" + v);
                } catch (Throwable e) {
                    System.out.println("    抛出 " + root(e));
                }
            });
            worker.setDaemon(true);
            worker.start();
            worker.join(BLACKHOLE_WAIT_MS);
            if (worker.isAlive()) {
                System.out.println("    !! 等了 " + (BLACKHOLE_WAIT_MS / 1000)
                        + " 秒还没回来——这条语句会一直卡着");
                System.out.println("    这正是「右键连接没反应」的成因：那一行永远停在"
                        + "「连接中…」，而去重集合里的这一项永远不会被移除，"
                        + "之后每次展开都被当成「已经在路上了」直接忽略");
            } else {
                System.out.println("    " + ms(t) + " ms 之后有了结果");
            }

            // 三、语句失败之后，再看这两个判据。
            //
            // 语句还卡着的话，这里**不能**去探活：执行语句的线程握着连接对象的锁，
            // isValid() 会一直等那把锁，连它自己的超时参数都轮不到生效。
            // 第一版没这个判断，探针自己在这儿挂了十三分钟——
            // 而那次意外恰好证明了这条，见类注释。
            System.out.println("  事后 isClosed()   = " + conn.isClosed());
            if (worker.isAlive()) {
                System.out.println("  事后 isValid      = 不测：语句还卡着，"
                        + "它握着连接的锁，探活会跟着一起卡死");
            } else {
                long t3 = System.nanoTime();
                System.out.println("  事后 isValid(2)   = " + validity(conn, 2)
                        + "   （耗时 " + ms(t3) + " ms）");
            }
        } finally {
            closeBounded(conn);
            proxy.stop();
        }
    }

    /**
     * 黑洞下 {@code isValid(n)} 守不守自己那个超时。
     *
     * <p>这一条单开一轮、单开一条连接，因为它和上面那轮互斥：探活失败之后驱动会把
     * 连接标记成已关闭，之后再发语句就立刻报错，测不出「会不会卡住」；
     * 反过来先发语句又会把连接弄死，测不出探活的真实耗时。
     *
     * <p>为什么非验不可：修法就是「复用连接之前先探一次活」。要是探活本身
     * 在黑洞下也会永远挂着，那这个修法只是把卡住的位置挪了个地方。
     */
    private static void isValidUnderBlackhole(ConnectionConfig real) throws Exception {
        System.out.println();
        System.out.println("=== 黑洞下 isValid(3) 会不会守住超时 ===");
        Proxy proxy = new Proxy(real.host(), real.port());
        proxy.start();
        ConnectionConfig viaProxy = real.copy()
                .setHost("127.0.0.1").setPort(proxy.localPort())
                .withPassword(real.password());
        DbConnection conn = Connections.open(viaProxy);
        try {
            conn.scalar("SELECT 1");
            proxy.blackhole();
            System.out.println("  已变成黑洞，现在探活…");

            long t = System.nanoTime();
            boolean[] result = new boolean[1];
            boolean[] done = new boolean[1];
            Thread worker = new Thread(() -> {
                result[0] = validity(conn, 3);
                done[0] = true;
            });
            worker.setDaemon(true);
            worker.start();
            worker.join(20_000);

            if (done[0]) {
                System.out.println("  isValid(3) = " + result[0]
                        + "，耗时 " + ms(t) + " ms  ← 守住了超时，可以拿它当判据");
            } else {
                System.out.println("  !! isValid(3) 等了 20 秒还没回来——它不守超时，"
                        + "光靠探活救不了这个场景，界面那一侧必须自己加超时");
            }
        } finally {
            closeBounded(conn);
            proxy.stop();
        }
    }

    /**
     * 有上限地关一条连接。
     *
     * <p>{@code close()} 在卡死的连接上<b>同样会阻塞</b>：它要拿连接对象的锁，
     * 而那把锁正握在卡住的查询线程手里（线程转储确认：close 处于
     * {@code BLOCKED, waiting to lock}）。探针第一版直接在 finally 里 close，
     * 于是自己挂了八分钟，一个字都没打出来。
     *
     * <p>产品里 {@code AppContext.closeSession} 是同样的处理：先摘掉引用，
     * 物理关闭丢给后台，绝不在调用方线程上等它。
     */
    private static void closeBounded(DbConnection conn) {
        Thread closer = new Thread(() -> {
            try {
                conn.close();
            } catch (Throwable ignored) {
                // 死连接关不掉很正常
            }
        });
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closer.isAlive()) {
            System.out.println("  （close() 也卡住了，交给守护线程，不等它）");
        }
    }

    private static boolean validity(DbConnection conn, int seconds) {
        try {
            java.lang.reflect.Field f = conn.getClass().getDeclaredField("conn");
            f.setAccessible(true);
            return ((java.sql.Connection) f.get(conn)).isValid(seconds);
        } catch (Throwable e) {
            return false;
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String root(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String m = cur.getMessage() == null ? "" : cur.getMessage().replaceAll("\\s+", " ").trim();
        return cur.getClass().getSimpleName() + ": " + (m.length() > 110 ? m.substring(0, 107) + "…" : m);
    }

    /** 一个最简单的 TCP 转发，可以按需要变成黑洞或者直接断开。 */
    private static final class Proxy {

        private final String host;
        private final int port;
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<Thread> pumps = new ArrayList<>();
        private volatile boolean blackhole;
        private volatile boolean stopped;

        Proxy(String host, int port) throws IOException {
            this.host = host;
            this.port = port;
            this.server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        }

        int localPort() {
            return server.getLocalPort();
        }

        void start() {
            Thread accept = new Thread(() -> {
                while (!stopped) {
                    try {
                        Socket in = server.accept();
                        Socket out = new Socket();
                        out.connect(new InetSocketAddress(host, port), 10_000);
                        sockets.add(in);
                        sockets.add(out);
                        pump(in, out);
                        pump(out, in);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            accept.setDaemon(true);
            accept.start();
            pumps.add(accept);
        }

        private void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    // 这里刻意<b>不</b>用 try-with-resources：它会在线程退出时把流关掉，
                    // 而关流就等于关 socket，对端立刻收到 RST——那就变回「直接断开」了，
                    // 黑洞这一路根本没被测到。第一版就是这么写的，两轮结果一模一样，
                    // 正是那个一模一样暴露了问题
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (blackhole) {
                            // 字节收下，不转发，也不关任何一端：
                            // 两边的 socket 都还开着，谁也收不到任何信号
                            park();
                            return;
                        }
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // 对端关了，这条泵就结束
                }
            });
            t.setDaemon(true);
            t.start();
            pumps.add(t);
        }

        /** 挂住不动，直到探针收摊。socket 保持打开，这才是黑洞。 */
        private void park() {
            while (!stopped) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** 直接断开：对端会收到 RST 或者读到 EOF。 */
        void hardClose() {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 关不掉就算了
                }
            }
        }

        /** 变成黑洞：既不转发也不关闭，对端什么信号都收不到。 */
        void blackhole() {
            blackhole = true;
        }

        /**
         * 已经黑掉的那条继续黑着，<b>新连接</b>恢复正常转发。
         *
         * <p>用来复现「网断了一阵又回来了」：手上那条连接已经废了，
         * 但重新连是连得上的。验证「重新连接」能不能救回来，就得是这个形状——
         * 全程黑洞的话，新连接也连不上，测出来的是另一回事。
         */
        void healNewConnections() {
            blackhole = false;
        }

        void stop() {
            stopped = true;
            hardClose();
            try {
                server.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }
}
