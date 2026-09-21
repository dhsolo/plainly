package com.plainly.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 模型快照与版本对比。
 *
 * <p>快照的意义在于「过一阵子回来还能比」。所以这里存了再读回来，
 * 改动库之后再比一次，看差异认不认得出来——存得下来但比不出差异的快照没有价值。
 */
@DisplayName("模型快照 · 存下来，改完还能比出差异")
class ModelSnapshotTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";

    @TempDir
    Path dir;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("snap-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_snap;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.executeDdlBatch(List.of(
                "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(64))"));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("存下来再读回来，结构一模一样")
    void roundTrip() {
        Path file = dir.resolve("v1.snapshot");
        ModelSnapshot.save(conn, SCHEMA, file);

        List<TableStructure> loaded = ModelSnapshot.load(file);
        List<TableStructure> live = ModelSnapshot.read(conn, SCHEMA);
        assertEquals(live.size(), loaded.size());

        // 和当前库比应当没有任何差异
        List<SchemaChange> changes = ModelSnapshot.compare(loaded, live);
        assertTrue(changes.isEmpty(), "刚存下来就该零差异，实得 " + changes);
    }

    @Test
    @DisplayName("改了库之后，比得出加了哪一列")
    void detectsAddedColumn() {
        Path file = dir.resolve("v2.snapshot");
        ModelSnapshot.save(conn, SCHEMA, file);
        List<TableStructure> before = ModelSnapshot.load(file);

        conn.executeDdlBatch(List.of("ALTER TABLE ORDERS ADD COLUMN CURRENCY CHAR(3)"));
        try {
            List<TableStructure> now = ModelSnapshot.read(conn, SCHEMA);
            List<SchemaChange> changes = ModelSnapshot.compare(now, before);
            assertTrue(!changes.isEmpty(), "加了一列就该有差异");
            // describe() 是给人看的概述（「修改表 ORDERS」），具体动了哪一列在变更本身里
            assertTrue(changes.toString().contains("CURRENCY"), changes.toString());
        } finally {
            conn.executeDdlBatch(List.of("ALTER TABLE ORDERS DROP COLUMN CURRENCY"));
        }
    }

    @Test
    @DisplayName("快照是文本，能进版本库也能用 diff 工具看")
    void snapshotIsReadableText() throws Exception {
        Path file = dir.resolve("v3.snapshot");
        ModelSnapshot.save(conn, SCHEMA, file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# Plainly 模型快照"), text.substring(0, 40));
        assertTrue(text.contains("TABLE\tORDERS"), text);
        assertTrue(text.contains("DECIMAL"), text);
    }

    @Test
    @DisplayName("不是快照的文件要当场拒绝，不能当成空模型")
    void rejectsForeignFile() throws Exception {
        Path file = dir.resolve("random.txt");
        Files.writeString(file, "这不是快照", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> ModelSnapshot.load(file));
    }
}
