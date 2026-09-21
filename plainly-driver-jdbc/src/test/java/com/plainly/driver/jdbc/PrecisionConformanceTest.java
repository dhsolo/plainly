package com.plainly.driver.jdbc;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精度一致性测试。
 *
 * <h2>这个测试的地位</h2>
 * 驱动配置、类型映射、绑定方式都可能被一次依赖升级悄悄推翻，而精度丢失是<b>静默</b>的：
 * 数据看起来还在，只是尾巴没了，事后无法从结果里发现。
 * 所以真正的保证不是文档也不是 code review，是这个跑在 CI 里的测试。
 *
 * <p><b>新增数据库驱动的准入门槛是通过本测试，而不是「能连上」。</b>
 * 加驱动的第一件事不是写连接代码，是让它跑通这里的每一条断言。
 *
 * <p>这里用 H2 嵌入库：无需 Docker、CI 直接可跑，且 {@code DECIMAL(38,10)} 语义完整。
 * MySQL / PostgreSQL / Oracle 应各自继承同一套用例，用 testcontainers 跑真实实例。
 */
@DisplayName("精度一致性 · H2")
class PrecisionConformanceTest {

    private static DbConnection conn;

    /** 库里的表名与列名统一大写，与 H2 对未加引号标识符的处理保持一致。 */
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "PRECISION_PROBE";

    // ---- 用例值：每一个都超出 double 的 15.95 位十进制有效数字 ----

    /** long 的上界。double 表示不了，会变成 9223372036854775808。 */
    private static final String BIGINT_MAX = "9223372036854775807";
    private static final String BIGINT_MIN = "-9223372036854775808";
    /** 2^53+1：double 能表示的最小失真整数，经典边界。 */
    private static final String UNSAFE_INT = "9007199254740993";
    /** 20 位整数 + 10 位小数，共 30 位有效数字。 */
    private static final String DECIMAL_FULL = "12345678901234567890.1234567890";
    /** 极小值，考察 scale 保留与科学计数法。 */
    private static final String DECIMAL_TINY = "0.0000000001";
    private static final String DECIMAL_NEG = "-99999999999999999999.9999999999";

    @BeforeAll
    static void openAndSeed() {
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("precision-probe")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_precision;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword("");
        conn = JdbcConnections.open(cfg);

        conn.execute("DROP TABLE IF EXISTS " + TABLE, 0);
        conn.execute("CREATE TABLE " + TABLE + " ("
                + "  ID          BIGINT       NOT NULL PRIMARY KEY,"
                + "  BIG_VAL     BIGINT,"
                + "  DEC_VAL     DECIMAL(38,10),"
                + "  NOTE        VARCHAR(64)"
                + ")", 0);

        // 用字面量插入：这一步必须无歧义，否则测的就不是读取路径了
        insert(1, BIGINT_MAX, DECIMAL_FULL, "'上界'");
        insert(2, BIGINT_MIN, DECIMAL_NEG, "NULL");
        insert(3, UNSAFE_INT, DECIMAL_TINY, "''");
        // 第 4 行专供往返测试写入，避免它改动其他用例读取的行
        insert(ROUNDTRIP_ID, "0", "0.0000000000", "'往返用例'");
    }

    /** 往返测试独占的行。测试之间不共享可变状态，否则执行顺序一变就互相污染。 */
    private static final int ROUNDTRIP_ID = 4;

