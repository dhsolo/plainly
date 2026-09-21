package com.plainly.driver;

import java.util.List;
import java.util.Locale;

/**
 * 类型名的语义判定与候选目录。
 *
 * <p>结构编辑器里用户直接敲类型名，没有 {@code java.sql.Types} 可查，
 * 只能按名字判断——而「这一列是不是精确数值」这个判断错了，
 * 编辑器就会为它生成错误的绑定方式。所以这套映射与
 * {@code TypeCategories} 的名称兜底分支保持同一套规则。
 */
public final class TypeNames {

    private TypeNames() {
    }

    /** 仅凭类型名判定语义类别。 */
    public static TypeCategory categoryOf(String typeName) {
        String n = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT).trim();
        if (n.isEmpty()) {
            return TypeCategory.OTHER;
        }
        // 精确数值必须最先判，判漏一个就是静默丢精度
        if (n.startsWith("DECIMAL") || n.startsWith("NUMERIC") || n.startsWith("NUMBER")
                || n.startsWith("MONEY") || n.startsWith("BIGINT")) {
            return TypeCategory.EXACT_NUMERIC;
        }
        if (n.startsWith("TINYINT") || n.startsWith("SMALLINT") || n.startsWith("MEDIUMINT")
                || n.equals("INT") || n.startsWith("INTEGER") || n.startsWith("INT ")
                || n.startsWith("SERIAL")) {
            return TypeCategory.INTEGER;
        }
        // BINARY_DOUBLE / BINARY_FLOAT 必须抢在下面那条 contains("BINARY") 前面判：
        // 它们是 Oracle 的浮点类型，名字里那个 BINARY 说的是「二进制浮点表示」，
        // 不是二进制数据。判成 BINARY，整列会被当成 BLOB 显示成 [BLOB 8 B 0x...]
        if (n.startsWith("BINARY_DOUBLE") || n.startsWith("BINARY_FLOAT")) {
            return TypeCategory.APPROX_NUMERIC;
        }
        if (n.startsWith("FLOAT") || n.startsWith("REAL") || n.startsWith("DOUBLE")) {
            return TypeCategory.APPROX_NUMERIC;
        }
        if (n.startsWith("BOOL") || n.equals("BIT")) {
            return TypeCategory.BOOLEAN;
        }
        if (n.startsWith("JSON")) {
            return TypeCategory.JSON;
        }
        if (n.startsWith("TIMESTAMP") || n.startsWith("DATETIME")
                || n.startsWith("DATE") || n.startsWith("TIME")) {
            return TypeCategory.TEMPORAL;
        }
        // RAW / LONG RAW 是 Oracle 的二进制类型，名字里没有 BINARY 也没有 BLOB
        if (n.contains("BLOB") || n.startsWith("BYTEA") || n.contains("BINARY")
                || n.equals("RAW") || n.startsWith("RAW(") || n.startsWith("LONG RAW")) {
            return TypeCategory.BINARY;
        }
        if (n.contains("CHAR") || n.contains("TEXT") || n.contains("CLOB")) {
            return TypeCategory.STRING;
        }
        return TypeCategory.OTHER;
    }

    /**
     * 括号里那个数字的含义。
     *
     * <p>{@code VARCHAR(255)}、{@code DECIMAL(38,10)}、{@code TIMESTAMP(6)}
     * 的括号参数看着都是「长度」，实际是三件完全不同的事，取值范围也不同。
     * 混为一谈会出真事故：把 VARCHAR(255) 改成 TIMESTAMP 时若把 255 带过去，
     * 就会生成数据库直接拒绝的 {@code TIMESTAMP(255)}。
     */
    public enum LengthKind {
        /** 不带参数，如 TEXT、BLOB、INT。 */
        NONE,
        /** 字符或字节长度，如 VARCHAR(255)。 */
        STRING_LENGTH,
        /** 十进制总位数，如 DECIMAL(38,10)。 */
        NUMERIC_PRECISION,
        /** 秒的小数位，取值 0–9，如 TIMESTAMP(6)。 */
        FRACTIONAL_SECONDS
    }

    /** 秒小数位的上限。SQL 标准与各家实现都是 9。 */
    public static final int MAX_FRACTIONAL_SECONDS = 9;

    public static LengthKind lengthKindOf(String typeName) {
        String n = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT).trim();
        if (n.startsWith("DECIMAL") || n.startsWith("NUMERIC") || n.startsWith("NUMBER")) {
            return LengthKind.NUMERIC_PRECISION;
        }
        if (n.startsWith("DATETIME") || n.startsWith("TIMESTAMP") || n.startsWith("TIME")) {
            return LengthKind.FRACTIONAL_SECONDS;
        }
        if (n.startsWith("VARCHAR") || n.startsWith("CHAR") || n.startsWith("NVARCHAR")
                || n.startsWith("NCHAR") || n.startsWith("VARBINARY") || n.startsWith("RAW")
                || (n.startsWith("BINARY") && !n.startsWith("BINARY_"))) {
            return LengthKind.STRING_LENGTH;
        }
        return LengthKind.NONE;
    }

    /** 该类型是否需要长度参数。 */
    public static boolean takesLength(String typeName) {
        return lengthKindOf(typeName) != LengthKind.NONE;
    }

    /** 把长度值夹到该类型允许的范围内。 */
    public static int clampLength(String typeName, int value) {
        if (value <= 0) {
            return 0;
        }
        return lengthKindOf(typeName) == LengthKind.FRACTIONAL_SECONDS
                ? Math.min(value, MAX_FRACTIONAL_SECONDS) : value;
    }

    /** 该类型是否需要小数位参数。 */
    public static boolean takesScale(String typeName) {
        String n = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT).trim();
        return n.startsWith("DECIMAL") || n.startsWith("NUMERIC") || n.startsWith("NUMBER");
    }

    /**
     * 新建表时那个默认主键列该用什么类型。
     *
     * <h2>为什么不能写死一个 BIGINT</h2>
     * {@code BIGINT} 在 MySQL / PostgreSQL / SQL Server / H2 / 达梦上都成立，
     * 唯独 <b>Oracle 没有这个类型</b>——发过去就是一句
     * {@code ORA-00902: 无效数据类型}，而用户只是点了「新建表」，
     * 根本没碰过类型那一栏。
     *
     * <p>SQLite 则是另一种讲究：它的自增只认<b>正好是 INTEGER</b> 的主键
     * （{@code INTEGER PRIMARY KEY AUTOINCREMENT}），写成 BIGINT 就不再是 rowid 别名，
     * 自增会安静地失效——比报错更难查。
     *
     * <p>Oracle 用 {@code NUMBER(19)}：它是精确数值，取值范围也对得上 BIGINT。
     */
    public static String defaultKeyType(DbType type) {
        switch (type) {
            case ORACLE:
                return "NUMBER(19)";
            case SQLITE:
                return "INTEGER";
            default:
                return "BIGINT";
        }
    }

    /**
     * 结构编辑器里新增一个字段时的默认类型。
     *
     * <p>Oracle 上给 {@code VARCHAR2}：{@code VARCHAR} 虽然它也认，
     * 但 Oracle 自己的文档明说那是保留给将来改语义用的，不该拿来建表。
     */
    public static String defaultColumnType(DbType type) {
        return type == DbType.ORACLE ? "VARCHAR2" : "VARCHAR";
    }

    /** 结构编辑器下拉里的常用类型。把精确数值排在数值类型最前面。 */
    public static List<String> catalogFor(DbType type) {
        switch (type) {
            case MYSQL:
                return List.of(
                        "BIGINT", "INT", "SMALLINT", "TINYINT", "DECIMAL", "DOUBLE", "FLOAT",
                        "VARCHAR", "CHAR", "TEXT", "LONGTEXT", "JSON",
                        "DATETIME", "TIMESTAMP", "DATE", "TIME",
                        "TINYINT(1)", "BLOB", "LONGBLOB", "VARBINARY");
            case POSTGRESQL:
                return List.of(
                        "BIGINT", "INTEGER", "SMALLINT", "NUMERIC", "DOUBLE PRECISION", "REAL",
                        "VARCHAR", "CHAR", "TEXT", "JSONB", "JSON",
                        "TIMESTAMP", "TIMESTAMPTZ", "DATE", "TIME",
                        "BOOLEAN", "BYTEA", "UUID");
            case SQLITE:
                return List.of("INTEGER", "TEXT", "REAL", "NUMERIC", "BLOB");
            case ORACLE:
                // NUMBER 排第一不只是习惯：Oracle 的整数也是 NUMBER(n)，
                // 而它是精确数值。顺手选到 BINARY_DOUBLE 才是丢精度的那条路
                return List.of(
                        "NUMBER", "NUMBER(19)", "NUMBER(38,10)", "BINARY_DOUBLE", "BINARY_FLOAT",
                        "VARCHAR2(255)", "NVARCHAR2(255)", "CHAR(1)", "CLOB", "NCLOB",
                        "DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE",
                        "BLOB", "RAW(2000)");
            case DM:
                // 达梦两套类型名都认（Oracle 的和 MySQL 的），
                // 这里给 Oracle 那一套，跟它默认的兼容模式一致
                return List.of(
                        "NUMBER", "BIGINT", "INT", "DECIMAL(38,10)", "DOUBLE", "FLOAT",
                        "VARCHAR(255)", "VARCHAR2(255)", "CHAR(1)", "TEXT", "CLOB",
                        "TIMESTAMP", "DATE", "TIME",
                        "BLOB", "BINARY", "BIT");
            case SQLSERVER:
                /*
                 * SQL Server 原来没有单独一条，掉进了下面 H2 那份清单里——
                 * 而那份清单里有一半是 SQL Server 不认的：
                 *
                 *   DOUBLE   → 它叫 FLOAT
                 *   CLOB     → 它叫 VARCHAR(MAX)
                 *   BLOB     → 它叫 VARBINARY(MAX)
                 *   BOOLEAN  → 它叫 BIT
                 *   UUID     → 它叫 UNIQUEIDENTIFIER
                 *   JSON     → 2025 之前根本没有这个类型
                 *
                 * 最阴的是 TIMESTAMP：SQL Server<b>有</b>这个词，但它是行版本戳
                 * （rowversion 的同义词），不是时间。照着建出来的列不会报错，
                 * 只是存进去的东西和用户以为的完全是两回事——比报错难查得多。
                 */
                return List.of(
                        "BIGINT", "INT", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC",
                        "FLOAT", "REAL", "MONEY",
                        "NVARCHAR(255)", "VARCHAR(255)", "NCHAR(1)", "CHAR(1)",
                        "NVARCHAR(MAX)", "VARCHAR(MAX)",
                        "DATETIME2", "DATETIMEOFFSET", "DATE", "TIME", "SMALLDATETIME",
                        "BIT", "UNIQUEIDENTIFIER", "VARBINARY(MAX)", "VARBINARY(255)");
            case H2:
            default:
                return List.of(
                        "BIGINT", "INTEGER", "SMALLINT", "TINYINT", "DECIMAL", "DOUBLE", "REAL",
                        "VARCHAR", "CHAR", "CLOB", "JSON",
                        "TIMESTAMP", "DATE", "TIME", "BOOLEAN", "VARBINARY", "BLOB", "UUID");
        }
    }
}
