package com.plainly.core.store;

import com.plainly.driver.DbException;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 虚拟外键：只存在本机、不写进数据库的表间关系标注。
 *
 * <h2>为什么需要它</h2>
 * 很多线上库<b>刻意不建物理外键</b>——分库分表做不了、写入性能、迁移方便，理由各不相同，
 * 但结果一样：ER 图上画出来是一堆互不相干的孤岛，「哪张表跟哪张表有关系」这件事
 * 只存在于人的脑子里。
 *
 * <p>{@code ModelService} 已经会按命名推测一部分（{@code order_id} → {@code orders}），
 * 但那只能覆盖最规整的命名。虚拟外键补的是剩下那些：用户自己标一次，
 * 之后 ER 图和表页的外键栏都认它。
 *
 * <h2>它绝不碰目标库</h2>
 * 存在本机配置库里，按连接分开。目标库上不会因此多出任何约束，
 * 也就不会有「标注一下，结果生产库多了个外键」这种事。
 * 代价是它<b>不做任何强制</b>：标了 A.b → B.id，插一条对不上的数据照样插得进去。
 * 界面上必须把这一点说清楚，否则用户会以为它有约束效力。
 */
public class VirtualKeyStore {

    /**
     * 虚拟外键在 {@link ForeignKeyInfo#name()} 里的前缀。
     *
     * <p>为什么用名字前缀而不是给记录加个字段：{@code ForeignKeyInfo} 在
     * driver-api 里，是驱动契约的一部分，而「虚拟外键」纯粹是本机的概念，
     * 不该渗进驱动层。约定收在这一个类里，别处只调 {@link #isVirtual}。
     */
    public static final String VIRTUAL_PREFIX = "虚拟 · ";

    private final LocalStore store;

    public VirtualKeyStore(LocalStore store) {
        this.store = store;
        migrate();
    }

    /** 这条外键是不是本机标注的虚拟外键。 */
    public static boolean isVirtual(ForeignKeyInfo key) {
        return key != null && key.name() != null && key.name().startsWith(VIRTUAL_PREFIX);
    }

    private void migrate() {
        try (Statement st = store.connection().createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS virtual_fk (
                      id            INTEGER PRIMARY KEY AUTOINCREMENT,
                      connection_id TEXT NOT NULL,
                      schema_name   TEXT,
                      table_name    TEXT NOT NULL,
                      columns       TEXT NOT NULL,
                      ref_schema    TEXT,
                      ref_table     TEXT NOT NULL,
                      ref_columns   TEXT NOT NULL,
                      note          TEXT,
                      created_at    TEXT NOT NULL
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_vfk_table"
                    + " ON virtual_fk(connection_id, schema_name, table_name)");
        } catch (SQLException e) {
            throw new DbException("准备虚拟外键表失败：" + e.getMessage(), e);
        }
    }

    /** 一条虚拟外键。 */
    public record VirtualKey(long id, String connectionId, String schema, String table,
                             List<String> columns, String refSchema, String refTable,
                             List<String> refColumns, String note) {

        /** 转成外键记录，好让 ER 图和外键栏不必分两套处理。 */
        public ForeignKeyInfo toForeignKey() {
            String label = note == null || note.isBlank() ? String.valueOf(id) : note;
            return new ForeignKeyInfo(VIRTUAL_PREFIX + label, schema, table, columns,
                    refSchema, refTable, refColumns, "—", "—");
        }
    }

    // ------------------------------------------------------------------ 读

    /** 一条连接上的全部虚拟外键。 */
    public List<VirtualKey> listAll(String connectionId) {
        return query("SELECT * FROM virtual_fk WHERE connection_id = ?"
                + " ORDER BY schema_name, table_name, id", connectionId);
    }

    /** 一个库里的全部虚拟外键。ER 图整库逆向时用。 */
    public List<VirtualKey> listInSchema(String connectionId, String schema) {
        return query("SELECT * FROM virtual_fk WHERE connection_id = ?"
                        + " AND IFNULL(schema_name, '') = IFNULL(?, '')"
                        + " ORDER BY table_name, id",
                connectionId, schema);
    }

    /** 本表指向别人的那些。 */
    public List<VirtualKey> listOutgoing(String connectionId, String schema, String table) {
        return query("SELECT * FROM virtual_fk WHERE connection_id = ?"
                        + " AND IFNULL(schema_name, '') = IFNULL(?, '')"
                        + " AND table_name = ? COLLATE NOCASE ORDER BY id",
                connectionId, schema, table);
    }

    /** 别人指向本表的那些。 */
    public List<VirtualKey> listIncoming(String connectionId, String schema, String table) {
        return query("SELECT * FROM virtual_fk WHERE connection_id = ?"
                        + " AND IFNULL(ref_schema, '') = IFNULL(?, '')"
                        + " AND ref_table = ? COLLATE NOCASE ORDER BY id",
                connectionId, schema, table);
    }

    private List<VirtualKey> query(String sql, String... args) {
        List<VirtualKey> out = new ArrayList<>();
        try (PreparedStatement ps = store.connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new DbException("读取虚拟外键失败：" + e.getMessage(), e);
        }
        return out;
    }

    private static VirtualKey read(ResultSet rs) throws SQLException {
        return new VirtualKey(rs.getLong("id"), rs.getString("connection_id"),
                rs.getString("schema_name"), rs.getString("table_name"),
                split(rs.getString("columns")), rs.getString("ref_schema"),
                rs.getString("ref_table"), split(rs.getString("ref_columns")),
                rs.getString("note"));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 新增一条。
     *
     * @return 新记录的 id
     */
    public long add(String connectionId, String schema, String table, List<String> columns,
                    String refSchema, String refTable, List<String> refColumns, String note) {
        if (columns.isEmpty() || refColumns.isEmpty()) {
            throw new DbException("两边都要至少选一列");
        }
        if (columns.size() != refColumns.size()) {
            throw new DbException("两边的列数要一样：本表选了 " + columns.size()
                    + " 列，对方选了 " + refColumns.size() + " 列");
        }
        String sql = "INSERT INTO virtual_fk (connection_id, schema_name, table_name, columns,"
                + " ref_schema, ref_table, ref_columns, note, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = store.connection()
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setString(i++, connectionId);
            ps.setString(i++, schema);
            ps.setString(i++, table);
            ps.setString(i++, String.join(",", columns));
            ps.setString(i++, refSchema);
            ps.setString(i++, refTable);
            ps.setString(i++, String.join(",", refColumns));
            ps.setString(i++, note);
            ps.setString(i, java.time.LocalDateTime.now().withNano(0).toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DbException("保存虚拟外键失败：" + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("DELETE FROM virtual_fk WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("删除虚拟外键失败：" + e.getMessage(), e);
        }
    }

    /** 连接被删掉时把它的标注一并清掉，不留孤儿记录。 */
    public void deleteForConnection(String connectionId) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("DELETE FROM virtual_fk WHERE connection_id = ?")) {
            ps.setString(1, connectionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("清理虚拟外键失败：" + e.getMessage(), e);
        }
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