    private static void insert(int id, String bigVal, String decVal, String note) {
        conn.execute("INSERT INTO " + TABLE + " (ID, BIG_VAL, DEC_VAL, NOTE) VALUES ("
                + id + ", " + bigVal + ", " + decVal + ", " + note + ")", 0);
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    // ------------------------------------------------------------------ 读取保真

    @Test
    @DisplayName("BIGINT 边界值读出的文本与写入字面量完全一致")
    void bigintBoundariesSurviveRead() {
        assertEquals(BIGINT_MAX, cell(1, "BIG_VAL"));
        assertEquals(BIGINT_MIN, cell(2, "BIG_VAL"));
        assertEquals(UNSAFE_INT, cell(3, "BIG_VAL"));
    }

    @Test
    @DisplayName("DECIMAL(38,10) 满位值一位不差")
    void decimalFullPrecisionSurvivesRead() {
        assertEquals(DECIMAL_FULL, cell(1, "DEC_VAL"));
        assertEquals(DECIMAL_NEG, cell(2, "DEC_VAL"));
    }

    @Test
    @DisplayName("极小值保留 scale，且不退化成科学计数法")
    void tinyDecimalKeepsScaleAndPlainForm() {
        String value = cell(3, "DEC_VAL");
        assertEquals(DECIMAL_TINY, value);
        assertFalse(value.toUpperCase().contains("E"),
                "输出出现科学计数法说明用的是 toString() 而不是 toPlainString()：" + value);
    }

    @Test
    @DisplayName("反证：这些用例值确实过不了 double，前面的断言才有意义")
    void doubleWouldActuallyLoseThesePrecisions() {
        // 这条不验证产品代码，而是验证「用例值确实处在 double 表示不了的区间」。
        // 少了它，前面几条断言有可能只是因为用例太温和而碰巧通过。
        assertFalse(survivesDouble(BIGINT_MAX), BIGINT_MAX + " 应当超出 double 的表示能力");
        assertFalse(survivesDouble(UNSAFE_INT), UNSAFE_INT + " 是 2^53+1，double 必然失真");
        assertFalse(survivesDouble(DECIMAL_FULL), DECIMAL_FULL + " 有 30 位有效数字，远超 double");
        assertFalse(survivesDouble(DECIMAL_NEG), DECIMAL_NEG + " 应当超出 double 的表示能力");

        // 反过来，极小值本身是 double 能精确表示的——
        // 它考察的是 scale 保留和科学计数法，不是位数。
        assertTrue(survivesDouble(DECIMAL_TINY), "用例设计说明：TINY 考察的是格式而非位数");
    }

    /** 字面量经 double 往返之后在数值上是否仍然相等。 */
    private static boolean survivesDouble(String literal) {
        java.math.BigDecimal original = new java.math.BigDecimal(literal);
        java.math.BigDecimal viaDouble =
                new java.math.BigDecimal(String.valueOf(Double.parseDouble(literal)));
        return original.compareTo(viaDouble) == 0;
    }

    // ------------------------------------------------------------------ NULL 语义

    @Test
    @DisplayName("NULL 与空字符串严格区分")
    void nullIsDistinctFromEmptyString() {
        assertNull(cell(2, "NOTE"), "SQL NULL 必须读成 Java null");
        assertEquals("", cell(3, "NOTE"), "空字符串必须读成空串，不能变成 null");
    }

    // ------------------------------------------------------------------ 往返保真

    @Test
    @DisplayName("读 → 参数化写回 → 再读，全程不变")
    void roundTripThroughParameterisedUpdate() {
        TableStructure structure = conn.describeTable(SCHEMA, TABLE);
        ColumnInfo decCol = columnNamed(structure, "DEC_VAL");
        ColumnInfo idCol = columnNamed(structure, "ID");

        assertEquals(TypeCategory.EXACT_NUMERIC, decCol.category(),
                "DECIMAL 必须落在 EXACT_NUMERIC，否则会走到有损读取路径");

        SqlDialect.PreparedSql update = conn.dialect()
                .buildUpdate(SCHEMA, TABLE, List.of(decCol), List.of(idCol));

        // 往返一轮：把满位值写进独占行，再读回来逐字符比对
        int affected = conn.executeUpdate(update,
                List.of(DECIMAL_FULL, String.valueOf(ROUNDTRIP_ID)));
        assertEquals(1, affected);
        assertEquals(DECIMAL_FULL, cell(ROUNDTRIP_ID, "DEC_VAL"),
                "写回后再读出现偏差，说明绑定路径经过了 double");

        // 再往返一轮负的极值，确认符号与最大 scale 同样无损
        conn.executeUpdate(update, List.of(DECIMAL_NEG, String.valueOf(ROUNDTRIP_ID)));
        assertEquals(DECIMAL_NEG, cell(ROUNDTRIP_ID, "DEC_VAL"));
    }

    @Test
    @DisplayName("生成的 UPDATE 是参数化的，值不出现在 SQL 文本里")
    void updateStatementIsParameterised() {
        TableStructure structure = conn.describeTable(SCHEMA, TABLE);
        SqlDialect.PreparedSql update = conn.dialect().buildUpdate(
                SCHEMA, TABLE,
                List.of(columnNamed(structure, "DEC_VAL")),
                List.of(columnNamed(structure, "ID")));

        assertTrue(update.sql().contains("?"), "应当使用占位符");
        assertFalse(update.sql().contains(DECIMAL_FULL),
                "值被拼进了 SQL 文本——既是注入风险，也是精度风险");
    }

    // ------------------------------------------------------------------ 元数据

    @Test
    @DisplayName("精确数值列被正确归类，且结果集可定位主键")
    void metadataClassifiesExactNumerics() {
        QueryResult r = conn.execute("SELECT ID, BIG_VAL, DEC_VAL, NOTE FROM " + TABLE, 10);

        assertEquals(TypeCategory.EXACT_NUMERIC, columnMeta(r, "BIG_VAL").category());
        assertEquals(TypeCategory.EXACT_NUMERIC, columnMeta(r, "DEC_VAL").category());
        assertEquals(TypeCategory.STRING, columnMeta(r, "NOTE").category());

        assertTrue(columnMeta(r, "ID").partOfKey(), "ID 是主键，应当被识别");
        assertTrue(r.isEditable(), "同源单表且主键可定位，结果集应当可编辑");
    }

    @Test
    @DisplayName("取不到主键的结果集降级为只读，而不是生成一条错的 UPDATE")
    void resultWithoutKeyIsNotEditable() {
        QueryResult r = conn.execute("SELECT COUNT(*) AS C FROM " + TABLE, 10);
        assertFalse(r.isEditable());
    }

    // ------------------------------------------------------------------ 辅助

    private static String cell(int id, String column) {
        QueryResult r = conn.execute(
                "SELECT " + column + " FROM " + TABLE + " WHERE ID = " + id, 10);
        assertEquals(1, r.rows().size(), "应当只命中一行");
        Row row = r.rows().get(0);
        return row.get(0);
    }

    private static ColumnMeta columnMeta(QueryResult r, String label) {
        int i = r.indexOf(label);
        assertTrue(i >= 0, "结果集里找不到列 " + label);
        return r.columns().get(i);
    }

    private static ColumnInfo columnNamed(TableStructure s, String name) {
        ColumnInfo found = s.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        assertNotNull(found, "表结构里找不到列 " + name);
        return found;
    }
}
