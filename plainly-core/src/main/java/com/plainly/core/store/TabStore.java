package com.plainly.core.store;

import com.plainly.driver.DbException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 标签页的保存、收藏，以及上次退出时的现场。
 *
 * <p>两件事共用一个类，因为它们存的是同一种东西——「一个标签页是什么」：
 * 哪条连接、哪个库、哪张表或哪段 SQL。区别只在于谁写的：
 * 保存和收藏是用户主动按下的，现场是退出时自动记的。
 *
 * <p>现场那张表整表覆写。它是快照不是流水账：留着上上次的现场没有任何用处，
 * 只会让「恢复」这个动作变成先要用户挑一份。
 */
public class TabStore {

    /** 标签页的两种：查询页带 SQL，表页带表名。 */
    public enum Kind {
        QUERY,
        TABLE;

        public static Kind of(String text) {
            return TABLE.name().equalsIgnoreCase(text) ? TABLE : QUERY;
        }
    }

    /**
     * 一条保存下来的标签页。
     *
     * @param id       主键，{@code <= 0} 表示还没入库
     * @param favorite 是否收藏。保存和收藏共用一条记录：收藏就是「保存 + 标个星」。
     *                 拆成两份会立刻带来「同一段 SQL 存了两条、改了其中一条」的问题
     */
    public record SavedTab(long id, Kind kind, String title, String connectionId,
                           String schema, String table, String sql, boolean favorite,
                           LocalDateTime updatedAt) {

        public static SavedTab query(String title, String connectionId, String schema,
                                     String sql, boolean favorite) {
            return new SavedTab(0, Kind.QUERY, title, connectionId, schema, null, sql,
                    favorite, LocalDateTime.now());
        }

        public static SavedTab table(String title, String connectionId, String schema,
                                     String table, boolean favorite) {
            return new SavedTab(0, Kind.TABLE, title, connectionId, schema, table, null,
                    favorite, LocalDateTime.now());
        }

        /** 列表里显示用的一行。 */
        public String oneLine() {
            if (kind == Kind.TABLE) {
                return schema == null || schema.isBlank()
                        ? String.valueOf(table) : schema + "." + table;
            }
            String flat = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
            return flat.length() <= 90 ? flat : flat.substring(0, 87) + "…";
        }
    }

    /**
     * 退出时记下的一个标签页。
     *
     * @param unsaved 有没有没保存的内容
     * @param savedId 当时绑在哪条保存记录上，0 表示这一页从没保存过。
     *                恢复时靠它把名字和「再按保存写回哪条」接回去
     */
    public record SessionTab(int position, Kind kind, String title, String connectionId,
                             String schema, String table, String sql, boolean unsaved,
                             long savedId) {
    }

    private final LocalStore store;

