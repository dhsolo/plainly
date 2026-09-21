package com.plainly.driver;

/**
 * 结果集中一列的元数据。类型信息挂在列上，不挂在每个值上。
 *
 * @param name        列名（{@code ResultSetMetaData.getColumnName}）
 * @param label       显示名，即 AS 别名（{@code getColumnLabel}）
 * @param nativeType  数据库原生类型名，如 {@code DECIMAL}、{@code BIGINT UNSIGNED}
 * @param category    语义类别，决定读取路径与导出策略
 * @param precision   总位数；不适用时为 0
 * @param scale       小数位；不适用时为 0
 * @param nullable    是否允许 NULL
 * @param schemaName  来源模式名，可能为空。写回时要靠它限定表——
 *                    同一个库里两个模式放同名表是常事，只认表名会写错库
 * @param tableName   来源表名，可能为空（表达式列、聚合列没有来源表）
 * @param partOfKey   是否属于主键/唯一键。可编辑网格靠它定位回写目标，
 *                    整行都取不到键时该结果集降级为只读
 * @param autoIncrement 是否自增
 */
public record ColumnMeta(
        String name,
        String label,
        String nativeType,
        TypeCategory category,
        int precision,
        int scale,
        boolean nullable,
        String schemaName,
        String tableName,
        boolean partOfKey,
        boolean autoIncrement) {

    /** 表头上显示的类型说明，如 {@code DECIMAL(38,10)}。 */
    public String displayType() {
        TypeNames.LengthKind kind = TypeNames.lengthKindOf(nativeType);
        switch (kind) {
            case NONE:
                // 整型的 precision 各家报的东西不一样（H2 报位宽 64，MySQL 报显示宽度 20），
                // 对使用者没有意义
                return nativeType;
            case FRACTIONAL_SECONDS:
                // 时间类型真正的参数是秒小数位，JDBC 把它放在 DECIMAL_DIGITS（scale）里；
                // COLUMN_SIZE（precision）是格式化后的字符串宽度，取它就会得到 TIMESTAMP(26) 这种假值
                return scale > 0 ? nativeType + "(" + scale + ")" : nativeType;
            case NUMERIC_PRECISION:
                if (precision <= 0) {
                    return nativeType;
                }
                return scale > 0
                        ? nativeType + "(" + precision + "," + scale + ")"
                        : nativeType + "(" + precision + ")";
            case STRING_LENGTH:
            default:
                return precision > 0 ? nativeType + "(" + precision + ")" : nativeType;
        }
    }

    /**
     * 这一列的值会不会超出 double 能精确表示的范围。
     *
     * <p>「精确数值」这条标记是用来提醒风险的，挂在每个 INTEGER 上只会变成背景噪音——
     * INT 最大十位数，double 存得下，本来就没有风险。
     * DECIMAL 一律算（小数位本身就存不进二进制浮点），整数则看位数：
     * BIGINT 十九位，早就越过 double 的十五位有效数字了。
     */
    public boolean riskyInDouble() {
        return category == TypeCategory.EXACT_NUMERIC
                || (category == TypeCategory.INTEGER && precision > 15);
    }

    public boolean isExact() {
        return category.isExact();
    }
}
