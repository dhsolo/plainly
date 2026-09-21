package com.plainly.core.sql;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 给表名起一个短别名。
 *
 * <h2>为什么按「每一段的首字母」来</h2>
 * 别名的用处是后面敲 {@code 别名.} 补字段，所以它要短、要打得快、还要认得出是哪张表。
 * 取每一段的首字母（{@code t_order_item → toi}）三样都占：长度跟着表名的段数走，
 * 一般是两三个字母；而且是<b>可预测</b>的——用户不用回头看一眼别名叫什么，
 * 心里默念一遍表名就知道。
 *
 * <p>试过的另外两种都更糟：整表名缩写（{@code order_item → orditm}）打起来并不比
 * 表名省事；顺序编号（{@code t1 t2 t3}）短是短，可三张表一多，
 * {@code t2.} 到底是哪张就得往上翻。
 *
 * <h2>重名必须躲开，而且不能只躲开别名</h2>
 * 同一条 SQL 里 {@code user_role} 和 {@code user_relation} 的首字母都是 {@code ur}。
 * 撞上了就在后面接数字。要躲开的不只是已有的别名，还有<b>没起别名的表名本身</b>——
 * {@code FROM ur JOIN user_role ur} 里那个 {@code ur} 会把前面那张表遮掉，
 * 而这种错数据库不一定报，只会让查询悄悄查错对象。
 *
 * <p>另外躲开短关键字：{@code as}、{@code in}、{@code on} 这些真的会被生成出来
 * （{@code app_stats → as}），而 {@code JOIN t ON as.x = ...} 是语法错误。
 */
public final class SqlAliases {

    /**
     * 不能拿来当别名的短词。
     *
     * <p>只收<b>三个字母及以内</b>的：别名是首字母拼出来的，再长的关键字生成不出来，
     * 列在这里只是噪音（{@code SELECT}、{@code WHERE} 四个字母起步，压根轮不到）。
     *
     * <h2>收的标准：在别名的位置上真的会解析不下去</h2>
     * 别名只出现在两个地方：{@code FROM t <别名>} 和 {@code <别名>.列}。所以要挡的是
     * 两类——一类在表名后面另有含义（{@code as} 后面还等着一个别名、{@code on} 会被
     * 当成连接条件、{@code for} 是 FOR UPDATE、{@code use} 是 MySQL 的索引提示），
     * 一类在主流数据库里根本不许当标识符（{@code in is or and not all any asc key int}）。
     *
     * <h2>刻意<b>没</b>收的</h2>
     * {@code to}、{@code at}、{@code of}、{@code day}、{@code row}、{@code sum} 这些
     * 虽然也是 SQL 词，但在别名的位置上完全合法。收进来的代价很具体：
     * {@code t_order} 的首字母正好是 {@code to}，收了它这张表就永远得叫 {@code to2}——
     * 而 {@code t_order} 是最常见的表名之一。挡一个不存在的问题，换来天天看见的丑东西，
     * 不划算。
     */
    private static final Set<String> RESERVED = Set.of(
            "as", "by", "if", "in", "is", "on", "or",
            "add", "all", "and", "any", "asc", "for", "int", "key", "not", "set", "use");

    private SqlAliases() {
    }

    /**
     * 给一张表起个别名。
     *
     * @param table 表名，可以带库名限定（{@code shop.t_order}），只取最后一段
     * @param taken 这个位置上已经被占掉的名字：已有的别名，以及没起别名的表名本身。
     *              大小写不敏感地比对——SQL 的标识符在多数库上就是不敏感的
     * @return 别名；表名里一个字母数字都没有时返回 null
     */
    public static String suggest(String table, Collection<String> taken) {
        String base = initials(simpleName(table));
        if (base.isEmpty()) {
            return null;
        }
        Set<String> used = new HashSet<>(RESERVED);
        if (taken != null) {
            for (String one : taken) {
                if (one != null && !one.isBlank()) {
                    used.add(simpleName(one).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!used.contains(base)) {
            return base;
        }
        // 从 2 开始：第一个不带数字，读起来才像个别名而不像编号
        for (int n = 2; n < 100; n++) {
            String candidate = base + n;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 同上，但这个位置上什么都没被占。 */
    public static String suggest(String table) {
        return suggest(table, List.of());
    }

    /**
     * 每一段的首字母。
     *
     * <p>分段看两样东西：下划线，以及小写到大写的转折——{@code OrderItem} 和
     * {@code order_item} 是同一件事的两种写法，只认下划线的话前者会变成一个 {@code o}。
     *
     * <p>首字母不是字母时（{@code 2024_stats}）在前面补一个 {@code t}：
     * 标识符不能以数字开头，生成一个用不了的别名比不生成更糟。
     */
    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        boolean atSegmentStart = true;
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                // 下划线、空格、引号……都算段的分界
                atSegmentStart = true;
                previous = c;
                continue;
            }
            boolean camelBoundary = Character.isUpperCase(c) && Character.isLowerCase(previous);
            if (atSegmentStart || camelBoundary) {
                out.append(Character.toLowerCase(c));
            }
            atSegmentStart = false;
            previous = c;
        }
        if (out.length() == 0) {
            return "";
        }
        if (!Character.isLetter(out.charAt(0))) {
            out.insert(0, 't');
        }
        return out.toString();
    }

    /** 去掉库名限定：{@code shop.t_order → t_order}。 */
    private static String simpleName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(dot + 1);
    }
}
