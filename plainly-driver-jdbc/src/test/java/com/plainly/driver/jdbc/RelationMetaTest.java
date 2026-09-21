package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 外键的读取。
 *
 * <p>盯住两件容易错的事：复合外键必须归成一条（元数据是一列一行，按行读会拆成
 * 几条毫无意义的单列外键），以及方向不能弄反——「本表指向谁」和「谁指向本表」
 * 用的是同一个记录类型，读反了界面上就会把依赖关系说颠倒。
 */
@DisplayName("外键元数据 · 在 H2 上实际读取")
class RelationMetaTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("fk-probe")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_fk;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));

        conn.executeDdlBatch(List.of(
                "CREATE TABLE PARENT (A INT, B INT, NOTE VARCHAR(20), PRIMARY KEY (A, B))",
                "CREATE TABLE CHILD (ID INT PRIMARY KEY, PA INT, PB INT,"
                        + " CONSTRAINT FK_CHILD_PARENT FOREIGN KEY (PA, PB)"
                        + " REFERENCES PARENT (A, B) ON DELETE CASCADE)"));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("复合外键归成一条，列按顺序排好")
    void compositeKeyStaysOne() {
        List<ForeignKeyInfo> keys = conn.listForeignKeys(SCHEMA, "CHILD");
        assertEquals(1, keys.size(), "两列的外键是一条，不是两条");
        ForeignKeyInfo k = keys.get(0);
        assertEquals(List.of("PA", "PB"), k.columns());
        assertEquals(List.of("A", "B"), k.refColumns());
        assertEquals("PARENT", k.refTable());
        assertEquals("CHILD", k.table());
        assertTrue(k.name().contains("FK_CHILD_PARENT"), "外键名：" + k.name());
    }

    @Test
    @DisplayName("级联规则翻成 SQL 里的写法")
    void translatesRules() {
        ForeignKeyInfo k = conn.listForeignKeys(SCHEMA, "CHILD").get(0);
        assertEquals("CASCADE", k.onDelete());
        assertTrue("RESTRICT".equals(k.onUpdate()) || "NO ACTION".equals(k.onUpdate()),
                "未指定 ON UPDATE 时应是限制类规则，实得 " + k.onUpdate());
    }

    @Test
    @DisplayName("方向不能弄反")
    void directionsAreNotSwapped() {
        assertTrue(conn.listForeignKeys(SCHEMA, "PARENT").isEmpty(),
                "PARENT 自己没有外键");
        List<ForeignKeyInfo> pointingAtParent = conn.listReferencingKeys(SCHEMA, "PARENT");
        assertEquals(1, pointingAtParent.size(), "有一张表指着 PARENT");
        assertEquals("CHILD", pointingAtParent.get(0).table());
        assertEquals("PARENT", pointingAtParent.get(0).refTable());
        assertTrue(conn.listReferencingKeys(SCHEMA, "CHILD").isEmpty(),
                "没有表指着 CHILD");
    }

    @Test
    @DisplayName("没有触发器的表返回空表，不抛异常")
    void triggersOnPlainTable() {
        assertTrue(conn.listTriggers(SCHEMA, "CHILD").isEmpty());
    }
}
