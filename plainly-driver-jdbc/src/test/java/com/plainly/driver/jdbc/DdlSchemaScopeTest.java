package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL 必须落在调用方指定的那个库上，与连接此刻指向哪儿无关。
 *
 * <h2>这条不变量是被一个真实故障逼出来的</h2>
 * 「新建触发器」在 MySQL 上报 {@code No database selected}。原因是
 * {@code CREATE TRIGGER} 的触发器名<b>不带库限定</b>，它认的是连接的当前库；
 * 而连接配置里不填默认库是很常见的做法，那条连接压根没有当前库。
 *
 * <p>更麻烦的是这个错的表象：用户看到的是「点新建触发器就报这个」，
 * 完全联想不到跟连接配置里那个空着的「默认库」有关。
 *
 * <h2>为什么这里用「不带库限定的建表」来验</h2>
 * H2 上复现不了 MySQL 那句报错——它总有一个当前 schema。但真正要守的性质不是
 * 那句报错，而是<b>「不带库限定的 DDL 落在哪个库上」</b>。这一点 H2 上验得了，
 * 而且验的就是同一个机制：执行前有没有把连接切过去。
 */
@DisplayName("DDL · 落在指定的库上")
class DdlSchemaScopeTest {

    private static DbConnection conn;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("ddl-scope")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_ddl_scope;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @BeforeEach
    void reset() {
        conn.execute("DROP SCHEMA IF EXISTS OTHER CASCADE", 0);
        conn.execute("CREATE SCHEMA OTHER", 0);
        conn.execute("DROP TABLE IF EXISTS PUBLIC.SCOPE_PROBE", 0);
        // 把连接指到别处，模拟「当前库不是目标库」
        conn.useSchema("PUBLIC");
    }

    private static long countIn(String schema) {
        return Long.parseLong(conn.scalar(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE TABLE_SCHEMA = '" + schema + "'"
                        + " AND TABLE_NAME = 'SCOPE_PROBE'"));
    }

    @Test
    @DisplayName("带库名的那一版：不带限定的语句也落在指定的库里")
    void ddlLandsInRequestedSchema() {
        conn.executeDdlBatch("OTHER", List.of("CREATE TABLE SCOPE_PROBE (ID INT)"));

        assertEquals(1, countIn("OTHER"), "表应当建在 OTHER 里");
        assertEquals(0, countIn("PUBLIC"), "不该建到连接原先指着的那个库里");
    }

    @Test
    @DisplayName("不带库名的那一版：落在连接当前指着的库里——正是出故障的那条路")
    void plainBatchFollowsCurrentSchema() {
        conn.executeDdlBatch(List.of("CREATE TABLE SCOPE_PROBE (ID INT)"));

        assertEquals(1, countIn("PUBLIC"));
        assertEquals(0, countIn("OTHER"));
    }

    @Test
    @DisplayName("切过去之后连接就留在那儿，后续的语句也跟着走")
    void schemaStaysAfterBatch() {
        conn.executeDdlBatch("OTHER", List.of("CREATE TABLE SCOPE_PROBE (ID INT)"));
        // 紧接着一条不带库限定的语句：它该落在 OTHER 里，因为连接已经在那儿了。
        // 这不是附带效果，而是必须如此——建完表马上建索引、加注释是常见的组合，
        // 那几条如果落回原来的库，报的是「表不存在」，看着莫名其妙
        conn.executeDdlBatch(List.of("CREATE INDEX IDX_SCOPE ON SCOPE_PROBE (ID)"));

        assertTrue(Long.parseLong(conn.scalar(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES"
                        + " WHERE TABLE_SCHEMA = 'OTHER' AND TABLE_NAME = 'SCOPE_PROBE'")) > 0,
                "索引该建在 OTHER 里那张表上");
    }

    @Test
    @DisplayName("库名为空时不切库，也不报错——建库那种场景本来就没有目标库可切")
    void blankSchemaIsNoOp() {
        conn.useSchema("OTHER");
        conn.executeDdlBatch("", List.of("CREATE TABLE SCOPE_PROBE (ID INT)"));

        assertEquals(1, countIn("OTHER"), "空库名不该把连接从 OTHER 上挪走");
    }
}
