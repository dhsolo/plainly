package com.plainly.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.jdbc.dialect.Dialects;
import com.plainly.driver.meta.DbObjects.ColumnInfo;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网格编辑缓冲：改值、新增行、删除行。
 *
 * <p>这里守的几条不变量，错了都不会当场报错，只会静静写出不对的数据：
 * 新增行里没填的列必须<b>不出现</b>在 INSERT 的列清单里（否则自增主键被插 NULL、
 * 带默认值的列变成空），删除必须按主键的<b>原值</b>定位（否则改了主键再删会删错行），
 * 三类语句必须按「删 → 改 → 增」的顺序发（否则「换掉这一行」会撞主键冲突）。
 */
@DisplayName("网格编辑 · 改值 / 新增行 / 删除行")
class EditBufferTest {

    private static final SqlDialect H2 = Dialects.forType(DbType.H2);

    /** 三列：id 是自增主键，name 非空无默认值，note 可空。 */
    private static QueryResult twoRows() {
        List<ColumnMeta> columns = List.of(
                new ColumnMeta("id", "id", "BIGINT", TypeCategory.INTEGER, 19, 0,
                        false, "shop", "orders", true, true),
                new ColumnMeta("name", "name", "VARCHAR", TypeCategory.STRING, 64, 0,
                        false, "shop", "orders", false, false),
                new ColumnMeta("note", "note", "VARCHAR", TypeCategory.STRING, 64, 0,
                        true, "shop", "orders", false, false));
        List<Row> rows = List.of(
                new Row(new String[]{"1", "甲", "x"}),
                new Row(new String[]{"2", "乙", null}));
        return QueryResult.of(columns, rows, 1, false, "SELECT * FROM orders");
    }

    private static List<ColumnInfo> structure() {
        return List.of(
                new ColumnInfo("id", "BIGINT", TypeCategory.INTEGER, 19, 0,
                        false, true, true, null, "", 1),
                new ColumnInfo("name", "VARCHAR", TypeCategory.STRING, 64, 0,
                        false, false, false, null, "", 2),
                new ColumnInfo("note", "VARCHAR", TypeCategory.STRING, 64, 0,
                        true, false, false, null, "", 3));
    }

    private static EditBuffer buffer() {
        return new EditBuffer(twoRows());
    }

    @Test
    @DisplayName("新增行的下标接在已有行后面，不与数据库里的行相撞")
    void newRowIndexes() {
        EditBuffer buffer = buffer();
        assertEquals(2, buffer.baseRowCount());
        assertEquals(2, buffer.addRow());
        assertEquals(3, buffer.addRow());
        assertTrue(buffer.isNewRow(2));
        assertFalse(buffer.isNewRow(1));
    }

    @Test
    @DisplayName("没填的列不进 INSERT 的列清单——留给自增和默认值")
    void unsetColumnsStayOutOfInsert() {
        EditBuffer buffer = buffer();
        int row = buffer.addRow();
        buffer.set(row, 1, "丙");   // 只填 name

        EditBuffer.PendingBatch batch = buffer.buildBatch(H2, "PUBLIC", "orders", structure());
        assertEquals(1, batch.inserts().size());
        String sql = batch.inserts().get(0).statement().sql();

        assertTrue(sql.contains("\"name\""), "填过的列要在：" + sql);
        assertFalse(sql.contains("\"id\""), "自增主键没填，不该出现在列清单里：" + sql);
        assertFalse(sql.contains("\"note\""), "没填的列不该出现在列清单里：" + sql);
        assertEquals(List.of("丙"), batch.inserts().get(0).values());
    }

    @Test
    @DisplayName("明确置成 NULL 的列要进列清单，值是 NULL——和「没填」不是一回事")
    void explicitNullIsNotUnset() {
        EditBuffer buffer = buffer();
        int row = buffer.addRow();
        buffer.set(row, 1, "丙");
        buffer.set(row, 2, null);   // 明确写个 NULL 进去

        String sql = buffer.buildBatch(H2, "PUBLIC", "orders", structure())
                .inserts().get(0).statement().sql();
        assertTrue(sql.contains("\"note\""), "明确填的 NULL 要写进去：" + sql);

        // 再退回「没填」，它就该从列清单里消失
        buffer.unset(row, 2);
        assertFalse(buffer.isSet(row, 2));
        assertFalse(buffer.buildBatch(H2, "PUBLIC", "orders", structure())
                .inserts().get(0).statement().sql().contains("\"note\""));
    }

