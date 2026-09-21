package com.plainly.core.query;

import com.plainly.driver.SqlDialect;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可视化查询的模型：拼哪些表、连哪些字段、选哪些列。
 *
 * <p>SQL 由这份模型生成，不反向解析——一旦用户切到编辑器手改，改动就归他，
 * 构建器不会再去覆盖。「双向同步」听起来美好，实际上是把用户写的 SQL 悄悄改掉。
 *
 * <p>聚合会改变结果类型：对 {@code DECIMAL(38,10)} 求 SUM，MySQL 会把结果放宽到
 * {@code DECIMAL(48,10)}。这不影响读取路径——结果仍落在精确数值类别，
 * 照样按原文取回，不经 double。
 */
public final class QueryPlan {

    /** 连接方式。 */
    public enum JoinType {
        INNER("INNER JOIN"), LEFT("LEFT JOIN"), RIGHT("RIGHT JOIN");

        private final String sql;

        JoinType(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        @Override
        public String toString() {
            return sql;
        }
    }

    /** 聚合函数。{@code NONE} 表示原样取值。 */
    public enum Aggregate {
        NONE(""), COUNT("COUNT"), SUM("SUM"), AVG("AVG"), MIN("MIN"), MAX("MAX");

        private final String sql;

        Aggregate(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        @Override
        public String toString() {
            return this == NONE ? "（不聚合）" : sql;
        }
    }

    /** 参与查询的一张表。 */
    public record Source(String table, String alias) {
    }

    /** 一条连接。 */
    public record Join(JoinType type, String leftAlias, String leftColumn,
                       String rightAlias, String rightColumn) {
    }

    /**
     * 一个输出字段。
     *
     * @param condition 直接写进 WHERE 的片段；这里刻意允许自由文本，
     *                  因为条件的花样太多，限制成下拉反而处处掣肘
     */
    public record Field(String alias, String column, String outputName, Aggregate aggregate,
                        boolean grouped, boolean output, String sort, String condition) {
    }

    private QueryPlan() {
    }

    /**
     * 生成 SQL。
     *
     * <p>有聚合就自动补 GROUP BY：把非聚合的输出列全放进去。忘了写 GROUP BY 是
     * 手写聚合查询时最常见的错误，构建器没有理由把这个坑留给用户。
     */
    public static String toSql(SqlDialect dialect, String schema, List<Source> sources,
                               List<Join> joins, List<Field> fields) {
        if (sources.isEmpty()) {
            return "-- 还没有选表";
        }
        List<Field> outputs = fields.stream().filter(Field::output).toList();
        if (outputs.isEmpty()) {
            return "-- 还没有选要输出的字段";
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < outputs.size(); i++) {
            if (i > 0) {
                sb.append(",\n       ");
            }
            sb.append(expression(outputs.get(i)));
            if (outputs.get(i).outputName() != null && !outputs.get(i).outputName().isBlank()) {
                sb.append(" AS ").append(dialect.quote(outputs.get(i).outputName()));
            }
        }

        Source first = sources.get(0);
        sb.append("\n  FROM ").append(dialect.qualify(schema, first.table()))
                .append(' ').append(first.alias());
        for (Join join : joins) {
            Source right = find(sources, join.rightAlias());
            if (right == null) {
                continue;
            }
            sb.append("\n  ").append(join.type().sql()).append(' ')
                    .append(dialect.qualify(schema, right.table())).append(' ')
                    .append(right.alias())
                    .append(" ON ").append(join.leftAlias()).append('.')
                    .append(dialect.quote(join.leftColumn()))
                    .append(" = ").append(join.rightAlias()).append('.')
                    .append(dialect.quote(join.rightColumn()));
        }

        List<String> conditions = new ArrayList<>();
        for (Field field : fields) {
            if (field.condition() != null && !field.condition().isBlank()) {
                conditions.add(qualified(field) + " " + field.condition().trim());
            }
        }
        if (!conditions.isEmpty()) {
            sb.append("\n WHERE ").append(String.join("\n   AND ", conditions));
        }

        boolean aggregated = outputs.stream().anyMatch(f -> f.aggregate() != Aggregate.NONE);
        if (aggregated) {
            Set<String> group = new LinkedHashSet<>();
            for (Field field : outputs) {
                if (field.aggregate() == Aggregate.NONE) {
                    group.add(qualified(field));
                }
            }
            if (!group.isEmpty()) {
                sb.append("\n GROUP BY ").append(String.join(", ", group));
            }
        }

        List<String> orders = new ArrayList<>();
        for (Field field : fields) {
            if (field.sort() != null && !field.sort().isBlank()) {
                String target = field.outputName() != null && !field.outputName().isBlank()
                        ? dialect.quote(field.outputName()) : expression(field);
                orders.add(target + " " + field.sort());
            }
        }
        if (!orders.isEmpty()) {
            sb.append("\n ORDER BY ").append(String.join(", ", orders));
        }
        return sb.toString();
    }

    private static String expression(Field field) {
        String qualified = qualified(field);
        return field.aggregate() == Aggregate.NONE
                ? qualified : field.aggregate().sql() + "(" + qualified + ")";
    }

    private static String qualified(Field field) {
        return field.alias() + "." + field.column();
    }

    private static Source find(List<Source> sources, String alias) {
        for (Source s : sources) {
            if (s.alias().equals(alias)) {
                return s;
            }
        }
        return null;
    }

    /** 给表起个短别名：取首字母，重了就补数字。 */
    public static String aliasFor(String table, Set<String> taken) {
        String base = table.replaceAll("[^A-Za-z0-9_]", "");
        base = base.isEmpty() ? "t" : base.substring(0, 1).toLowerCase(java.util.Locale.ROOT);
        String alias = base;
        int n = 2;
        while (taken.contains(alias)) {
            alias = base + n++;
        }
        return alias;
    }
}
