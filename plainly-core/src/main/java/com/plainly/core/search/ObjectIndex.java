package com.plainly.core.search;

import com.plainly.driver.meta.DbObjects.ColumnRef;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 对象搜索的索引与匹配。
 *
 * <h2>为什么换成索引</h2>
 * 原来的做法是每敲一个字符就用 JDBC 的名字模式去查一轮元数据。实测下来两个硬伤：
 * <ul>
 *   <li><b>搜不到注释。</b>JDBC 的名字模式只匹配<b>列名</b>。而中文库里列名多是英文
 *       （{@code createTime}），注释才是人真正会去搜的那个词（「创建时间」）——
 *       实测搜「创建时间」<b>零结果</b>，而库里到处都是这个注释。</li>
 *   <li><b>每次十几趟往返。</b>12 个库就是 13 次元数据查询、约 150ms，
 *       而且每敲一个字符重来一遍。</li>
 * </ul>
 * 改成一次性把库里的表名、列名、注释拉进内存建索引，之后全在本地匹配：
 * 注释能搜了，往返降到零，还腾出了做模糊匹配的余地。
 *
 * <h2>匹配分几档</h2>
 * 档次的顺序就是「用户多半想要哪个」的顺序，不是随手排的：
 * <ol start="0">
 *   <li>名字完全相同；</li>
 *   <li>名字以它开头；</li>
 *   <li>名字包含它；</li>
 *   <li><b>去掉下划线之后</b>名字包含它——{@code orderitem} 能找到 {@code order_item}。
 *       少打一个下划线是最常见的输入习惯，之前这种输入是零结果；</li>
 *   <li>注释包含它。</li>
 * </ol>
 * 同档里表排在字段前面，名字短的排在前面——短名字更可能是用户想要的那个主体。
 */
public final class ObjectIndex {

    /** 索引里的一条。表和字段用同一种结构，{@code column} 为空就是表。 */
    public record Entry(ObjectSearch.Kind kind, String schema, String table,
                        String name, String detail, String comment) {

        static Entry ofTable(TableInfo t) {
            ObjectSearch.Kind kind = t.kind() == ObjectKind.TABLE
                    ? ObjectSearch.Kind.TABLE : ObjectSearch.Kind.VIEW;
            return new Entry(kind, t.schema(), t.name(), t.name(),
                    kind.label(), nz(t.comment()));
        }

        static Entry ofColumn(ColumnRef c) {
            return new Entry(ObjectSearch.Kind.COLUMN, c.schema(), c.table(),
                    c.column(), nz(c.nativeType()), nz(c.comment()));
        }

        /** 面包屑：库 · 表。 */
        public String path() {
            StringBuilder sb = new StringBuilder(nz(schema));
            if (kind == ObjectSearch.Kind.COLUMN && table != null && !table.isBlank()) {
                sb.append(sb.length() > 0 ? " · " : "").append(table);
            }
            return sb.toString();
        }
    }

    /** 一条命中，外加它是<b>凭什么</b>命中的——凭注释命中时界面要把注释显出来。 */
    public record Hit(Entry entry, int rank, boolean matchedComment) {
    }

    /** 名字完全相同。 */
    static final int EXACT = 0;
    /** 名字以它开头。 */
    static final int PREFIX = 1;
    /** 名字包含它。 */
    static final int CONTAINS = 2;
    /** 去掉分隔符之后名字包含它。 */
    static final int LOOSE = 3;
    /** 注释包含它。 */
    static final int COMMENT = 4;

    private ObjectIndex() {
    }

    /**
     * 建表名索引。
     *
     * <p>序列和事件不进来：搜到之后的动作是「打开」，而它们打不开——
     * 列一个点了没反应的结果，比不列出来糟。
     */
    public static List<Entry> fromTables(List<TableInfo> tables) {
        List<Entry> out = new ArrayList<>(tables.size());
        tables.stream().filter(t -> t.kind().hasRows())
                .forEach(t -> out.add(Entry.ofTable(t)));
        return out;
    }

    public static List<Entry> fromColumns(List<ColumnRef> columns) {
        List<Entry> out = new ArrayList<>(columns.size());
        columns.forEach(c -> out.add(Entry.ofColumn(c)));
        return out;
    }

    /**
     * 在索引里找。
     *
     * @param needle 用户敲的片段；空白时返回空表——几千个对象一次全铺出来，
     *               弹窗只会变成一堵墙
     */
    public static List<Hit> search(List<Entry> entries, String needle, int limit) {
        List<Hit> out = new ArrayList<>();
        if (needle == null || needle.isBlank() || limit <= 0) {
            return out;
        }
        String lower = needle.strip().toLowerCase(Locale.ROOT);
        String loose = stripSeparators(lower);

        for (Entry e : entries) {
            int rank = rankOf(e, lower, loose);
            if (rank >= 0) {
                out.add(new Hit(e, rank, rank == COMMENT));
            }
        }
        out.sort(ranking());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    static int rankOf(Entry e, String lowerNeedle, String looseNeedle) {
        String name = e.name() == null ? "" : e.name().toLowerCase(Locale.ROOT);
        if (name.equals(lowerNeedle)) {
            return EXACT;
        }
        if (name.startsWith(lowerNeedle)) {
            return PREFIX;
        }
        if (name.contains(lowerNeedle)) {
            return CONTAINS;
        }
        // 少打一个下划线不该就找不到：orderitem → order_item
        if (!looseNeedle.isEmpty() && stripSeparators(name).contains(looseNeedle)) {
            return LOOSE;
        }
        // 注释是中文库里唯一能按中文搜到的东西
        String comment = e.comment() == null ? "" : e.comment().toLowerCase(Locale.ROOT);
        if (!comment.isEmpty() && comment.contains(lowerNeedle)) {
            return COMMENT;
        }
        return -1;
    }

    /**
     * 名次比较器。
     *
     * <p>单独暴露：跨连接搜索时要把各连接各自排好的结果并成一份总榜，
     * 那时候必须用同一把尺子。
     */
    public static Comparator<Hit> ranking() {
        return Comparator
                .comparingInt(Hit::rank)
                .thenComparingInt((Hit h) -> h.entry().kind() == ObjectSearch.Kind.COLUMN ? 1 : 0)
                .thenComparingInt(h -> h.entry().name().length())
                .thenComparing(h -> h.entry().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(h -> nz(h.entry().schema()));
    }

    /**
     * 去掉下划线、连字符和空格。
     *
     * <p>只去分隔符，不做更花哨的模糊匹配（比如首字母缩写）。实测过：
     * 拿 {@code oi} 去做子序列匹配，第一条捞出来的是 {@code ado_point}——
     * 噪音比信号多。宁可少认几种写法，也不要让第一条结果是个不相干的东西。
     */
    static String stripSeparators(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '_' && c != '-' && c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
