package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.TypeNames;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构设计器那个类型下拉里，不能出现这一家<b>根本没有</b>的类型。
 *
 * <h2>为什么值得一条条钉死</h2>
 * 选到一个不存在的类型，后果分两种，第二种才是真麻烦：
 * <ul>
 *   <li><b>报错</b>——Oracle 上选 {@code BIGINT} 直接 {@code ORA-00902 无效数据类型}。
 *       难受，但看得见；</li>
 *   <li><b>不报错，但不是那个意思</b>——SQL Server 上<b>有</b> {@code TIMESTAMP} 这个词，
 *       可它是行版本戳（rowversion 的同义词），不是时间。建出来的表一切正常，
 *       只是那一列存的东西和用户以为的完全是两回事。</li>
 * </ul>
 *
 * <p>SQL Server 原来压根没有自己的清单，掉进了 H2 那一份里——上面两种它都占了。
 */
@DisplayName("类型清单 · 不出现这一家没有的类型")
class TypeCatalogTest {

    private static List<String> upper(DbType type) {
        return TypeNames.catalogFor(type).stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();
    }

    /** 清单里有没有某个类型。按类型名的头部比，{@code VARCHAR(255)} 也算 {@code VARCHAR}。 */
    private static boolean has(DbType type, String name) {
        String wanted = name.toUpperCase(Locale.ROOT);
        return upper(type).stream().anyMatch(t -> {
            int paren = t.indexOf('(');
            return (paren < 0 ? t : t.substring(0, paren)).equals(wanted);
        });
    }

    private static void mustNotHave(DbType type, String... names) {
        for (String name : names) {
            assertFalse(has(type, name),
                    type.name() + " 没有 " + name + " 这个类型，不该出现在下拉里："
                            + TypeNames.catalogFor(type));
        }
    }

    @Test
    @DisplayName("每一家的清单都不为空、无重复")
    void everyCatalogIsSane() {
        for (DbType type : DbType.values()) {
            if (type == DbType.REDIS) {
                continue;   // 键值库没有列，也就没有类型可选
            }
            List<String> catalog = TypeNames.catalogFor(type);
            assertFalse(catalog.isEmpty(), type + " 的类型清单是空的");
            assertEquals(catalog.size(), catalog.stream().distinct().count(),
                    type + " 的类型清单里有重复项：" + catalog);
        }
    }

    @Test
    @DisplayName("Oracle：没有 BIGINT / TEXT / BOOLEAN / DOUBLE，整数走 NUMBER")
    void oracle() {
        mustNotHave(DbType.ORACLE,
                "BIGINT", "INT", "INTEGER", "SMALLINT", "TINYINT",
                "TEXT", "BOOLEAN", "DOUBLE", "DATETIME", "JSON");
        assertTrue(has(DbType.ORACLE, "NUMBER"));
        assertTrue(has(DbType.ORACLE, "VARCHAR2"));
        // 新建表默认给的那个主键类型也必须是这一家真有的
        assertEquals("NUMBER(19)", TypeNames.defaultKeyType(DbType.ORACLE));
    }

    @Test
    @DisplayName("SQL Server：没有 DOUBLE / CLOB / BLOB / BOOLEAN / UUID，也不该给 TIMESTAMP")
    void sqlServer() {
        mustNotHave(DbType.SQLSERVER,
                "DOUBLE", "CLOB", "BLOB", "BOOLEAN", "UUID", "TEXT", "JSON",
                "NUMBER", "VARCHAR2");
        // TIMESTAMP 在 SQL Server 上是行版本戳，不是时间——它不报错，只是意思完全不同
        mustNotHave(DbType.SQLSERVER, "TIMESTAMP");
        assertTrue(has(DbType.SQLSERVER, "DATETIME2"), "时间该用 DATETIME2");
        assertTrue(has(DbType.SQLSERVER, "BIT"), "布尔该用 BIT");
        assertTrue(has(DbType.SQLSERVER, "UNIQUEIDENTIFIER"));
    }

    @Test
    @DisplayName("MySQL：不该混进 Oracle 那套名字")
    void mysql() {
        mustNotHave(DbType.MYSQL,
                "VARCHAR2", "NUMBER", "CLOB", "NCLOB", "UUID", "BOOLEAN", "BYTEA");
        assertTrue(has(DbType.MYSQL, "BIGINT"));
        assertTrue(has(DbType.MYSQL, "DATETIME"));
    }

    @Test
    @DisplayName("PostgreSQL：不该混进 MySQL / SQL Server 那套名字")
    void postgres() {
        mustNotHave(DbType.POSTGRESQL,
                "DATETIME", "NVARCHAR", "CLOB", "BLOB", "TINYINT", "MONEY",
                "VARCHAR2", "NUMBER", "UNIQUEIDENTIFIER");
        assertTrue(has(DbType.POSTGRESQL, "BYTEA"));
        assertTrue(has(DbType.POSTGRESQL, "UUID"));
    }

    @Test
    @DisplayName("SQLite：只有那五种存储类，别的都是自欺欺人")
    void sqlite() {
        // SQLite 什么类型名都收，但真正起作用的只有这几种亲和类型。
        // 摆一堆 DATETIME、BOOLEAN 出来，会让人以为它真的会按那个类型校验
        assertEquals(List.of("INTEGER", "TEXT", "REAL", "NUMERIC", "BLOB"),
                TypeNames.catalogFor(DbType.SQLITE));
        // 自增只认正好是 INTEGER 的主键，写成 BIGINT 会安静地失去自增
        assertEquals("INTEGER", TypeNames.defaultKeyType(DbType.SQLITE));
    }

    @Test
    @DisplayName("默认主键类型必须出现在这一家自己的清单里")
    void defaultKeyTypeIsInItsOwnCatalog() {
        for (DbType type : DbType.values()) {
            if (type == DbType.REDIS) {
                continue;
            }
            String key = TypeNames.defaultKeyType(type);
            assertTrue(has(type, key.split("\\(")[0]),
                    type + " 的默认主键类型 " + key + " 不在它自己的清单里："
                            + TypeNames.catalogFor(type));
        }
    }
}
