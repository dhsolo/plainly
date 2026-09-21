package com.plainly.driver.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 筛选与排序。
 *
 * <p>这里只描述「筛什么、怎么排」，不拼 SQL——拼装是方言的事，
 * 值更是一律走参数绑定，绝不进 SQL 文本。把长数值拼进语句，
 * 要么被驱动按 double 解析，要么被数据库按字面量推断类型，两条路都会丢精度。
 *
 * <p>筛选在数据库里执行，不是在已加载的那一页里过滤：页上只有当前这几百行，
 * 在上面筛出来的结果对整张表毫无意义。
 */
public record FilterSpec(List<Condition> conditions, List<Sort> sorts) {

    public static FilterSpec empty() {
        return new FilterSpec(List.of(), List.of());
    }

    public boolean isEmpty() {
        return conditions.isEmpty() && sorts.isEmpty();
    }

    /** 一行筛选条件之间的连接词。第一行的连接词不参与拼装。 */
    public enum Combiner {
        AND("并且"), OR("或者");

        private final String label;

        Combiner(String label) {
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

    /**
     * 比较方式。
     *
     * <p>{@code arity} 是要绑几个值：{@code IS NULL} 一个都不绑，
     * {@code BETWEEN} 绑两个。少绑或多绑都会让驱动抛出难懂的参数错误，
     * 所以这件事写在类型里，而不是散落在拼装代码里。
     */
    public enum Operator {
        EQ("等于", "=", 1),
        NE("不等于", "<>", 1),
        GT("大于", ">", 1),
        GE("大于等于", ">=", 1),
        LT("小于", "<", 1),
        LE("小于等于", "<=", 1),
        LIKE("包含", "LIKE", 1),
        NOT_LIKE("不包含", "NOT LIKE", 1),
        BETWEEN("介于", "BETWEEN", 2),
        IS_NULL("为 NULL", "IS NULL", 0),
        IS_NOT_NULL("不为 NULL", "IS NOT NULL", 0);

        private final String label;
        private final String sql;
        private final int arity;

        Operator(String label, String sql, int arity) {
            this.label = label;
            this.sql = sql;
            this.arity = arity;
        }

        public String label() {
            return label;
        }

        public String sql() {
            return sql;
        }

        public int arity() {
            return arity;
        }

        /** 包含类的比较要给值套上通配符，这一步不能让用户自己记。 */
        public boolean wrapsWildcards() {
            return this == LIKE || this == NOT_LIKE;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 一行条件。
     *
     * <p>值一律是字符串——和结果集里的值同一种载体。到绑定那一步才按列的类型
     * 变成 BigDecimal 之类，这是全程唯一的一次转换。
     */
    public record Condition(Combiner combiner, String column, Operator operator,
                            String value, String value2) {

        public List<String> values() {
            List<String> out = new ArrayList<>();
            if (operator.arity() >= 1) {
                out.add(operator.wrapsWildcards() ? "%" + nz(value) + "%" : value);
            }
            if (operator.arity() >= 2) {
                out.add(value2);
            }
            return out;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }

    /** 一行排序。 */
    public record Sort(String column, boolean descending) {
    }
}
