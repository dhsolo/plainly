package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.jdbc.dialect.Dialects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 触发器的 DDL。
 *
 * <p>{@code CREATE TRIGGER} 是整个 SQL 里方言差异最大的语句之一，
 * {@code DROP TRIGGER} 也一样——挂在 schema 下还是挂在表上，各家不同。
 * 拼错了不会在生成时报错，要等用户点下「应用」才在数据库那边炸，
 * 所以这些差异必须在这一层就钉死。
 */
@DisplayName("触发器 DDL · 各家写法差异钉死在这一层")
class TriggerDdlTest {

    @Test
    @DisplayName("MySQL：触发器挂在 schema 下")
    void mysqlDrop() {
        SqlDialect d = Dialects.forType(DbType.MYSQL);
        assertEquals("DROP TRIGGER `shop`.`t_audit`",
                d.dropTriggerDdl("shop", "orders", "t_audit"));
    }

    @Test
    @DisplayName("达梦：字典视图那一列叫 TRIGGERING_TYPE，不是 Oracle 的 TRIGGER_TYPE")
    void dmTriggersQueryUsesItsOwnColumnName() {
        SqlDialect dm = Dialects.forType(DbType.DM);
        String sql = dm.triggersQuery("shenjian", "ss");

        // 这一条是在 DM 8.1.2 实例上撞出来的：继承 Oracle 那条语句发过去，
        // DM 回「无效的列名[TRIGGER_TYPE]」，而这个异常会被 listTriggers 吞掉——
        // 用户建完触发器回到列表看到空的，只能怀疑是创建失败了
        assertTrue(sql.contains("TRIGGERING_TYPE"), sql);
        assertFalse(sql.contains(" TRIGGER_TYPE"), "DM 上没有 TRIGGER_TYPE 这一列：" + sql);
        assertTrue(sql.contains("ALL_TRIGGERS"), sql);
        assertTrue(sql.contains("TABLE_OWNER = 'shenjian'"), sql);
        assertTrue(sql.contains("TABLE_NAME = 'ss'"), sql);

        // Oracle 那边保持原样，别改错了对象
        assertTrue(Dialects.forType(DbType.ORACLE).triggersQuery("s", "t")
                .contains("TRIGGER_TYPE"));
    }

    @Test
    @DisplayName("触发器名的作用域按家不同：查重名的范围也得跟着变")
    void triggerNameScopeDiffersByVendor() {
        // MySQL：名字在整个 database 里唯一。只查当前表会漏掉跨表撞名，
        // 用户拿到的就是 ERROR 1359 这句原始报错，而「它挂在哪张表上」一个字都没有
        String mysql = Dialects.forType(DbType.MYSQL).triggerNameConflictQuery("shop", "t_audit");
        assertNotNull(mysql);
        assertTrue(mysql.contains("TRIGGER_SCHEMA = 'shop'"), mysql);
        assertTrue(mysql.contains("TRIGGER_NAME = 't_audit'"), mysql);
        assertTrue(mysql.contains("EVENT_OBJECT_TABLE"), "要能说出它占在哪张表上：" + mysql);

        // Oracle / 达梦：模式内唯一，而且撞名是静默覆盖，这条查询更要紧
        for (DbType type : new DbType[]{DbType.ORACLE, DbType.DM}) {
            String sql = Dialects.forType(type).triggerNameConflictQuery("APP", "T_AUDIT");
            assertNotNull(sql, type + " 的触发器名是模式内唯一的");
            assertTrue(sql.contains("ALL_TRIGGERS"), sql);
            assertTrue(sql.contains("TABLE_NAME"), sql);
        }

        // PostgreSQL：名字挂在表上，两张表各有一个同名触发器完全合法。
        // 返回 null 表示「按表隔离」，调用方退回到只查当前表
        assertNull(Dialects.forType(DbType.POSTGRESQL)
                .triggerNameConflictQuery("public", "t_audit"),
                "PG 的触发器名是每表唯一的，不该按库去查重名");
    }

