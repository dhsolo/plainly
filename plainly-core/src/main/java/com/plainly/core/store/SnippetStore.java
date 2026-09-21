package com.plainly.core.store;

import com.plainly.driver.DbException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 代码片段。
 *
 * <h2>和查询历史、收藏的区别</h2>
 * 三张表记的是三件事，混在一起哪一件都做不好：
 * <ul>
 *   <li>{@code query_history} —— 跑过什么。每条对应一次真实执行；</li>
 *   <li>{@code saved_tab} —— 留着以后接着用的<b>那一页</b>，带着连接和库；</li>
 *   <li>{@code snippet} —— 一段<b>可复用的写法</b>，和具体的库无关。
 *       「分页查询怎么写」「怎么查锁等待」这类东西，每次都去历史里翻是翻不到的，
 *       因为它上一次出现时的表名和这一次不一样。</li>
 * </ul>
 *
 * <h2>前缀是它的入口</h2>
 * 片段光存着没用，得在敲字的时候浮出来。所以每条有一个短前缀，
 * 在编辑器里敲那个前缀就能补全出整段。没有前缀的片段只能从管理窗口里翻，
 * 那基本等于没有。
 */
public class SnippetStore {

    /** 一个片段。{@code prefix} 是补全时敲的那几个字符。 */
    public record Snippet(long id, String name, String prefix, String body, String note) {
    }

    private final LocalStore store;

    public SnippetStore(LocalStore store) {
        this.store = store;
        boolean fresh = !tableExists();
        migrate();
        if (fresh) {
            // 只在第一次建表时塞几条。用户把它们删光之后不该在下次启动时又冒出来——
            // 那会让人觉得这个工具不听话
            seed();
        }
    }

    private boolean tableExists() {
        try (Statement st = store.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='snippet'")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private void migrate() {
        try (Statement st = store.connection().createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS snippet (
                      id         INTEGER PRIMARY KEY AUTOINCREMENT,
                      name       TEXT NOT NULL,
                      prefix     TEXT,
                      body       TEXT NOT NULL,
                      note       TEXT,
                      updated_at TEXT NOT NULL
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_snippet_prefix ON snippet(prefix)");
        } catch (SQLException e) {
            throw new DbException("准备代码片段表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 出厂自带的几条。
     *
     * <p>挑的都是「知道该这么写、但每次都要现查语法」的东西。刻意不放
     * 各家方言特有的写法：出厂片段在别的库上跑不通，比没有片段更糟。
     */
    private void seed() {
        save(new Snippet(0, "分页查询", "page",
                "SELECT *\nFROM ${table}\nORDER BY ${order_column}\nLIMIT 50 OFFSET 0",
                "先 ORDER BY 再分页——不指定顺序的分页，翻到第二页可能又见到第一页的行"));
        save(new Snippet(0, "带条件计数", "cnt",
                "SELECT COUNT(*) AS total\nFROM ${table}\nWHERE ${condition}", ""));
        save(new Snippet(0, "安全的 UPDATE", "upd",
                "-- 先用 SELECT 数一遍，确认影响面对得上再改成 UPDATE\n"
                        + "SELECT COUNT(*) FROM ${table} WHERE ${condition};\n\n"
                        + "UPDATE ${table}\nSET ${column} = ${value}\nWHERE ${condition};",
                "先数一遍是这条片段存在的全部意义"));
        save(new Snippet(0, "找重复行", "dup",
                "SELECT ${column}, COUNT(*) AS c\nFROM ${table}\n"
                        + "GROUP BY ${column}\nHAVING COUNT(*) > 1\nORDER BY c DESC", ""));
        save(new Snippet(0, "两表差集", "diff",
                "SELECT a.*\nFROM ${table_a} a\n"
                        + "LEFT JOIN ${table_b} b ON a.${key} = b.${key}\n"
                        + "WHERE b.${key} IS NULL",
                "在 a 里而不在 b 里的那些"));
    }

    public List<Snippet> list() {
        List<Snippet> out = new ArrayList<>();
        try (Statement st = store.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM snippet ORDER BY name")) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new DbException("读取代码片段失败：" + e.getMessage(), e);
        }
        return out;
    }

    /**
     * 按前缀找。给编辑器的补全用。
     *
     * <p>名字也参与匹配：用户记住的往往是「分页」这两个字，而不是当初起的
     * {@code page} 这个前缀。
     */
    public List<Snippet> matching(String typed) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        String needle = typed.trim().toLowerCase(java.util.Locale.ROOT);
        List<Snippet> out = new ArrayList<>();
        for (Snippet s : list()) {
            String prefix = s.prefix() == null ? "" : s.prefix().toLowerCase(java.util.Locale.ROOT);
            String name = s.name() == null ? "" : s.name().toLowerCase(java.util.Locale.ROOT);
            if ((!prefix.isEmpty() && prefix.startsWith(needle)) || name.contains(needle)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 新增或更新。id 为 0 表示新增，返回落库后的 id。 */
    public long save(Snippet snippet) {
        if (snippet.name() == null || snippet.name().isBlank()) {
            throw new DbException("片段得有个名字");
        }
        if (snippet.body() == null || snippet.body().isBlank()) {
            throw new DbException("片段的内容是空的");
        }
        String now = java.time.LocalDateTime.now().withNano(0).toString();
        if (snippet.id() > 0) {
            try (PreparedStatement ps = store.connection().prepareStatement(
                    "UPDATE snippet SET name=?, prefix=?, body=?, note=?, updated_at=?"
                            + " WHERE id=?")) {
                ps.setString(1, snippet.name());
                ps.setString(2, snippet.prefix());
                ps.setString(3, snippet.body());
                ps.setString(4, snippet.note());
                ps.setString(5, now);
                ps.setLong(6, snippet.id());
                ps.executeUpdate();
                return snippet.id();
            } catch (SQLException e) {
                throw new DbException("保存代码片段失败：" + e.getMessage(), e);
            }
        }
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO snippet (name, prefix, body, note, updated_at) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, snippet.name());
            ps.setString(2, snippet.prefix());
            ps.setString(3, snippet.body());
            ps.setString(4, snippet.note());
            ps.setString(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DbException("保存代码片段失败：" + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("DELETE FROM snippet WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除代码片段失败：" + e.getMessage(), e);
        }
    }

    private static Snippet read(ResultSet rs) throws SQLException {
        return new Snippet(rs.getLong("id"), rs.getString("name"), rs.getString("prefix"),
                rs.getString("body"), rs.getString("note"));
    }
}
