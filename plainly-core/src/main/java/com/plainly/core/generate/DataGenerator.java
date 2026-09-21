package com.plainly.core.generate;

import com.plainly.driver.DbConnection;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongConsumer;

/**
 * 造测试数据。
 *
 * <p>数值一律用 {@link BigDecimal} 按列的 precision/scale 生成，不走 {@code Random.nextDouble()}——
 * 一个专门用来测精度的工具，自己造数据时把精度丢了就太说不过去了。
 * 而且用 double 造出来的 {@code DECIMAL(38,10)} 永远只有十五位有效数字，
 * 那样的数据测不出任何精度问题。
 */
public final class DataGenerator {

    /** 一列怎么造。 */
    public enum Strategy {
        AUTO("按类型自动"),
        SEQUENCE("递增序号"),
        RANDOM("随机"),
        FIXED("固定值"),
        NULL("留空");

        private final String label;

        Strategy(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一列的生成规则。 */
    public record Rule(String column, Strategy strategy, String fixedValue) {
    }

    private DataGenerator() {
    }

    /**
     * 生成并写入。
     *
     * @param rules 每列一条规则；没给规则的列按 AUTO 处理
     * @return 实际写入的行数
     */
    public static long generate(DbConnection conn, String schema, String table,
                                List<ColumnInfo> columns, List<Rule> rules, int rows,
                                int batchSize, long seed, LongConsumer progress) {
        List<ColumnInfo> writable = new ArrayList<>();
        List<Rule> effective = new ArrayList<>();
        for (ColumnInfo column : columns) {
            Rule rule = ruleFor(rules, column.name());
            if (rule != null && rule.strategy() == Strategy.NULL && !column.nullable()) {
                // 非空列留空必然被数据库拒绝，与其等它报错不如现在就按类型造一个
                rule = new Rule(column.name(), Strategy.AUTO, null);
            }
            if (column.autoIncrement()) {
                // 自增列交给数据库，硬塞值反而会把序列弄乱
                continue;
            }
            if (invented(rule) && column.category() == TypeCategory.OTHER) {
                // 认不出来的类型，编不出合法的值——理由见 auto()。
                //
                // 非空又没有默认值的话，跳过去也一定被拒。与其让用户收到一句
                // 「null value in column violates not-null constraint」，
                // 不如现在就说清楚是哪一列、为什么、以及怎么办
                if (!column.nullable() && column.defaultValue() == null) {
                    // 指路要指到具体哪一列、改哪一格。说「给它选固定值」听着清楚，
                    // 真去界面上找的时候会发现有两列都和「固定值」有关：
                    // 一列是下拉的「生成方式」，另一列才是填值的「固定值」
                    throw new IllegalArgumentException(
                            "列 " + column.name() + "（类型 " + column.displayType()
                            + "）该填什么，本工具推断不出来，而它又是非空的。"
                            + System.lineSeparator()
                            + "在上面的字段表里找到这一行，把「生成方式」改成「固定值」，"
                            + "再到「固定值」那一列填一个这个类型认的值。");
                }
                continue;
            }
            writable.add(column);
            effective.add(rule == null ? new Rule(column.name(), Strategy.AUTO, null) : rule);
        }

        SqlDialect.PreparedSql insert = conn.dialect().buildInsert(schema, table, writable);
        Random random = new Random(seed);
        List<List<String>> batch = new ArrayList<>();
        long written = 0;

        for (int i = 0; i < rows; i++) {
            List<String> values = new ArrayList<>(writable.size());
            for (int c = 0; c < writable.size(); c++) {
                values.add(value(writable.get(c), effective.get(c), i, random));
            }
            batch.add(values);
            if (batch.size() >= batchSize) {
                conn.executeBatch(insert, batch);
                written += batch.size();
                batch.clear();
                progress.accept(written);
            }
        }
        if (!batch.isEmpty()) {
            conn.executeBatch(insert, batch);
            written += batch.size();
        }
        progress.accept(written);
        return written;
    }

    /** 单独暴露出来，好在测试里逐条核对生成的值。 */
    public static String value(ColumnInfo column, Rule rule, int index, Random random) {
        switch (rule.strategy()) {
            case NULL:
                return null;
            case FIXED:
                return rule.fixedValue();
            case SEQUENCE:
                return String.valueOf(index + 1);
            case RANDOM:
            case AUTO:
            default:
                return auto(column, index, random);
        }
    }

    private static String auto(ColumnInfo column, int index, Random random) {
        TypeCategory category = column.category();
        switch (category) {
            case INTEGER:
                // 也要按类型的上界来。SMALLINT 的上界是 32767、TINYINT 只有 127，
                // 写死一个「六位以内的随机数」在这两种列上必然越界，
                // 而报错来自数据库，用户只是点了「生成测试数据」
                return integerValue(column, random);
            case EXACT_NUMERIC:
                return decimal(column, random);
            case APPROX_NUMERIC:
                return String.valueOf(Math.round(random.nextDouble() * 10000) / 100.0);
            case BOOLEAN:
                return random.nextBoolean() ? "1" : "0";
            case TEMPORAL:
                return temporal(column, random);
            case JSON:
                return "{\"n\": " + random.nextInt(1000) + "}";
            case BINARY:
                return "";
            case OTHER:
                // 认不出来的类型：uuid、枚举、inet、xml、数组、regclass……
                //
                // <b>这一支原来和 STRING 合在一起</b>，于是给一个 regclass 列编出
                // {@code partrel-1-bb} 这样的字符串，服务端一 CAST 就报
                // 「relation "partrel-1-bb" does not exist」。uuid、枚举、inet
                // 同样接不住随手编的文本——这些类型各有各的格式，
                // <b>编出来的值几乎一定是非法的</b>。
                //
                // 所以这里明说「编不出来」。调用方据此跳过这一列，
                // 交给数据库的默认值；非空又没默认值时当场说清楚，
                // 而不是让用户去读一句数据库的原始报错
                return null;
            case STRING:
            default:
                return text(column, index, random);
        }
    }

    /** 这条规则是不是「由本工具凭类型编一个值」。 */
    private static boolean invented(Rule rule) {
        return rule == null || rule.strategy() == Strategy.AUTO
                || rule.strategy() == Strategy.RANDOM;
    }

    /**
     * 按列的 precision / scale 造一个恰好装得下的十进制数。
     *
     * <p>整数位取满、小数位取满，正是最容易暴露精度问题的那种值——
     * 造一堆 {@code 1.5} 测不出任何东西。
     */
    private static String decimal(ColumnInfo column, Random random) {
        int precision = column.precision() > 0 ? Math.min(column.precision(), 38) : 10;
        int scale = Math.min(Math.max(column.scale(), 0), Math.max(0, precision - 1));
        int intDigits = Math.max(1, precision - scale);

        // 定宽整数类型不能按位数填满：BIGINT 报的 precision 是 19，填满就是
        // 9999999999999999999，而它的上界只有 9223372036854775807——
        // 数据库会退回一句 bigint out of range，而用户只是点了「生成测试数据」。
        // 这些类型改成在 [上界/10, 上界] 里取值：既保住「位数拉满、能压出精度问题」
        // 这个本意，又一定装得下
        if (scale == 0 && boundedIntegerMax(column.nativeType()) != null) {
            return integerValue(column, random);
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < intDigits; i++) {
            digits.append((char) ('0' + (i == 0 ? 1 + random.nextInt(9) : random.nextInt(10))));
        }
        BigDecimal value = new BigDecimal(digits.toString());
        if (scale > 0) {
            StringBuilder frac = new StringBuilder();
            for (int i = 0; i < scale; i++) {
                frac.append((char) ('0' + random.nextInt(10)));
            }
            value = new BigDecimal(digits + "." + frac);
        }
        return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * 一个整数列的值。
     *
     * <p>认得出上界就贴着上界取（{@code [上界/10, 上界]}），认不出就退回一个小随机数。
     * 贴着上界取不是为了好看：这个生成器的用处之一是压出精度问题，
     * 而只有接近 {@code BIGINT} 上界的值才做得到——满屏的 42 什么都测不出来。
     */
    private static String integerValue(ColumnInfo column, Random random) {
        java.math.BigInteger max = boundedIntegerMax(column.nativeType());
        if (max == null) {
            return String.valueOf(Math.abs(random.nextInt(1_000_000)));
        }
        return nearMax(max, random).toString();
    }

    /**
     * 这个类型有没有一个固定的上界；没有（DECIMAL / NUMERIC 这类任意精度的）返回 null。
     *
     * <p>只认无符号那一半的上界，也就是<b>正数</b>范围。生成的测试数据一律非负，
     * 所以不必区分有符号无符号——按有符号的上界取值，无符号列同样装得下。
     */
    private static java.math.BigInteger boundedIntegerMax(String nativeType) {
        String n = nativeType == null ? "" : nativeType.toUpperCase(java.util.Locale.ROOT).trim();
        // 先掐掉括号里的参数：BIGINT(20)、INT UNSIGNED 都要能认出来
        int paren = n.indexOf('(');
        if (paren > 0) {
            n = n.substring(0, paren).trim();
        }
        if (n.startsWith("BIGINT") || n.equals("INT8")) {
            return java.math.BigInteger.valueOf(Long.MAX_VALUE);
        }
        if (n.startsWith("SMALLINT") || n.equals("INT2")) {
            return java.math.BigInteger.valueOf(Short.MAX_VALUE);
        }
        if (n.startsWith("TINYINT")) {
            return java.math.BigInteger.valueOf(Byte.MAX_VALUE);
        }
        if (n.startsWith("MEDIUMINT")) {
            return java.math.BigInteger.valueOf(8_388_607L);
        }
        if (n.startsWith("INTEGER") || n.equals("INT") || n.equals("INT4")) {
            return java.math.BigInteger.valueOf(Integer.MAX_VALUE);
        }
        // NUMBER / DECIMAL / NUMERIC 是任意精度的，由 precision 说了算，不在这里限
        return null;
    }

    /**
     * 在 {@code [max/10, max]} 里取一个值。
     *
     * <p>取上界那一段而不是全区间：这个生成器的用处之一就是压出精度问题，
     * 而只有接近上界的值才做得到——满屏的 42 测不出任何东西。
     */
    private static java.math.BigInteger nearMax(java.math.BigInteger max, Random random) {
        java.math.BigInteger lower = max.divide(java.math.BigInteger.TEN);
        java.math.BigInteger span = max.subtract(lower).add(java.math.BigInteger.ONE);
        java.math.BigInteger offset =
                new java.math.BigInteger(span.bitLength() + 8, random).mod(span);
        return lower.add(offset);
    }

    private static String temporal(ColumnInfo column, Random random) {
        String type = column.nativeType() == null ? "" : column.nativeType().toUpperCase();
        LocalDateTime when = LocalDateTime.now()
                .minusDays(random.nextInt(365))
                .withNano(0);
        if (type.contains("DATE") && !type.contains("TIME")) {
            return when.toLocalDate().toString();
        }
        if (type.startsWith("TIME") && !type.contains("STAMP")) {
            return when.toLocalTime().toString();
        }
        return when.toString().replace('T', ' ');
    }

    private static String text(ColumnInfo column, int index, Random random) {
        int max = column.precision() > 0 ? Math.min(column.precision(), 32) : 12;
        String base = column.name().toLowerCase() + "-" + (index + 1) + "-"
                + Integer.toHexString(random.nextInt(0x10000));
        return base.length() <= max ? base : base.substring(0, max);
    }

    private static Rule ruleFor(List<Rule> rules, String column) {
        for (Rule rule : rules) {
            if (rule.column().equalsIgnoreCase(column)) {
                return rule;
            }
        }
        return null;
    }

    /** 生成不写库，只给出前几行给人看看长什么样。 */
    public static List<List<String>> preview(List<ColumnInfo> columns, List<Rule> rules,
                                             int rows, long seed) {
        Random random = new Random(seed);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            List<String> row = new ArrayList<>();
            for (ColumnInfo column : columns) {
                Rule rule = ruleFor(rules, column.name());
                row.add(value(column, rule == null
                        ? new Rule(column.name(), Strategy.AUTO, null) : rule, i, random));
            }
            out.add(row);
        }
        return out;
    }

    /** 今天的日期，给界面上的默认文件名之类用。 */
    public static LocalDate today() {
        return LocalDate.now();
    }
}
