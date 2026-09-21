package com.plainly.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自动别名。
 *
 * <p>这里的错代价不对等：
 * <ul>
 *   <li><b>别名起得不好看</b>——用户改两个字母，损失一秒；</li>
 *   <li><b>别名和别人撞了</b>——{@code FROM user_role ur JOIN user_relation ur} 语法上
 *       未必报错，查出来的却是另一张表的数据。这种错要等到看见结果不对才会被发现。</li>
 * </ul>
 * 所以下面「躲开重名」那几条比「拼得漂亮」更要紧。
 */
@DisplayName("SQL · 自动别名")
class SqlAliasesTest {

    @Test
    @DisplayName("取每一段的首字母")
    void initialsOfEachSegment() {
        assertEquals("toi", SqlAliases.suggest("t_order_item"));
        assertEquals("u", SqlAliases.suggest("users"));
        assertEquals("sur", SqlAliases.suggest("sys_user_role"));
    }

    @Test
    @DisplayName("驼峰也算分段")
    void camelCaseCountsAsSegments() {
        // 只认下划线的话，OrderItem 会缩成一个 o，和 Order 撞上
        assertEquals("oi", SqlAliases.suggest("OrderItem"));
        assertEquals("tui", SqlAliases.suggest("TblUserInfo"));
    }

    @Test
    @DisplayName("带库名限定时只看表名那一段")
    void ignoresSchemaPrefix() {
        assertEquals("to", SqlAliases.suggest("shop.t_order"));
    }

    @Test
    @DisplayName("和已有的名字撞了就往后排号")
    void avoidsTakenNames() {
        assertEquals("ur2", SqlAliases.suggest("user_relation", List.of("ur")));
        assertEquals("ur3", SqlAliases.suggest("user_relation", List.of("ur", "ur2")));
    }

    @Test
    @DisplayName("躲开的不只是别名，还有没起别名的表名本身")
    void avoidsBareTableNames() {
        // FROM ur JOIN user_role ur —— 后面那个 ur 会把前面那张表遮掉
        assertEquals("ur2", SqlAliases.suggest("user_role", List.of("ur")));
        assertEquals("u2", SqlAliases.suggest("users", List.of("u")));
    }

    @Test
    @DisplayName("比对不分大小写")
    void matchingIsCaseInsensitive() {
        assertEquals("ur2", SqlAliases.suggest("user_role", List.of("UR")));
    }

    @Test
    @DisplayName("躲开短关键字")
    void avoidsShortKeywords() {
        // app_stats 的首字母正好是 as，而 JOIN t ON as.x = ... 是语法错误
        assertEquals("as2", SqlAliases.suggest("app_stats"));
        assertEquals("on2", SqlAliases.suggest("order_note"));
        assertEquals("in2", SqlAliases.suggest("item_no"));
    }

    @Test
    @DisplayName("首字母是数字时补一个字母开头")
    void neverStartsWithDigit() {
        String alias = SqlAliases.suggest("2024_stats");
        assertNotNull(alias);
        assertTrue(Character.isLetter(alias.charAt(0)),
                "别名不能以数字开头，实际是 " + alias);
    }

    @Test
    @DisplayName("一个字母数字都没有的名字起不出别名")
    void givesUpWhenThereIsNothingToWorkWith() {
        assertNull(SqlAliases.suggest("___"));
        assertNull(SqlAliases.suggest(""));
        assertNull(SqlAliases.suggest(null));
    }

    @Test
    @DisplayName("同一张表连起两次，两个别名不会撞")
    void selfJoinGetsDistinctAliases() {
        String first = SqlAliases.suggest("t_employee", List.of());
        String second = SqlAliases.suggest("t_employee", List.of(first));
        assertNotEquals(first, second);
    }
}
