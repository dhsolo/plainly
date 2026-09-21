package com.plainly.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import java.io.IOException;
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
 * 逻辑备份与还原。
 *
 * <p>备份的价值在于还原。所以这里不看文件长什么样，而是备份出来之后
 * 还到一个空库里，再逐位比对高精度列——「备份成功」四个字不算数。
 */
@DisplayName("备份与还原 · 备了要能还回来")
class BackupTest {

    private static final String HUGE = "12345678901234567890.1234567890";

    private static DbConnection source;
    private static DbConnection target;
    private static final String SCHEMA = "PUBLIC";

    @TempDir
    Path dir;

    @BeforeAll
    static void open() {
        source = JdbcConnections.open(new ConnectionConfig()
                .setName("bk-src").setType(DbType.H2)
                .setFilePath("mem:plainly_bk_src;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        target = JdbcConnections.open(new ConnectionConfig()
                .setName("bk-dst").setType(DbType.H2)
                .setFilePath("mem:plainly_bk_dst;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));

        source.executeDdlBatch(List.of(
                "CREATE TABLE ITEMS (ID INT PRIMARY KEY, PRICE DECIMAL(38,10),"
                        + " LABEL VARCHAR(32))",
                "CREATE INDEX IDX_ITEMS_LABEL ON ITEMS (LABEL)",
                "INSERT INTO ITEMS VALUES (1, " + HUGE + ", '高精度')",
                "INSERT INTO ITEMS VALUES (2, 0.0000000001, NULL)",
                "INSERT INTO ITEMS VALUES (3, -5600, '含''引号')"));
    }

    @AfterAll
    static void close() {
        if (source != null) {
            source.close();
        }
        if (target != null) {
            target.close();
        }
    }

    /**
     * 序列必须进备份，而且起始值要取<b>当前</b>值。
     *
     * <h2>为什么这一条值得单独钉住</h2>
     * 序列漏掉或者起始值取错，备份和还原<b>都不会报错</b>——文件生成了、
     * 还原也跑通了，直到某次插入撞上主键冲突才暴露，那时离还原往往已经过去很久。
     *
     * <p>真机上已经验过四家（H2、PostgreSQL 16、Oracle 23ai、达梦 8），
     * 见 {@code tools/SequenceBackupProbe.java}。这里守的是不依赖外部实例的那一半。
     */
    @Test
    @DisplayName("序列进了备份，起始值是当前值而不是当初的起始值")
    void sequencesAreBackedUpAtTheirCurrentValue() throws Exception {
        source.executeDdlBatch(List.of(
                "CREATE SEQUENCE SEQ_BK_PROBE START WITH 100 INCREMENT BY 7"));
        try {
            // 取三次：100、107、114，下一个是 121
            for (int i = 0; i < 3; i++) {
                source.execute("SELECT NEXT VALUE FOR SEQ_BK_PROBE", 1);
            }

            Path file = dir.resolve("seq.sql");
            BackupService.Result result =
                    BackupService.backup(source, SCHEMA, file, false, m -> { });
            String sql = Files.readString(file);

            assertTrue(result.sequences() >= 1, "统计里应当算上序列");
            assertTrue(sql.contains("CREATE SEQUENCE"), "备份里应当有 CREATE SEQUENCE");
            assertTrue(sql.contains("SEQ_BK_PROBE"), "应当是这个序列");
            assertTrue(sql.contains("START WITH 121"),
                    "起始值应当是当前的 121，不是当初的 100。实际内容：" + sql);
            assertTrue(sql.contains("INCREMENT BY 7"), "步长应当带过去");
        } finally {
            source.executeDdlBatch(List.of("DROP SEQUENCE SEQ_BK_PROBE"));
        }
    }

    @Test
    @DisplayName("备份再还原，行数与高精度值都对得上")
    void roundTrip() throws IOException {
        Path file = dir.resolve("backup.sql");
        BackupService.Result result = BackupService.backup(source, SCHEMA, file, true, s -> { });

        assertEquals(1, result.tables());
        assertEquals(3, result.rows());
        assertTrue(Files.size(file) > 0);

        target.executeDdlBatch(List.of("DROP TABLE IF EXISTS ITEMS"));
        int executed = BackupService.restore(target, SCHEMA, file, s -> { });
        assertTrue(executed >= 4, "至少建表 + 建索引 + 三条 INSERT，实得 " + executed);

        QueryResult count = target.execute("SELECT COUNT(*) FROM ITEMS", 1);
        assertEquals("3", count.rows().get(0).get(0));

        QueryResult price = target.execute("SELECT PRICE FROM ITEMS WHERE ID = 1", 1);
        assertEquals(HUGE, price.rows().get(0).get(0), "备份还原后一位不差");
    }

    @Test
    @DisplayName("只备结构时不写数据")
    void structureOnly() throws IOException {
        Path file = dir.resolve("schema-only.sql");
        BackupService.Result result = BackupService.backup(source, SCHEMA, file, false, s -> { });

        assertEquals(0, result.rows());
        String script = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(script.contains("CREATE TABLE"), script);
        assertTrue(!script.contains("INSERT INTO"), "只备结构就不该有 INSERT");
    }

    @Test
    @DisplayName("文件头写明这不是物理备份")
    void headerStatesLimits() throws IOException {
        Path file = dir.resolve("header.sql");
        BackupService.backup(source, SCHEMA, file, false, s -> { });
        String head = Files.readString(file, StandardCharsets.UTF_8);
        // 头部必须如实说清「装了什么、没装什么」。
        // 这一条随着备份内容扩到视图 / 例程 / 触发器一起改过：
        // 头部要是还写着「不含存储过程」，那就成了一句假话——
        // 而备份文件的头部正是用户唯一会读的那几行
        assertTrue(head.contains("视图"), head.substring(0, Math.min(400, head.length())));
        assertTrue(head.contains("存储过程"), head.substring(0, Math.min(400, head.length())));
        assertTrue(head.contains("触发器"), head.substring(0, Math.min(400, head.length())));
        assertTrue(head.contains("用户与权限"), "还是要说清楚哪些确实没装");
        assertTrue(head.contains("不是一致性快照"));
        assertTrue(head.contains("DEFINER"), "例程带着 DEFINER，还原到别的实例会受影响，得说");
    }

    @Test
    @DisplayName("外键要备份进去，而且排在所有表和数据之后")
    void foreignKeysAreBackedUp() throws IOException {
        source.executeDdlBatch(List.of(
                "DROP TABLE IF EXISTS FK_CHILD",
                "DROP TABLE IF EXISTS FK_PARENT",
                "CREATE TABLE FK_PARENT (ID INT PRIMARY KEY)",
                "CREATE TABLE FK_CHILD (ID INT PRIMARY KEY, PID INT,"
                        + " CONSTRAINT FK_CHILD_PID FOREIGN KEY (PID) REFERENCES FK_PARENT(ID))"));
        try {
            Path file = dir.resolve("fk.sql");
            BackupService.Result result = BackupService.backup(source, SCHEMA, file, true, s -> { });
            String text = Files.readString(file, StandardCharsets.UTF_8);

            // 建表语句里不含外键（createTableDdl 只写列和主键），所以外键必须单独一段。
            // 漏掉的话，还原出来的库表和数据都在、参照完整性一条不剩，而且全程不报错
            assertTrue(result.foreignKeys() > 0, "外键没有备份进去：" + result.describe());
            assertTrue(text.contains("FOREIGN KEY"), text);
            assertTrue(text.contains("FK_CHILD_PID"), "约束名要保住，否则以后删不掉：" + text);

            int fkAt = text.indexOf("========== 外键");
            int lastCreateTable = text.lastIndexOf("CREATE TABLE");
            assertTrue(fkAt > lastCreateTable,
                    "外键必须排在所有建表语句之后——它指向的表未必在自己前面");
        } finally {
            // 这几张表是建在共享的那个库上的，不清掉会把别的用例数出来的表数搞乱。
            // 放在 finally 里：断言失败时也得清，否则一次失败会连累后面所有用例
            source.executeDdlBatch(List.of(
                    "DROP TABLE IF EXISTS FK_CHILD",
                    "DROP TABLE IF EXISTS FK_PARENT"));
        }
    }

    @Test
    @DisplayName("值里的引号与 NULL 都能原样还回来")
    void quotesAndNullsSurvive() throws IOException {
        Path file = dir.resolve("quotes.sql");
        BackupService.backup(source, SCHEMA, file, true, s -> { });
        target.executeDdlBatch(List.of("DROP TABLE IF EXISTS ITEMS"));
        BackupService.restore(target, SCHEMA, file, s -> { });

        QueryResult r = target.execute("SELECT LABEL FROM ITEMS WHERE ID = 3", 1);
        assertEquals("含'引号", r.rows().get(0).get(0));

        QueryResult nulls = target.execute("SELECT LABEL FROM ITEMS WHERE ID = 2", 1);
        assertEquals(null, nulls.rows().get(0).get(0));
    }
}
