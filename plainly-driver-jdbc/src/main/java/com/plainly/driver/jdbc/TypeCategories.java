package com.plainly.driver.jdbc;

import com.plainly.driver.TypeCategory;

import java.sql.Types;
import java.util.Locale;

/**
 * {@code java.sql.Types} → {@link TypeCategory} 的映射。
 *
 * <p>这里唯一真正重要的判断是：哪些类型进 {@link TypeCategory#EXACT_NUMERIC}。
 * 判错一个类型，那一列就会静默丢精度，且事后无法从数据里看出来。
 */
public final class TypeCategories {

    private TypeCategories() {
    }

    public static TypeCategory of(int sqlType, String typeName) {
        String name = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);

        switch (sqlType) {
            // ---- 精确数值：必须走 BigDecimal ----
            case Types.BIGINT:
            case Types.DECIMAL:
            case Types.NUMERIC:
                return TypeCategory.EXACT_NUMERIC;

            // ---- 宽度安全的整型；仍按文本传递 ----
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                return TypeCategory.INTEGER;

            // ---- 近似数值 ----
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
                return TypeCategory.APPROX_NUMERIC;

            case Types.BOOLEAN:
            case Types.BIT:
                // MySQL 的 BIT(n>1) 是位串，不是布尔
                return name.startsWith("BIT") && !"BIT".equals(name)
                        ? TypeCategory.BINARY : TypeCategory.BOOLEAN;

            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return TypeCategory.TEMPORAL;

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return TypeCategory.BINARY;

            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return "JSON".equals(name) ? TypeCategory.JSON : TypeCategory.STRING;

            default:
                break;
        }

        // 驱动把类型报成 OTHER 时，退回按类型名判断。
        // PostgreSQL 的 numeric 在某些路径下就会走到这里。
        if (name.contains("DECIMAL") || name.contains("NUMERIC") || name.contains("MONEY")
                || name.contains("BIGINT") || name.equals("NUMBER")) {
            return TypeCategory.EXACT_NUMERIC;
        }
        if (name.contains("JSON")) {
            return TypeCategory.JSON;
        }
        // Oracle 的 BINARY_DOUBLE / BINARY_FLOAT 用的是驱动私有的类型号（101/100），
        // 走不到上面的 switch，只能靠名字认。而且必须抢在下面 contains("BINARY") 之前——
        // 它们是浮点数，不是二进制数据
        if (name.startsWith("BINARY_DOUBLE") || name.startsWith("BINARY_FLOAT")) {
            return TypeCategory.APPROX_NUMERIC;
        }
        if (name.contains("TIMESTAMP") || name.contains("DATE") || name.contains("TIME")) {
            return TypeCategory.TEMPORAL;
        }
        if (name.contains("BLOB") || name.contains("BYTEA") || name.contains("BINARY")
                || name.equals("RAW") || name.startsWith("LONG RAW")) {
            return TypeCategory.BINARY;
        }
        return TypeCategory.OTHER;
    }
}
