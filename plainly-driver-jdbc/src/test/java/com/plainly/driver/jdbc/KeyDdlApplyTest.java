package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多列索引与多列外键，真的建出来再读回来。
 *
 * <p>「语句拼对了」和「数据库认」是两件事。尤其是<b>列的顺序</b>：
 * 拼错顺序不会有任何报错，索引照样建得起来、外键照样加得上，
 * 只是它加速的查询变了、配对的列错了。所以这里必须建完再读回来逐个比对。
 */
@DisplayName("索引与外键 · 建出来读回来，顺序要对得上")
class KeyDdlApplyTest {

    private static final String SCHEMA = "PUBLIC";
    private static DbConnection conn;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("keys").setType(DbType.H2)
                .setFilePath("mem:plainly_keys;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.execute("CREATE TABLE region (country VARCHAR(8), city VARCHAR(32),"
                + " name VARCHAR(64), PRIMARY KEY (country, city))", 0);
        conn.execute("CREATE TABLE shop (id BIGINT PRIMARY KEY,"
                + " country VARCHAR(8), city VARCHAR(32), title VARCHAR(64))", 0);
    }

    @AfterAll
    static void close() {
        conn.close();
    }

    private static IndexInfo indexNamed(String name) {
        return conn.describeTable(SCHEMA, "SHOP").indexes().stream()
                .filter(i -> i.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("多列索引：列的顺序原样保留")
    void multiColumnIndexKeepsOrder() {
        IndexInfo wanted = new IndexInfo("IDX_SHOP_PLACE",
                List.of("CITY", "COUNTRY"), false, false);
        conn.executeDdlBatch(List.of(
                conn.dialect().createIndexDdl(SCHEMA, "SHOP", wanted)));

        IndexInfo got = indexNamed("IDX_SHOP_PLACE");
        assertEquals(List.of("CITY", "COUNTRY"), got.columns(),
                "(CITY, COUNTRY) 和 (COUNTRY, CITY) 是两个不同的索引，顺序不能乱");
        assertFalse(got.unique());
    }

    @Test
    @DisplayName("唯一索引建得出来，也删得掉")
    void uniqueIndexRoundTrip() {
        conn.executeDdlBatch(List.of(conn.dialect().createIndexDdl(SCHEMA, "SHOP",
                new IndexInfo("IDX_SHOP_TITLE", List.of("TITLE"), true, false))));
        assertTrue(indexNamed("IDX_SHOP_TITLE").unique());

        conn.executeDdlBatch(List.of(
                conn.dialect().dropIndexDdl(SCHEMA, "SHOP", "IDX_SHOP_TITLE")));
        org.junit.jupiter.api.Assertions.assertNull(indexNamed("IDX_SHOP_TITLE"));
    }

    @Test
    @DisplayName("「改索引」其实是删了重建，两条语句一起跑得通")
    void editIndexIsDropThenCreate() {
        conn.executeDdlBatch(List.of(conn.dialect().createIndexDdl(SCHEMA, "SHOP",
                new IndexInfo("IDX_SHOP_EDIT", List.of("COUNTRY"), false, false))));

        // 界面上的「改」就是发这两条
        conn.executeDdlBatch(List.of(
                conn.dialect().dropIndexDdl(SCHEMA, "SHOP", "IDX_SHOP_EDIT"),
                conn.dialect().createIndexDdl(SCHEMA, "SHOP",
                        new IndexInfo("IDX_SHOP_EDIT", List.of("COUNTRY", "CITY"),
                                true, false))));

        IndexInfo got = indexNamed("IDX_SHOP_EDIT");
        assertEquals(List.of("COUNTRY", "CITY"), got.columns());
        assertTrue(got.unique(), "唯一性也该跟着改过来");
    }

    @Test
    @DisplayName("多列外键：两边的列按位置配对，读回来顺序不变")
    void compositeForeignKey() {
        conn.executeDdlBatch(List.of(conn.dialect().addForeignKeyDdl(
                SCHEMA, "SHOP", "FK_SHOP_REGION",
                List.of("COUNTRY", "CITY"),
                SCHEMA, "REGION", List.of("COUNTRY", "CITY"),
                "CASCADE", "SET NULL")));

        List<ForeignKeyInfo> keys = conn.listForeignKeys(SCHEMA, "SHOP");
        ForeignKeyInfo fk = keys.stream()
                .filter(k -> k.name().equalsIgnoreCase("FK_SHOP_REGION"))
                .findFirst().orElseThrow();

        assertEquals(List.of("COUNTRY", "CITY"), fk.columns());
        assertEquals(List.of("COUNTRY", "CITY"), fk.refColumns());
        assertEquals("REGION", fk.refTable());
        assertEquals("SET NULL", fk.onDelete());
        assertEquals("CASCADE", fk.onUpdate(), "ON UPDATE 之前根本没接，现在要真的传下去");
    }

    @Test
    @DisplayName("外键删得掉")
    void dropForeignKey() {
        conn.executeDdlBatch(List.of(conn.dialect().addForeignKeyDdl(
                SCHEMA, "SHOP", "FK_SHOP_TMP", List.of("COUNTRY", "CITY"),
                SCHEMA, "REGION", List.of("COUNTRY", "CITY"), null, null)));
        assertTrue(hasKey("FK_SHOP_TMP"));

        conn.executeDdlBatch(List.of(
                conn.dialect().dropForeignKeyDdl(SCHEMA, "SHOP", "FK_SHOP_TMP")));
        assertFalse(hasKey("FK_SHOP_TMP"));
    }

    private static boolean hasKey(String name) {
        return conn.listForeignKeys(SCHEMA, "SHOP").stream()
                .anyMatch(k -> k.name().equalsIgnoreCase(name));
    }

    @Test
    @DisplayName("唯一索引撞上既有重复值就该失败，且原样报出来")
    void uniqueIndexFailsOnDuplicates() {
        conn.execute("CREATE TABLE dup (v VARCHAR(8))", 0);
        conn.execute("INSERT INTO dup VALUES ('a'), ('a')", 0);

        RuntimeException boom = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> conn.executeDdlBatch(List.of(conn.dialect().createIndexDdl(
                        SCHEMA, "DUP",
                        new IndexInfo("IDX_DUP", List.of("V"), true, false)))));
        assertTrue(boom.getMessage() != null && !boom.getMessage().isBlank(),
                "失败要说得出话，不能是个空异常");
    }
}
