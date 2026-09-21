package com.plainly.driver;

import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.util.Locale;

/**
 * 跨库类型映射。
 *
 * <p>把一张表从 MySQL 搬到 PostgreSQL，难的不是搬数据，是给每一列找一个装得下的类型。
 * 找错了不会报错——{@code BIGINT UNSIGNED} 映射成 {@code BIGINT} 语法完全合法，
 * 只有当某一行的值超过 {@code 9223372036854775807} 时才会溢出，
 * 而那时候数据已经搬了一半。
 *
 * <p>所以这里的规则一律往「装得下」的方向偏：宁可用更宽的类型，也不赌源库里没有大值。
 * 每条映射都带一句为什么，界面上直接显示，让人能核对而不是只能相信。
 */
public final class TypeMapper {

    /**
     * 一列的映射结果。
     *
     * @param target 目标库的类型名
     * @param exact 是否精确对应；false 表示换了个类型来兜住值域
     * @param reason 换类型的原因；精确对应时为空
     */
    public record Mapping(String target, boolean exact, String reason) {

        static Mapping exact(String target) {
            return new Mapping(target, true, "");
        }

        static Mapping widened(String target, String reason) {
            return new Mapping(target, false, reason);
        }
    }

    private TypeMapper() {
    }

    /** 映射一列，并把结果写进一份可直接用于建表的草稿。 */
    public static ColumnDraft map(ColumnInfo source, DbType from, DbType to) {
        Mapping mapping = mapType(source, from, to);
        ColumnDraft draft = ColumnDraft.newFrom(source);
        draft.setNativeType(stripArgs(mapping.target()));
        applyLength(draft, source, mapping.target());
        return draft;
    }

    /**
     * 源列在目标库里该用什么类型。
     *
     * <p>同库之间原样返回——没有必要为了「统一」把 {@code TINYINT} 改成别的什么。
     */
    public static Mapping mapType(ColumnInfo source, DbType from, DbType to) {
        String type = source.nativeType() == null ? "" : source.nativeType().trim();
        String upper = type.toUpperCase(Locale.ROOT);
        if (from == to) {
            return Mapping.exact(type);
        }

        boolean unsigned = upper.contains("UNSIGNED");
        String base = upper.replace("UNSIGNED", "").replace("ZEROFILL", "").trim();

        switch (to) {
            case POSTGRESQL:
                return toPostgres(base, unsigned, source);
            case MYSQL:
                return toMySql(base, source);
            case SQLITE:
                return toSqlite(base);
            case H2:
            default:
                return toH2(base, unsigned, source);
        }
    }

    private static Mapping toPostgres(String base, boolean unsigned, ColumnInfo source) {
        switch (base) {
            case "TINYINT":
                // MySQL 用 TINYINT(1) 表示布尔，但 TINYINT 本身能存 0..255，
                // 一律当布尔会把 200 变成 true，所以只按值域挑整型
                return Mapping.exact("SMALLINT");
            case "SMALLINT":
                return unsigned ? Mapping.widened("INTEGER",
                        "PostgreSQL 没有无符号整型，SMALLINT 上界 32767 装不下无符号的 65535")
                        : Mapping.exact("SMALLINT");
            case "MEDIUMINT":
                return Mapping.exact("INTEGER");
            case "INT":
            case "INTEGER":
                return unsigned ? Mapping.widened("BIGINT",
                        "PostgreSQL 没有无符号整型，INTEGER 装不下无符号的 4294967295")
                        : Mapping.exact("INTEGER");
            case "BIGINT":
                return unsigned ? Mapping.widened("NUMERIC(20)",
                        "PostgreSQL 没有无符号整型。上界 18446744073709551615 超出它的 BIGINT，"
                                + "映射成 BIGINT 会溢出，所以改用 NUMERIC(20) 保住全部位数")
                        : Mapping.exact("BIGINT");
            case "DECIMAL":
            case "NUMERIC":
                return Mapping.exact("NUMERIC");
            case "FLOAT":
                return Mapping.exact("REAL");
            case "DOUBLE":
            case "DOUBLE PRECISION":
                return Mapping.exact("DOUBLE PRECISION");
            case "DATETIME":
                return Mapping.exact("TIMESTAMP");
            case "TIMESTAMP":
                return Mapping.exact("TIMESTAMP");
            case "DATE":
                return Mapping.exact("DATE");
            case "TIME":
                return Mapping.exact("TIME");
            case "YEAR":
                return Mapping.widened("SMALLINT", "PostgreSQL 没有 YEAR 类型，按整数存年份");
            case "CHAR":
            case "CHARACTER":
                return Mapping.exact("CHAR");
            case "VARCHAR":
            case "CHARACTER VARYING":
                return Mapping.exact("VARCHAR");
            case "TINYTEXT":
            case "TEXT":
            case "MEDIUMTEXT":
            case "LONGTEXT":
            case "CLOB":
                return Mapping.exact("TEXT");
            case "JSON":
                return Mapping.exact("JSONB");
            case "TINYBLOB":
            case "BLOB":
            case "MEDIUMBLOB":
            case "LONGBLOB":
            case "BINARY":
            case "VARBINARY":
                return Mapping.exact("BYTEA");
            case "BIT":
                return Mapping.exact("BOOLEAN");
            case "ENUM":
            case "SET":
                return Mapping.widened("VARCHAR(255)",
                        "PostgreSQL 没有等价的 ENUM/SET，按文本存原值");
            default:
                return fallback(base, source);
        }
    }

