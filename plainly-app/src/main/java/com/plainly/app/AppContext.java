package com.plainly.app;

import com.plainly.core.query.QueryService;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.HistoryStore;
import com.plainly.core.store.LocalStore;
import com.plainly.core.store.Retention;
import com.plainly.core.store.TabStore;
import com.plainly.core.store.UiState;
import com.plainly.core.task.TaskStore;
import com.plainly.core.task.TaskScheduler;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.core.db.Connections;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** 应用级共享状态：本机配置库、连接注册表、后台执行器、已打开的会话。 */
public class AppContext implements AutoCloseable {

    private final LocalStore localStore;
    private final CredentialStore credentialStore;
    private final ConnectionRegistry registry;
    private final QueryService queryService;

    /**
     * 连接 id → 已建立的会话。一条保存的连接同时最多一个活动会话。
     *
     * <h2>为什么必须是并发映射</h2>
     * {@link #openSession} 的文档写得很清楚：<b>要在后台线程上调用</b>——探活和建连
     * 都可能卡住，放在界面线程上会冻住整个窗口。于是这张表天天被后台线程写。
     * 而界面线程同时在读它、遍历它（状态栏、退出前检查未提交事务、关闭全部连接）。
     *
     * <p>普通 HashMap 在这种并发下会坏掉——不是抛异常那种坏法，是安静地丢条目
     * 或者死循环。遍历时撞上一次写入则是 {@code ConcurrentModificationException}，
     * 表现为「启动时莫名其妙报个异常」，而它跟用户当时在做什么毫无关系。
     * {@code DbSession} 里那两张元数据缓存早就是并发映射了，理由一模一样。
     *
     * <p>顺序在这里没有意义（按 id 查的表），所以放弃 LinkedHashMap 不损失什么。
     */
    private final Map<String, DbSession> sessions = new ConcurrentHashMap<>();

    private final TaskStore taskStore;
    private final HistoryStore historyStore;
    private final TabStore tabStore;
    private final UiState uiState;
    private final TaskScheduler scheduler;
    private final com.plainly.core.store.VirtualKeyStore virtualKeys;
    private final com.plainly.core.store.SnippetStore snippets;

    public AppContext() {
        this.localStore = new LocalStore();
        this.credentialStore = CredentialStore.forCurrentPlatform();
        this.registry = new ConnectionRegistry(localStore, credentialStore);
        this.queryService = new QueryService();
        this.taskStore = new TaskStore(localStore);
        this.historyStore = new HistoryStore(localStore);
        this.tabStore = new TabStore(localStore);
        this.uiState = new UiState(localStore);
        this.virtualKeys = new com.plainly.core.store.VirtualKeyStore(localStore);
        this.snippets = new com.plainly.core.store.SnippetStore(localStore);
        // 进程内调度：界面上那句「应用关掉就不会触发」说的就是它
        this.scheduler = new TaskScheduler(taskStore, registry, message -> { });
        this.scheduler.start();

        // 启动时清一次：只靠写入时摊销的话，一台长期不用的机器上那些过期记录
        // 永远等不到下一次写入，也就永远清不掉
        queryService.submit(() -> Retention.sweep(localStore));
    }

    public TaskStore taskStore() {
        return taskStore;
    }

    /** 本机标注的虚拟外键。目标库上没有这些约束，它们只影响 ER 图和外键栏的显示。 */
    public com.plainly.core.store.VirtualKeyStore virtualKeys() {
        return virtualKeys;
    }

    /** SQL 代码片段。 */
    public com.plainly.core.store.SnippetStore snippets() {
        return snippets;
    }

    public HistoryStore historyStore() {
        return historyStore;
    }

    public TabStore tabStore() {
        return tabStore;
    }

    /** 界面偏好：窗口几何、侧栏宽度、上次用过的目录。 */
    public UiState uiState() {
        return uiState;
    }

    public ConnectionRegistry registry() {
        return registry;
    }

