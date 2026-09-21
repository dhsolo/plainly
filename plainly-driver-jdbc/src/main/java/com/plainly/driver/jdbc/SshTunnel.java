package com.plainly.driver.jdbc;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.plainly.driver.DbException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * SSH 隧道。
 *
 * <p>跳板机后面的库是日常配置，不是边角情形：很多生产库根本不对外开端口。
 *
 * <p>做法是本地端口转发——在本机挑一个空闲端口，JDBC 连它，流量经 SSH 转到目标库。
 * 所以隧道必须先于 JDBC 建立，且在连接活着的整段时间里都不能断。
 *
 * <p>刻意不做「自动接受主机密钥」。那等于把中间人攻击的防线整个撤掉，
 * 而 SSH 的全部安全性就建立在这道验证上。第一次连接时由用户确认指纹。
 */
public class SshTunnel implements AutoCloseable {

    /** 隧道配置。 */
    public record Config(String host, int port, String user, String password,
                         String privateKeyPath, String passphrase,
                         String targetHost, int targetPort, boolean acceptUnknownHost) {
    }

    private final Session session;
    private final int localPort;

    private SshTunnel(Session session, int localPort) {
        this.session = session;
        this.localPort = localPort;
    }

    /** JDBC 该连的本地端口。 */
    public int localPort() {
        return localPort;
    }

    /**
     * 建立隧道。
     *
     * @return 一个已经打通的隧道；调用方负责在连接关闭时 close 它
     */
    public static SshTunnel open(Config config) {
        try {
            JSch jsch = new JSch();
            if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
                if (config.passphrase() != null && !config.passphrase().isEmpty()) {
                    jsch.addIdentity(config.privateKeyPath(), config.passphrase());
                } else {
                    jsch.addIdentity(config.privateKeyPath());
                }
            }

            Session session = jsch.getSession(config.user(), config.host(),
                    config.port() <= 0 ? 22 : config.port());
            if (config.password() != null && !config.password().isEmpty()) {
                session.setPassword(config.password());
            }

            Properties props = new Properties();
            // StrictHostKeyChecking 关掉就等于放弃了 SSH 的全部安全保证，
            // 所以它只在用户明确勾选之后才为 no
            props.put("StrictHostKeyChecking", config.acceptUnknownHost() ? "no" : "ask");
            props.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
            session.setConfig(props);
            session.setServerAliveInterval(30_000);

            session.connect(15_000);
            // 本地端口传 0：让操作系统挑一个空闲的，免得撞上别的程序
            int localPort = session.setPortForwardingL(0,
                    config.targetHost(), config.targetPort());
            return new SshTunnel(session, localPort);
        } catch (JSchException e) {
            throw new DbException("SSH 隧道建立失败：" + e.getMessage(), e);
        }
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    /** 从连接配置里读隧道参数。没配就返回 null。 */
    public static Config configFrom(Map<String, String> extras, String targetHost,
                                    int targetPort) {
        if (extras == null || !"true".equalsIgnoreCase(extras.get("ssh.enabled"))) {
            return null;
        }
        return new Config(
                extras.getOrDefault("ssh.host", ""),
                parse(extras.get("ssh.port"), 22),
                extras.getOrDefault("ssh.user", ""),
                extras.get("ssh.password"),
                extras.get("ssh.keyPath"),
                extras.get("ssh.passphrase"),
                targetHost, targetPort,
                "true".equalsIgnoreCase(extras.get("ssh.acceptUnknownHost")));
    }

    /** 把隧道参数写回 extras，供保存用。 */
    public static Map<String, String> toExtras(boolean enabled, String host, int port,
                                               String user, String password, String keyPath,
                                               boolean acceptUnknownHost) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ssh.enabled", String.valueOf(enabled));
        out.put("ssh.host", host == null ? "" : host);
        out.put("ssh.port", String.valueOf(port <= 0 ? 22 : port));
        out.put("ssh.user", user == null ? "" : user);
        if (password != null && !password.isEmpty()) {
            out.put("ssh.password", password);
        }
        out.put("ssh.keyPath", keyPath == null ? "" : keyPath);
        out.put("ssh.acceptUnknownHost", String.valueOf(acceptUnknownHost));
        return out;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
