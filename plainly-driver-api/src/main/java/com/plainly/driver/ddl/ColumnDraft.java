package com.plainly.driver.ddl;

import com.plainly.driver.TypeCategory;
import com.plainly.driver.TypeNames;
import com.plainly.driver.meta.DbObjects.ColumnInfo;

/**
 * 结构编辑器里的一个字段草稿。
 *
 * <p>可变对象：网格直接就地改它。{@link #originalName} 为 {@code null} 表示新增字段，
 * 否则记录它在库里的原名——改名之后仍要靠它定位原列。
 */
public class ColumnDraft {

    private String name = "";
    /** 库中原名；null 表示这是新增的字段。 */
    private final String originalName;
    private String nativeType = "VARCHAR";
    private int precision;
    private int scale;
    private boolean nullable = true;
    private boolean primaryKey;
    private boolean autoIncrement;
    private String defaultValue;
    private String comment = "";

    private ColumnDraft(String originalName) {
        this.originalName = originalName;
    }

    /**
     * 从库里已有的字段构造草稿。
     *
     * <p>时间类型要单独处理：JDBC 把秒小数位放在 {@code DECIMAL_DIGITS}（scale）里，
     * 而 {@code COLUMN_SIZE}（precision）是格式化后的字符串宽度（H2 对 TIMESTAMP 报 26）。
     * 草稿统一用 {@link #precision} 承载「那个唯一的括号参数」，
     * 所以这里要把 scale 挪过去——否则同一列在库里显示成 {@code TIMESTAMP(6)}、
     * 在草稿里算成 {@code TIMESTAMP(26)}，一打开设计器就会冒出一条根本不存在的变更。
     */
    public static ColumnDraft of(ColumnInfo c) {
        ColumnDraft d = new ColumnDraft(c.name());
        d.name = c.name();
        d.nativeType = c.nativeType();
        if (TypeNames.lengthKindOf(c.nativeType()) == TypeNames.LengthKind.FRACTIONAL_SECONDS) {
            d.precision = c.scale();
            d.scale = 0;
        } else {
            d.precision = c.precision();
            d.scale = c.scale();
        }
        d.nullable = c.nullable();
        d.primaryKey = c.primaryKey();
        d.autoIncrement = c.autoIncrement();
        d.defaultValue = c.defaultValue();
        d.comment = c.comment() == null ? "" : c.comment();
        return d;
    }

    /** 新增字段。 */
    public static ColumnDraft added(String name) {
        ColumnDraft d = new ColumnDraft(null);
        d.name = name;
        d.nativeType = "VARCHAR";
        d.precision = 255;
        return d;
    }

    /**
     * 以 {@code source} 的定义新增一个字段。用于结构同步：源库有、目标库没有的列。
     *
     * <p>与 {@link #of} 的区别只在 {@code originalName}：这里是 {@code null}，
     * 因此 {@link TableDiff} 会把它算作新增而不是修改。
     */
    public static ColumnDraft newFrom(ColumnInfo source) {
        ColumnDraft d = new ColumnDraft(null);
        d.copyDefinitionFrom(source);
        return d;
    }

    /**
     * 把 {@code target} 这一列改成 {@code source} 的定义。用于结构同步：两边都有的列。
     *
     * <p>{@code originalName} 取目标库的列名——DDL 要靠它定位；
     * 其余属性全部来自源库，因为同步方向是「以源为准」。
     */
    public static ColumnDraft rebase(ColumnInfo target, ColumnInfo source) {
        ColumnDraft d = new ColumnDraft(target.name());
        d.copyDefinitionFrom(source);
        return d;
    }

    /** 复制一列的定义（不含 originalName）。时间类型的括号参数同 {@link #of} 处理。 */
    private void copyDefinitionFrom(ColumnInfo c) {
        this.name = c.name();
        this.nativeType = c.nativeType();
        if (TypeNames.lengthKindOf(c.nativeType()) == TypeNames.LengthKind.FRACTIONAL_SECONDS) {
            this.precision = c.scale();
            this.scale = 0;
        } else {
            this.precision = c.precision();
            this.scale = c.scale();
        }
        this.nullable = c.nullable();
        this.primaryKey = c.primaryKey();
        this.autoIncrement = c.autoIncrement();
        this.defaultValue = c.defaultValue();
        this.comment = c.comment() == null ? "" : c.comment();
    }

