package com.plainly.core.store;

import com.plainly.driver.DbException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询历史与收藏。
 *
 * <p>历史是自动记的，收藏是人手动标的——两者共用一张表，因为它们是同一件东西的两种状态：
 * 一条跑过的语句被标记之后就该留下来。分成两张表就得处理「收藏的那条历史被清理了怎么办」。
 *
 * <p>只记语句本身和它跑得怎么样，不记结果。结果动辄几十万行，
 * 而且过一会儿就不是当时那个结果了。
 */
public class HistoryStore {

    /** 一条历史。 */
    public record Entry(long id, String connectionId, String sql, LocalDateTime executedAt,
                        long elapsedMillis, long rowCount, boolean succeeded, String error,
                        boolean favorite, String title) {

        /** 列表里显示用的一行：语句压成一行、截短。 */
        public String oneLine() {
            String flat = sql.replaceAll("\\s+", " ").trim();
            return flat.length() <= 90 ? flat : flat.substring(0, 87) + "…";
        }
    }

    /**
     * 每写这么多条清理一次。
     *
     * <p>原来是每插一条就全表清一次——那条 DELETE 带子查询，跑一次要扫一遍历史表。
     * 用户每执行一句 SQL 都要陪跑一次，而清理晚几十条完全没有影响。
     */
    private static final int PRUNE_EVERY = 50;

    private final LocalStore store;
    private int sinceLastPrune;

    public HistoryStore(LocalStore store) {
        this.store = store;
    }

    public void record(String connectionId, String sql, long elapsedMillis, long rowCount,
                       boolean succeeded, String error) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO query_history (connection_id, sql_text, executed_at, elapsed_ms,"
                        + " row_count, succeeded, error_text) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, connectionId);
            ps.setString(2, sql);
            ps.setString(3, LocalDateTime.now().toString());
            ps.setLong(4, elapsedMillis);
            ps.setLong(5, rowCount);
            ps.setInt(6, succeeded ? 1 : 0);
            ps.setString(7, error);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 记历史失败不该影响用户正在做的事
            return;
        }
        if (++sinceLastPrune >= PRUNE_EVERY) {
            sinceLastPrune = 0;
            Retention.sweep(store);
        }
    }

    /**
     * 查历史。
     *
     * @param keyword 按语句内容过滤；空则不过滤
     * @param onlyFavorites 只看收藏
     */
    public List<Entry> list(String keyword, boolean onlyFavorites, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM query_history WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND sql_text LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        if (onlyFavorites) {
            sql.append(" AND favorite = 1");
        }
        sql.append(" ORDER BY executed_at DESC LIMIT ?");
        args.add(limit);

        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = store.connection().prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new DbException("读取查询历史失败：" + e.getMessage(), e);
        }
        return out;
    }

    private Entry read(ResultSet rs) throws SQLException {
        return new Entry(rs.getLong("id"), rs.getString("connection_id"),
                rs.getString("sql_text"), LocalDateTime.parse(rs.getString("executed_at")),
                rs.getLong("elapsed_ms"), rs.getLong("row_count"),
                rs.getInt("succeeded") != 0, rs.getString("error_text"),
                rs.getInt("favorite") != 0, rs.getString("title"));
    }

    public void setFavorite(long id, boolean favorite, String title) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "UPDATE query_history SET favorite = ?, title = ? WHERE id = ?")) {
            ps.setInt(1, favorite ? 1 : 0);
            ps.setString(2, title);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("更新收藏失败：" + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "DELETE FROM query_history WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除历史失败：" + e.getMessage(), e);
        }
    }

    /** 清空非收藏的历史。收藏的留着——那是人手动标过的。 */
    public int clearHistory() {
        try (PreparedStatement ps = store.connection().prepareStatement(
                "DELETE FROM query_history WHERE favorite = 0")) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("清空历史失败：" + e.getMessage(), e);
        }
    }
}
