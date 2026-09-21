package com.plainly.app.view.sql;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.function.IntFunction;

/**
 * 行号栏。
 *
 * <h2>为什么要自己设字体</h2>
 * {@link LineNumberFactory} 自己设的是 {@code Monospaced 13}，本来是等宽的。
 * 坏事的是我们原来在 CSS 里那两行：
 *
 * <pre>
 * .code-area .lineno {
 *     -fx-font-family: "Cascadia Mono", Consolas, monospace;   &#47;* 解析不出来 *&#47;
 *     -fx-font-size: 11px;
 * }
 * </pre>
 *
 * <p>那个族名列表在这里<b>一个都没解析上</b>，JavaFX 于是退回 <b>System</b>——
 * 一个比例字体——而字号 11px 照样生效。实测：改动前行号用的是
 * {@code System Regular 11.0}；去掉这两行则是 {@code Monospaced 13.0}。
 *
 * <p><b>整件事没有任何报错。</b>字体族写错了不会抛异常，只会安静地换一个，
 * 而换上来的那个恰好不等宽。
 *
 * <h2>比例字体在行号上会露馅</h2>
 * 行号是右对齐补空格的（40 行的文档里第 9 行是 {@code " 9"}）。等宽字体下空格和数字一样宽，
 * 各行的行号格因此一样宽；比例字体下空格只有数字的一半——实测 3.25 对 6.45——
 * 于是<b>个位数的那些行，行号格窄 3.2 像素，那一行的正文跟着往左挪 3.2 像素</b>。
 *
 * <p>等宽正文下这个错位一眼就看得出来。它还有个很迷惑人的表现：在第 9 行上面回车一下，
 * 那一行变成第 10 行，就「自己好了」——因为两位数不再需要补空格。
 *
 * <p>所以这里自己把字体定死，并且<b>只从已经装着的族里挑</b>，
 * 同时保住原本想要的 11px 字号——CSS 那边只留颜色和内边距。
 */
public final class LineNumbers {

    private LineNumbers() {
    }

    /**
     * 行号用的字体族。
     *
     * <p>逐个挑已经装着的，挑不到就用 {@code Monospaced}——那是 JavaFX 的逻辑字体，
     * 一定存在、也一定等宽。不写死某一个具体族名的理由就在上面：
     * 写一个不存在的族名不会报错，只会安静地换成比例字体。
     */
    private static final String FAMILY = pickMonospaced();

    /** 和 {@code .code-area .lineno} 里写的字号保持一致；那边只剩颜色和内边距还有效。 */
    private static final double SIZE = 11;

    public static IntFunction<Node> factory(CodeArea area) {
        IntFunction<Node> base = LineNumberFactory.get(area);
        Font font = Font.font(FAMILY, SIZE);
        return index -> {
            Node node = base.apply(index);
            if (node instanceof Label label) {
                label.setFont(font);
            }
            return node;
        };
    }

    private static String pickMonospaced() {
        for (String family : new String[] {"Cascadia Mono", "Consolas", "DejaVu Sans Mono"}) {
            if (Font.getFamilies().contains(family)) {
                return family;
            }
        }
        return "Monospaced";
    }
}
