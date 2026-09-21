package com.plainly.core.store;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 已保存的连接。
 *
 * <p>口令经 {@link CredentialStore} 加密后单独存放，
 * 取出的 {@link ConnectionConfig} 的 password 字段恒为 {@code null}——
 * 要用的时候显式调 {@link #resolvePassword} 换一个带口令的临时副本。
 * 这样口令就不会跟着配置对象在界面各处流转。
 */
public class ConnectionRegistry {

    private final LocalStore store;
    private final CredentialStore credentials;

    public ConnectionRegistry(LocalStore store, CredentialStore credentials) {
        this.store = store;
        this.credentials = credentials;
    }

    public CredentialStore credentials() {
        return credentials;
    }

    /**
     * 全部连接，按分组、再按原有顺序。
     *
     * <p>分组排在一起是必需的：树上一个分组就是一个目录，成员散落在列表各处时，
     * 目录出现的位置就取决于「谁排在最前」——那是一个用户看不见也控制不了的规则。
     * 没有分组的（{@code group_name} 为 NULL 或空）排在最后，仍留在树的顶层。
     *
     * <p>分组之间的先后是 SQLite 的 BINARY 排序，也就是按码点。中文分组名因此
     * 不按拼音排——「乙」在「甲」前面。这里不去纠正它：SQLite 默认不带 ICU，
     * 而分组之间谁先谁后并不影响找得到找不到。组内仍按用户自己的顺序。
     */
    public List<ConnectionConfig> listAll() {
        List<ConnectionConfig> list = new ArrayList<>();
        String sql = "SELECT * FROM connections"
                + " ORDER BY CASE WHEN group_name IS NULL OR group_name = '' THEN 1 ELSE 0 END,"
                + " group_name, sort_order, name";
        try (PreparedStatement ps = store.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(read(rs));
            }
        } catch (SQLException e) {
            throw new DbException("读取连接列表失败：" + e.getMessage(), e);
        }
        return list;
    }

    private ConnectionConfig read(ResultSet rs) throws SQLException {
        return new ConnectionConfig()
                .setId(rs.getString("id"))
                .setName(rs.getString("name"))
                .setType(parseType(rs.getString("db_type")))
                .setHost(nz(rs.getString("host")))
                .setPort(rs.getInt("port"))
                .setUser(nz(rs.getString("username")))
                .setDatabase(nz(rs.getString("database_name")))
                .setFilePath(nz(rs.getString("file_path")))
                .setTimezone(nz(rs.getString("timezone")))
                .setCharset(nz(rs.getString("charset")))
                .setColor(nz(rs.getString("color")))
                .setGroup(nz(rs.getString("group_name")))
                .setReadOnly(rs.getInt("read_only") != 0)
                .setSavePassword(rs.getInt("save_password") != 0);
        // 注意：password 刻意不填充
    }

    private static DbType parseType(String name) {
        try {
            return DbType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DbType.MYSQL;
        }
    }

    /**
     * 新增或更新。
     *
     * <h2>口令的三种情形</h2>
     * 混起来就会出「改个分组把口令弄丢了」或者「密码清不掉」这两类问题，所以写明白：
     * <ul>
     *   <li>{@code password == null} —— <b>这次不碰口令</b>，库里那份原样保留。
     *       改分组、改标记色、改只读都走这条路，它们手上的配置口令字段本来就是 null；</li>
     *   <li>{@code password} 非空 —— 换成这个新的；</li>
     *   <li>{@code password} 是<b>空串</b> —— 明确要清掉。用户把密码框清空了就是这个意思。</li>
     * </ul>
     * 第三种是后加的：在密码框会回显已存口令之后，「空」才第一次有了确切含义。
     * 在那之前框里永远是空的，空只能理解成「没改」。
     */
    public ConnectionConfig save(ConnectionConfig config) {
        if (config.id() == null || config.id().isBlank()) {
            config.setId(UUID.randomUUID().toString());
        }
        String encrypted = null;
        if (config.savePassword() && config.password() != null && !config.password().isEmpty()) {
            encrypted = credentials.protect(config.password());
        }

        String sql = """
                INSERT INTO connections
                  (id, name, db_type, host, port, username, password_enc, database_name,
                   file_path, timezone, charset, color, group_name, read_only, save_password,
                   sort_order)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, COALESCE((SELECT sort_order FROM connections WHERE id = ?), 0))
                ON CONFLICT(id) DO UPDATE SET
                  name=excluded.name, db_type=excluded.db_type, host=excluded.host,
                  port=excluded.port, username=excluded.username,
                  password_enc=COALESCE(excluded.password_enc, connections.password_enc),
                  database_name=excluded.database_name, file_path=excluded.file_path,
                  timezone=excluded.timezone, charset=excluded.charset, color=excluded.color,
                  group_name=excluded.group_name,
                  read_only=excluded.read_only, save_password=excluded.save_password
                """;
        try (PreparedStatement ps = store.connection().prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, config.id());
            ps.setString(i++, config.name());
            ps.setString(i++, config.type().name());
            ps.setString(i++, config.host());
            ps.setInt(i++, config.port());
            ps.setString(i++, config.user());
            ps.setString(i++, encrypted);
            ps.setString(i++, config.database());
            ps.setString(i++, config.filePath());
            ps.setString(i++, config.timezone());
            ps.setString(i++, config.charset());
            ps.setString(i++, config.color());
            ps.setString(i++, config.group());
            ps.setInt(i++, config.readOnly() ? 1 : 0);
            ps.setInt(i++, config.savePassword() ? 1 : 0);
            ps.setString(i, config.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("保存连接失败：" + e.getMessage(), e);
        }

        // 不勾「保存密码」，或者明确把口令清成了空串——两种都要把旧密文抹掉。
        // 上面那条 UPSERT 用的是 COALESCE(新, 旧)，它只会保留旧值，清不掉
        if (!config.savePassword() || "".equals(config.password())) {
            clearPassword(config.id());
        }
        return config;
    }

    private void clearPassword(String id) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("UPDATE connections SET password_enc = NULL WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("清除口令失败：" + e.getMessage(), e);
        }
    }

    public void delete(String id) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("DELETE FROM connections WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除连接失败：" + e.getMessage(), e);
        }
    }

    /**
     * 返回一个带上明文口令的临时副本，仅用于建立连接。
     * 解不开（换了机器或换了 Windows 用户）时口令为空，由界面提示重新输入。
     */
    public ConnectionConfig resolvePassword(ConnectionConfig config) {
        if (config.password() != null) {
            return config;
        }
        String encrypted = null;
        try (PreparedStatement ps = store.connection()
                .prepareStatement("SELECT password_enc FROM connections WHERE id = ?")) {
            ps.setString(1, config.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    encrypted = rs.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new DbException("读取口令失败：" + e.getMessage(), e);
        }
        String plain = encrypted == null ? "" : credentials.unprotect(encrypted);
        return config.withPassword(plain == null ? "" : plain);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