    public CredentialStore credentialStore() {
        return credentialStore;
    }

    public QueryService queryService() {
        return queryService;
    }

    public LocalStore localStore() {
        return localStore;
    }

    /**
     * 取已有会话，没有就现建一个。口令在这一刻才解密。
     *
     * <p>复用之前会<b>真去探一次活</b>，而不是只看 {@code isClosed()}。
     * 后者只知道「本地关过没有」，服务端把会话掐掉（wait_timeout 到点、DBA KILL、
     * 防火墙清理空闲连接）它察觉不到——于是这里会把一条已经死了的连接交出去，
     * 用户看到的是「点连接没反应」或者一条莫名其妙的报错。
     *
     * <p>探活有一次网络往返的代价，所以只放在这里：开会话是明确的用户动作，
     * 不是每帧都跑的东西。
     *
     * <p><b>要在后台线程上调用。</b>连接断了的时候，这一下最多要等
     * {@code isAlive()} 的超时。
     */
    public DbSession openSession(ConnectionConfig config) {
        DbSession existing = sessions.get(config.id());
        /*
         * 正在跑语句的连接<b>不去探活</b>，直接复用。
         *
         * 这不是省一次往返，是避免一次死等：isValid() 要先拿到连接对象的锁，
         * 而那把锁正握在执行语句的那个线程手里（线程转储确认过：查询线程
         * 「locked ConnectionImpl」卡在 socket 读上，探活线程「waiting to lock」
         * 同一个对象）。也就是说——
         *
         *   · 语句只是慢：探活会一直等到它跑完，用户展开个树要等几分钟；
         *   · 语句卡死了：探活跟着一起卡死，而 isValid 的超时参数
         *     压根没轮到生效，它还没走到网络那一层。
         *
         * 两种情况下探活都给不出有用的答案，所以干脆不问。真的卡死时，
         * 界面那一侧的加载超时会兜住，并提示用户用「重新连接」——
         * 那条路会先把会话从表里摘掉，于是这里根本碰不到这条死连接。
         */
        if (existing != null && (existing.connection().isBusy()
                || existing.connection().isAlive())) {
            return existing;
        }
        if (existing != null) {
            // 死的不能留着：留着的话下一次 isClosed() 仍然说「还开着」，
            // 于是又把它交出去，循环往复
            closeSession(config.id());
        }
        ConnectionConfig withPassword = registry.resolvePassword(config);
        DbConnection conn = Connections.open(withPassword);
        DbSession session = new DbSession(config, conn);
        /*
         * 用 putIfAbsent 而不是 put：两个后台线程可能同时为同一条连接走到这儿
         * （展开树的同时另一个对话框也在取库列表）。直接 put 的话，后到的那条会把
         * 先到的顶掉，而被顶掉的那个连接<b>再也没人关得掉</b>——它会一直占着
         * 服务端的一个会话，直到进程退出。
         */
        DbSession raced = sessions.putIfAbsent(config.id(), session);
        if (raced != null) {
            closeQuietly(session);
            return raced;
        }
        return session;
    }

