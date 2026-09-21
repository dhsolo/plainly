package com.plainly.core.ddl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 复制表生成的语句。
 *
 * <p>这件事失败起来有两种样子，第二种要命得多：
 * <ul>
 *   <li>语句跑不通——当场报错，用户看得见；</li>
 *   <li>语句跑通了但内容不对——数据串了列、索引没建上。表看着好好的，
 *       错误要到很久以后才被发现，而那时源表可能已经删了。</li>
 * </ul>
 */
@DisplayName("复制表 · 语句生成")
class TableCopyTest {

    private final SqlDialect mysql = Dialects.forType(DbType.MYSQL);

    private TableStructure orders() {
        List<ColumnInfo> columns = List.of(
                new ColumnInfo("id", "BIGINT", TypeCategory.INTEGER, 20, 0,
                        false, true, true, null, "主键", 1),
                new ColumnInfo("uid", "BIGINT", TypeCategory.INTEGER, 20, 0,
                        false, false, false, null, "下单人", 2),
                new ColumnInfo("amount", "DECIMAL", TypeCategory.EXACT_NUMERIC, 12, 2,
                        true, false, false, null, "金额", 3));
        List<IndexInfo> indexes = List.of(
                new IndexInfo("PRIMARY", List.of("id"), true, true),
                new IndexInfo("idx_orders_uid", List.of("uid"), false, false),
                new IndexInfo("uk_amount", List.of("amount"), true, false));
        return new TableStructure(
                new TableInfo("shop", "orders", ObjectKind.TABLE, "订单", 100),
                columns, indexes);
    }

    private TableCopy.Plan plan(TableCopy.Options options) {
        return TableCopy.build(mysql, "shop", orders(), options);
    }

