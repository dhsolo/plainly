package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.jdbc.dialect.Dialects;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 建库、清空表、删表。
 *
 * <p>这三条的方言差异都有实际后果：SQLite 没有 {@code TRUNCATE} 也建不了库，
 * MySQL 的「库」是 database 而不是 schema。拼错了不会在生成时报错，
 * 要等用户在右键菜单上点下去、语句发到数据库才炸。
 */
@DisplayName("建库 / 清空 / 删表 · 各家差异钉死在这一层")
class SchemaAndTruncateDdlTest {

    private static final SqlDialect MYSQL = Dialects.forType(DbType.MYSQL);
    private static final SqlDialect PG = Dialects.forType(DbType.POSTGRESQL);
    private static final SqlDialect SQLITE = Dialects.forType(DbType.SQLITE);
    private static final SqlDialect H2 = Dialects.forType(DbType.H2);

    // ------------------------------------------------------------------ 建库

    @Test
    @DisplayName("MySQL 的「库」是 database，不是 schema")
    void mysqlCreatesDatabase() {
        assertEquals("CREATE DATABASE `shop`", MYSQL.createSchemaDdl("shop", null, null));
    }

    @Test
    @DisplayName("MySQL 能指定字符集和排序规则")
    void mysqlCharset() {
        assertEquals("CREATE DATABASE `shop` DEFAULT CHARACTER SET utf8mb4"
                        + " COLLATE utf8mb4_general_ci",
                MYSQL.createSchemaDdl("shop", "utf8mb4", "utf8mb4_general_ci"));
        assertTrue(MYSQL.supportsSchemaCharset());
    }

    @Test
    @DisplayName("H2 / PostgreSQL 建的是 schema，也不需要挑字符集")
    void othersCreateSchema() {
        assertEquals("CREATE SCHEMA \"shop\"", H2.createSchemaDdl("shop", null, null));
        assertEquals("CREATE SCHEMA \"shop\"", PG.createSchemaDdl("shop", null, null));
        assertFalse(H2.supportsSchemaCharset());
        assertFalse(PG.supportsSchemaCharset());
    }

    @Test
    @DisplayName("SQLite 建不了库，而且要说清为什么")
    void sqliteCannotCreateSchema() {
        String why = SQLITE.schemaCreationUnsupportedReason();
        assertNotNull(why);
        assertTrue(why.contains("文件"), "要讲清楚是它的模型使然：" + why);
        assertNull(MYSQL.schemaCreationUnsupportedReason());
        assertNull(PG.schemaCreationUnsupportedReason());
    }

    // ------------------------------------------------------------------ 清空

    @Test
    @DisplayName("多数家用 TRUNCATE")
    void truncate() {
        assertEquals("TRUNCATE TABLE `shop`.`orders`",
                MYSQL.truncateTableDdl("shop", "orders"));
        assertEquals("TRUNCATE TABLE \"shop\".\"orders\"",
                PG.truncateTableDdl("shop", "orders"));
    }

    @Test
    @DisplayName("SQLite 没有 TRUNCATE，退回不带 WHERE 的 DELETE")
    void sqliteTruncateIsDelete() {
        assertEquals("DELETE FROM \"orders\"", SQLITE.truncateTableDdl("main", "orders"));
    }

    @Test
    @DisplayName("每家都要讲清「清空」在自己这儿到底是什么行为")
    void truncateNotesAreSpecific() {
        assertTrue(MYSQL.truncateNote().contains("AUTO_INCREMENT"), MYSQL.truncateNote());
        assertTrue(MYSQL.truncateNote().contains("回滚"), MYSQL.truncateNote());
        // SQLite 上自增不归零，这跟别家相反，必须说
        assertTrue(SQLITE.truncateNote().contains("AUTOINCREMENT"), SQLITE.truncateNote());
        assertTrue(SQLITE.truncateNote().contains("DELETE"), SQLITE.truncateNote());
        assertTrue(PG.truncateNote().contains("回滚"), PG.truncateNote());
    }

    // ------------------------------------------------------------------ 真跑一遍

    @Test
    @DisplayName("在 H2 上真的建库、清空、删表")
    void roundTripOnH2() {
        try (DbConnection conn = JdbcConnections.open(new ConnectionConfig()
                .setName("ddl3").setType(DbType.H2)
                .setFilePath("mem:plainly_ddl3;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""))) {

            conn.executeDdlBatch(List.of(conn.dialect().createSchemaDdl("SHOP", null, null)));
            assertTrue(conn.listSchemas().stream().anyMatch(s -> s.name().equals("SHOP")),
                    "新建的库要能在库列表里看到");

            conn.execute("CREATE TABLE SHOP.ORDERS (ID BIGINT PRIMARY KEY, NOTE VARCHAR(32))", 0);
            conn.execute("INSERT INTO SHOP.ORDERS VALUES (1,'a'), (2,'b')", 0);
            assertEquals("2", conn.scalar("SELECT COUNT(*) FROM SHOP.ORDERS"));

            conn.executeDdlBatch(List.of(conn.dialect().truncateTableDdl("SHOP", "ORDERS")));
            assertEquals("0", conn.scalar("SELECT COUNT(*) FROM SHOP.ORDERS"),
                    "清空之后行数归零，表还在");
            assertFalse(conn.describeTable("SHOP", "ORDERS").columns().isEmpty(),
                    "清空的是数据不是结构");

            conn.executeDdlBatch(List.of(conn.dialect().dropTableDdl("SHOP", "ORDERS")));
            assertTrue(conn.listTables("SHOP").stream()
                            .noneMatch(t -> t.name().equals("ORDERS")),
                    "删了就不该还在表列表里");
        }
    }
}
