package com.plainly.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;
import com.plainly.driver.jdbc.JdbcConnections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据传输的两条读法。
 *
 * <p>正常是源、目标各一条连接，源那条开游标流式读。源和目标是<b>同一个</b>连接对象时
 * 流式读不了（同一条连接上边读游标边写），要退回分页。两条路都得一行不差。
 */
@DisplayName("数据传输 · 流式读与分页读都一行不差")
class TransferStreamingTest {

    private static final int ROWS = 5000;

    private static DbConnection open(String name) {
        return JdbcConnections.open(new ConnectionConfig()
                .setName(name).setType(DbType.H2)
                .setFilePath("mem:" + name + ";DB_CLOSE_DELAY=-1")
                .setUser("sa").setPassword(""));
    }

    private static void seed(DbConnection conn, String schema) {
        conn.executeDdlBatch(List.of(
                "CREATE TABLE " + schema + ".T (ID INT PRIMARY KEY, V VARCHAR(20))",
                "INSERT INTO " + schema + ".T SELECT X, 'v' || X FROM SYSTEM_RANGE(1, " + ROWS + ")"));
    }

    @Test
    @DisplayName("两条连接：游标流式读，批量写，行数一致")
    void streamsBetweenTwoConnections() {
        try (DbConnection src = open("plainly_tr_src"); DbConnection dst = open("plainly_tr_dst")) {
            seed(src, "PUBLIC");
            TransferService.TablePlan plan = TransferService.plan(src, "PUBLIC", "T", dst, "PUBLIC");
            long[] lastProgress = {0};
            TransferService.Outcome out = TransferService.transfer(src, "PUBLIC", plan, dst, "PUBLIC",
                    TransferService.TargetMode.CREATE, 700, n -> lastProgress[0] = n);

            assertTrue(out.ok(), out.problem());
            assertEquals(ROWS, out.read());
            assertEquals(ROWS, lastProgress[0], "进度最后要报到总行数");
            assertEquals("一致 · " + ROWS + " 行",
                    TransferService.verify(src, "PUBLIC", dst, "PUBLIC", "T"));
        }
    }

    @Test
    @DisplayName("同一个连接、两个库之间：退回分页读，照样一行不差")
    void sameConnectionFallsBackToPaging() {
        try (DbConnection conn = open("plainly_tr_same")) {
            conn.executeDdlBatch(List.of("CREATE SCHEMA A", "CREATE SCHEMA B"));
            seed(conn, "A");
            TransferService.TablePlan plan = TransferService.plan(conn, "A", "T", conn, "B");
            TransferService.Outcome out = TransferService.transfer(conn, "A", plan, conn, "B",
                    TransferService.TargetMode.CREATE, 700, n -> { });

            assertTrue(out.ok(), out.problem());
            assertEquals(ROWS, out.read());
            assertEquals(String.valueOf(ROWS), conn.scalar("SELECT COUNT(*) FROM B.T"));
        }
    }
}
