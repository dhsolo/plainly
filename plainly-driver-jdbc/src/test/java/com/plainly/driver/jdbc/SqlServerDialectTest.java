package com.plainly.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.TypeMapper;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL Server 方言。
 *
 * <p>只验证生成的字符串——手上没有可用的 SQL Server 实例，
 * 所以本项目真正的准入门槛（{@code PrecisionConformanceTest} 在真库上跑通）
 * 对它还没达成。{@code DbType.SQLSERVER} 的显示名里写明了这一点，
 * 界面上也就照实显示「未验证精度」。
 *
 * <p>把没验证过的东西说成验证过了，比不支持它更糟。
 */
@DisplayName("SQL Server 方言 · 仅生成层")
class SqlServerDialectTest {

    private final SqlDialect dialect = new Dialects.SqlServerDialect();

    private static ColumnInfo column(String name, String type, int precision, int scale) {
        return new ColumnInfo(name, type, TypeCategory.EXACT_NUMERIC, precision, scale,
                true, false, false, null, "", 1);
    }

    @Test
    @DisplayName("标识符用方括号，里面的右括号要双写")
    void quotesWithBrackets() {
        assertEquals("[orders]", dialect.quote("orders"));
        assertEquals("[we]]ird]", dialect.quote("we]ird"));
    }

    @Test
    @DisplayName("分页用 OFFSET/FETCH，且一定带 ORDER BY")
    void paginationAlwaysOrders() {
        String sql = dialect.selectPage("dbo", "orders", null, 100, 200);
        assertTrue(sql.contains("ORDER BY (SELECT NULL)"),
                "没有排序时也得补一个，否则 OFFSET 语法不成立：" + sql);
        assertTrue(sql.contains("OFFSET 200 ROWS"), sql);
        assertTrue(sql.contains("FETCH NEXT 100 ROWS ONLY"), sql);

        String ordered = dialect.selectPage("dbo", "orders", "[id] DESC", 50, 0);
        assertTrue(ordered.contains("ORDER BY [id] DESC"), ordered);
    }

    @Test
    @DisplayName("DROP INDEX 要带表名")
    void dropIndexNeedsTable() {
        String sql = dialect.dropIndexDdl("dbo", "orders", "idx_amount");
        assertEquals("DROP INDEX [idx_amount] ON [dbo].[orders]", sql);
    }

    @Test
    @DisplayName("改视图用 CREATE OR ALTER")
    void viewUsesCreateOrAlter() {
        String sql = dialect.createOrReplaceViewDdl("dbo", "v_gmv", "SELECT 1");
        assertTrue(sql.startsWith("CREATE OR ALTER VIEW"), sql);
    }

    @Test
    @DisplayName("明确不支持单语句执行计划，返回 null 而不是发一条跑不通的语句")
    void explainIsHonestlyUnsupported() {
        assertNull(dialect.explainQuery("SELECT 1"));
    }

    @Test
    @DisplayName("从 MySQL 搬过来时，无符号 BIGINT 同样要放宽")
    void unsignedBigintStillWidens() {
        TypeMapper.Mapping m = TypeMapper.mapType(
                column("c", "BIGINT UNSIGNED", 20, 0), DbType.MYSQL, DbType.SQLSERVER);
        assertTrue(!m.exact(), "换了类型就不该说成精确对应：" + m.target());
    }

    @Test
    @DisplayName("建表语句里的高精度列保持 DECIMAL(38,10)")
    void createTableKeepsPrecision() {
        String ddl = dialect.createTableDdl("dbo", "orders",
                List.of(com.plainly.driver.ddl.ColumnDraft.of(
                        column("amount", "DECIMAL", 38, 10))),
                List.of());
        assertTrue(ddl.contains("DECIMAL(38,10)"), ddl);
    }
}
