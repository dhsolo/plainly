package com.plainly.driver.jdbc;

import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 人大金仓 V8R3 的元数据：绕开驱动，自己发 SQL。
 *
 * <h2>为什么非要自己来</h2>
 * V8R3 的系统目录叫 {@code sys_catalog}；V8R6 起才改成和 PostgreSQL 一致的
 * {@code pg_catalog}。而<b>官方能拿到的最老驱动就是 V8R6</b>，它按 {@code pg_catalog}
 * 拼元数据查询，于是 {@link java.sql.DatabaseMetaData} 上每一个方法都报
 * {@code relation "PG_CATALOG.PG_namespace" does not exist}。
 *
 * <p>换驱动这条路是堵死的，实测过全部 12 个可获得的版本：
 * <ul>
 *   <li>V8R6 的驱动<b>自己带版本校验</b>，连都不让连
 *       （{@code database version does not match driver version}）；</li>
 *   <li>V9 的驱动连得上，但同样只认 {@code pg_catalog}。</li>
 * </ul>
 *
 * <p>所以剩下的唯一办法：用 V9 驱动建立连接，元数据全部改由本类发 SQL 取。
 * 实测 15 条元数据查询全部可用——模式、表、列（含精度与小数位）、主键、索引、
 * 外键、注释、行数、视图定义、序列、触发器、例程，一条不缺。
 *
 * <h2>还有一处：参数必须走简单查询协议</h2>
 * 见 {@code JdbcConnections} 里对 {@code preferQueryMode} 的处理。
 *
 * <h2>取舍</h2>
 * 这一层只在<b>确实撞上 V8R3</b> 时才启用（{@link #detect}），
 * 判据是症状而不是版本号。别的库一行代码都不会走到这里，
 * 原来的 {@code DatabaseMetaData} 路径保持原样。
 */
final class KingbaseLegacy {

    private KingbaseLegacy() {
    }

    /**
     * 这条连接是不是「驱动按 pg_catalog 查，而服务端只有 sys_catalog」。
     *
     * <p>用症状判断，不比版本号：版本号的格式各版本都在变，比错了会把好连接拦下来。
     * 而「{@code pg_catalog} 查不到、{@code sys_catalog} 查得到」是确凿的，
     * 除了这一种情况没有别的解释。
     */
    static boolean detect(Connection conn) {
        return !canQuery(conn, "SELECT 1 FROM pg_catalog.pg_namespace WHERE 1 = 0")
                && canQuery(conn, "SELECT 1 FROM sys_catalog.sys_namespace WHERE 1 = 0");
    }

    private static boolean canQuery(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.executeQuery(sql).close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 模式

    /**
     * 模式列表。
     *
     * <p>过滤掉 {@code SYS} 开头的系统模式和 {@code information_schema}——
     * 金仓的系统模式有七八个（SYS_CATALOG、SYS_TOAST、SYSAUDIT……），
     * 不滤掉的话用户自己那一个会淹在里面。
     */
    static List<SchemaInfo> schemas(Connection conn, String current) throws SQLException {
        List<SchemaInfo> out = new ArrayList<>();
        String sql = "SELECT nspname FROM sys_catalog.sys_namespace"
                + " WHERE nspname NOT LIKE 'SYS%' AND nspname NOT LIKE 'sys%'"
                + " AND nspname <> 'information_schema' ORDER BY nspname";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                out.add(new SchemaInfo(name, name.equalsIgnoreCase(current)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 对象

    /**
     * 表、视图与序列。
     *
     * <p>行数用 {@code reltuples}（统计信息里的估算值，和别家一致），
     * 没做过 ANALYZE 的表会是 0 或 -1；这里把 0 之外的负数归一成 -1，
     * 让界面显示「未知」而不是一个假的精确值。
     */
    static List<TableInfo> tables(Connection conn, String schema) throws SQLException {
        List<TableInfo> out = new ArrayList<>();
        String sql = "SELECT ct.relname, ct.relkind, ct.reltuples::bigint,"
                + " sys_catalog.obj_description(ct.oid)"
                + " FROM sys_catalog.sys_class ct"
                + " JOIN sys_catalog.sys_namespace n ON n.oid = ct.relnamespace"
                + " WHERE n.nspname = ? AND ct.relkind IN ('r', 'v', 'm', 'S')"
                + " ORDER BY ct.relname";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(2);
                    long rows = rs.getLong(3);
                    out.add(new TableInfo(schema, rs.getString(1), kindOf(kind),
                            nz(rs.getString(4)),
                            "r".equals(kind) ? (rows < 0 ? -1L : rows) : -1L));
                }
            }
        }
        return out;
    }

    private static ObjectKind kindOf(String relkind) {
        switch (relkind) {
            case "v":
                return ObjectKind.VIEW;
            case "m":
                return ObjectKind.MATERIALIZED_VIEW;
            case "S":
                return ObjectKind.SEQUENCE;
            default:
                return ObjectKind.TABLE;
        }
    }

    // ------------------------------------------------------------------ 结构

    static TableStructure structure(Connection conn, String schema, String table,
                                    TableInfo info) throws SQLException {
        Set<String> pks = primaryKeys(conn, schema, table);
        Map<String, String> comments = columnComments(conn, schema, table);
        List<ColumnInfo> columns = columns(conn, schema, table, pks, comments);
        return new TableStructure(info, columns, indexes(conn, schema, table));
    }

    /**
     * 列信息。
     *
     * <p>类型名取 {@code udt_name}（{@code INT4}、{@code VARCHAR}、{@code NUMERIC}），
     * 而不是 {@code data_type}——后者给的是 {@code CHARACTER VARYING} 这种
     * SQL 标准写法，拼回 DDL 时和建表用的写法对不上。{@code udt_name}
     * 和 pgjdbc 在别处报的类型名是同一套，只是大小写不同。
     *
     * <p>长度与精度分别来自 {@code character_maximum_length} 和
     * {@code numeric_precision}：一个类型只会用到其中一个。
     */
    private static List<ColumnInfo> columns(Connection conn, String schema, String table,
                                            Set<String> pks, Map<String, String> comments)
            throws SQLException {
        List<ColumnInfo> out = new ArrayList<>();
        String sql = "SELECT column_name, udt_name, character_maximum_length,"
                + " numeric_precision, numeric_scale, is_nullable, column_default,"
                + " ordinal_position, datetime_precision"
                + " FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String type = nz(rs.getString(2));
                    int length = rs.getInt(3);
                    int numeric = rs.getInt(4);
                    int scale = rs.getInt(5);
                    String def = rs.getString(7);
                    // serial 在系统表里就是一个普通整数列加 nextval 默认值，
                    // 没有别的标记——和 PostgreSQL 一样
                    boolean auto = def != null
                            && def.trim().toUpperCase(Locale.ROOT).startsWith("NEXTVAL(");
                    out.add(new ColumnInfo(
                            name,
                            type,
                            TypeCategories.of(sqlTypeOf(type), type),
                            length > 0 ? length : numeric,
                            scale,
                            "YES".equalsIgnoreCase(nz(rs.getString(6))),
                            pks.contains(name),
                            auto,
                            def,
                            nz(comments.get(name)),
                            rs.getInt(8)));
                }
            }
        }
        return out;
    }

    /**
     * PostgreSQL 内部类型名到 JDBC 类型号。
     *
     * <p>为什么要自己映一层：{@link TypeCategories} 按名字的那条回退分支认的是
     * {@code NUMERIC}、{@code TIMESTAMP} 这类通用词，不认 {@code INT4}、
     * {@code BOOL} 这些 PostgreSQL 的内部名——{@code INT4} 会掉进 OTHER，
     * 于是一个整数列被当成未知类型，排序和对齐都跟着错。
     *
     * <p>与其去改那个已经在七八种库上验过的共享逻辑，不如在这里把名字翻译成
     * 标准类型号再交给它。
     */
    private static int sqlTypeOf(String udtName) {
        switch (udtName.toUpperCase(Locale.ROOT)) {
            case "INT2":
                return Types.SMALLINT;
            case "INT4":
                return Types.INTEGER;
            case "INT8":
                return Types.BIGINT;
            case "NUMERIC":
            case "DECIMAL":
                return Types.NUMERIC;
            case "FLOAT4":
                return Types.REAL;
            case "FLOAT8":
                return Types.DOUBLE;
            case "BOOL":
                return Types.BOOLEAN;
            case "DATE":
                return Types.DATE;
            case "TIME":
            case "TIMETZ":
                return Types.TIME;
            case "TIMESTAMP":
            case "TIMESTAMPTZ":
                return Types.TIMESTAMP;
            case "BYTEA":
                return Types.BINARY;
            case "VARCHAR":
            case "BPCHAR":
            case "CHAR":
            case "TEXT":
            case "NAME":
                return Types.VARCHAR;
            default:
                // 认不出来交给按名字那条回退，别硬塞一个错的类型号
                return Types.OTHER;
        }
    }

    private static Set<String> primaryKeys(Connection conn, String schema, String table)
            throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT k.column_name FROM information_schema.table_constraints t"
                + " JOIN information_schema.key_column_usage k"
                + " ON k.constraint_name = t.constraint_name"
                + " AND k.table_schema = t.table_schema AND k.table_name = t.table_name"
                + " WHERE t.table_schema = ? AND t.table_name = ?"
                + " AND t.constraint_type = 'PRIMARY KEY'"
                + " ORDER BY k.ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static Map<String, String> columnComments(Connection conn, String schema, String table)
            throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        String sql = "SELECT a.attname, sys_catalog.col_description(ct.oid, a.attnum)"
                + " FROM sys_catalog.sys_class ct"
                + " JOIN sys_catalog.sys_namespace n ON n.oid = ct.relnamespace"
                + " JOIN sys_catalog.sys_attribute a ON a.attrelid = ct.oid"
                + " AND a.attnum > 0 AND NOT a.attisdropped"
                + " WHERE n.nspname = ? AND ct.relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String c = rs.getString(2);
                    if (c != null && !c.isBlank()) {
                        out.put(rs.getString(1), c);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 索引。
     *
     * <p>列名和<b>列的顺序</b>从 {@code sys_get_indexdef} 生成的那条语句里取，
     * 而不是去连 {@code sys_attribute}。原因是顺序：{@code indkey} 是
     * {@code int2vector}，9.2 那一代没有 {@code unnest(...) WITH ORDINALITY}
     * 也没有 {@code array_position}，按 {@code attnum} 连出来的是<b>表里的列序</b>，
     * 不是索引里的列序。复合索引上这两者常常不一样，而索引的列序决定它能不能被用上，
     * 显示错了会让人得出完全相反的判断。
     *
     * <p>{@code sys_get_indexdef} 是服务端自己生成的规范语句，取最外层括号里那一段即可。
     * 函数索引（括号里是表达式）会原样显示出来——不好看，但不会说谎。
     */
    private static List<IndexInfo> indexes(Connection conn, String schema, String table)
            throws SQLException {
        List<IndexInfo> out = new ArrayList<>();
        String sql = "SELECT ci.relname, i.indisunique, i.indisprimary,"
                + " sys_catalog.sys_get_indexdef(i.indexrelid)"
                + " FROM sys_catalog.sys_index i"
                + " JOIN sys_catalog.sys_class ci ON ci.oid = i.indexrelid"
                + " JOIN sys_catalog.sys_class ct ON ct.oid = i.indrelid"
                + " JOIN sys_catalog.sys_namespace n ON n.oid = ct.relnamespace"
                + " WHERE n.nspname = ? AND ct.relname = ?"
                + " ORDER BY ci.relname";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new IndexInfo(rs.getString(1),
                            indexColumns(rs.getString(4)),
                            rs.getBoolean(2), rs.getBoolean(3)));
                }
            }
        }
        return out;
    }

    /** 从 {@code CREATE INDEX ... (a, b)} 里取出最外层括号中的列名。 */
    private static List<String> indexColumns(String indexDef) {
        List<String> out = new ArrayList<>();
        if (indexDef == null) {
            return out;
        }
        int open = indexDef.indexOf('(');
        int close = indexDef.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return out;
        }
        for (String part : indexDef.substring(open + 1, close).split(",")) {
            String col = part.trim();
            // 去掉排序方向和 NULLS FIRST/LAST 这类修饰，留下列名本身
            int space = col.indexOf(' ');
            if (space > 0) {
                col = col.substring(0, space);
            }
            col = col.replace("\"", "").trim();
            if (!col.isEmpty()) {
                out.add(col);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
