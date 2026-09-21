package com.plainly.driver.jdbc;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 同一条连接被多个后台线程同时读元数据。
 *
 * <h2>这条测试守的是一个真实故障</h2>
 * 用户报「启动时报 java.util.ConcurrentModificationException」，界面上显示成
 * 「读取 auth_dev_ctrl 失败」。根因是主键缓存原来是普通 {@code HashMap}，
 * 而取主键的那个 {@code computeIfAbsent} 的映射函数里夹着一次 JDBC 往返——
 * 窗口有整整一次网络往返那么长，另一个线程在这期间碰一下这张表就抛 CME。
 *
 * <p>为什么会有多个线程同时读同一条连接：展开树、预热对象搜索索引、
 * 恢复上次的标签页，各自都在后台线程上跑，而它们共用一条连接。
 *
 * <p>H2 是内存库，一次往返快得多，所以这条测试<b>不保证每次都能复现</b>原来的竞争；
 * 它守的是「并发读元数据不抛异常」这个结果。跑很多轮来提高撞上的概率。
 */
@DisplayName("元数据 · 多线程并发读同一条连接")
class MetadataConcurrencyTest {

    private static DbConnection conn;

    @BeforeAll
    static void open() {
        conn = JdbcConnections.open(new ConnectionConfig()
                .setName("meta-conc")
                .setType(DbType.H2)
                .setFilePath("mem:plainly_meta_conc;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword(""));
        List<String> ddl = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ddl.add("CREATE TABLE IF NOT EXISTS CONC_" + i
                    + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(32))");
        }
        conn.executeDdlBatch(ddl);
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("并发读表清单和结果集元数据，不能抛 ConcurrentModificationException")
    void concurrentMetadataReadsAreSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            // 一半的线程列表，一半的线程跑查询——后者会去填主键缓存，
            // 而列表那条路（序列/事件的查询）也会走到同一个缓存上
            jobs.add(() -> {
                for (int i = 0; i < 40; i++) {
                    conn.listTables("PUBLIC");
                }
                return null;
            });
            jobs.add(() -> {
                for (int i = 0; i < 40; i++) {
                    conn.execute("SELECT * FROM CONC_" + (i % 30), 1);
                }
                return null;
            });
        }
        try {
            List<Future<Object>> results = pool.invokeAll(jobs, 120, TimeUnit.SECONDS);
            for (Future<Object> f : results) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (containsConcurrentModification(cause)) {
                        fail("并发读元数据抛了 ConcurrentModificationException：" + cause);
                    }
                    // 别的异常（H2 在高并发下偶发的锁超时之类）不是这条测试要守的东西
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private static boolean containsConcurrentModification(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.util.ConcurrentModificationException) {
                return true;
            }
        }
        return false;
    }
}
