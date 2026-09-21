package com.plainly.core.query;

import com.plainly.driver.DbConnection;
import com.plainly.driver.QueryResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台执行查询。
 *
 * <p>界面线程绝不碰数据库——一条慢查询会把整个窗口冻住。
 * 本类不依赖 JavaFX，返回 {@link CompletableFuture}，由界面自己切回 UI 线程。
 */
public class QueryService implements AutoCloseable {

    private final ExecutorService pool;

    public QueryService() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "plainly-query-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newCachedThreadPool(factory);
    }

    public CompletableFuture<QueryResult> submit(DbConnection conn, String sql, int maxRows) {
        return CompletableFuture.supplyAsync(() -> conn.execute(sql, maxRows), pool);
    }

    public <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, pool);
    }

    public void runAsync(Runnable work) {
        pool.execute(work);
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
