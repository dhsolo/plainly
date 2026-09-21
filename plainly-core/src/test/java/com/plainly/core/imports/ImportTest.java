package com.plainly.core.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.query.ConflictPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 导入。
 *
 * <p>最要紧的一条：CSV 里是什么字符，进库就得是什么值。所以用一个 30 位的十进制数
 * 走完整条路——解析、映射、绑定、入库、再读回来比对。中间任何一步走了 double，
 * 这个数都会变，而且不会有任何报错。
 */
@DisplayName("导入 · CSV 到 H2")
class ImportTest {

    /** 30 位有效数字，double 只能保 15 位。 */
    private static final String HUGE = "12345678901234567890.1234567890";

    private static DbConnection conn;
    private static List<ColumnInfo> columns;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "IMPORT_PROBE";

    @TempDir
    Path dir;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("import-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_import;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.executeDdlBatch(List.of(
                "CREATE TABLE " + TABLE + " (ID INT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NOTE VARCHAR(64))"));
        columns = conn.describeTable(SCHEMA, TABLE).columns();
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @BeforeEach
    void clean() {
        conn.executeDdlBatch(List.of("DELETE FROM " + TABLE));
    }

    private Path csv(String content) throws IOException {
        Path file = dir.resolve("in.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private ImportOptions options(Path file) {
        return new ImportOptions()
                .setSource(file)
                .setHasHeader(true)
                .setConflictPolicy(ConflictPolicy.ABORT)
                .setWriteErrorFile(false)
                .setMappings(List.of(
                        new ImportOptions.ColumnMapping(0, "id", "ID"),
                        new ImportOptions.ColumnMapping(1, "amount", "AMOUNT"),
                        new ImportOptions.ColumnMapping(2, "note", "NOTE")));
    }

    private static String amountOf(int id) {
        QueryResult r = conn.execute("SELECT AMOUNT FROM " + TABLE + " WHERE ID = " + id, 1);
        return r.rows().isEmpty() ? null : r.rows().get(0).get(0);
    }

    // ------------------------------------------------------------------ 解析

    @Test
    @DisplayName("引号里的分隔符和换行不算数")
    void csvRespectsQuotes() throws IOException {
        Path file = csv("id,amount,note\n1,1.5,\"含逗号, 和\r\n换行\"\n");
        List<List<String>> rows = CsvParser.head(file, ',', StandardCharsets.UTF_8, 10);
        assertEquals(2, rows.size(), "引号里的换行不该把一行切成两行");
        // 引号里的字节原样保留，包括 CRLF——值里是什么就是什么，不替用户改写
        assertEquals("含逗号, 和\r\n换行", rows.get(1).get(2));
    }

    @Test
    @DisplayName("双写的引号是一个引号")
    void csvUnescapesQuotes() throws IOException {
        Path file = csv("a\n\"她说\"\"好\"\"\"\n");
        List<List<String>> rows = CsvParser.head(file, ',', StandardCharsets.UTF_8, 10);
        assertEquals("她说\"好\"", rows.get(1).get(0));
    }

    @Test
    @DisplayName("吃掉 BOM，否则第一列列名永远匹配不上")
    void csvStripsBom() throws IOException {
        Path file = dir.resolve("bom.csv");
        Files.writeString(file, "﻿id,amount\n1,2\n", StandardCharsets.UTF_8);
        List<List<String>> rows = CsvParser.head(file, ',', StandardCharsets.UTF_8, 5);
        assertEquals("id", rows.get(0).get(0));
    }

    @Test
    @DisplayName("末尾没有换行的最后一行也要读到")
    void csvKeepsLastLine() throws IOException {
        Path file = csv("id\n1\n2");
        assertEquals(3, CsvParser.head(file, ',', StandardCharsets.UTF_8, 10).size());
    }

    // ------------------------------------------------------------------ 精度

    @Test
    @DisplayName("按文本读入：30 位小数一位不差地进库")
    void textModeKeepsEveryDigit() throws IOException {
        Path file = csv("id,amount,note\n1," + HUGE + ",高精度\n");
        Importer.Result r = Importer.run(conn, SCHEMA, TABLE, columns,
                options(file).setNumberMode(ImportOptions.NumberMode.TEXT), n -> { });

        assertEquals(1, r.read());
        assertEquals(1, r.written());
        assertEquals(HUGE, amountOf(1), "文件里是什么，库里就该是什么");
    }

    @Test
    @DisplayName("按 double 读入：同一个值会被截断——这是用户选的，但结果得是真的")
    void doubleModeLosesDigits() throws IOException {
        Path file = csv("id,amount,note\n1," + HUGE + ",走 double\n");
        Importer.run(conn, SCHEMA, TABLE, columns,
                options(file).setNumberMode(ImportOptions.NumberMode.DOUBLE), n -> { });

        String stored = amountOf(1);
        assertTrue(stored != null && !stored.startsWith(HUGE.substring(0, 20)),
                "选了 double 就该看到损失，实得 " + stored);
    }

    // ------------------------------------------------------------------ 行为

    @Test
    @DisplayName("试运行不写库")
    void dryRunWritesNothing() throws IOException {
        Path file = csv("id,amount,note\n1,1.5,试运行\n2,2.5,试运行\n");
        Importer.Result r = Importer.run(conn, SCHEMA, TABLE, columns,
                options(file).setDryRun(true), n -> { });

        assertEquals(2, r.read());
        assertEquals(2, r.written(), "试运行照样报告会写多少行");
        assertNull(amountOf(1), "但库里一行都不该有");
    }

    @Test
    @DisplayName("不映射的列不进库")
    void unmappedColumnIsSkipped() throws IOException {
        Path file = csv("id,amount,note\n1,1.5,不要这一列\n");
        ImportOptions o = options(file).setMappings(List.of(
                new ImportOptions.ColumnMapping(0, "id", "ID"),
                new ImportOptions.ColumnMapping(1, "amount", "AMOUNT"),
                new ImportOptions.ColumnMapping(2, "note", null)));
        Importer.run(conn, SCHEMA, TABLE, columns, o, n -> { });

        QueryResult r = conn.execute("SELECT NOTE FROM " + TABLE + " WHERE ID = 1", 1);
        assertNull(r.rows().get(0).get(0));
    }

    @Test
    @DisplayName("空值：可空列当作 NULL，不是空字符串")
    void blankBecomesNull() {
        ColumnInfo nullable = new ColumnInfo("NOTE", "VARCHAR", TypeCategory.STRING,
                64, 0, true, false, false, null, "", 3);
        assertNull(Importer.convert("", nullable, ImportOptions.NumberMode.TEXT));
        assertNull(Importer.convert("   ", nullable, ImportOptions.NumberMode.TEXT));
    }

    @Test
    @DisplayName("数值列里的非数字当场报出来，不等到入库")
    void badNumberIsReported() throws IOException {
        Path file = csv("id,amount,note\n1,abc,坏值\n2,2.5,好值\n");
        Importer.Result r = Importer.run(conn, SCHEMA, TABLE, columns,
                options(file).setConflictPolicy(ConflictPolicy.SKIP), n -> { });

        assertEquals(2, r.read());
        assertEquals(1, r.failed());
        assertEquals(1, r.written());
        assertTrue(r.problems().get(0).contains("第 1 行"), r.problems().toString());
    }

    @Test
    @DisplayName("失败行写进 .err.csv，改完能直接再导一次")
    void failedRowsGoToErrorFile() throws IOException {
        Path file = csv("id,amount,note\n1,abc,坏值\n");
        Importer.Result r = Importer.run(conn, SCHEMA, TABLE, columns,
                options(file).setWriteErrorFile(true).setConflictPolicy(ConflictPolicy.SKIP),
                n -> { });

        assertTrue(r.errorFile() != null && Files.exists(r.errorFile()));
        assertTrue(Files.readString(r.errorFile(), StandardCharsets.UTF_8).contains("abc"));
        assertTrue(r.errorFile().getFileName().toString().endsWith(".err.csv"));
    }

    @Test
    @DisplayName("主键冲突：跳过时保留已有行")
    void conflictSkipKeepsExisting() throws IOException {
        Importer.run(conn, SCHEMA, TABLE, columns,
                options(csv("id,amount,note\n1,1.5,先来的\n")), n -> { });
        Importer.run(conn, SCHEMA, TABLE, columns,
                options(csv("id,amount,note\n1,9.9,后来的\n"))
                        .setConflictPolicy(ConflictPolicy.SKIP), n -> { });

        QueryResult r = conn.execute("SELECT NOTE FROM " + TABLE + " WHERE ID = 1", 1);
        assertEquals("先来的", r.rows().get(0).get(0));
    }

    @Test
    @DisplayName("一列都没映射时直接拒绝，而不是插出一堆空行")
    void refusesEmptyMapping() throws IOException {
        Path file = csv("id\n1\n");
        ImportOptions o = options(file).setMappings(List.of(
                new ImportOptions.ColumnMapping(0, "id", null)));
        assertThrows(RuntimeException.class,
                () -> Importer.run(conn, SCHEMA, TABLE, columns, o, n -> { }));
    }
}