    public TabStore(LocalStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ 保存 / 收藏

    /**
     * 存一条，返回它的 id。
     *
     * <p>{@code tab.id() > 0} 时是覆盖写回——同一个标签页反复按保存，
     * 应该一直是那一条记录，而不是每按一次多一条。
     */
    public long save(SavedTab tab) {
        try {
            if (tab.id() > 0) {
                update(tab);
                return tab.id();
            }
            return insert(tab);
        } catch (SQLException e) {
            throw new DbException("保存标签页失败：" + e.getMessage(), e);
        }
    }

    private long insert(SavedTab tab) throws SQLException {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO saved_tab (kind, title, connection_id, schema_name, table_name,"
                        + " sql_text, favorite, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, tab);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private void update(SavedTab tab) throws SQLException {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "UPDATE saved_tab SET kind=?, title=?, connection_id=?, schema_name=?,"
                        + " table_name=?, sql_text=?, favorite=?, updated_at=? WHERE id=?")) {
            bind(ps, tab);
            ps.setLong(9, tab.id());
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, SavedTab tab) throws SQLException {
        ps.setString(1, tab.kind().name());
        ps.setString(2, tab.title());
        ps.setString(3, tab.connectionId());
        ps.setString(4, tab.schema());
        ps.setString(5, tab.table());
        ps.setString(6, tab.sql());
        ps.setInt(7, tab.favorite() ? 1 : 0);
        ps.setString(8, LocalDateTime.now().toString());
    }

    public void setFavorite(long id, boolean favorite) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "UPDATE saved_tab SET favorite=? WHERE id=?")) {
            ps.setInt(1, favorite ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("更新收藏失败：" + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "DELETE FROM saved_tab WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除失败：" + e.getMessage(), e);
        }
    }

    /** 收藏的排在前面，其次按最近保存。 */
    public List<SavedTab> list(boolean onlyFavorites) {
        String sql = "SELECT id, kind, title, connection_id, schema_name, table_name, sql_text,"
                + " favorite, updated_at FROM saved_tab"
                + (onlyFavorites ? " WHERE favorite=1" : "")
                + " ORDER BY favorite DESC, updated_at DESC";
        List<SavedTab> out = new ArrayList<>();
        try (PreparedStatement ps = store.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SavedTab(rs.getLong(1), Kind.of(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getInt(8) != 0, parse(rs.getString(9))));
            }
        } catch (SQLException e) {
            throw new DbException("读取收藏失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** 按 id 取一条；没有就返回 null。 */
    public SavedTab find(long id) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT id, kind, title, connection_id, schema_name, table_name, sql_text,"
                        + " favorite, updated_at FROM saved_tab WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SavedTab(rs.getLong(1), Kind.of(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getInt(8) != 0, parse(rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new DbException("读取收藏失败：" + e.getMessage(), e);
        }
    }

    private static LocalDateTime parse(String text) {
        try {
            return text == null ? LocalDateTime.now() : LocalDateTime.parse(text);
        } catch (RuntimeException e) {
            return LocalDateTime.now();
        }
    }

    // ------------------------------------------------------------------ 退出现场

    /** 整表覆写。空列表就是「上次退出时一个标签页都没开」。 */
    public void saveSession(List<SessionTab> openTabs) {
        try {
            store.connection().setAutoCommit(false);
            try (Statement st = store.connection().createStatement()) {
                st.executeUpdate("DELETE FROM session_tab");
            }
            try (PreparedStatement ps = store.connection().prepareStatement(
                    "INSERT INTO session_tab (position, kind, title, connection_id, schema_name,"
                            + " table_name, sql_text, unsaved, saved_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (SessionTab tab : openTabs) {
                    ps.setInt(1, tab.position());
                    ps.setString(2, tab.kind().name());
                    ps.setString(3, tab.title());
                    ps.setString(4, tab.connectionId());
                    ps.setString(5, tab.schema());
                    ps.setString(6, tab.table());
                    ps.setString(7, tab.sql());
                    ps.setInt(8, tab.unsaved() ? 1 : 0);
                    ps.setLong(9, tab.savedId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            store.connection().commit();
        } catch (SQLException e) {
            rollback();
            throw new DbException("记录标签页现场失败：" + e.getMessage(), e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void rollback() {
        try {
            store.connection().rollback();
        } catch (SQLException ignored) {
            // 回滚都失败了，这里再抛只会盖住真正那个异常
        }
    }

    private void restoreAutoCommit() {
        try {
            store.connection().setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }

    public List<SessionTab> lastSession() {
        List<SessionTab> out = new ArrayList<>();
        try (PreparedStatement ps = store.connection().prepareStatement(
                "SELECT position, kind, title, connection_id, schema_name, table_name, sql_text,"
                        + " unsaved, saved_id FROM session_tab ORDER BY position");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SessionTab(rs.getInt(1), Kind.of(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getInt(8) != 0, rs.getLong(9)));
            }
        } catch (SQLException e) {
            throw new DbException("读取上次的标签页失败：" + e.getMessage(), e);
        }
        return out;
    }

    public void clearSession() {
        try (Statement st = store.connection().createStatement()) {
            st.executeUpdate("DELETE FROM session_tab");
        } catch (SQLException e) {
            throw new DbException("清除标签页现场失败：" + e.getMessage(), e);
        }
    }
}
