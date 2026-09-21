package com.plainly.driver.redis;

import java.util.regex.Pattern;

/**
 * Redis 的键名通配符。
 *
 * <h2>为什么要在客户端也实现一份</h2>
 * 服务端的 {@code SCAN MATCH} 只收<b>一个</b>模式，而这里常常有两个条件要同时满足：
 * 键空间的前缀（{@code user*}）和用户搜的那个模式（{@code *1001*}）。
 * 两个 glob 没法 AND 成一个，所以做法是：把更能筛掉东西的那个发给服务端，
 * 另一个在本地再过一遍。本地这一遍就靠这个类。
 *
 * <h2>规则跟着 Redis 走</h2>
 * {@code *} 任意长度，{@code ?} 一个字符，{@code [abc]} / {@code [a-c]} / {@code [^a]}
 * 字符集，{@code \} 转义。<b>不是</b>正则：{@code .} 和 {@code +} 在这里就是普通字符，
 * 按正则去理解会让 {@code user.1} 匹配上 {@code userX1}。
 *
 * <p>做法是转成正则再匹配，转的时候把每一个字面字符都 {@code Pattern.quote} 掉——
 * 这是唯一能保证「用户打的点就是点」的写法。
 */
final class RedisGlob {

    private RedisGlob() {
    }

    /** 空模式和 {@code *} 都表示「全都要」。 */
    static boolean matchesEverything(String glob) {
        return glob == null || glob.isEmpty() || glob.equals("*");
    }

    static boolean matches(String glob, String value) {
        if (matchesEverything(glob)) {
            return true;
        }
        return toRegex(glob).matcher(value).matches();
    }

    /** 编译好的模式，循环里用——几万个键逐个编译一次正则太浪费。 */
    static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    i++;
                    break;
                case '?':
                    regex.append('.');
                    i++;
                    break;
                case '[':
                    i = appendCharClass(glob, i, regex);
                    break;
                case '\\':
                    // 转义：下一个字符按字面算。反斜杠在末尾时它自己就是字面量
                    if (i + 1 < glob.length()) {
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                        i += 2;
                    } else {
                        regex.append(Pattern.quote("\\"));
                        i++;
                    }
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(c)));
                    i++;
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * 字符集 {@code [...]}。
     *
     * <p>没有收尾的 {@code ]} 时，把这个 {@code [} 当普通字符——Redis 就是这么处理的，
     * 而且用户键名里带方括号的情况远比写字符集常见。
     */
    private static int appendCharClass(String glob, int start, StringBuilder regex) {
        int close = findClose(glob, start);
        if (close < 0) {
            regex.append(Pattern.quote("["));
            return start + 1;
        }
        regex.append('[');
        int i = start + 1;
        if (i < close && (glob.charAt(i) == '^')) {
            regex.append('^');
            i++;
        }
        while (i < close) {
            char c = glob.charAt(i);
            if (c == '\\' && i + 1 < close) {
                regex.append(quoteInClass(glob.charAt(i + 1)));
                i += 2;
                continue;
            }
            // a-c 这种区间原样放进去，其余字符逐个转义
            if (c == '-' && i > start + 1 && i + 1 < close) {
                regex.append('-');
                i++;
                continue;
            }
            regex.append(quoteInClass(c));
            i++;
        }
        regex.append(']');
        return close + 1;
    }

    /** 字符集里面 {@code Pattern.quote} 不适用（它产生的是 \Q...\E），只能逐个转义。 */
    private static String quoteInClass(char c) {
        if ("\\]^-[&".indexOf(c) >= 0) {
            return "\\" + c;
        }
        return String.valueOf(c);
    }

    private static int findClose(String glob, int start) {
        for (int i = start + 1; i < glob.length(); i++) {
            if (glob.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (glob.charAt(i) == ']' && i > start + 1) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把用户在搜索框里打的东西变成一个 glob。
     *
     * <h2>为什么要有这一步</h2>
     * 大多数时候用户想的是「键名里有 1001 的」，而不是「键名正好是 1001」。
     * 直接把输入当模式用，搜 {@code 1001} 会一条都搜不到——看着像库里没有，
     * 其实是没写通配符。
     *
     * <p>所以：<b>输入里带了通配符就照原样用</b>（说明用户知道自己在写模式），
     * <b>没带就两边加星号</b>。判据是用户自己的输入，不是猜的。
     * 界面上会把变换后的模式显示出来，不让它成为一件暗箱里的事。
     */
    static String fromSearchText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        boolean hasWildcard = trimmed.indexOf('*') >= 0
                || trimmed.indexOf('?') >= 0
                || trimmed.indexOf('[') >= 0;
        return hasWildcard ? trimmed : "*" + trimmed + "*";
    }
}