    public boolean isNew() {
        return originalName == null;
    }

    public String originalName() {
        return originalName;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String nativeType() {
        return nativeType;
    }

    /**
     * 换类型。
     *
     * <p>关键在于<b>括号参数不能跨语义继承</b>：
     * {@code VARCHAR(255)} 的 255 是字符数，{@code TIMESTAMP(n)} 的 n 是秒小数位（上限 9）。
     * 直接沿用会生成 {@code TIMESTAMP(255)} 这种数据库当场拒绝的 DDL。
     * 所以参数含义一变就清零，让用户重新给一个有意义的值。
     */
    public void setNativeType(String nativeType) {
        TypeNames.LengthKind before = TypeNames.lengthKindOf(this.nativeType);
        this.nativeType = nativeType == null ? "" : nativeType.trim();
        TypeNames.LengthKind after = TypeNames.lengthKindOf(this.nativeType);

        if (after == TypeNames.LengthKind.NONE || after != before) {
            precision = 0;
        } else {
            precision = TypeNames.clampLength(this.nativeType, precision);
        }
        if (!TypeNames.takesScale(this.nativeType)) {
            scale = 0;
        }
    }

    public int precision() {
        return precision;
    }

    public void setPrecision(int precision) {
        this.precision = TypeNames.clampLength(nativeType, Math.max(0, precision));
    }

    public int scale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = Math.max(0, scale);
    }

    public boolean nullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean primaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
        if (primaryKey) {
            // 主键隐含非空，先在模型里保持自洽，免得生成自相矛盾的 DDL
            this.nullable = false;
        }
    }

    public boolean autoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue == null || defaultValue.isBlank() ? null : defaultValue.trim();
    }

    public String comment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? "" : comment;
    }

    public TypeCategory category() {
        return TypeNames.categoryOf(nativeType);
    }

    /** 带参数的完整类型，如 {@code DECIMAL(38,10)}。 */
    public String fullType() {
        if (precision <= 0 || !TypeNames.takesLength(nativeType)) {
            return nativeType;
        }
        if (scale > 0 && TypeNames.takesScale(nativeType)) {
            return nativeType + "(" + precision + "," + scale + ")";
        }
        return nativeType + "(" + precision + ")";
    }

    /** 与库中原始定义相比，除名字之外是否有实质变化。 */
    public boolean differsFrom(ColumnInfo original) {
        return !fullType().equalsIgnoreCase(original.displayType())
                || nullable != original.nullable()
                || autoIncrement != original.autoIncrement()
                || !equalsNullable(defaultValue, original.defaultValue())
                || !comment.equals(original.comment() == null ? "" : original.comment());
    }

    /**
     * 比较默认值，把 null 和空白当成同一件事。
     *
     * <p>这条宽容<b>只有在默认值已经归一成字面量之后才成立</b>：那时「默认空字符串」
     * 是 {@code ''}（两个字符，不空白），「没有默认值」是 null，两者分得开。
     * 归一化之前 MySQL 把空字符串默认值报成空串，和「没有默认值」在这里长得一模一样，
     * 于是一个真实的差异被安静地吃掉了——见 {@code SqlDialect.normalizeDefault}。
     */
    private static boolean equalsNullable(String a, String b) {
        String x = a == null || a.isBlank() ? null : a.trim();
        String y = b == null || b.isBlank() ? null : b.trim();
        return x == null ? y == null : x.equals(y);
    }

    public ColumnInfo toColumnInfo(int ordinal) {
        return new ColumnInfo(name, nativeType, category(), precision, scale,
                nullable, primaryKey, autoIncrement, defaultValue, comment, ordinal);
    }

    @Override
    public String toString() {
        return name + " " + fullType();
    }
}
