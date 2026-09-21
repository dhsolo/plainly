package com.plainly.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 连接分组的存取。
 *
 * <p>分组是纯粹的界面组织信息，存坏了不会报错——只会在下次启动时发现自己整理过的
 * 目录没了，或者一条连接跑到了别的组里。这类失效没有任何提示，所以只能靠这里守住。
 *
 * <p>另有一条更隐蔽的：改分组走的是 {@code save()}，而 {@code save()} 同时负责口令。
 * 如果改一次分组把已保存的口令清掉了，用户下次连库要重新输密码，而他会以为是
 * 工具在乱改自己的配置。所以那条也必须测。
 */
@DisplayName("连接 · 分组")
class ConnectionGroupTest {

    @TempDir
    Path dir;

    private LocalStore local;

    private ConnectionRegistry registry() {
        local = new LocalStore(dir.resolve("plainly.db"));
        return new ConnectionRegistry(local, new CredentialStore.UnprotectedCredentialStore());
    }

    @AfterEach
    void closeStore() {
        if (local != null) {
            local.close();
            local = null;
        }
    }

    private ConnectionConfig make(ConnectionRegistry reg, String name, String group) {
        return reg.save(new ConnectionConfig()
                .setName(name)
                .setType(DbType.MYSQL)
                .setHost("127.0.0.1")
                .setPort(3306)
                .setUser("root")
                .setGroup(group));
    }

    private ConnectionConfig find(ConnectionRegistry reg, String name) {
        return reg.listAll().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("分组存得下、读得回")
    void roundTrip() {
        ConnectionRegistry reg = registry();
        make(reg, "生产库", "线上");
        assertEquals("线上", find(reg, "生产库").group());
    }

    @Test
    @DisplayName("没设分组的读回来是空串，不是 null")
    void ungroupedIsBlankNotNull() {
        ConnectionRegistry reg = registry();
        make(reg, "本地", "");
        String group = find(reg, "本地").group();
        assertNotNull(group, "界面上到处在 group().isBlank()，一个 null 就是一次空指针");
        assertEquals("", group);
    }

    @Test
    @DisplayName("同组的排在一起，没分组的排在最后")
    void groupedFirst() {
        ConnectionRegistry reg = registry();
        make(reg, "散的甲", "");
        make(reg, "乙组一", "beta");
        make(reg, "散的乙", "");
        make(reg, "甲组一", "alpha");
        make(reg, "乙组二", "beta");

        List<String> groups = reg.listAll().stream().map(ConnectionConfig::group).toList();

        // 一、没分组的全在最后。它们留在树的最外层，夹在两个目录中间会很怪
        assertEquals(2, groups.subList(3, 5).stream().filter(String::isBlank).count(),
                "没分组的应当排在最后：" + groups);

        // 二、同一个分组必须连续。树上一个分组就是一个目录，
        // 成员散在列表各处时，目录该出现在哪个位置就没有答案了
        assertEquals(3, distinctRuns(groups), "分组不连续：" + groups);
    }

    /** 相邻且相同的算一段，返回段数。等于分组数说明每组都是连续的。 */
    private static int distinctRuns(List<String> values) {
        int runs = 0;
        String previous = null;
        for (String value : values) {
            if (!value.equals(previous)) {
                runs++;
                previous = value;
            }
        }
        return runs;
    }

    @Test
    @DisplayName("改分组不会把已保存的口令弄丢")
    void regroupKeepsPassword() {
        ConnectionRegistry reg = registry();
        ConnectionConfig saved = reg.save(new ConnectionConfig()
                .setName("带口令的")
                .setType(DbType.MYSQL)
                .setUser("root")
                .setPassword("s3cret")
                .setSavePassword(true));

        // 界面上拿到的配置口令恒为 null，改分组就是拿这一份去 save
        ConnectionConfig fromUi = find(reg, "带口令的");
        assertEquals(null, fromUi.password());
        reg.save(fromUi.copy().setGroup("线上"));

        ConnectionConfig after = reg.resolvePassword(find(reg, "带口令的"));
        assertEquals("s3cret", after.password(), "改个分组就要用户重新输密码，那是工具的错");
        assertEquals("线上", find(reg, "带口令的").group());
        assertEquals(saved.id(), find(reg, "带口令的").id());
    }

    @Test
    @DisplayName("口令：null 是「不碰」，空串是「清掉」")
    void passwordNullMeansKeepEmptyMeansClear() {
        ConnectionRegistry reg = registry();
        reg.save(new ConnectionConfig().setName("甲").setType(DbType.MYSQL)
                .setUser("root").setPassword("s3cret").setSavePassword(true));

        // null：改别的东西时不该动口令。界面上拿到的配置口令字段恒为 null，
        // 改分组、改颜色走的都是这条路
        ConnectionConfig fromUi = find(reg, "甲");
        assertEquals(null, fromUi.password());
        reg.save(fromUi.copy().setColor("#c0392b"));
        assertEquals("s3cret", reg.resolvePassword(find(reg, "甲")).password(),
                "改个颜色不该把口令弄丢");

        // 空串：用户把密码框清空了，就是要清掉。
        // UPSERT 里的 COALESCE(新, 旧) 只会保留旧值，清不掉，得另外来一下
        reg.save(find(reg, "甲").copy().setPassword("").setSavePassword(true));
        assertEquals("", reg.resolvePassword(find(reg, "甲")).password(),
                "清空密码框之后还留着旧口令，用户是看不出来的");
    }

    @Test
    @DisplayName("copy() 要把标记色和分组一起带上")
    void copyCarriesColorAndGroup() {
        ConnectionConfig original = new ConnectionConfig()
                .setName("生产")
                .setColor("#c0392b")
                .setGroup("线上");
        ConnectionConfig copy = original.copy();
        // withPassword() 就是走的 copy()，而那条路上出来的副本一路走到界面。
        // 颜色掉了就等于生产库的红标没了——最需要它的时候
        assertEquals("#c0392b", copy.color());
        assertEquals("线上", copy.group());
        assertEquals("#c0392b", original.withPassword("x").color());
    }
}
