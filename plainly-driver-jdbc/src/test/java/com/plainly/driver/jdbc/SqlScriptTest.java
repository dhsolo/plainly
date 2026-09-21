package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.SqlScript;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脚本切分。
 *
 * <p>这些用例全是「按字面 split(&quot;;&quot;) 会切错」的地方——
 * 切错的后果不是报错，是把半截 SQL 发给数据库，报错信息还完全指不到真正的原因。
 */
class SqlScriptTest {

    @Test
    @DisplayName("按分号切成多条")
    void splitsOnSemicolon() {
        List<String> parts = SqlScript.split("SELECT 1; SELECT 2;\nSELECT 3");
        assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), parts);
    }

    @Test
    @DisplayName("末尾分号不产生空语句")
    void trailingSemicolonIsNotAStatement() {
        assertEquals(List.of("SELECT 1"), SqlScript.split("SELECT 1;\n\n"));
    }

    @Test
    @DisplayName("单条语句原样返回")
    void singleStatement() {
        assertEquals(List.of("SELECT 1"), SqlScript.split("  SELECT 1  "));
    }

    @Test
    @DisplayName("空白与纯注释不算语句")
    void blankAndCommentOnly() {
        assertTrue(SqlScript.split("").isEmpty());
        assertTrue(SqlScript.split("   \n  ").isEmpty());
        assertTrue(SqlScript.split("-- 只有注释\n").isEmpty());
        assertTrue(SqlScript.split("/* 只有注释 */;").isEmpty());
    }

    @Test
    @DisplayName("字符串里的分号不切")
    void semicolonInsideString() {
        List<String> parts = SqlScript.split("SELECT 'a;b' AS x; SELECT 2");
        assertEquals(List.of("SELECT 'a;b' AS x", "SELECT 2"), parts);
    }

    @Test
    @DisplayName("双写单引号是转义，不是字符串结束")
    void doubledQuoteIsEscape() {
        List<String> parts = SqlScript.split("SELECT 'it''s; ok'; SELECT 2");
        assertEquals(List.of("SELECT 'it''s; ok'", "SELECT 2"), parts);
    }

    @Test
    @DisplayName("反斜杠转义只在 MySQL 模式生效")
    void backslashEscapeIsDialectSpecific() {
        String mysql = "SELECT 'it\\'s; ok'; SELECT 2";
        assertEquals(List.of("SELECT 'it\\'s; ok'", "SELECT 2"), SqlScript.split(mysql, true));

        // PostgreSQL 默认反斜杠是普通字符：'C:\' 是一条完整的字符串，
        // 若当成转义就会把后面的内容一路读穿，整页只剩一条语句
        String pg = "SELECT 'C:\\'; SELECT 2";
        assertEquals(List.of("SELECT 'C:\\'", "SELECT 2"), SqlScript.split(pg, false));
    }

    @Test
    @DisplayName("标识符里的分号不切")
    void semicolonInsideIdentifier() {
        assertEquals(List.of("SELECT \"a;b\" FROM t", "SELECT 2"),
                SqlScript.split("SELECT \"a;b\" FROM t; SELECT 2"));
        assertEquals(List.of("SELECT `a;b` FROM t", "SELECT 2"),
                SqlScript.split("SELECT `a;b` FROM t; SELECT 2"));
        assertEquals(List.of("SELECT [a;b] FROM t", "SELECT 2"),
                SqlScript.split("SELECT [a;b] FROM t; SELECT 2"));
    }

    @Test
    @DisplayName("注释里的分号不切")
    void semicolonInsideComment() {
        assertEquals(List.of("SELECT 1 -- 这里有个分号 ;\nFROM t", "SELECT 2"),
                SqlScript.split("SELECT 1 -- 这里有个分号 ;\nFROM t; SELECT 2"));
        assertEquals(List.of("SELECT /* ; */ 1", "SELECT 2"),
                SqlScript.split("SELECT /* ; */ 1; SELECT 2"));
        assertEquals(List.of("SELECT 1 # ;\nFROM t", "SELECT 2"),
                SqlScript.split("SELECT 1 # ;\nFROM t; SELECT 2"));
    }

    @Test
    @DisplayName("美元引用的函数体整体保留")
    void dollarQuotedBodyStaysWhole() {
        String script = "CREATE FUNCTION f() RETURNS int AS $$\n"
                + "BEGIN\n  RETURN 1;\nEND;\n"
                + "$$ LANGUAGE plpgsql;\nSELECT f()";
        List<String> parts = SqlScript.split(script);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).startsWith("CREATE FUNCTION"));
        assertTrue(parts.get(0).endsWith("LANGUAGE plpgsql"));
        assertEquals("SELECT f()", parts.get(1));
    }

    @Test
    @DisplayName("带标签的美元引用同样整体保留")
    void taggedDollarQuote() {
        String script = "DO $body$ BEGIN RAISE NOTICE 'x;y'; END $body$;\nSELECT 1";
        List<String> parts = SqlScript.split(script);
        assertEquals(2, parts.size());
        assertEquals("SELECT 1", parts.get(1));
    }

    @Test
    @DisplayName("孤立的美元符号不当成引用")
    void loneDollarIsNotAQuote() {
        assertEquals(List.of("SELECT $1", "SELECT 2"), SqlScript.split("SELECT $1; SELECT 2"));
    }

    @Test
    @DisplayName("DELIMITER 换分隔符：触发器体不被拦腰截断")
    void delimiterDirectiveChangesSeparator() {
        String script = String.join("\n",
                "DROP TABLE IF EXISTS `t`;",
                "DELIMITER $$",
                "CREATE TRIGGER `tr` AFTER INSERT ON `t` FOR EACH ROW",
                "BEGIN",
                "  SET @x = 1;",
                "  SET @y = 2;",
                "END$$",
                "DELIMITER ;",
                "INSERT INTO `t` VALUES (1);");
        List<String> parts = SqlScript.split(script, true);
        assertEquals(3, parts.size());
        // DELIMITER 本身是客户端指令，不能当语句发给服务端
        assertTrue(parts.stream().noneMatch(p -> p.toUpperCase().startsWith("DELIMITER")));
        assertTrue(parts.get(1).startsWith("CREATE TRIGGER"));
        assertTrue(parts.get(1).endsWith("END"));
        assertTrue(parts.get(1).contains("SET @x = 1;"));
        assertEquals("INSERT INTO `t` VALUES (1)", parts.get(2));
    }

    @Test
    @DisplayName("mysqldump 的 /*!50003 */ 触发器段照样切得开")
    void mysqldumpTriggerSection() {
        String script = String.join("\n",
                "DELIMITER ;;",
                "/*!50003 DROP TRIGGER*//*!50032 IF EXISTS */ /*!50003 `a` */;;",
                "/*!50003 CREATE*/ /*!50003 TRIGGER `a` AFTER INSERT ON `t` FOR EACH ROW",
                "BEGIN",
                "  SET @x = 1;",
                "END */;;",
                "DELIMITER ;");
        List<String> parts = SqlScript.split(script, true);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).contains("DROP TRIGGER"));
        assertTrue(parts.get(1).contains("CREATE"));
        assertTrue(parts.get(1).endsWith("*/"));
    }

    @Test
    @DisplayName("可执行注释只在 MySQL 上算语句，别的库仍当注释丢掉")
    void executableCommentIsMysqlOnly() {
        String script = "/*!40101 SET @x = 1 */;";
        assertEquals(List.of("/*!40101 SET @x = 1 */"), SqlScript.split(script, true));
        assertTrue(SqlScript.split(script, false).isEmpty());
    }

    @Test
    @DisplayName("DELIMITER 只在行首认，别处出现的同名标识符不算指令")
    void delimiterOnlyAtLineStart() {
        assertEquals(List.of("SELECT delimiter FROM t", "SELECT 2"),
                SqlScript.split("SELECT delimiter FROM t; SELECT 2"));
        assertEquals(List.of("SELECT * FROM delimiters", "SELECT 2"),
                SqlScript.split("SELECT * FROM delimiters;\nSELECT 2"));
    }

    @Test
    @DisplayName("换过分隔符之后，分号回到普通字符")
    void semicolonIsPlainOnceDelimiterChanged() {
        List<String> parts = SqlScript.split("DELIMITER $$\nSELECT 1; SELECT 2$$", true);
        assertEquals(List.of("SELECT 1; SELECT 2"), parts);
    }

    @Test
    @DisplayName("引号没闭合也要终止，不能死循环")
    void unterminatedQuoteTerminates() {
        assertEquals(List.of("SELECT 'abc"), SqlScript.split("SELECT 'abc"));
        assertEquals(List.of("SELECT /* abc"), SqlScript.split("SELECT /* abc"));
    }
}