    /**
     * 手上还开着未提交事务的那些连接的名字。
     *
     * <p>退出前和断开前都要问一遍：未提交的事务在连接关闭时会被服务端回滚，
     * 那批改动就没了。用户多半以为已经存进去了——他按过「保存」，
     * 网格也确实刷新了，只是那一切都还在事务里。
     */
    public java.util.List<String> connectionsWithPendingTransaction() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (DbSession s : sessions.values()) {
            try {
                if (s.hasPendingTransaction()) {
                    names.add(s.config().name());
                }
            } catch (RuntimeException ignored) {
                // 连接已经不可用时问不出来，那就没有什么可丢的了
            }
        }
        return names;
    }

    /** 随便一个已连接的会话。没有则返回 null。 */
    public DbSession anyConnected() {
        return sessions.values().stream().findFirst().orElse(null);
    }

    public DbSession sessionFor(String connectionId) {
        return sessions.get(connectionId);
    }

    /**
     * 界面上「这条连接是不是连着的」。
     *
     * <p>刻意<b>只</b>看 {@code isClosed()}，不探活：这个方法在单元格渲染里被调用，
     * 每滚动一行就要跑一次，塞进一次网络往返会让整棵树卡死。
     *
     * <p>代价是它可能说谎——服务端把会话掐了，这里仍然回 true。所以：
     * <ul>
     *   <li>凡是<b>要用</b>这条连接的地方，走 {@link #openSession}，那边会探活；</li>
     *   <li>凡是拿这个结果<b>禁用</b>某个操作的地方，都得想一想：说谎的那一刻，
     *       用户是不是就被挡在门外了。树上的「连接」菜单项就吃过这个亏——
     *       它曾经在「已连接」时置灰，而连接早就死了，于是那一项永远点不动。</li>
     * </ul>
     */
    public boolean isConnected(String connectionId) {
        DbSession s = sessions.get(connectionId);
        return s != null && !s.connection().isClosed();
    }

    /**
     * 后台探一次活，发现死了就把会话丢掉，然后回调。
     *
     * <p>给「操作失败了，但不确定是连接断了还是别的原因」的场合用：
     * 权限不足不该把会话丢掉，连接断了则必须丢——丢掉之后树上那个「在线」
     * 才会消失，用户才知道该重连。
     *
     * @param onDropped 确实丢掉了会话时调用；在调用方给的线程语义之外，
     *                  由调用方自己切回 UI 线程
     */
    public void dropIfDead(String connectionId, Runnable onDropped) {
        DbSession s = sessions.get(connectionId);
        if (s == null) {
            return;
        }
        queryService.runAsync(() -> {
            if (!s.connection().isAlive()) {
                closeSession(connectionId);
                onDropped.run();
            }
        });
    }

    /**
     * 断开一条会话。
     *
     * <p>分两步，而且顺序要紧：<b>先</b>从表里摘掉（同步、立即），
     * <b>再</b>去关物理连接（后台）。
     *
     * <p>为什么物理关闭要放后台：连接断在「对端不回也不断」的状态时
     * （网络中断、防火墙静默丢包），{@code close()} 自己也会卡住——
     * 它要往一个没人接的 socket 上发一个 COM_QUIT，那个写操作没有超时。
     * 这个方法是从界面线程调的（右键「断开」、「重新连接」），
     * 在那儿卡住就是整个窗口僵死。
     *
     * <p>摘掉引用这一步是同步的，所以界面立刻就认为「没连接了」，
     * 后台那条连接慢慢死掉不影响任何人。
     */
    public void closeSession(String connectionId) {
        DbSession s = sessions.remove(connectionId);
        if (s == null) {
            return;
        }
        queryService.runAsync(() -> closeQuietly(s));
    }

    private static void closeQuietly(DbSession session) {
        try {
            session.connection().close();
        } catch (RuntimeException ignored) {
            // 断开时的异常不值得打扰用户；连接本来就已经不可用了
        }
    }

    /**
     * 退出时等着关连接的上限。
     *
     * <p>关连接只是礼貌：进程马上就没了，服务端那边的会话跟着断。
     * 但它不能拖着退出——断在黑洞状态的连接，{@code close()} 会一直卡着，
     * 用户看到的是「点了关闭窗口，程序不退」。
     */
    private static final long SHUTDOWN_CLOSE_MILLIS = 2000;

    @Override
    public void close() {
        scheduler.close();

        java.util.List<DbSession> open = new java.util.ArrayList<>(sessions.values());
        sessions.clear();
        // 单开一条线程去关，主线程最多等它两秒。
        // 用守护线程：过了这两秒就不管了，它不会拦着 JVM 退出
        Thread closer = new Thread(() -> open.forEach(AppContext::closeQuietly),
                "plainly-shutdown-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(SHUTDOWN_CLOSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        queryService.close();
        localStore.close();
    }
}