    @Test
    @DisplayName("一个字段都没填的新增行当场拒绝，不静静插一条全默认值的记录")
    void emptyNewRowRefused() {
        EditBuffer buffer = buffer();
        buffer.addRow();
        DbException e = assertThrows(DbException.class,
                () -> buffer.buildBatch(H2, "PUBLIC", "orders", structure()));
        assertTrue(e.getMessage().contains("一个字段都没填"), e.getMessage());
    }

    @Test
    @DisplayName("NOT NULL、无默认值、非自增的列没填，提交前就说出来")
    void missingRequiredIsReported() {
        EditBuffer buffer = buffer();
        int row = buffer.addRow();
        buffer.set(row, 2, "只填了可空的那列");

        List<String> problems = buffer.missingRequired(structure());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("name"), problems.get(0));
        // 自增主键没填不算缺——那正是交给数据库的
        assertFalse(problems.get(0).contains("id"), problems.get(0));
    }

    @Test
    @DisplayName("删除按主键的原值定位：同一行既改了主键又要删，删的仍是原来那一行")
    void deleteUsesOriginalKey() {
        EditBuffer buffer = buffer();
        buffer.set(0, 0, "999");     // 把第一行的主键从 1 改成 999
        buffer.setDeleted(0, true);

        EditBuffer.PendingBatch batch = buffer.buildBatch(H2, "PUBLIC", "orders", structure());
        assertEquals(1, batch.deletes().size());
        assertEquals(List.of("1"), batch.deletes().get(0).values());
        // 已经要删的行不再发 UPDATE：那条语句必然影响 0 行，只会让计数对不上
        assertTrue(batch.updates().isEmpty(), "标了删除的行不该再生成 UPDATE");
    }

    @Test
    @DisplayName("执行顺序是删 → 改 → 增：先插后删会撞主键冲突")
    void statementOrderIsDeleteUpdateInsert() {
        EditBuffer buffer = buffer();
        buffer.setDeleted(0, true);
        buffer.set(1, 1, "改过的乙");
        int row = buffer.addRow();
        buffer.set(row, 1, "新来的");

        List<EditBuffer.PendingUpdate> ordered =
                buffer.buildBatch(H2, "PUBLIC", "orders", structure()).inOrder();
        assertEquals(3, ordered.size());
        assertTrue(ordered.get(0).statement().sql().startsWith("DELETE"), ordered.get(0).statement().sql());
        assertTrue(ordered.get(1).statement().sql().startsWith("UPDATE"), ordered.get(1).statement().sql());
        assertTrue(ordered.get(2).statement().sql().startsWith("INSERT"), ordered.get(2).statement().sql());
    }

    @Test
    @DisplayName("撤掉中间的新增行之后，后面那些行的下标要跟着前移")
    void removingNewRowShiftsTheRest() {
        EditBuffer buffer = buffer();
        int first = buffer.addRow();
        int second = buffer.addRow();
        buffer.set(first, 1, "第一个");
        buffer.set(second, 1, "第二个");

        assertTrue(buffer.removeNewRow(first));
        assertEquals(1, buffer.newRowCount());
        // 原来的第二个新增行现在顶到了 first 的位置上
        assertEquals("第二个", buffer.displayValue(first, 1));
    }

    @Test
    @DisplayName("改值 / 增行 / 删行都算改动，放弃之后一条不剩")
    void countsAndRevert() {
        EditBuffer buffer = buffer();
        buffer.set(0, 1, "改过");
        buffer.setDeleted(1, true);
        buffer.addRow();

        assertEquals(3, buffer.changeCount());
        assertEquals("改 1 格 · 增 1 行 · 删 1 行", buffer.describeChanges());

        buffer.revertAll();
        assertFalse(buffer.hasChanges());
        assertEquals(0, buffer.newRowCount());
        assertEquals(0, buffer.deletedCount());
    }
}
