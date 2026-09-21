package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import com.plainly.driver.query.ConflictPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主键冲突时的三种处理。
 *
 * <p>三家的写法完全不同（{@code INSERT IGNORE} / {@code ON CONFLICT} / {@code MERGE}），
 * 拼错了不会在生成阶段暴露，只会在真跑的时候报语法错。所以 H2 这一路真跑，
 * 其余方言至少断言生成的语句长什么样。
 */
@DisplayName("插入冲突策略")
class ConflictInsertTest {

    private static DbConnection conn;
    private static List<ColumnInfo> columns;
    private static List<ColumnInfo> keys;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "CONFLICT_PROBE";
    private static final String HUGE = "12345678901234567890.1234567890";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("conflict-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_conflict;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.executeDdlBatch(List.of(
                "CREATE TABLE " + TABLE + " (ID INT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(32))"));
        TableStructure structure = conn.describeTable(SCHEMA, TABLE);
        columns = structure.columns();
        keys = columns.stream().filter(ColumnInfo::primaryKey).toList();
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @BeforeEach
    void reset() {
        conn.executeDdlBatch(List.of("DELETE FROM " + TABLE));
        conn.executeBatch(conn.dialect().buildInsert(SCHEMA, TABLE, columns),
                List.of(List.of("1", "1.5", "原来的")));
    }

    private static String noteOf(int id) {
        QueryResult r = conn.execute("SELECT NOTE FROM " + TABLE + " WHERE ID = " + id, 1);
        return r.rows().isEmpty() ? null : r.rows().get(0).get(0);
    }

    private static long count() {
        return Long.parseLong(conn.execute("SELECT COUNT(*) FROM " + TABLE, 1)
                .rows().get(0).get(0));
    }

    @Test
    @DisplayName("跳过：撞上的不动，其余照写")
    void skipKeepsExisting() {
        SqlDialect.PreparedSql sql = conn.dialect()
                .buildInsert(SCHEMA, TABLE, columns, keys, ConflictPolicy.SKIP);
        assertNotNull(sql);
        conn.executeBatch(sql, List.of(
                List.of("1", "9.9", "新的"),
                List.of("2", HUGE, "另一行")));

        assertEquals("原来的", noteOf(1), "撞上主键的那行不该被改");
        assertEquals("另一行", noteOf(2), "不冲突的行要写进去");
        assertEquals(2, count());
    }

    @Test
    @DisplayName("更新：撞上的用新值覆盖")
    void updateOverwrites() {
        SqlDialect.PreparedSql sql = conn.dialect()
                .buildInsert(SCHEMA, TABLE, columns, keys, ConflictPolicy.UPDATE);
        assertNotNull(sql);
        conn.executeBatch(sql, List.of(
                List.of("1", HUGE, "覆盖后"),
                List.of("2", "2.5", "新增")));

        assertEquals("覆盖后", noteOf(1));
        assertEquals(2, count());
        // 覆盖进去的高精度值一位不差
        QueryResult r = conn.execute("SELECT AMOUNT FROM " + TABLE + " WHERE ID = 1", 1);
        assertEquals(HUGE, r.rows().get(0).get(0));
    }

    @Test
    @DisplayName("中止：撞上就抛，交给调用方决定")
    void abortThrows() {
        SqlDialect.PreparedSql sql = conn.dialect()
                .buildInsert(SCHEMA, TABLE, columns, keys, ConflictPolicy.ABORT);
        assertTrue(sql.sql().toUpperCase().startsWith("INSERT INTO"), sql.sql());
        try {
            conn.executeBatch(sql, List.of(List.of("1", "1.0", "撞车")));
            org.junit.jupiter.api.Assertions.fail("应当抛出主键冲突");
        } catch (RuntimeException expected) {
            assertEquals("原来的", noteOf(1));
        }
    }

    @Test
    @DisplayName("没有主键时，跳过与更新都无从判定")
    void noKeyMeansNoPolicy() {
        assertTrue(conn.dialect().buildInsert(SCHEMA, TABLE, columns, List.of(),
                ConflictPolicy.UPDATE) == null);
    }

    @Test
    @DisplayName("MySQL 用 INSERT IGNORE 与 ON DUPLICATE KEY UPDATE")
    void mysqlSyntax() {
        SqlDialect mysql = new Dialects.MySqlDialect();
        String skip = mysql.buildInsert("db", "t", columns, keys, ConflictPolicy.SKIP).sql();
        assertTrue(skip.startsWith("INSERT IGNORE INTO"), skip);

        String update = mysql.buildInsert("db", "t", columns, keys, ConflictPolicy.UPDATE).sql();
        assertTrue(update.contains("ON DUPLICATE KEY UPDATE"), update);
        assertTrue(update.contains("VALUES(`AMOUNT`)"), update);
    }

    @Test
    @DisplayName("PostgreSQL 用 ON CONFLICT ... EXCLUDED")
    void postgresSyntax() {
        SqlDialect pg = new Dialects.PostgresDialect();
        String skip = pg.buildInsert("public", "t", columns, keys, ConflictPolicy.SKIP).sql();
        assertTrue(skip.contains("ON CONFLICT (\"ID\") DO NOTHING"), skip);

        String update = pg.buildInsert("public", "t", columns, keys, ConflictPolicy.UPDATE).sql();
        assertTrue(update.contains("DO UPDATE SET"), update);
        assertTrue(update.contains("EXCLUDED."), update);
    }

    @Test
    @DisplayName("SQLite 用 INSERT OR IGNORE 与小写的 excluded")
    void sqliteSyntax() {
        SqlDialect sqlite = new Dialects.SqliteDialect();
        String skip = sqlite.buildInsert("main", "t", columns, keys, ConflictPolicy.SKIP).sql();
        assertTrue(skip.startsWith("INSERT OR IGNORE INTO"), skip);

        String update = sqlite.buildInsert("main", "t", columns, keys, ConflictPolicy.UPDATE).sql();
        assertTrue(update.contains("excluded."), update);
    }

    @Test
    @DisplayName("三种策略的占位符数量都等于列数")
    void placeholdersMatchColumns() {
        for (ConflictPolicy policy : ConflictPolicy.values()) {
            SqlDialect.PreparedSql sql = conn.dialect()
                    .buildInsert(SCHEMA, TABLE, columns, keys, policy);
            assertEquals(columns.size(), sql.boundColumns().size(), policy.name());
        }
    }
}
