package com.plainly.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.jdbc.JdbcConnections;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据生成器。
 *
 * <p>最要紧的一条：造出来的 {@code DECIMAL(38,10)} 必须真的有 38 位有效数字。
 * 用 {@code Random.nextDouble()} 造数据，写进去的永远只有十五位——
 * 拿这种数据去测精度，什么问题都测不出来。
 */
@DisplayName("数据生成器")
class DataGeneratorTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "GEN_PROBE";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("gen-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_gen;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
        conn.executeDdlBatch(List.of(
                "CREATE TABLE " + TABLE + " (ID INT PRIMARY KEY, AMOUNT DECIMAL(38,10),"
                        + " NAME VARCHAR(16), CREATED TIMESTAMP)"));
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

    private static List<ColumnInfo> columns() {
        return conn.describeTable(SCHEMA, TABLE).columns();
    }

    @Test
    @DisplayName("造出来的 DECIMAL(38,10) 真的有 38 位有效数字")
    void decimalsUseFullPrecision() {
        ColumnInfo amount = columns().stream()
                .filter(c -> c.name().equals("AMOUNT")).findFirst().orElseThrow();
        String value = DataGenerator.value(amount,
                new DataGenerator.Rule("AMOUNT", DataGenerator.Strategy.AUTO, null),
                0, new Random(1));

        BigDecimal decimal = new BigDecimal(value);
        assertEquals(10, decimal.scale(), "小数位要填满：" + value);
        assertEquals(38, decimal.precision(), "有效位数要填满，否则测不出精度问题：" + value);
    }

    @Test
    @DisplayName("定宽整数不能越界——BIGINT 填满 19 位就超过上界了")
    void boundedIntegersStayInRange() {
        // 真实故障：PostgreSQL 上生成测试数据报 bigint out of range，
        // 造出来的值是 9498372540635905450，而上界只有 9223372036854775807。
        // 根因是按 precision 填位数——BIGINT 报的 precision 是 19，填满必然越界
        conn.executeDdlBatch(List.of(
                "DROP TABLE IF EXISTS INT_PROBE",
                "CREATE TABLE INT_PROBE (B BIGINT, I INT, S SMALLINT, D DECIMAL(38,10))"));
        List<ColumnInfo> columns = conn.describeTable(SCHEMA, "INT_PROBE").columns();

        java.util.Map<String, BigDecimal> limits = java.util.Map.of(
                "B", BigDecimal.valueOf(Long.MAX_VALUE),
                "I", BigDecimal.valueOf(Integer.MAX_VALUE),
                "S", BigDecimal.valueOf(Short.MAX_VALUE));

        // 多跑几轮：越界是概率性的，单次通过说明不了什么
        for (int seed = 0; seed < 200; seed++) {
            List<List<String>> rows = DataGenerator.preview(columns, List.of(), 1, seed);
            for (int i = 0; i < columns.size(); i++) {
                BigDecimal limit = limits.get(columns.get(i).name());
                if (limit == null) {
                    continue;
                }
                BigDecimal value = new BigDecimal(rows.get(0).get(i));
                assertTrue(value.compareTo(limit) <= 0,
                        columns.get(i).name() + " 越界了：" + value + " > " + limit);
                assertTrue(value.signum() >= 0, columns.get(i).name() + " 不该是负数：" + value);
            }
        }
    }

    @Test
    @DisplayName("但定宽整数仍然要贴着上界取值——满屏的 42 压不出精度问题")
    void boundedIntegersStayLarge() {
        conn.executeDdlBatch(List.of(
                "DROP TABLE IF EXISTS INT_PROBE2",
                "CREATE TABLE INT_PROBE2 (B BIGINT)"));
        ColumnInfo b = conn.describeTable(SCHEMA, "INT_PROBE2").columns().get(0);

        // 取值区间是 [上界/10, 上界]，所以至少 18 位——正是 double 装不下的那一段
        BigDecimal floor = BigDecimal.valueOf(Long.MAX_VALUE / 10);
        for (int seed = 0; seed < 50; seed++) {
            BigDecimal value = new BigDecimal(DataGenerator.value(b,
                    new DataGenerator.Rule("B", DataGenerator.Strategy.AUTO, null),
                    0, new Random(seed)));
            assertTrue(value.compareTo(floor) >= 0, "太小了，压不出精度问题：" + value);
        }
    }

    @Test
    @DisplayName("同一个种子造出同一批数据")
    void seedIsReproducible() {
        List<List<String>> a = DataGenerator.preview(columns(), List.of(), 5, 42);
        List<List<String>> b = DataGenerator.preview(columns(), List.of(), 5, 42);
        assertEquals(a, b);
    }

    @Test
    @DisplayName("递增序号就是 1、2、3")
    void sequenceCountsUp() {
        List<DataGenerator.Rule> rules = List.of(
                new DataGenerator.Rule("ID", DataGenerator.Strategy.SEQUENCE, null));
        List<List<String>> rows = DataGenerator.preview(columns(), rules, 3, 7);
        assertEquals("1", rows.get(0).get(0));
        assertEquals("3", rows.get(2).get(0));
    }

    @Test
    @DisplayName("非空列不会被留空——那样只会被数据库拒绝")
    void notNullColumnIsNeverBlank() {
        ColumnInfo id = columns().stream()
                .filter(c -> c.name().equals("ID")).findFirst().orElseThrow();
        assertTrue(id.primaryKey());

        // 直接对可空列取 NULL 是允许的
        ColumnInfo name = columns().stream()
                .filter(c -> c.name().equals("NAME")).findFirst().orElseThrow();
        assertNull(DataGenerator.value(name,
                new DataGenerator.Rule("NAME", DataGenerator.Strategy.NULL, null),
                0, new Random(1)));
    }

    @Test
    @DisplayName("真写进库，行数与精度都对得上")
    void writesRows() {
        List<DataGenerator.Rule> rules = List.of(
                new DataGenerator.Rule("ID", DataGenerator.Strategy.SEQUENCE, null));
        long written = DataGenerator.generate(conn, SCHEMA, TABLE, columns(), rules,
                50, 20, 99, n -> { });
        assertEquals(50, written);

        QueryResult count = conn.execute("SELECT COUNT(*) FROM " + TABLE, 1);
        assertEquals("50", count.rows().get(0).get(0));

        QueryResult sample = conn.execute(
                "SELECT AMOUNT FROM " + TABLE + " ORDER BY ID LIMIT 1", 1);
        BigDecimal stored = new BigDecimal(sample.rows().get(0).get(0));
        assertEquals(38, stored.precision(), "存进库再读回来仍是 38 位：" + stored);
    }

    @Test
    @DisplayName("固定值就原样写进去")
    void fixedValueIsUsed() {
        List<DataGenerator.Rule> rules = List.of(
                new DataGenerator.Rule("ID", DataGenerator.Strategy.SEQUENCE, null),
                new DataGenerator.Rule("NAME", DataGenerator.Strategy.FIXED, "固定"));
        DataGenerator.generate(conn, SCHEMA, TABLE, columns(), rules, 3, 10, 5, n -> { });

        QueryResult r = conn.execute("SELECT DISTINCT NAME FROM " + TABLE, 10);
        assertEquals(1, r.rows().size());
        assertEquals("固定", r.rows().get(0).get(0));
    }

    // ------------------------------------------------ 认不出来的类型

    /**
     * 真实故障：往一张有 {@code regclass} 列的表里生成数据，报
     * {@code relation "partrel-1-bb" does not exist}。
     *
     * <p>{@code partrel-1-bb} 是本工具编出来的——那一列的类别是 OTHER，
     * 而 OTHER 原来和 STRING 合在一条分支上，于是按字符串列的规则造了个值。
     * {@code uuid}、枚举、{@code inet}、{@code xml}、数组都一样接不住随手编的文本：
     * <b>这些类型各有各的格式，编出来的值几乎一定是非法的。</b>
     *
     * <p>所以认不出来就说认不出来，返回 null。
     */
    @Test
    @DisplayName("认不出来的类型不硬编值")
    void unknownTypesAreNotInvented() {
        ColumnInfo odd = new ColumnInfo("PARTREL", "regclass",
                com.plainly.driver.TypeCategory.OTHER, 0, 0, true, false, false, null, "", 1);
        assertNull(DataGenerator.value(odd,
                new DataGenerator.Rule("PARTREL", DataGenerator.Strategy.AUTO, null),
                0, new Random(1)),
                "编不出合法的值就该返回 null，而不是按字符串列的规则造一个");
    }

    /**
     * 而用户自己指定的固定值要照用——那是他给出的逃生口。
     *
     * <p>「认不出来」说的是本工具推断不出来，不是这一列不能写。
     */
    @Test
    @DisplayName("认不出来的类型上，用户给的固定值照用")
    void fixedValueStillWinsOnUnknownTypes() {
        ColumnInfo odd = new ColumnInfo("PARTREL", "regclass",
                com.plainly.driver.TypeCategory.OTHER, 0, 0, true, false, false, null, "", 1);
        assertEquals("public.t", DataGenerator.value(odd,
                new DataGenerator.Rule("PARTREL", DataGenerator.Strategy.FIXED, "public.t"),
                0, new Random(1)));
    }
}
