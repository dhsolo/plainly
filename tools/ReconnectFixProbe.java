import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「长时间断开之后连不上」这个 bug，改完之后到底好没好。
 *
 * <h2>复现的是哪一种断法</h2>
 * 用户描述的是「放了很久」，对应的是最难受的那一种：<b>对端不回也不断</b>
 * （笔记本睡眠、VPN 掉线、防火墙静默清理空闲连接）。手上那条连接彻底废了，
 * 但网络其实是通的——重新连是连得上的。
 *
 * <p>所以本地转发要做成：已经黑掉的那条继续黑着，<b>新连接照常放行</b>。
 * 全程黑洞的话，新连接也连不上，测出来的是另一回事。
 *
 * <h2>要证明的四件事</h2>
 * <ol>
 *   <li>{@code isConnected()} 仍然会说谎（它只看 isClosed）——这是已知的、
 *       有意保留的行为，因为它在渲染里每帧都跑，不能塞网络往返；</li>
 *   <li>因此<b>不能</b>拿它去禁用「连接」菜单项。修法是那一项永远可点；</li>
 *   <li>「重新连接」走的 {@code closeSession + openSession} 能真的换来一条新连接；</li>
 *   <li>整个过程<b>不阻塞</b>——closeSession 必须立刻返回，哪怕那条死连接
 *       的物理关闭要在后台卡上很久。</li>
 * </ol>
 *
 * <p><b>只读。</b>对真库只发 {@code SELECT 1}。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/ReconnectFixProbe.java [连接名片段]</pre>
 */
public class ReconnectFixProbe {

    private static int failures;

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

            Proxy proxy = new Proxy(real.host(), real.port());
            proxy.start();
            try {
                // 口令已经解出来了，openSession 里的 resolvePassword 会原样返回，
                // 不会再去注册表里按 id 找——所以这个临时 id 不需要真的存在
                ConnectionConfig cfg = real.copy()
                        .setId("reconnect-probe")
                        .setName("探针连接")
                        .setHost("127.0.0.1")
                        .setPort(proxy.localPort())
                        .withPassword(real.password());

                System.out.println("=== 一、正常连上 ===");
                DbSession first = ctx.openSession(cfg);
                System.out.println("  " + first.connection().serverVersion());
                System.out.println("  SELECT 1 -> " + first.connection().scalar("SELECT 1"));
                check("isConnected() = true", ctx.isConnected(cfg.id()));

                System.out.println();
                System.out.println("=== 二、连接在空闲中变成黑洞 ===");
                // 先把连接弄进「废掉」的状态：黑洞之后发一条语句，它会一直卡着，
                // 那个线程从此握着连接的锁不放——这正是真实现场的样子
                proxy.blackhole();
                Thread stuck = new Thread(() -> {
                    try {
                        first.connection().scalar("SELECT 1");
                    } catch (Throwable ignored) {
                        // 卡住或报错都行，这个线程只是用来把连接弄废
                    }
                });
                stuck.setDaemon(true);
                stuck.start();
                Thread.sleep(1500);
                proxy.healNewConnections();
                System.out.println("  旧连接已废（那条语句还卡着："
                        + stuck.isAlive() + "），新连接放行");

                System.out.println("  isConnected() = " + ctx.isConnected(cfg.id())
                        + "   ← 已知会说谎：它只看 isClosed()，不探活");
                System.out.println("  所以界面上「连接」那一项不能拿它来置灰，否则就是永远点不动");

                System.out.println();
                System.out.println("=== 三、走「重新连接」那条路 ===");
                long t = System.nanoTime();
                ctx.closeSession(cfg.id());
                long closeMs = ms(t);
                System.out.println("  closeSession 用时 " + closeMs + " ms");
                check("closeSession 立刻返回（不等物理关闭）", closeMs < 500);
                check("会话已经从表里摘掉", !ctx.isConnected(cfg.id()));

                t = System.nanoTime();
                DbSession second = ctx.openSession(cfg);
                long openMs = ms(t);
                System.out.println("  openSession 用时 " + openMs + " ms");
                check("拿到的是一条新连接", second != first);
                String one = second.connection().scalar("SELECT 1");
                System.out.println("  新连接 SELECT 1 -> " + one);
                check("新连接可用", "1".equals(one));
                check("isConnected() 恢复成 true", ctx.isConnected(cfg.id()));
                check("整个重连没有被那条卡死的语句拖住（" + (closeMs + openMs) + " ms）",
                        closeMs + openMs < 15_000);

                System.out.println();
                System.out.println("=== 四、旧的那条还卡着吗 ===");
                System.out.println("  卡住的线程仍然存活：" + stuck.isAlive()
                        + "  ← 谁也弄不醒它，但它是守护线程，碍不着任何人");

                ctx.closeSession(cfg.id());
            } finally {
                proxy.stop();
            }
        }

        System.out.println();
        System.out.println(failures == 0
                ? "全部通过：连接废掉之后，「重新连接」能换来一条可用的新连接，且不卡界面"
                : failures + " 项失败");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "[对] " : "[错] ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 见 ConnectionDeathProbe 里的同名类，这里只保留用得上的部分。 */
    private static final class Proxy {

        private final String host;
        private final int port;
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
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
        }

        private void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    // 不用 try-with-resources：退出时关流等于关 socket，
                    // 对端会收到 RST，黑洞就变成了「主动断开」，测的就不是这一路了
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (blackhole) {
                            park();
                            return;
                        }
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // 对端关了，这条泵结束
                }
            });
            t.setDaemon(true);
            t.start();
        }

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

        void blackhole() {
            blackhole = true;
        }

        /** 已经黑掉的继续黑着，新连接恢复正常——「网断了一阵又回来了」。 */
        void healNewConnections() {
            blackhole = false;
        }

        void stop() {
            stopped = true;
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 关不掉就算了
                }
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }
}
