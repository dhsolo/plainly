package com.plainly.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbException;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 虚拟外键。
 *
 * <p>它是纯本机的标注，所以这里守的都是「界面能不能正确地把它和真外键区分开」：
 * 方向要分得清（本表指向谁 / 谁指向本表），转成 {@code ForeignKeyInfo} 之后
 * 必须还认得出是虚拟的——认不出的话，用户会以为库里真有这条约束。
 */
@DisplayName("虚拟外键 · 本机标注")
class VirtualKeyStoreTest {

    @TempDir
    Path dir;

    private LocalStore local;
    private VirtualKeyStore store;

    @BeforeEach
    void open() {
        local = new LocalStore(dir.resolve("plainly.db"));
        store = new VirtualKeyStore(local);
    }

    @AfterEach
    void close() {
        if (local != null) {
            local.close();
        }
    }

    @Test
    @DisplayName("标注之后，两个方向都查得到")
    void bothDirections() {
        store.add("conn-1", "PUBLIC", "orders", List.of("user_id"),
                "PUBLIC", "users", List.of("id"), "订单归属");

        assertEquals(1, store.listOutgoing("conn-1", "PUBLIC", "orders").size(),
                "orders 指向别人");
        assertEquals(1, store.listIncoming("conn-1", "PUBLIC", "users").size(),
                "users 被别人指着");
        assertTrue(store.listOutgoing("conn-1", "PUBLIC", "users").isEmpty(),
                "方向不能反过来");
    }

    @Test
    @DisplayName("标注按连接隔开：另一条连接上看不见")
    void scopedToConnection() {
        store.add("conn-1", "PUBLIC", "orders", List.of("user_id"),
                "PUBLIC", "users", List.of("id"), null);
        assertTrue(store.listOutgoing("conn-2", "PUBLIC", "orders").isEmpty());
    }

    @Test
    @DisplayName("转成外键记录之后仍然认得出是虚拟的")
    void staysRecognizableAsVirtual() {
        store.add("conn-1", "PUBLIC", "orders", List.of("user_id"),
                "PUBLIC", "users", List.of("id"), "订单归属");
        ForeignKeyInfo fk = store.listOutgoing("conn-1", "PUBLIC", "orders").get(0).toForeignKey();

        assertTrue(VirtualKeyStore.isVirtual(fk), "名字里要带得出虚拟这个标记：" + fk.name());
        assertEquals("orders", fk.table());
        assertEquals("users", fk.refTable());
        assertEquals(List.of("user_id"), fk.columns());
        assertEquals(List.of("id"), fk.refColumns());

        // 数据库里读出来的真外键不能被误判成虚拟的
        ForeignKeyInfo real = new ForeignKeyInfo("fk_orders_user", "PUBLIC", "orders",
                List.of("user_id"), "PUBLIC", "users", List.of("id"), "NO ACTION", "NO ACTION");
        assertFalse(VirtualKeyStore.isVirtual(real));
    }

    @Test
    @DisplayName("两边列数对不上时当场拒绝——存下来只会在画图时出错")
    void columnCountMustMatch() {
        DbException e = assertThrows(DbException.class, () ->
                store.add("conn-1", "PUBLIC", "orders", List.of("a", "b"),
                        "PUBLIC", "users", List.of("id"), null));
        assertTrue(e.getMessage().contains("列数要一样"), e.getMessage());
    }

    @Test
    @DisplayName("删掉连接时它的标注一并清掉，不留孤儿")
    void deleteForConnection() {
        store.add("conn-1", "PUBLIC", "orders", List.of("user_id"),
                "PUBLIC", "users", List.of("id"), null);
        store.add("conn-2", "PUBLIC", "orders", List.of("user_id"),
                "PUBLIC", "users", List.of("id"), null);

        store.deleteForConnection("conn-1");
        assertTrue(store.listAll("conn-1").isEmpty());
        assertEquals(1, store.listAll("conn-2").size(), "别的连接的标注不能被连累");
    }
}
