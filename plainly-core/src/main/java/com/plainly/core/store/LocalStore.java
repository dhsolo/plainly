package com.plainly.core.store;

import com.plainly.driver.DbException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 本机配置库。保存连接、查询历史、代码片段。
 *
 * <p>用 SQLite 而不是 JSON/properties：连接多起来之后要做检索和历史查询，
 * 而且这本身就是一次「自己吃自己的狗粮」。
 */
public class LocalStore implements AutoCloseable {

    private final Connection conn;
    private final Path dbPath;

    public LocalStore() {
        this(defaultLocation());
    }

    public LocalStore(Path dbPath) {
        this.dbPath = dbPath;
        try {
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            migrate();
        } catch (Exception e) {
            throw new DbException("无法打开本机配置库 " + dbPath + "：" + e.getMessage(), e);
        }
    }

    /** {@code %APPDATA%\Plainly\plainly.db}，无 APPDATA 时退回用户目录。 */
    public static Path defaultLocation() {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Paths.get(appData, "Plainly")
                : Paths.get(System.getProperty("user.home"), ".plainly");
        return base.resolve("plainly.db");
    }

    public Path path() {
        return dbPath;
    }

    /**
     * 底层连接。
     *
     * <p>公开出来是给同一个本地库上的其它表用的（任务、运行历史），
     * 它们和连接、查询历史共用一个 SQLite 文件——各开各的连接只会互相锁。
     */
    public Connection connection() {
        return conn;
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS connections (
                      id            TEXT PRIMARY KEY,
                      name          TEXT NOT NULL,
                      db_type       TEXT NOT NULL,
                      host          TEXT,
                      port          INTEGER,
                      username      TEXT,
                      password_enc  TEXT,
                      database_name TEXT,
                      file_path     TEXT,
                      timezone      TEXT,
                      charset       TEXT,
                      read_only     INTEGER NOT NULL DEFAULT 0,
                      save_password INTEGER NOT NULL DEFAULT 1,
                      sort_order    INTEGER NOT NULL DEFAULT 0
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS query_history (
                      id            INTEGER PRIMARY KEY AUTOINCREMENT,
                      connection_id TEXT,
                      sql_text      TEXT NOT NULL,
                      executed_at   TEXT NOT NULL,
                      elapsed_ms    INTEGER,
                      row_count     INTEGER,
                      succeeded     INTEGER NOT NULL DEFAULT 1,
                      error_text    TEXT
                    )""");
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_history_time ON query_history(executed_at DESC)");

            /*
             * 保存 / 收藏的标签页。
             *
             * 和 query_history 分开：那张表记的是「跑过什么」，每条都对应一次真实执行；
             * 这张记的是「留着以后用什么」，可能一次都没跑过。混在一起，
             * 「清理历史」和「按时间排序」两件事都会立刻变得说不清。
             */
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS saved_tab (
                      id            INTEGER PRIMARY KEY AUTOINCREMENT,
                      kind          TEXT NOT NULL,
                      title         TEXT NOT NULL,
                      connection_id TEXT,
                      schema_name   TEXT,
                      table_name    TEXT,
                      sql_text      TEXT,
                      favorite      INTEGER NOT NULL DEFAULT 0,
                      updated_at    TEXT NOT NULL
                    )""");

            /*
             * 上次退出时开着哪些标签页。
             *
             * 整表覆写，不做历史——它是一份现场快照，不是流水账。
             * position 就是标签页在那一排里的先后，恢复时按它排。
             */
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS session_tab (
                      position      INTEGER PRIMARY KEY,
                      kind          TEXT NOT NULL,
                      title         TEXT,
                      connection_id TEXT,
                      schema_name   TEXT,
                      table_name    TEXT,
                      sql_text      TEXT,
                      unsaved       INTEGER NOT NULL DEFAULT 0
                    )""");

            // 后加的列：老配置库升上来时补一列，已经有了就跳过
            addColumnIfMissing(st, "connections", "color", "TEXT");
            addColumnIfMissing(st, "connections", "group_name", "TEXT");
            addColumnIfMissing(st, "query_history", "favorite", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "query_history", "title", "TEXT");
            // 现场里记下这一页当时绑在哪条保存记录上：不记的话，恢复回来的页
            // 会丢掉用户起的名字，再按一次保存就又存出一条新的
            addColumnIfMissing(st, "session_tab", "saved_id", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    /**
     * 加一列，已经存在就当没事发生。
     *
     * <p>SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，只能先问一遍。
     * 直接吞掉异常也能跑，但那样连「加错列名」这种真问题也会被一起吞掉。
     */
    private void addColumnIfMissing(Statement st, String table, String column, String type)
            throws SQLException {
        try (java.sql.ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭本机库失败无需向用户报错
        }
    }
}
