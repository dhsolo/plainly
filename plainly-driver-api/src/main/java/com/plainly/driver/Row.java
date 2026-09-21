package com.plainly.driver;

import java.util.Arrays;
import java.util.Objects;

/**
 * 结果集中的一行。
 *
 * <h2>精度铁律</h2>
 * 单元格值的<b>唯一合法载体是 {@link String}</b>。这不是实现细节，是整个项目的地基约束：
 *
 * <ul>
 *   <li>{@code null} 元素表示 SQL NULL，与空字符串 {@code ""} 严格区分；</li>
 *   <li>精确数值（BIGINT / DECIMAL / NUMBER）由 {@code ResultSet.getBigDecimal()}
 *       读出后经 {@code toPlainString()} 转为文本，全程不经过 {@code double} 或 {@code long}；</li>
 *   <li>本类<b>没有</b>、也永远不会有 {@code getInt()} / {@code getDouble()} 之类的访问器。
 *       Java 的 {@code ResultSet} 把这些有损的取值方式和无损的摆在一起，是最常见的翻车点；
 *       在契约层把它们去掉，调用方就没有机会用错。</li>
 * </ul>
 *
 * 值的类型信息不挂在值上，而挂在列上（{@link ColumnMeta}）——
 * 一列只有一种类型，10 万行不该产生 10 万个包装对象。
 */
public final class Row {

    private final String[] values;

    public Row(String[] values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    /** 第 {@code index} 列的原始文本，{@code null} 表示 SQL NULL。索引从 0 开始。 */
    public String get(int index) {
        return values[index];
    }

    public boolean isNull(int index) {
        return values[index] == null;
    }

    public int size() {
        return values.length;
    }

    /**
     * 返回内部数组本身，不做防御性复制。
     * <p>调用方<b>不得</b>修改返回的数组；这里让出封装是为了让大结果集避免逐行拷贝。
     * 需要修改请用 {@link #withValue(int, String)}。
     */
    public String[] rawValues() {
        return values;
    }

    /** 返回替换了某一列之后的新行，原行不变。 */
    public Row withValue(int index, String value) {
        String[] copy = values.clone();
        copy[index] = value;
        return new Row(copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Row other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
