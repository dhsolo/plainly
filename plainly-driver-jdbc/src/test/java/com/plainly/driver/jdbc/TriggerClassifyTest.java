package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.TriggerEvent;
import com.plainly.driver.meta.DbObjects.TriggerInfo;
import com.plainly.driver.meta.DbObjects.TriggerTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 触发器的分类识别与按分类生成语句。
 *
 * <p>分类错了不会报错——它只会让触发器出现在错误的分组下面，用户在
 * 「AFTER UPDATE」里找不到自己那个 AFTER UPDATE 触发器，然后以为工具丢了东西。
 */
@DisplayName("触发器 · 分类与建表语句")
class TriggerClassifyTest {

    @Test
    @DisplayName("元数据给了时机和事件就直接用")
    void fromMetadata() {
        TriggerInfo info = new TriggerInfo("t", "BEFORE", "INSERT", "BEGIN END");
        assertEquals(TriggerTiming.BEFORE, info.timingOf());
        assertEquals(TriggerEvent.INSERT, info.eventOf());
        assertEquals("BEFORE INSERT", info.category());
    }

    @Test
    @DisplayName("元数据没给（SQLite）就从整条语句的声明部分认")
    void fromStatement() {
        TriggerInfo info = new TriggerInfo("t", "", "",
                "CREATE TRIGGER t BEFORE DELETE ON orders FOR EACH ROW"
                        + " BEGIN INSERT INTO log VALUES (OLD.id); END");
        assertEquals("BEFORE DELETE", info.category());
    }

    @Test
    @DisplayName("只认声明，不认触发器体——体里的 INSERT 不能把它变成 INSERT 触发器")
    void bodyDoesNotConfuseTheEvent() {
        TriggerInfo info = new TriggerInfo("t", "", "",
                "CREATE TRIGGER t AFTER UPDATE ON orders FOR EACH ROW"
                        + " BEGIN INSERT INTO log VALUES (NEW.id); END");
        assertEquals("AFTER UPDATE", info.category(),
                "触发器体本来就是去改别的表的，整段拿去匹配必然认错");
    }

    @Test
    @DisplayName("认不出来就是「未分类」，不硬塞进某一类")
    void unknownStaysUnknown() {
        TriggerInfo info = new TriggerInfo("t", null, null, "谁知道这是什么");
        assertNull(info.timingOf());
        assertNull(info.eventOf());
        assertEquals("未分类", info.category());
    }

    @Test
    @DisplayName("六种组合各自生成对应的 MySQL 语句")
    void mysqlCoversAllSix() {
        SqlDialect mysql = Dialects.forType(DbType.MYSQL);
        for (TriggerTiming timing : TriggerTiming.values()) {
            for (TriggerEvent event : TriggerEvent.values()) {
                String ddl = mysql.createTriggerDdl("shop", "orders", "trg", timing, event,
                        mysql.triggerBodyTemplate(event));
                assertTrue(ddl.contains(timing.name() + " " + event.name()),
                        "语句里要写明分类：" + ddl);
                assertTrue(ddl.contains("FOR EACH ROW"), ddl);
            }
        }
    }

    @Test
    @DisplayName("提示随事件变：INSERT 上没有 OLD，DELETE 上没有 NEW")
    void rowReferenceHintFollowsEvent() {
        SqlDialect mysql = Dialects.forType(DbType.MYSQL);
        assertTrue(mysql.triggerBodyTemplate(TriggerEvent.INSERT).contains("NEW"));
        assertTrue(mysql.triggerBodyTemplate(TriggerEvent.DELETE).contains("OLD"));
        // 写错了不会当场报错，要等触发器真被触发时才炸，所以模板里就得说清楚
        assertTrue(mysql.triggerBodyTemplate(TriggerEvent.UPDATE).contains("NEW"));
        assertTrue(mysql.triggerBodyTemplate(TriggerEvent.UPDATE).contains("OLD"));
    }

    @Test
    @DisplayName("PostgreSQL 连函数一起建，DELETE 上返回 OLD")
    void postgresBuildsFunctionToo() {
        SqlDialect pg = Dialects.forType(DbType.POSTGRESQL);
        String ddl = pg.createTriggerDdl("public", "orders", "trg",
                TriggerTiming.BEFORE, TriggerEvent.DELETE, "");
        assertTrue(ddl.contains("CREATE OR REPLACE FUNCTION"), "少了函数那条，执行必然报找不到函数");
        assertTrue(ddl.contains("RETURN OLD;"), "DELETE 触发器返回 NEW 等于把这一行放过去");
        assertTrue(ddl.contains("BEFORE DELETE ON"), ddl);
    }

    @Test
    @DisplayName("Oracle 用 CREATE OR REPLACE，不必先删")
    void oracleReplacesInPlace() {
        String ddl = Dialects.forType(DbType.ORACLE).createTriggerDdl("hr", "emp", "trg",
                TriggerTiming.AFTER, TriggerEvent.INSERT, "BEGIN NULL; END;");
        assertTrue(ddl.toUpperCase().startsWith("CREATE OR REPLACE"), ddl);
    }

    @Test
    @DisplayName("谁的元数据是整条语句，谁就只能整条改")
    void fullStatementDialects() {
        assertTrue(Dialects.forType(DbType.SQLITE).triggerActionIsFullStatement());
        assertTrue(!Dialects.forType(DbType.MYSQL).triggerActionIsFullStatement());
        assertTrue(!Dialects.forType(DbType.POSTGRESQL).triggerActionIsFullStatement());
    }

    @Test
    @DisplayName("H2 写不了触发器，生成的语句是 null 而不是一条跑不通的 SQL")
    void h2RefusesInsteadOfGeneratingBrokenSql() {
        SqlDialect h2 = Dialects.forType(DbType.H2);
        assertNull(h2.createTriggerDdl("PUBLIC", "T", "trg",
                TriggerTiming.AFTER, TriggerEvent.INSERT, "BEGIN END"));
        assertTrue(h2.triggerUnsupportedReason() != null);
    }
}
