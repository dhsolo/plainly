package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 报错里点名的对象和我们操作的对象对不上时，要把方向指出去。
 *
 * <p>真实来源：一张表上挂着 AFTER UPDATE 触发器，触发器里
 * {@code UPDATE ele_point t SET ...} 引用的表已经不存在了。
 * MySQL 报的是触发器语句里的别名（{@code nj.t}），附上的语句原文却是我们发的那条 UPDATE，
 * 看起来就像工具生成了一条错误的 SQL——用户会去查一个根本不存在的问题。
 */
class ErrorHintTest {

    private static SQLException tableMissing(String message) {
        return new SQLException(message, "42S02", 1146);
    }

    @Test
    @DisplayName("报的表名与操作的表不同：给出触发器/视图的方向")
    void hintsWhenNamesDiffer() {
        String hint = JdbcConnection.mismatchHint(
                tableMissing("Table 'nj.t' doesn't exist"),
                "UPDATE `nj`.`ele_dev` SET `name` = ? WHERE `id` = ?");
        assertTrue(hint != null && hint.contains("nj.t"), "要点出报错里的名字：" + hint);
        assertTrue(hint.contains("ele_dev"), "要点出本次操作的表：" + hint);
        assertTrue(hint.contains("触发器"), "要指出可能的来源：" + hint);
    }

    @Test
    @DisplayName("报的就是操作的那张表：不添乱")
    void noHintWhenSameTable() {
        assertNull(JdbcConnection.mismatchHint(
                tableMissing("Table 'nj.ele_dev' doesn't exist"),
                "UPDATE `nj`.`ele_dev` SET `name` = ? WHERE `id` = ?"));
        assertNull(JdbcConnection.mismatchHint(
                tableMissing("Table 'ele_dev' doesn't exist"),
                "UPDATE `ele_dev` SET `name` = ?"));
    }

    @Test
    @DisplayName("不是「表不存在」这类错误：不添乱")
    void noHintForOtherErrors() {
        assertNull(JdbcConnection.mismatchHint(
                new SQLException("Duplicate entry '1' for key 'PRIMARY'", "23000", 1062),
                "UPDATE `nj`.`ele_dev` SET `name` = ?"));
    }

    @Test
    @DisplayName("认得出三种写法的目标表")
    void parsesTargetTable() {
        assertTrue("ele_dev".equals(JdbcConnection.targetTable(
                "UPDATE `nj`.`ele_dev` SET `name` = ?")));
        assertTrue("orders".equals(JdbcConnection.targetTable(
                "insert into \"public\".\"orders\" (a) values (?)")));
        assertTrue("orders".equals(JdbcConnection.targetTable(
                "DELETE FROM orders WHERE id = ?")));
        assertNull(JdbcConnection.targetTable("SELECT * FROM orders"));
        assertNull(JdbcConnection.targetTable(null));
    }

    @Test
    @DisplayName("消息里没有引号名字时不猜")
    void noHintWithoutQuotedName() {
        assertNull(JdbcConnection.mismatchHint(
                tableMissing("something went wrong"),
                "UPDATE `nj`.`ele_dev` SET `name` = ?"));
    }
}