    @Test
    @DisplayName("只复制结构时不产生 INSERT")
    void structureOnly() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", true, false));
        assertNull(p.insert(), "勾的是「仅结构」，多灌一遍数据就是把用户没要的东西塞给他");
        assertTrue(p.ddl().get(0).contains("orders_copy"));
        assertTrue(p.ddl().get(0).contains("PRIMARY KEY"), "主键要跟过去：" + p.ddl().get(0));
    }

    @Test
    @DisplayName("主键索引不再单独建一次——它已经在 CREATE TABLE 里了")
    void primaryIndexIsNotRepeated() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", true, false));
        assertEquals(3, p.ddl().size(), "一条建表 + 两条非主键索引");
        assertFalse(String.join(" ", p.ddl()).contains("PRIMARY`"),
                "再建一次主键索引会直接撞名报错");
    }

    @Test
    @DisplayName("不要索引时就只有建表那一条")
    void withoutIndexes() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", false, false));
        assertEquals(1, p.ddl().size());
    }

    @Test
    @DisplayName("索引名跟着表名改，避免在 schema 级唯一的库上撞名")
    void indexNamesAreRenamed() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", true, false));
        String all = String.join(" ", p.ddl());
        assertTrue(all.contains("idx_orders_copy_uid"),
                "名字里含原表名的，把那一段换掉：" + all);
        assertTrue(all.contains("orders_copy_uk_amount"),
                "不含原表名的加前缀：" + all);
        assertFalse(all.contains("`idx_orders_uid`"), "原名一个都不该留下");
    }

    @Test
    @DisplayName("唯一索引复制过去还是唯一索引")
    void uniquenessSurvives() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", true, false));
        String unique = p.ddl().stream().filter(s -> s.contains("uk_amount")).findFirst()
                .orElseThrow();
        assertTrue(unique.contains("UNIQUE"),
                "丢了 UNIQUE 不会报错，只是这张表以后能塞进重复值：" + unique);
    }

    @Test
    @DisplayName("灌数据时列名写全，不用 SELECT *")
    void insertListsColumns() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop", "orders_copy", true, true));
        String insert = p.insert();
        assertFalse(insert.contains("*"),
                "星号按位置对齐，列序一不同就会安静地串列：" + insert);
        assertTrue(insert.contains("(`id`, `uid`, `amount`)"), insert);
        assertTrue(insert.contains("SELECT `id`, `uid`, `amount`"), insert);
        assertTrue(insert.contains("FROM `shop`.`orders`"), insert);
    }

    @Test
    @DisplayName("复制到另一个库：建在目标库，读的仍是源库")
    void crossSchema() {
        TableCopy.Plan p = plan(new TableCopy.Options("shop_bak", "orders", true, true));
        assertTrue(p.ddl().get(0).contains("`shop_bak`.`orders`"), p.ddl().get(0));
        assertTrue(p.insert().contains("INTO `shop_bak`.`orders`"), p.insert());
        assertTrue(p.insert().contains("FROM `shop`.`orders`"),
                "源和目标同名时最容易写成自己插自己：" + p.insert());
    }

    @Test
    @DisplayName("all() 的顺序就是执行顺序：先建表，再建索引，最后灌数据")
    void executionOrder() {
        List<String> all = plan(new TableCopy.Options("shop", "orders_copy", true, true)).all();
        assertEquals(4, all.size());
        assertTrue(all.get(0).startsWith("CREATE TABLE"));
        assertTrue(all.get(3).startsWith("INSERT INTO"),
                "先灌数据再建唯一索引，撞上重复值时表已经写满了");
    }

    @Test
    @DisplayName("索引名过长时截断，不生成一条注定被数据库拒绝的语句")
    void longIndexNamesAreTruncated() {
        String longName = "idx_" + "x".repeat(80);
        String renamed = TableCopy.renameIndex(longName, "orders", "orders_copy");
        assertTrue(renamed.length() <= 60, "MySQL 的索引名上限是 64：" + renamed.length());
    }

    @Test
    @DisplayName("带数据复制之后要把自增计数器推到已有数据之后")
    void autoIncrementCounterIsAdvanced() {
        TableCopy.Options options = new TableCopy.Options("shop", "orders_copy", true, true);
        assertEquals("id", TableCopy.autoIncrementColumn(orders()));
        String sql = TableCopy.restartAutoIncrement(mysql, orders(), options, 25);
        // 下一个该发的号是 26。给 25 的话，第一条新数据就撞上已有的第 25 行
        assertTrue(sql.contains("26"), sql);
        assertTrue(sql.contains("orders_copy"), sql);
    }

    @Test
    @DisplayName("没有自增列就没有计数器可推")
    void noAutoIncrementColumn() {
        TableStructure plain = new TableStructure(
                new TableInfo("shop", "t", ObjectKind.TABLE, "", -1),
                List.of(new ColumnInfo("k", "VARCHAR", TypeCategory.STRING, 32, 0,
                        false, true, false, null, "", 1)),
                List.of());
        assertNull(TableCopy.autoIncrementColumn(plain));
        assertNull(TableCopy.restartAutoIncrement(mysql, plain,
                new TableCopy.Options("shop", "t2", true, true), 9));
    }

    @Test
    @DisplayName("SQLite 写不出自增，就如实返回「不支持」而不是一条假语句")
    void sqliteAdmitsItCannot() {
        SqlDialect sqlite = Dialects.forType(DbType.SQLITE);
        assertTrue(sqlite.autoIncrementUnsupportedReason() != null);
        assertNull(TableCopy.restartAutoIncrement(sqlite, orders(),
                new TableCopy.Options("main", "orders_copy", true, true), 25),
                "建表时压根没写自增，推计数器只会报一条看不懂的错");
    }

    @Test
    @DisplayName("PostgreSQL 上同样能生成一份完整的语句")
    void postgresToo() {
        TableCopy.Plan p = TableCopy.build(Dialects.forType(DbType.POSTGRESQL),
                "public", orders(),
                new TableCopy.Options("public", "orders_copy", true, true));
        assertTrue(p.ddl().get(0).contains("\"orders_copy\""), p.ddl().get(0));
        assertTrue(p.insert().contains("\"public\".\"orders_copy\""), p.insert());
    }
}