    @Test
    @DisplayName("达梦：读回来的是整条语句，但新建时不该让用户整条写")
    void dmReadsFullStatementButComposesBodyOnly() {
        SqlDialect dm = Dialects.forType(DbType.DM);

        // 这两个标志必须分开。合成一个的表现是：新建对话框里放的是把名字写死在
        // 里面的整条语句，上面那个「名称」输入框成了摆设——改了名字点创建，
        // 执行的仍是语句里那个旧名字，于是「新建的触发器没出现」，
        // 真相是又把同名那个覆盖了一遍。在 DM 实例上撞出来过
        assertTrue(dm.triggerActionIsFullStatement(), "读：TRIGGER_BODY 是整条语句");
        assertFalse(dm.composesFullStatement(), "写：DM 认本工具拼的 CREATE OR REPLACE");

        // 而 SQLite 两边都是整条语句，默认跟随，不受这次拆分影响
        SqlDialect sqlite = Dialects.forType(DbType.SQLITE);
        assertEquals(sqlite.triggerActionIsFullStatement(), sqlite.composesFullStatement());
    }

    @Test
    @DisplayName("达梦的 TRIGGER_BODY 是整条 CREATE 语句，改触发器不能再往外套一层")
    void dmTriggerBodyIsFullStatement() {
        // DM 读回来的是 CREATE OR REPLACE TRIGGER "模式"."名字" ... 开头的完整语句。
        // 当成触发器体再套一层 CREATE TRIGGER，发出去必然是语法错误
        assertTrue(Dialects.forType(DbType.DM).triggerActionIsFullStatement());
        // Oracle 存的只是 BEGIN...END 那一段，仍然要外面那圈声明
        assertFalse(Dialects.forType(DbType.ORACLE).triggerActionIsFullStatement());
    }

    @Test
    @DisplayName("PostgreSQL：触发器挂在表上，少了 ON 子句直接报错")
    void postgresDrop() {
        SqlDialect d = Dialects.forType(DbType.POSTGRESQL);
        assertEquals("DROP TRIGGER \"t_audit\" ON \"shop\".\"orders\"",
                d.dropTriggerDdl("shop", "orders", "t_audit"));
    }

    @Test
    @DisplayName("SQLite：只有一个库，不做 schema 限定")
    void sqliteDrop() {
        SqlDialect d = Dialects.forType(DbType.SQLITE);
        assertEquals("DROP TRIGGER \"t_audit\"",
                d.dropTriggerDdl("main", "orders", "t_audit"));
    }

    @Test
    @DisplayName("PostgreSQL 的模板必须带上那个函数——只给 CREATE TRIGGER 是跑不通的")
    void postgresTemplateIncludesFunction() {
        SqlDialect d = Dialects.forType(DbType.POSTGRESQL);
        String template = d.createTriggerTemplate("shop", "orders", "t_audit");
        assertTrue(template.contains("RETURNS trigger"),
                "PostgreSQL 的逻辑写在函数里，不能直接写在触发器里：" + template);
        assertTrue(template.contains("EXECUTE FUNCTION"), template);
        assertTrue(template.contains("LANGUAGE plpgsql"), template);
    }

    @Test
    @DisplayName("MySQL 的模板是 BEGIN...END，不带函数")
    void mysqlTemplate() {
        String template = Dialects.forType(DbType.MYSQL)
                .createTriggerTemplate("shop", "orders", "t_audit");
        assertTrue(template.contains("FOR EACH ROW"), template);
        assertTrue(template.contains("BEGIN"), template);
        assertFalse(template.contains("RETURNS trigger"), template);
    }

    @Test
    @DisplayName("H2 说不出 SQL 模板，因为它的触发器根本不是 SQL")
    void h2CannotWriteTriggersInSql() {
        SqlDialect d = Dialects.forType(DbType.H2);
        assertNull(d.createTriggerTemplate("PUBLIC", "ORDERS", "T_AUDIT"),
                "给一个能打字但保存必然失败的框，比直说做不到糟糕");
        String why = d.triggerUnsupportedReason();
        assertNotNull(why);
        assertTrue(why.contains("Java"), "要说清是为什么，不能只说「不支持」：" + why);
    }

    @Test
    @DisplayName("能写触发器的家不给拒绝理由")
    void othersHaveNoReason() {
        assertNull(Dialects.forType(DbType.MYSQL).triggerUnsupportedReason());
        assertNull(Dialects.forType(DbType.POSTGRESQL).triggerUnsupportedReason());
        assertNull(Dialects.forType(DbType.SQLITE).triggerUnsupportedReason());
    }

    @Test
    @DisplayName("H2 仍然删得掉——不能写不等于不能删")
    void h2CanStillDrop() {
        assertEquals("DROP TRIGGER \"PUBLIC\".\"T_AUDIT\"",
                Dialects.forType(DbType.H2).dropTriggerDdl("PUBLIC", "ORDERS", "T_AUDIT"));
    }
}
