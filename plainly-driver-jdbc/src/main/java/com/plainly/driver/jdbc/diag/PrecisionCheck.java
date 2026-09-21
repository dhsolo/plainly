package com.plainly.driver.jdbc.diag;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 精度一致性自检——拿到<b>真实实例</b>上跑的那一份。
 *
 * <h2>为什么要有它，而不是只有单元测试</h2>
 * {@code PrecisionConformanceTest} 跑在 H2 上，CI 里随时能跑，但它证明不了
 * Oracle、达梦、SQL Server 上的行为——那几家的驱动、类型映射、绑定方式都不一样，
 * 而精度丢失是<b>静默</b>的：数据看着还在，只是尾巴没了，事后从结果里看不出来。
 *
 * <p>README 里长期挂着一条「Oracle / 达梦 / SQL Server 的精度未在真实实例上验证」，
 * 靠的就是缺这么一次真跑。这个类把那套断言搬到任意一条活连接上。
 *
 * <h2>为什么是「报告」而不是「断言」</h2>
 * 单元测试第一条断言失败就停了，那在 CI 里是对的。但对一家没验过的数据库，
 * 要的是<b>一整张成绩单</b>：哪几条过了、哪几条没过、没过的具体差在哪。
 * 停在第一条只会让人反复跑好几轮。
 *
 * <h2>它会动数据库</h2>
 * 建一张 {@value #TABLE} 表、插四行、做一次参数化写回，结束时删掉。
 * 除此之外不碰任何东西。
 */
public final class PrecisionCheck {

    /** 探针表名。起得长而且带前缀，免得撞上真实业务表。 */
    public static final String TABLE = "PLAINLY_PRECISION_PROBE";

    /** 一条检查的结论。 */
    public enum Verdict {
        /** 行为符合预期。 */
        PASS,
        /** 行为不符合预期——精度或语义丢了。 */
        FAIL,
        /** 这一家本来就是这个行为，不是缺陷，但使用者必须知道。 */
        VENDOR
    }

    public record Finding(String name, Verdict verdict, String detail) {
    }

    public record Report(DbType type, String serverVersion, List<Finding> findings) {

        public boolean allPassed() {
            return findings.stream().noneMatch(f -> f.verdict() == Verdict.FAIL);
        }

        public long count(Verdict verdict) {
            return findings.stream().filter(f -> f.verdict() == verdict).count();
        }
    }

    // ---- 用例值：每一个都超出 double 的 15.95 位十进制有效数字 ----

    private static final String BIGINT_MAX = "9223372036854775807";
    private static final String BIGINT_MIN = "-9223372036854775808";
    /** 2^53+1：double 能表示的最小失真整数。 */
    private static final String UNSAFE_INT = "9007199254740993";
    private static final String DECIMAL_FULL = "12345678901234567890.1234567890";
    private static final String DECIMAL_TINY = "0.0000000001";
    private static final String DECIMAL_NEG = "-99999999999999999999.9999999999";

    private static final int ROUNDTRIP_ID = 4;

    private PrecisionCheck() {
    }

    /**
     * 在一条活连接上跑完整套检查。
     *
     * @param schema 建探针表的模式。必须是当前账号写得进去的地方
     */
    public static Report run(DbConnection conn, String schema) {
        List<Finding> findings = new ArrayList<>();
        DbType type = conn.config().type();
        try {
            createProbe(conn, schema, type);
            seed(conn, schema);

            checkBigintBoundaries(conn, schema, findings);
            checkDecimalPrecision(conn, schema, findings);
            checkTinyDecimal(conn, schema, findings);
            checkNullVersusEmpty(conn, schema, type, findings);
            checkMetadata(conn, schema, findings);
            checkRoundTrip(conn, schema, findings);
            checkCaseValuesReallyExceedDouble(findings);
        } catch (RuntimeException e) {
            findings.add(new Finding("准备探针表", Verdict.FAIL,
                    "跑不下去了：" + e.getMessage()));
        } finally {
            dropQuietly(conn, schema);
        }
        return new Report(type, conn.serverVersion(), findings);
    }

    // ------------------------------------------------------------------ 建表与灌数

    /**
     * 各家的类型名不一样，这里必须按家给。
     *
     * <p>Oracle 和达梦没有 {@code BIGINT} 也没有 {@code DECIMAL}——
     * 用它们的写法建表会直接 {@code ORA-00902}，那样测的就不是精度而是拼写了。
     */
    private static void createProbe(DbConnection conn, String schema, DbType type) {
        boolean oracleFamily = type == DbType.ORACLE || type == DbType.DM;
        String bigint = oracleFamily ? "NUMBER(19)" : "BIGINT";
        String decimal = oracleFamily ? "NUMBER(38,10)" : "DECIMAL(38,10)";
        String text = oracleFamily ? "VARCHAR2(64)" : "VARCHAR(64)";

        dropQuietly(conn, schema);
        conn.execute("CREATE TABLE " + qualified(conn, schema) + " ("
                + " ID " + bigint + " NOT NULL PRIMARY KEY,"
                + " BIG_VAL " + bigint + ","
                + " DEC_VAL " + decimal + ","
                + " NOTE " + text + ")", 0);
    }

    private static void seed(DbConnection conn, String schema) {
        insert(conn, schema, 1, BIGINT_MAX, DECIMAL_FULL, "'上界'");
        insert(conn, schema, 2, BIGINT_MIN, DECIMAL_NEG, "NULL");
        insert(conn, schema, 3, UNSAFE_INT, DECIMAL_TINY, "''");
        insert(conn, schema, ROUNDTRIP_ID, "0", "0.0000000000", "'往返用例'");
    }

    private static void insert(DbConnection conn, String schema, int id,
                               String bigVal, String decVal, String note) {
        conn.execute("INSERT INTO " + qualified(conn, schema)
                + " (ID, BIG_VAL, DEC_VAL, NOTE) VALUES ("
                + id + ", " + bigVal + ", " + decVal + ", " + note + ")", 0);
    }

    private static void dropQuietly(DbConnection conn, String schema) {
        try {
            conn.execute("DROP TABLE " + qualified(conn, schema), 0);
        } catch (RuntimeException ignored) {
            // 表本来就不在，或者没权限删——都不该盖掉真正要报告的结论
        }
    }

    private static String qualified(DbConnection conn, String schema) {
        return conn.dialect().qualify(schema, TABLE);
    }

    // ------------------------------------------------------------------ 各项检查

    private static void checkBigintBoundaries(DbConnection conn, String schema,
                                              List<Finding> findings) {
        expect(findings, "BIGINT 上界读回原文", BIGINT_MAX, cell(conn, schema, 1, "BIG_VAL"));
        expect(findings, "BIGINT 下界读回原文", BIGINT_MIN, cell(conn, schema, 2, "BIG_VAL"));
        expect(findings, "2^53+1 读回原文", UNSAFE_INT, cell(conn, schema, 3, "BIG_VAL"));
    }

    private static void checkDecimalPrecision(DbConnection conn, String schema,
                                              List<Finding> findings) {
        expect(findings, "30 位有效数字读回原文", DECIMAL_FULL, cell(conn, schema, 1, "DEC_VAL"));
        expect(findings, "负的满位值读回原文", DECIMAL_NEG, cell(conn, schema, 2, "DEC_VAL"));
    }

    private static void checkTinyDecimal(DbConnection conn, String schema,
                                         List<Finding> findings) {
        String value = cell(conn, schema, 3, "DEC_VAL");
        expect(findings, "极小值保留 scale", DECIMAL_TINY, value);
        boolean scientific = value != null && value.toUpperCase(java.util.Locale.ROOT).contains("E");
        findings.add(new Finding("极小值不用科学计数法",
                scientific ? Verdict.FAIL : Verdict.PASS,
                scientific ? "读到 " + value + "，说明走的是 toString() 而不是 toPlainString()"
                        : "读到 " + value));
    }

    /**
     * NULL 与空字符串。
     *
     * <p>有些库<b>把空字符串当成 NULL</b>——Oracle 几十年就是这样，
     * 不是本工具的缺陷，也改不掉。但使用者必须知道：在这种库上，
     * 「这个字段是空的」和「这个字段没填」分不开。所以这一条在它们身上
     * 记成 {@link Verdict#VENDOR} 而不是 FAIL——把厂商行为报成缺陷，
     * 和把缺陷报成厂商行为一样有害。
     *
     * <h2>为什么改成问服务端，而不是列一份产品名单</h2>
     * 原来这里写死了「Oracle 或达梦」。openGauss 撞上来时才发现这条判据不对：
     * 它的行为取决于建库时的 {@code DBCOMPATIBILITY}——默认的 A 模式下
     * 空串就是 NULL（实测 {@code SELECT '' IS NULL} 返回真），
     * 而同一个 openGauss 建成 PG 模式时两者是分开的。
     *
     * <p>按产品名写死的话，PG 模式下真出了问题也会被记成「厂商行为」，
     * <b>把真缺陷盖掉</b>——而这正是这套检查最不能犯的错。所以改成直接问服务端：
     * 它自己说空串是 NULL，那就是它的语义；它说不是，那读出 null 就是我们的问题。
     *
     * <h2>顺带纠正一个错了很久的说法</h2>
     * 原来那份名单里写着「Oracle 和达梦」。改成问服务端之后，在真机上一问才发现
     * <b>达梦那半句是错的</b>（2026-09-11 实测）：
     *
     * <pre>
     * 达梦 8.1.2      SELECT '' IS NULL FROM DUAL  ->  0    空串不是 NULL
     * Oracle 23ai     SELECT '' IS NULL FROM DUAL  ->  1    空串就是 NULL
     * </pre>
     *
     * <p>那句话之所以能错这么久，正是因为它<b>从来没被执行到</b>——
     * 达梦上空串本来就读得回来，走的是 PASS 那一支，名单里那一项是死的。
     * 写死的判据不只是不灵活，它还会把错误的认知一直留在注释里。
     */
    private static void checkNullVersusEmpty(DbConnection conn, String schema, DbType type,
                                             List<Finding> findings) {
        String nullValue = cell(conn, schema, 2, "NOTE");
        findings.add(new Finding("SQL NULL 读成 Java null",
                nullValue == null ? Verdict.PASS : Verdict.FAIL,
                nullValue == null ? "" : "读到的是 [" + nullValue + "]"));

        String empty = cell(conn, schema, 3, "NOTE");
        if ("".equals(empty)) {
            findings.add(new Finding("空字符串不被读成 NULL", Verdict.PASS, ""));
        } else if (empty == null && serverTreatsEmptyAsNull(conn)) {
            findings.add(new Finding("空字符串不被读成 NULL", Verdict.VENDOR,
                    type.displayName() + " 上服务端自己就认为空串等于 NULL"
                            + "（SELECT '' IS NULL 返回真），这是它的语义，"
                            + "不是读取路径的问题。这种库上「空」和「没填」分不开"));
        } else {
            findings.add(new Finding("空字符串不被读成 NULL", Verdict.FAIL,
                    "读到的是 " + (empty == null ? "null" : "[" + empty + "]")));
        }
    }

    /**
     * 服务端自己认不认为空串就是 NULL。
     *
     * <p>两种写法都试：有 {@code DUAL} 的那几家不接受没有 FROM 的 SELECT。
     * 两条都问不出来时返回 false——问不出来就不该替它开脱。
     */
    private static boolean serverTreatsEmptyAsNull(DbConnection conn) {
        for (String sql : new String[] {
                "SELECT CASE WHEN '' IS NULL THEN 1 ELSE 0 END",
                "SELECT CASE WHEN '' IS NULL THEN 1 ELSE 0 END FROM DUAL"}) {
            try {
                String value = conn.scalar(sql);
                if (value != null) {
                    String v = value.trim();
                    return v.startsWith("1") || v.equalsIgnoreCase("t")
                            || v.equalsIgnoreCase("true");
                }
            } catch (RuntimeException ignored) {
                // 这种写法它不认，换下一种
            }
        }
        return false;
    }

    private static void checkMetadata(DbConnection conn, String schema, List<Finding> findings) {
        QueryResult r = conn.execute("SELECT ID, BIG_VAL, DEC_VAL, NOTE FROM "
                + qualified(conn, schema), 10);

        expectCategory(findings, "BIG_VAL 归类为精确数值", r, "BIG_VAL", TypeCategory.EXACT_NUMERIC);
        expectCategory(findings, "DEC_VAL 归类为精确数值", r, "DEC_VAL", TypeCategory.EXACT_NUMERIC);
        expectCategory(findings, "NOTE 归类为字符串", r, "NOTE", TypeCategory.STRING);

        // 判据是「这次到底有没有拿到表名」，不是「这一家声明自己报不报」。
        //
        // 静态声明在金仓上就不成立：报不报取决于<b>服务端版本</b>——V8R3 的系统目录
        // 叫 sys_catalog，驱动问不出来；同一个驱动连 V8R6 就问得出。
        // 按家写死的话，要么冤枉新版本，要么给老版本发一张它不配的通行证。
        //
        // 更要紧的是留住牙齿：拿到了表名却仍然定位不到主键，那是<b>我们的缺陷</b>，
        // 必须记 FAIL。一律按厂商行为放过，会把真问题盖掉
        boolean reportsTables = r.columns().stream()
                .anyMatch(c -> c.tableName() != null && !c.tableName().isBlank());
        ColumnMeta id = columnMeta(r, "ID");
        boolean keyFound = id != null && id.partOfKey();

        if (keyFound) {
            findings.add(new Finding("结果集认得出主键", Verdict.PASS, ""));
        } else if (!reportsTables) {
            // 驱动一律返回空表名，谁也定位不到主键。这不是本工具的判据错了，
            // 是那份元数据根本没给
            findings.add(new Finding("结果集认得出主键", Verdict.VENDOR,
                    "这一家的驱动不报结果集的来源表，裸查询定位不到主键"));
        } else {
            findings.add(new Finding("结果集认得出主键", Verdict.FAIL,
                    id == null ? "结果集里没有 ID 列" : ""));
        }

        if (r.isEditable()) {
            findings.add(new Finding("单表结果集可编辑", Verdict.PASS, ""));
        } else if (!reportsTables) {
            findings.add(new Finding("裸查询的结果集可编辑", Verdict.VENDOR,
                    "只读，原因：" + r.readOnlyReason()));
        } else {
            findings.add(new Finding("单表结果集可编辑", Verdict.FAIL,
                    "只读原因：" + r.readOnlyReason()));
        }

        // 表页走的不是裸查询：它知道自己打开的是哪张表，会把表名补回结果集。
        // 上面那条厂商限制到底影不影响「打开表改数据」，得真验一遍才知道，
        // 不能靠推断——推断出来的结论正是这次要消灭的东西
        if (!reportsTables) {
            try {
                var pk = conn.describeTable(schema, TABLE).primaryKeyColumns();
                QueryResult attributed = r.attributedTo("", TABLE, pk);
                findings.add(new Finding("补上表名后可编辑（表页走的就是这条路）",
                        attributed.isEditable() ? Verdict.PASS : Verdict.FAIL,
                        attributed.isEditable() ? "主键列：" + pk
                                : "仍然只读：" + attributed.readOnlyReason()));
            } catch (RuntimeException e) {
                findings.add(new Finding("补上表名后可编辑（表页走的就是这条路）",
                        Verdict.FAIL, e.getMessage()));
            }
        }

        QueryResult noKey = conn.execute("SELECT COUNT(*) AS C FROM "
                + qualified(conn, schema), 10);
        findings.add(new Finding("取不到主键时降级为只读",
                noKey.isEditable() ? Verdict.FAIL : Verdict.PASS,
                noKey.isEditable() ? "聚合结果被判成可编辑，写回会写错地方" : ""));
    }

    private static void checkRoundTrip(DbConnection conn, String schema, List<Finding> findings) {
        TableStructure structure = conn.describeTable(schema, TABLE);
        ColumnInfo decCol = columnNamed(structure, "DEC_VAL");
        ColumnInfo idCol = columnNamed(structure, "ID");
        if (decCol == null || idCol == null) {
            findings.add(new Finding("读 → 参数化写回 → 再读", Verdict.FAIL,
                    "表结构里找不到 DEC_VAL 或 ID"));
            return;
        }

        SqlDialect.PreparedSql update = conn.dialect()
                .buildUpdate(schema, TABLE, List.of(decCol), List.of(idCol));
        findings.add(new Finding("写回语句是参数化的",
                update.sql().contains("?") && !update.sql().contains(DECIMAL_FULL)
                        ? Verdict.PASS : Verdict.FAIL,
                update.sql()));

        conn.executeUpdate(update, List.of(DECIMAL_FULL, String.valueOf(ROUNDTRIP_ID)));
        expect(findings, "满位值写回后再读不变",
                DECIMAL_FULL, cell(conn, schema, ROUNDTRIP_ID, "DEC_VAL"));

        conn.executeUpdate(update, List.of(DECIMAL_NEG, String.valueOf(ROUNDTRIP_ID)));
        expect(findings, "负的满位值写回后再读不变",
                DECIMAL_NEG, cell(conn, schema, ROUNDTRIP_ID, "DEC_VAL"));
    }

    /**
     * 反证：这些用例值确实过不了 double。
     *
     * <p>少了这一条，前面所有断言都可能只是因为用例太温和而碰巧通过。
     */
    private static void checkCaseValuesReallyExceedDouble(List<Finding> findings) {
        for (String literal : new String[]{BIGINT_MAX, UNSAFE_INT, DECIMAL_FULL, DECIMAL_NEG}) {
            findings.add(new Finding("反证：" + literal + " 超出 double",
                    survivesDouble(literal) ? Verdict.FAIL : Verdict.PASS,
                    survivesDouble(literal) ? "这个用例值 double 也扛得住，证明不了什么" : ""));
        }
    }

    private static boolean survivesDouble(String literal) {
        BigDecimal original = new BigDecimal(literal);
        BigDecimal viaDouble = new BigDecimal(String.valueOf(Double.parseDouble(literal)));
        return original.compareTo(viaDouble) == 0;
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 比对一个数值读回来的文本。
     *
     * <h2>为什么「文本不同」不一定就是失败</h2>
     * Oracle 的 NUMBER <b>不保留末尾零</b>：写进去 {@code ...1234567890}，
     * 读出来是 {@code ...123456789}。用 {@code TO_CHAR} 直接问数据库，
     * 它自己给的也是后者——也就是说这不是读取路径削掉的，是它压根没存。
     *
     * <p>这两个文本代表的是<b>同一个数</b>，一位有效数字都没丢。所以记成厂商行为，
     * 不是缺陷。把它报成缺陷，等于让人去修一个修不掉、也不该修的东西；
     * 而反过来把真正的精度丢失轻描淡写成「厂商行为」，那更糟。
     * 判据就是这一句：<b>数值相等吗</b>。
     */
    private static void expect(List<Finding> findings, String name,
                               String expected, String actual) {
        if (expected.equals(actual)) {
            findings.add(new Finding(name, Verdict.PASS, ""));
            return;
        }
        if (actual != null && numericallyEqual(expected, actual)) {
            findings.add(new Finding(name, Verdict.VENDOR,
                    "读回 " + actual + "，和写入的 " + expected
                            + " 是同一个数，只是末尾零没保留——这是 NUMBER 的存储语义，"
                            + "数值一位没差"));
            return;
        }
        findings.add(new Finding(name, Verdict.FAIL, "期望 " + expected + "，实得 " + actual));
    }

    private static boolean numericallyEqual(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void expectCategory(List<Finding> findings, String name, QueryResult r,
                                       String column, TypeCategory expected) {
        ColumnMeta meta = columnMeta(r, column);
        if (meta == null) {
            findings.add(new Finding(name, Verdict.FAIL, "结果集里没有 " + column));
            return;
        }
        boolean ok = meta.category() == expected;
        findings.add(new Finding(name, ok ? Verdict.PASS : Verdict.FAIL,
                ok ? meta.nativeType() : "实得 " + meta.category()
                        + "（原生类型 " + meta.nativeType() + "）——"
                        + "归错类会让它走上有损的读取路径"));
    }

    private static String cell(DbConnection conn, String schema, int id, String column) {
        QueryResult r = conn.execute("SELECT " + column + " FROM " + qualified(conn, schema)
                + " WHERE ID = " + id, 10);
        return r.rows().isEmpty() ? null : r.rows().get(0).get(0);
    }

    private static ColumnMeta columnMeta(QueryResult r, String label) {
        for (ColumnMeta c : r.columns()) {
            if (c.label().equalsIgnoreCase(label) || c.name().equalsIgnoreCase(label)) {
                return c;
            }
        }
        return null;
    }

    private static ColumnInfo columnNamed(TableStructure s, String name) {
        return s.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }
}
