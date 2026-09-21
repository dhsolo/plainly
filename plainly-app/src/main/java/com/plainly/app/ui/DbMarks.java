package com.plainly.app.ui;

import com.plainly.driver.DbType;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

/**
 * 数据库种类的标记：一个带字母的小色块。
 *
 * <h2>为什么是字母色块，不是各家的 logo</h2>
 * 树上原来所有连接共用一个圆柱图标，六条连接摆在一起完全分不出哪条是 Redis
 * 哪条是 MySQL。要区分只有三条路：
 *
 * <ul>
 *   <li><b>画各家的 logo</b>——海豚、大象、那些形状在 15px 上根本认不出来，
 *       而且是别人的商标；</li>
 *   <li><b>在名字后面写上类型</b>——最准，但树的宽度只有两百多像素，
 *       名字本身常常是一串 IP，再挂一个「MySQL / MariaDB」直接挤没了；</li>
 *   <li><b>字母色块</b>——占的地方和原来那个图标一样大，颜色扫一眼就分开了，
 *       颜色认不准还有字母兜底。</li>
 * </ul>
 *
 * <p>所以选第三条。<b>颜色是辅助，字母才是判据</b>：色觉差异、显示器偏色、
 * 甚至截图压缩都可能让两个红色看着一样，字母不会。
 *
 * <p>颜色尽量贴着各家自己的品牌色（这样见过的人一眼就对上），但 Oracle 和 Redis
 * 都是红的——它们靠 {@code O} 和 {@code R} 分开，不靠颜色。
 *
 * <h2>字母不能重复，这条由测试守着</h2>
 * 加 MongoDB 的时候这条被破过一次：它也拿了 {@code M}，和 MySQL 撞了，
 * 只剩底色（青 vs 绿）在区分。而上面那句「颜色是辅助」正是在说这样不行——
 * 色觉差异、显示器偏色、截图压缩都可能让两个块看着一样，字母不会。
 *
 * <p>所以 {@code DbMarksTest} 会检查所有种类的字母两两不同。想加新库时才发现
 * 字母不够用，是好事——那说明该认真挑一个，而不是随手拿一个已经有人用的。
 */
public final class DbMarks {

    private DbMarks() {
    }

    /**
     * 标记上那个字。
     *
     * <p>一律一个字符。两个字符在 15px 的块里每个只剩五像素宽，糊成一团，
     * 还不如一个字看得清。达梦用汉字「达」——它没有广为人知的拉丁缩写，
     * 而 {@code D} 会和别的家撞；MongoDB 用「文」，理由同上（{@code M} 被 MySQL 占着）。
     *
     * <p><b>返回值必须两两不同</b>，见类注释。
     */
    public static String letter(DbType type) {
        switch (type) {
            case MYSQL:
                return "M";
            case POSTGRESQL:
                return "P";
            case ORACLE:
                return "O";
            case DM:
                return "达";
            case REDIS:
                return "R";
            case MONGODB:
                // 不能也用 M——MySQL 占着，而字母才是判据。
                // 用汉字还多一层好处：CJK 字形和拉丁字母<b>形状</b>就不一样，
                // 灰度打印、色觉差异下照样分得开，而 G（MonGo）只是又一个字母。
                // 「文」取自文档库，那正是它区别于这里每一家的地方
                return "文";
            case KINGBASE:
                // 「金」取自金仓。拉丁字母这边 K 还空着，但汉字和字母的字形差别更大，
                // 灰度打印和色觉受限时也分得开——和达梦用「达」是同一个理由
                return "金";
            case OCEANBASE:
                return "海";
            case GAUSSDB:
                return "G";
            case SQLITE:
                return "S";
            case H2:
                return "H";
            case SQLSERVER:
                return "Q";
            default:
                return "?";
        }
    }

    /** 底色。 */
    public static String color(DbType type) {
        switch (type) {
            case MYSQL:
                return "#00758f";
            case POSTGRESQL:
                return "#336791";
            case ORACLE:
                return "#b8412f";
            case DM:
                return "#d17d1b";
            case REDIS:
                return "#c6302b";
            case MONGODB:
                return "#4faa41";
            case KINGBASE:
                return "#9b2d5e";
            case OCEANBASE:
                return "#0f8b8d";
            case GAUSSDB:
                return "#a0522d";
            case SQLITE:
                return "#5a7a8a";
            case H2:
                return "#2f6f4f";
            case SQLSERVER:
                return "#6b4f9e";
            default:
                return Icons.MUTED;
        }
    }

    /**
     * 一个标记。
     *
     * @param dim 连接未打开时置灰。原来「连上没连上」是靠图标颜色区分的
     *            （青色 / 灰色），换成按种类上色之后那条线索就没了，
     *            所以用透明度接上——旁边虽然还有个「在线」角标，
     *            但一眼扫过树的时候看的是左边这一列，不是右边
     */
    public static StackPane mark(DbType type, double size, boolean dim) {
        Rectangle plate = new Rectangle(size, size);
        plate.setArcWidth(size * 0.45);
        plate.setArcHeight(size * 0.45);
        plate.setFill(Color.web(color(type)));

        /*
         * 用裸 Text 而不是 Label，两个理由，缺一条这个块就出毛病。
         *
         * <b>居中</b>：Label 的高度是整行的高度——含升部和降部的空档。M 这样的大写字母
         * 用不到降部那一截，StackPane 把「行盒」摆正，字就被下面那块空档顶得偏上。
         * 15px 的块上差零点几个像素也看得出来。VISUAL 边界量的是墨迹本身，居中的于是也是墨迹
         * （量过：Label 版偏上 0.22px，「达」偏下 0.98px；换过来之后都在 0.03px 以内，
         * 剩下的是像素对齐的取整，躲不掉也不该躲——取整换来的是字更清楚）。
         *
         * <b>颜色</b>：Label 里那个 LabeledText 带 {@code .text} 样式类，
         * 会被 plainly.css 里 {@code .tree-cell:selected .text} 命中——而那条规则染的强调色
         * #0f6f70 和 MySQL 块的 #00758f 几乎是同一个色，一选中字就没了，
         * 且 {@code setTextFill} 拦不住它。裸 Text 不带样式类，选择器够不着。
         */
        Text text = new Text(letter(type));
        text.setBoundsType(TextBoundsType.VISUAL);
        text.setFill(Color.WHITE);
        text.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, size * 0.62));
        // 汉字比拉丁字母占得满，字号收一点才不会顶到边
        if (letter(type).codePointAt(0) > 0x2E80) {
            text.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, size * 0.56));
        }

        StackPane pane = new StackPane(plate, text);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        // 0.45 试过，字在 15px 上就认不出来了——而「这是哪一家」比
        // 「连上没连上」更常被人看，后者右边还有个「在线」角标兜着
        pane.setOpacity(dim ? 0.62 : 1.0);
        pane.getStyleClass().add("db-mark");
        return pane;
    }
}
