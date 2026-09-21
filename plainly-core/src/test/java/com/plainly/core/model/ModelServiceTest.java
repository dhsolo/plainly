package com.plainly.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 逆向数据模型。
 *
 * <p>关键分寸：有外键约束的关系是事实，按命名猜出来的只是线索。
 * 两者在图上是实线和虚线，在数据里就必须是不同的标记——
 * 一旦混为一谈，用户会把猜测当成库里真有的约束。
 */
@DisplayName("数据模型 · 逆向与关系推测")
class ModelServiceTest {

    private static DbConnection conn;
    private static final String SCHEMA = "PUBLIC";

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("model-probe").setType(DbType.H2)
                .setFilePath("mem:plainly_model;DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));

        conn.executeDdlBatch(List.of(
                "CREATE TABLE CUSTOMERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))",
                "CREATE TABLE COUPONS (ID BIGINT PRIMARY KEY, CODE VARCHAR(32))",
                // 有外键约束
                "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, CUSTOMER_ID BIGINT,"
                        + " COUPON_ID BIGINT, AMOUNT DECIMAL(38,10),"
                        + " CONSTRAINT FK_ORDER_CUSTOMER FOREIGN KEY (CUSTOMER_ID)"
                        + " REFERENCES CUSTOMERS (ID))",
                // 没有约束，只能按命名猜
                "CREATE TABLE ORDER_ITEMS (ID BIGINT PRIMARY KEY, ORDER_ID BIGINT,"
                        + " QUANTITY INT)"));
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    private static ModelService.Model model() {
        return ModelService.reverse(conn, SCHEMA,
                List.of("CUSTOMERS", "COUPONS", "ORDERS", "ORDER_ITEMS"));
    }

    @Test
    @DisplayName("四张表都进模型，主键读得出来")
    void reversesEntities() {
        ModelService.Model m = model();
        assertEquals(4, m.entities().size());
        ModelService.Entity orders = m.entities().stream()
                .filter(e -> e.name().equals("ORDERS")).findFirst().orElseThrow();
        assertEquals(List.of("ID"), orders.primaryKey());
        assertEquals(4, orders.columns().size());
    }

    @Test
    @DisplayName("有外键的关系标为确定")
    void confirmedRelationFromForeignKey() {
        ModelService.Relation r = model().relations().stream()
                .filter(x -> x.fromTable().equals("ORDERS") && x.confirmed())
                .findFirst().orElseThrow();
        assertEquals("CUSTOMERS", r.toTable());
        assertEquals(List.of("CUSTOMER_ID"), r.fromColumns());
        assertTrue(r.note().contains("外键约束"), r.note());
    }

    @Test
    @DisplayName("没有约束的按命名推测，且必须标成推测")
    void guessedRelation() {
        ModelService.Relation r = model().relations().stream()
                .filter(x -> x.fromTable().equals("ORDER_ITEMS"))
                .findFirst().orElseThrow();
        assertEquals("ORDERS", r.toTable());
        assertFalse(r.confirmed(), "猜出来的不能说成事实");
        assertTrue(r.note().contains("没有对应外键约束"), r.note());
    }

    @Test
    @DisplayName("同一列已有外键就不再重复猜一遍")
    void doesNotGuessWhatIsAlreadyConfirmed() {
        long toCustomers = model().relations().stream()
                .filter(r -> r.fromTable().equals("ORDERS") && r.toTable().equals("CUSTOMERS"))
                .count();
        assertEquals(1, toCustomers, "CUSTOMER_ID 只该有一条关系");
    }

    @Test
    @DisplayName("COUPON_ID 没有约束，但名字对得上 COUPONS，算一条推测")
    void guessesAcrossPluralNames() {
        ModelService.Relation r = model().relations().stream()
                .filter(x -> x.fromTable().equals("ORDERS") && !x.confirmed())
                .findFirst().orElseThrow();
        assertEquals("COUPONS", r.toTable());
    }

    @Test
    @DisplayName("自己指向自己不算关系")
    void ignoresSelfReference() {
        assertTrue(model().relations().stream().noneMatch(
                r -> r.fromTable().equalsIgnoreCase(r.toTable())));
    }

    @Test
    @DisplayName("建库脚本先建表再加外键，推测出来的不写进去")
    void buildScriptSeparatesFacts() {
        List<String> script = ModelService.buildScript(conn, model());
        long creates = script.stream().filter(s -> s.startsWith("CREATE TABLE")).count();
        long alters = script.stream().filter(s -> s.startsWith("ALTER TABLE")).count();
        assertEquals(4, creates);
        assertEquals(1, alters, "只有那条真外键该写进脚本");
        assertTrue(script.indexOf(script.stream().filter(s -> s.startsWith("ALTER TABLE"))
                .findFirst().orElseThrow()) >= creates, "外键必须排在所有建表之后");
    }
}
