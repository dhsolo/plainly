package com.plainly.core.search;

import com.plainly.driver.meta.DbObjects.ColumnRef;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 对象搜索：在已知的表名、视图名、列名里按片段找东西。
 *
 * <p>这里只负责<b>排序</b>，不负责取数据。取数据要不要发请求、发几次、缓存多久，
 * 是调用方的事；排序规则是纯函数，能单独测——搜索质量的好坏几乎全在排序上，
 * 混进 I/O 就没法验证了。
 *
 * <h2>名次怎么排</h2>
 * 一个人在搜索框里敲 {@code order}，心里想的多半是那张叫 {@code order} 的表，
 * 而不是 {@code t_work_order_detail} 里的 {@code order_no} 字段。所以：
 * <ol>
 *   <li>完全相同 &gt; 开头匹配 &gt; 中间匹配；</li>
 *   <li>同一档里，表和视图排在列前面；</li>
 *   <li>再同档，名字短的在前——短名字更可能是用户想要的那个主体。</li>
 * </ol>
 * 全程忽略大小写：库里的表名是 {@code T_ORDER} 还是 {@code t_order}，
 * 取决于建库的人当年的习惯，不该让用户去猜。
 */
public final class ObjectSearch {

    /** 命中的东西是什么。 */
    public enum Kind {
        TABLE("表"),
        VIEW("视图"),
        COLUMN("字段");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一条命中。
     *
     * @param table  命中的表；命中的是列时，是这一列所在的表
     * @param name   命中的名字本身（表名或列名）
     * @param detail 右侧的补充说明，如列的类型、表的注释
     */
    public record Hit(Kind kind, String schema, String table, String name, String detail,
                      int rank) {

        /** 面包屑：库 · 表 · 列，缺哪一段就省哪一段。 */
        public String path() {
            StringBuilder sb = new StringBuilder();
            if (schema != null && !schema.isBlank()) {
                sb.append(schema);
            }
            if (kind == Kind.COLUMN && table != null && !table.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(table);
            }
            return sb.toString();
        }
    }

    private ObjectSearch() {
    }

    /**
     * 排名。
     *
     * @param needle  用户敲进去的片段；空白时返回空表（不做「显示全部」——
     *                几千张表一次全铺出来，弹窗只会变成一堵墙）
     * @param tables  候选表与视图
     * @param columns 候选列
     * @param limit   最多返回多少条
     */
    public static List<Hit> match(String needle, List<TableInfo> tables,
                                  List<ColumnRef> columns, int limit) {
        List<Hit> out = new ArrayList<>();
        if (needle == null || needle.isBlank() || limit <= 0) {
            return out;
        }
        String lower = needle.strip().toLowerCase(Locale.ROOT);

        for (TableInfo t : tables) {
            // 序列和事件不进搜索结果：搜到之后的动作是「打开」，而它们打不开。
            // 列一个点了没反应的结果，比不列出来糟
            if (!t.kind().hasRows()) {
                continue;
            }
            int rank = rankOf(t.name(), lower);
            if (rank < 0) {
                continue;
            }
            Kind kind = t.kind() == ObjectKind.TABLE ? Kind.TABLE : Kind.VIEW;
            out.add(new Hit(kind, t.schema(), t.name(), t.name(),
                    t.comment() == null || t.comment().isBlank() ? kind.label() : t.comment(),
                    rank));
        }
        for (ColumnRef c : columns) {
            int rank = rankOf(c.column(), lower);
            if (rank < 0) {
                continue;
            }
            out.add(new Hit(Kind.COLUMN, c.schema(), c.table(), c.column(),
                    c.nativeType(), rank));
        }

        out.sort(ranking());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * 名次比较器。
     *
     * <p>单独暴露出来，是因为跨连接搜索时要把各连接各自排好的结果再并成一份总榜——
     * 那时候必须用同一把尺子，否则合并出来的顺序会和单连接时不一致。
     */
    public static Comparator<Hit> ranking() {
        return Comparator
                .comparingInt(Hit::rank)
                .thenComparingInt((Hit h) -> h.kind() == Kind.COLUMN ? 1 : 0)
                .thenComparingInt(h -> h.name().length())
                .thenComparing(Hit::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(h -> h.schema() == null ? "" : h.schema());
    }

    /** 0 完全相同、1 开头、2 中间；-1 表示不匹配。 */
    static int rankOf(String candidate, String lowerNeedle) {
        if (candidate == null) {
            return -1;
        }
        String c = candidate.toLowerCase(Locale.ROOT);
        if (c.equals(lowerNeedle)) {
            return 0;
        }
        if (c.startsWith(lowerNeedle)) {
            return 1;
        }
        return c.contains(lowerNeedle) ? 2 : -1;
    }
}
