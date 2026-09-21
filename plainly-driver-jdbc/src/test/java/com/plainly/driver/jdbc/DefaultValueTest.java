package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.jdbc.dialect.Dialects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 默认值的归一化。
 *
 * <p>这是结构同步在真实 MySQL 库上炸掉的那个原因。{@code ColumnInfo.defaultValue}
 * 的契约是「能直接拼进 DDL 的字面量」，但 MySQL 驱动给的是<b>原始值</b>：
 * 默认空串就返回空串，默认 {@code http://a/b.png} 就原样返回、不带引号。
 * 照原样拼出来是 {@code DEFAULT } 和 {@code DEFAULT http://a/b.png}，
 * 两条都是语法错误，而且要等用户点了「应用」才在数据库那边炸。
 */
@DisplayName("默认值 · 拼进 DDL 之前先归一成字面量")
class DefaultValueTest {

    private static final SqlDialect MYSQL = Dialects.forType(DbType.MYSQL);
    private static final SqlDialect H2 = Dialects.forType(DbType.H2);

    // ------------------------------------------------------------------ MySQL

    @Test
    @DisplayName("字符串默认值要加引号，否则拼出来是裸的 URL")
    void quotesStringDefaults() {
        assertEquals("'http://24.62.1.51:30141/image/system/transform.png'",
                MYSQL.normalizeDefault("http://24.62.1.51:30141/image/system/transform.png",
                        TypeCategory.STRING));
    }

    @Test
    @DisplayName("默认空串是 '' 两个字符，不是空白")
    void emptyStringDefaultBecomesQuotedEmpty() {
        assertEquals("''", MYSQL.normalizeDefault("", TypeCategory.STRING));
    }

    @Test
    @DisplayName("值里的单引号要双写，反斜杠要转义")
    void escapesQuotes() {
        assertEquals("'it''s'", MYSQL.normalizeDefault("it's", TypeCategory.STRING));
        assertEquals("'a\\\\b'", MYSQL.normalizeDefault("a\\b", TypeCategory.STRING));
    }

    @Test
    @DisplayName("只有一个空格的默认值，那个空格要留住")
    void keepsSignificantWhitespace() {
        assertEquals("' '", MYSQL.normalizeDefault(" ", TypeCategory.STRING));
    }

    @Test
    @DisplayName("CURRENT_TIMESTAMP 是表达式，加了引号就从「取当前时间」变成「存这串字」")
    void doesNotQuoteExpressions() {
        for (String expr : new String[]{"CURRENT_TIMESTAMP", "current_timestamp",
                "CURRENT_TIMESTAMP(6)", "NOW()", "CURRENT_DATE", "LOCALTIMESTAMP", "UUID()"}) {
            assertEquals(expr.trim(), MYSQL.normalizeDefault(expr, TypeCategory.TEMPORAL),
                    expr + " 被当成字面量引起来了——那会安静地改掉表的语义");
        }
    }

    @Test
    @DisplayName("MySQL 8 的表达式默认值存成带括号的形式，也不能引")
    void doesNotQuoteParenthesisedExpression() {
        assertEquals("((`a` + 1))",
                MYSQL.normalizeDefault("((`a` + 1))", TypeCategory.STRING));
    }

    @Test
    @DisplayName("数值默认值不加引号；空白说明这一列其实没有默认值")
    void numericDefaults() {
        assertEquals("0", MYSQL.normalizeDefault("0", TypeCategory.INTEGER));
        assertEquals("1.50", MYSQL.normalizeDefault("1.50", TypeCategory.EXACT_NUMERIC));
        assertNull(MYSQL.normalizeDefault("", TypeCategory.INTEGER));
    }

    @Test
    @DisplayName("没有默认值就是 null，不是空串")
    void nullStaysNull() {
        assertNull(MYSQL.normalizeDefault(null, TypeCategory.STRING));
    }

    // ------------------------------------------------------------------ 别家

    @Test
    @DisplayName("H2 / PostgreSQL 给的本来就是字面量，原样放过")
    void othersPassThrough() {
        assertEquals("'CNY'", H2.normalizeDefault("'CNY'", TypeCategory.STRING));
        assertEquals("'CNY'::character varying",
                Dialects.forType(DbType.POSTGRESQL)
                        .normalizeDefault("'CNY'::character varying", TypeCategory.STRING));
    }

    // ------------------------------------------------------------------ 拼出来的语句

    @Test
    @DisplayName("归一之后拼出来的列定义是完整的")
    void columnDefinitionIsWellFormed() {
        ColumnDraft draft = ColumnDraft.added("imgUrl");
        draft.setNativeType("VARCHAR");
        draft.setPrecision(256);
        draft.setNullable(true);
        draft.setDefaultValue(MYSQL.normalizeDefault("http://a/b.png", TypeCategory.STRING));
        assertEquals("`imgUrl` VARCHAR(256) NULL DEFAULT 'http://a/b.png'",
                MYSQL.columnDefinition(draft));
    }

    @Test
    @DisplayName("默认值为空白时干脆不写 DEFAULT——写出来是条跑不通的语句")
    void blankDefaultIsOmitted() {
        ColumnDraft draft = ColumnDraft.added("createTime");
        draft.setNativeType("VARCHAR");
        draft.setPrecision(64);
        draft.setNullable(true);
        draft.setDefaultValue("");
        String sql = MYSQL.columnDefinition(draft);
        assertFalse(sql.contains("DEFAULT"), "实得：" + sql);
        assertEquals("`createTime` VARCHAR(64) NULL", sql);
    }

    @Test
    @DisplayName("默认空串（已归一成 ''）仍然要写出来")
    void quotedEmptyDefaultSurvives() {
        ColumnDraft draft = ColumnDraft.added("createTime");
        draft.setNativeType("VARCHAR");
        draft.setPrecision(64);
        draft.setNullable(true);
        draft.setDefaultValue("''");
        assertTrue(MYSQL.columnDefinition(draft).endsWith("DEFAULT ''"),
                MYSQL.columnDefinition(draft));
    }
}
