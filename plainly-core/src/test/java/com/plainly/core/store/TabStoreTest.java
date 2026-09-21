package com.plainly.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.core.store.TabStore.Kind;
import com.plainly.core.store.TabStore.SavedTab;
import com.plainly.core.store.TabStore.SessionTab;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 标签页的保存、收藏与退出现场。
 *
 * <p>这些用例盯的是「关掉之后还在不在」——存错了不会当场报错，
 * 要等到下次打开、东西没了才发现，那时候已经找不回来了。
 */
@DisplayName("标签页 · 保存与现场")
class TabStoreTest {

    @TempDir
    Path dir;

    private LocalStore local;

    /** 每个用例一个干净的库；用完必须关——Windows 上不关，临时目录删不掉。 */
    private TabStore store() {
        local = new LocalStore(dir.resolve("plainly.db"));
        return new TabStore(local);
    }

    @AfterEach
    void closeStore() {
        if (local != null) {
            local.close();
            local = null;
        }
    }

    @Test
    @DisplayName("保存一条查询，读得回来")
    void saveAndList() {
        TabStore store = store();
        long id = store.save(SavedTab.query("日订单", "conn-1", "shop", "SELECT 1", false));
        assertTrue(id > 0);

        List<SavedTab> all = store.list(false);
        assertEquals(1, all.size());
        SavedTab one = all.get(0);
        assertEquals("日订单", one.title());
        assertEquals("SELECT 1", one.sql());
        assertEquals("shop", one.schema());
        assertEquals(Kind.QUERY, one.kind());
        assertFalse(one.favorite());
    }

    @Test
    @DisplayName("同一条反复保存是覆盖，不是每次多一条")
    void resaveOverwrites() {
        TabStore store = store();
        long id = store.save(SavedTab.query("草稿", "conn-1", "shop", "SELECT 1", false));

        SavedTab again = new SavedTab(id, Kind.QUERY, "草稿", "conn-1", "shop",
                null, "SELECT 2", false, java.time.LocalDateTime.now());
        store.save(again);

        List<SavedTab> all = store.list(false);
        assertEquals(1, all.size(), "反复保存不该堆出多条记录");
        assertEquals("SELECT 2", all.get(0).sql());
    }

    @Test
    @DisplayName("收藏的排在前面，只看收藏时非收藏的不出现")
    void favoritesFirst() {
        TabStore store = store();
        store.save(SavedTab.query("普通", "c", "s", "SELECT 1", false));
        long star = store.save(SavedTab.query("收藏的", "c", "s", "SELECT 2", true));

        assertEquals("收藏的", store.list(false).get(0).title());
        assertEquals(1, store.list(true).size());

        store.setFavorite(star, false);
        assertTrue(store.list(true).isEmpty());
    }

    @Test
    @DisplayName("表页存的是连接 · 库 · 表，不带 SQL")
    void tableTabKeepsLocation() {
        TabStore store = store();
        store.save(SavedTab.table("ORDERS", "conn-1", "shop", "ORDERS", true));
        SavedTab one = store.list(true).get(0);
        assertEquals(Kind.TABLE, one.kind());
        assertEquals("ORDERS", one.table());
        assertEquals("shop.ORDERS", one.oneLine());
    }

    @Test
    @DisplayName("删除之后就不在清单里了")
    void deleteRemoves() {
        TabStore store = store();
        long id = store.save(SavedTab.query("临时", "c", "s", "SELECT 1", false));
        store.delete(id);
        assertTrue(store.list(false).isEmpty());
    }

    @Test
    @DisplayName("退出现场按顺序存取，未保存标记跟着回来")
    void sessionRoundTrip() {
        TabStore store = store();
        store.saveSession(List.of(
                new SessionTab(0, Kind.QUERY, "查询 A", "c1", "shop", null, "SELECT 1", true, 0),
                new SessionTab(1, Kind.TABLE, "ORDERS", "c1", "shop", "ORDERS", null, false, 0)));

        List<SessionTab> back = store.lastSession();
        assertEquals(2, back.size());
        assertEquals("查询 A", back.get(0).title());
        assertTrue(back.get(0).unsaved());
        assertEquals(Kind.TABLE, back.get(1).kind());
        assertEquals("ORDERS", back.get(1).table());
        assertFalse(back.get(1).unsaved());
    }

    @Test
    @DisplayName("现场是快照：再存一次就把上一次整个换掉")
    void sessionIsSnapshotNotLog() {
        TabStore store = store();
        store.saveSession(List.of(
                new SessionTab(0, Kind.QUERY, "旧的", "c1", "s", null, "SELECT 1", true, 0),
                new SessionTab(1, Kind.QUERY, "旧的 2", "c1", "s", null, "SELECT 2", true, 0)));
        store.saveSession(List.of(
                new SessionTab(0, Kind.QUERY, "新的", "c1", "s", null, "SELECT 3", false, 0)));

        List<SessionTab> back = store.lastSession();
        assertEquals(1, back.size(), "上一次的现场应该被整个换掉");
        assertEquals("新的", back.get(0).title());
    }

    @Test
    @DisplayName("现场记得这一页绑在哪条保存记录上")
    void sessionKeepsSavedBinding() {
        TabStore store = store();
        long id = store.save(SavedTab.query("答案", "c1", "shop", "SELECT 42", true));
        // 保存之后又改了没保存：现场里存的该是改过的那份，同时记着绑定
        store.saveSession(List.of(new SessionTab(0, Kind.QUERY, "答案", "c1", "shop",
                null, "SELECT 42 -- 改过", true, id)));

        SessionTab back = store.lastSession().get(0);
        assertEquals(id, back.savedId());
        assertEquals("SELECT 42 -- 改过", back.sql());
        assertTrue(back.unsaved());
        // 绑定指向的那条记录里，仍是上次保存下去的内容
        assertEquals("SELECT 42", store.find(id).sql());
    }

    @Test
    @DisplayName("从没保存过的页，绑定是 0")
    void sessionWithoutBinding() {
        TabStore store = store();
        store.saveSession(List.of(new SessionTab(0, Kind.QUERY, "临时", "c1", "shop",
                null, "SELECT 1", true, 0)));
        assertEquals(0, store.lastSession().get(0).savedId());
    }

    @Test
    @DisplayName("清空现场之后，下次启动不该再问要不要恢复")
    void clearSession() {
        TabStore store = store();
        store.saveSession(List.of(
                new SessionTab(0, Kind.QUERY, "x", "c1", "s", null, "SELECT 1", true, 0)));
        store.clearSession();
        assertTrue(store.lastSession().isEmpty());
    }

    @Test
    @DisplayName("空现场存得下：上次退出时没开标签页也是一种状态")
    void emptySessionIsValid() {
        TabStore store = store();
        store.saveSession(List.of(
                new SessionTab(0, Kind.QUERY, "x", "c1", "s", null, "SELECT 1", true, 0)));
        store.saveSession(List.of());
        assertTrue(store.lastSession().isEmpty());
    }
}
