package com.plainly.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 从建触发器语句里认名字。
 *
 * <p>这个结论会被用来<b>拦住</b>用户：语句里的名字和界面上填的对不上就不让提交。
 * 所以两种错的代价不对称——认错了会拦住正确的操作（很烦），
 * 认不出来只是不拦（回到原来的行为）。因此认不出时必须返回 null，不能猜。
 */
@DisplayName("触发器名 · 从语句里认出来")
class TriggerNamesTest {

    @Test
    @DisplayName("带模式限定的引号标识符：只要名字那一截")
    void qualifiedQuoted() {
        // 这一条就是达梦实例上读回来的真实形状
        assertEquals("ss_after_insert", TriggerNames.parse(
                "CREATE OR REPLACE TRIGGER \"shenjian\".\"ss_after_insert\""
                        + " AFTER INSERT ON \"shenjian\".\"ss\" FOR EACH ROW BEGIN NULL; END;"));
    }

    @Test
    @DisplayName("MySQL 的反引号")
    void backticks() {
        assertEquals("t_audit", TriggerNames.parse(
                "CREATE TRIGGER `shop`.`t_audit` BEFORE INSERT ON `shop`.`orders`"));
    }

    @Test
    @DisplayName("SQL Server 的方括号")
    void brackets() {
        assertEquals("t_audit", TriggerNames.parse(
                "CREATE TRIGGER [dbo].[t_audit] ON [dbo].[orders] AFTER INSERT"));
    }

    @Test
    @DisplayName("不带引号的裸标识符")
    void bare() {
        assertEquals("trg_x", TriggerNames.parse(
                "CREATE TRIGGER trg_x BEFORE UPDATE ON t FOR EACH ROW BEGIN END"));
        assertEquals("trg_x", TriggerNames.parse(
                "CREATE TRIGGER main.trg_x BEFORE UPDATE ON t"));
    }

    @Test
    @DisplayName("IF NOT EXISTS 要跳过——不跳就会把 IF 当成名字，然后拦住正确的操作")
    void skipsIfNotExists() {
        assertEquals("trg_x", TriggerNames.parse(
                "CREATE TRIGGER IF NOT EXISTS trg_x AFTER DELETE ON t BEGIN SELECT 1; END"));
        assertEquals("trg_x", TriggerNames.parse(
                "create trigger if not exists \"trg_x\" after delete on t"));
    }

    @Test
    @DisplayName("换行和多空格不影响")
    void whitespace() {
        assertEquals("trg_x", TriggerNames.parse(
                "CREATE OR REPLACE TRIGGER\n    trg_x\nBEFORE INSERT ON t"));
    }

    @Test
    @DisplayName("认不出来就返回 null，绝不猜——猜错了拦的是对的操作")
    void unrecognised() {
        assertNull(TriggerNames.parse(null));
        assertNull(TriggerNames.parse(""));
        assertNull(TriggerNames.parse("BEGIN NULL; END;"), "触发器体里没有 CREATE TRIGGER");
        assertNull(TriggerNames.parse("CREATE TRIGGER"), "只有关键字，后面什么都没有");
        assertNull(TriggerNames.parse("CREATE TRIGGER   "));
    }

    @Test
    @DisplayName("大小写不敏感地找关键字，但名字原样返回")
    void keepsOriginalCase() {
        assertEquals("Trg_Mixed", TriggerNames.parse(
                "create trigger Trg_Mixed before insert on t"));
    }


    // ---------------------------------------------- 会不会静默覆盖同名的那个

    /**
     * PostgreSQL 那段以 {@code CREATE OR REPLACE FUNCTION} 开头——
     * 但那个 OR REPLACE 管的是<b>函数</b>，挂触发器用的还是光秃秃的
     * {@code CREATE TRIGGER}，撞名一定被拒。
     *
     * <p>原来的判据是「整段以 CREATE OR REPLACE 开头」，在这里判成了 true，
     * 于是两件事同时坏掉：新建撞名时弹出一个做不到的「会覆盖，确定继续？」，
     * 用户点了继续只会撞回数据库的原始报错；改已有触发器时不发 DROP，
     * 接着报 already exists——改触发器整个走不通。
     */
    @Test
    @DisplayName("PostgreSQL 的建触发器脚本不算「会覆盖」")
    void postgresScriptDoesNotReplaceTrigger() {
        String pg = "CREATE OR REPLACE FUNCTION \"public\".\"t_fn\"() RETURNS trigger AS $$\n"
                + "BEGIN\n    RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql;\n\n"
                + "CREATE TRIGGER \"t\"\nAFTER INSERT ON \"public\".\"x\"\n"
                + "FOR EACH ROW EXECUTE FUNCTION \"public\".\"t_fn\"();";
        assertFalse(TriggerNames.replacesExisting(pg), pg);
    }

    @Test
    @DisplayName("Oracle / 达梦 的 CREATE OR REPLACE TRIGGER 算「会覆盖」")
    void oracleStyleReplaces() {
        assertTrue(TriggerNames.replacesExisting(
                "CREATE OR REPLACE TRIGGER \"S\".\"T\"\nAFTER INSERT ON \"S\".\"X\"\nBEGIN\nEND;"));
        // 中间多几个换行、空格也得认出来
        assertTrue(TriggerNames.replacesExisting("create   or\n  replace\tTRIGGER t AFTER INSERT"));
    }

    @Test
    @DisplayName("光秃秃的 CREATE TRIGGER 不算")
    void plainCreateDoesNotReplace() {
        assertFalse(TriggerNames.replacesExisting(
                "CREATE TRIGGER t AFTER INSERT ON x FOR EACH ROW BEGIN END"));
        assertFalse(TriggerNames.replacesExisting(null));
    }

    /**
     * 按词比对，不是 {@code contains}。
     *
     * <p>{@code CREATE OR REPLACE TRIGGERS_LOG} 建的不是触发器；用 contains
     * 会把它算进来，于是在一个根本不涉及触发器的语句上承诺「会覆盖同名触发器」。
     */
    @Test
    @DisplayName("TRIGGER 后面粘着别的字母不算")
    void wordBoundaryMatters() {
        assertFalse(TriggerNames.replacesExisting("CREATE OR REPLACE TRIGGERS_LOG AS SELECT 1"));
    }
}
