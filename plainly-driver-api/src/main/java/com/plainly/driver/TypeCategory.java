package com.plainly.driver;

/**
 * 列的语义类别。决定读取路径、对齐方式、排序比较器和导出策略。
 *
 * <p>之所以要有这一层而不是直接用 {@code java.sql.Types}，是因为真正影响正确性的
 * 分界线只有一条：<b>这一列是不是必须精确</b>。
 */
public enum TypeCategory {

    /**
     * 精确数值：BIGINT、DECIMAL、NUMERIC、Oracle NUMBER。
     * <p>必须走 {@code getBigDecimal()} / {@code getString()}，绝不允许 {@code getDouble()}。
     * 这是整个项目最关键的类别。
     */
    EXACT_NUMERIC(true),

    /**
     * 近似数值：FLOAT、REAL、DOUBLE。
     * <p>本身就是 IEEE 754，读成 double 不丢精度。但仍统一按文本传递，
     * 一是规则统一，二是避免展示层出现 {@code 0.1+0.2} 那类刺眼的输出。
     */
    APPROX_NUMERIC(false),

    /** 整型且宽度安全（TINYINT/SMALLINT/INTEGER）。仍按文本传递。 */
    INTEGER(true),

    STRING(false),
    BOOLEAN(false),
    TEMPORAL(false),
    BINARY(false),
    JSON(false),
    OTHER(false);

    private final boolean exact;

    TypeCategory(boolean exact) {
        this.exact = exact;
    }

    /** 该类别是否要求精确保真。导出、比较、回写都会查这个标志。 */
    public boolean isExact() {
        return exact;
    }

    /** 是否为数值列（用于右对齐与等宽字体渲染）。 */
    public boolean isNumeric() {
        return this == EXACT_NUMERIC || this == APPROX_NUMERIC || this == INTEGER;
    }
}