    private static Mapping toMySql(String base, ColumnInfo source) {
        switch (base) {
            case "SMALLINT":
            case "INTEGER":
            case "INT":
            case "BIGINT":
            case "DATE":
            case "TIME":
            case "CHAR":
            case "VARCHAR":
                return Mapping.exact(base.equals("INTEGER") ? "INT" : base);
            case "CHARACTER":
                return Mapping.exact("CHAR");
            case "CHARACTER VARYING":
                return Mapping.exact("VARCHAR");
            case "NUMERIC":
            case "DECIMAL":
                return Mapping.exact("DECIMAL");
            case "REAL":
                return Mapping.exact("FLOAT");
            case "DOUBLE PRECISION":
                return Mapping.exact("DOUBLE");
            case "TIMESTAMP":
                return Mapping.widened("DATETIME",
                        "MySQL 的 TIMESTAMP 只到 2038 年，改用 DATETIME 避免截断");
            case "TEXT":
                return Mapping.exact("LONGTEXT");
            case "BYTEA":
                return Mapping.exact("LONGBLOB");
            case "JSONB":
            case "JSON":
                return Mapping.exact("JSON");
            case "BOOLEAN":
                return Mapping.exact("TINYINT(1)");
            case "UUID":
                return Mapping.widened("CHAR(36)", "MySQL 没有 UUID 类型，按定长文本存");
            default:
                return fallback(base, source);
        }
    }

    /**
     * SQLite 只有五种存储类，别的写法它照收但按亲和性归类。
     *
     * <p>要紧的是 DECIMAL：SQLite 会把它归到 NUMERIC 亲和性，
     * 存进去的高精度值可能被转成 REAL——也就是 double。所以一律用 TEXT 存，
     * 这是在 SQLite 上保住精度的唯一办法。
     */
    private static Mapping toSqlite(String base) {
        switch (base) {
            case "DECIMAL":
            case "NUMERIC":
                return Mapping.widened("TEXT",
                        "SQLite 的 NUMERIC 亲和性会把高精度值转成 REAL（double），改用 TEXT 才保得住每一位");
            case "TINYINT":
            case "SMALLINT":
            case "MEDIUMINT":
            case "INT":
            case "INTEGER":
            case "BIGINT":
                return Mapping.exact("INTEGER");
            case "FLOAT":
            case "REAL":
            case "DOUBLE":
            case "DOUBLE PRECISION":
                return Mapping.exact("REAL");
            case "BLOB":
            case "BYTEA":
            case "LONGBLOB":
            case "VARBINARY":
                return Mapping.exact("BLOB");
            default:
                return Mapping.exact("TEXT");
        }
    }

    private static Mapping toH2(String base, boolean unsigned, ColumnInfo source) {
        switch (base) {
            case "TINYINT":
                return Mapping.exact("TINYINT");
            case "MEDIUMINT":
                return Mapping.exact("INTEGER");
            case "INT":
                return unsigned ? Mapping.widened("BIGINT", "H2 没有无符号整型，放宽一档避免溢出")
                        : Mapping.exact("INTEGER");
            case "BIGINT":
                return unsigned ? Mapping.widened("NUMERIC(20)",
                        "H2 没有无符号整型，BIGINT 装不下无符号上界")
                        : Mapping.exact("BIGINT");
            case "DECIMAL":
                return Mapping.exact("NUMERIC");
            case "DATETIME":
                return Mapping.exact("TIMESTAMP");
            case "LONGTEXT":
            case "MEDIUMTEXT":
            case "TEXT":
                return Mapping.exact("CLOB");
            case "LONGBLOB":
            case "MEDIUMBLOB":
            case "BYTEA":
                return Mapping.exact("BLOB");
            case "JSONB":
                return Mapping.exact("JSON");
            default:
                return fallback(base, source);
        }
    }

    /**
     * 认不出来的类型。
     *
     * <p>不猜。原样带过去，标成「不确定」让人自己看一眼——
     * 悄悄换成 VARCHAR(255) 才是真的危险。
     */
    private static Mapping fallback(String base, ColumnInfo source) {
        return new Mapping(base, false, "目标库里没有对应类型，原样保留，请自行确认");
    }

    private static String stripArgs(String type) {
        int paren = type.indexOf('(');
        return paren < 0 ? type : type.substring(0, paren).trim();
    }

    /** 目标类型自带括号（如 NUMERIC(20)）时以它为准，否则沿用源列的长度与小数位。 */
    private static void applyLength(ColumnDraft draft, ColumnInfo source, String target) {
        int paren = target.indexOf('(');
        if (paren < 0) {
            draft.setPrecision(source.precision());
            draft.setScale(source.scale());
            return;
        }
        String args = target.substring(paren + 1, target.length() - 1);
        String[] parts = args.split(",");
        try {
            draft.setPrecision(Integer.parseInt(parts[0].trim()));
            draft.setScale(parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0);
        } catch (NumberFormatException e) {
            draft.setPrecision(source.precision());
            draft.setScale(source.scale());
        }
    }
}
