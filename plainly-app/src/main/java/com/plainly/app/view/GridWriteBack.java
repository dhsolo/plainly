package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.UiUtils;
import com.plainly.core.edit.EditBuffer;
import com.plainly.driver.meta.DbObjects.TableStructure;
import javafx.application.Platform;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 把数据网格里的改动写回数据库。
 *
 * <h2>为什么单独放一个文件</h2>
 * 可编辑的网格现在有两处：表标签页的「数据」视图，和 SQL 查询的结果区。
 * 两边写回的规则必须<b>一模一样</b>——同一条 UPDATE、同一道必填校验、
 * 删除前同一句确认。一旦分成两份，迟早出现「在表页删要确认、在查询结果里直接就删了」
 * 这种差别，而用户完全没有理由预期它们不同。
 *
 * <p>{@link DraftFieldCells} 是同一个教训：抄一遍等于把坑也复制一份，
 * 而且只会修好被人报上来的那一份。
 */
public final class GridWriteBack {

    private GridWriteBack() {
    }

    /**
     * 执行写回。整个过程异步，回调都在 FX 线程上。
     *
     * <h2>为什么不整批塞进一个事务</h2>
     * 多数库的 DML 事务是能回滚的，这一点本来可以做；但当前连接的自动提交状态
     * 由用户自己掌握（见「事务」那一组按钮）。在这里私自开一个事务，会把用户手上
     * 那个还没提交的事务搅乱。手动事务模式下这一批本来就在他的事务里，
     * 自动提交模式下则逐条落库——两种都符合用户此刻对这条连接的预期。
     *
     * @param onSaved  写回成功后调用，通常是重新拉一遍数据
     * @param onFailed 没写成（校验没过、用户取消、数据库报错）时调用，用来把按钮状态还原
     */
    public static void commit(AppContext context, DbSession session, String schema, String table,
                       DataGridPane grid, Window owner, Consumer<String> status,
                       Runnable onSaved, Runnable onFailed) {
        EditBuffer buffer = grid.editBuffer();
        if (buffer == null || !buffer.hasChanges()) {
            onFailed.run();
            return;
        }
        TableStructure structure;
        try {
            structure = session.structure(schema, table);
        } catch (RuntimeException e) {
            UiUtils.showError(owner, "读取表结构失败，无法生成写回语句", e);
            onFailed.run();
            return;
        }

        // NOT NULL、没默认值、又不是自增的列没填，数据库一定退回来。
        // 在这里说清是哪一行哪一列，比拿一条约束报错让用户自己猜强
        List<String> missing = buffer.missingRequired(structure.columns());
        if (!missing.isEmpty()) {
            UiUtils.showInfo(owner, "新增行还缺必填字段，没有写库",
                    String.join(System.lineSeparator(), missing));
            onFailed.run();
            return;
        }

        EditBuffer.PendingBatch batch;
        try {
            batch = buffer.buildBatch(session.connection().dialect(), schema, table,
                    structure.columns());
        } catch (RuntimeException e) {
            UiUtils.showError(owner, "无法生成写回语句", e);
            onFailed.run();
            return;
        }

        if (!batch.deletes().isEmpty()) {
            StringBuilder ask = new StringBuilder("这一批里有 ")
                    .append(batch.deletes().size()).append(" 行要删除。删掉的行找不回来。");
            if (!batch.updates().isEmpty() || !batch.inserts().isEmpty()) {
                ask.append(System.lineSeparator()).append("同时还会改 ")
                        .append(batch.updates().size()).append(" 行、新增 ")
                        .append(batch.inserts().size()).append(" 行。");
            }
            ask.append(System.lineSeparator()).append("确定执行？");
            if (!UiUtils.confirm(owner, "确认删除", ask.toString())) {
                onFailed.run();
                return;
            }
        }

        List<EditBuffer.PendingUpdate> ordered = batch.inOrder();
        context.queryService().submit(() -> {
            int affected = 0;
            for (EditBuffer.PendingUpdate u : ordered) {
                affected += session.connection().executeUpdate(u.statement(), u.values());
            }
            return affected;
        }).whenComplete((affected, error) -> Platform.runLater(() -> {
            if (error != null) {
                // 「数据未改变」这句话在有多条语句时是假的：前面几条可能已经生效了。
                // 说清楚跑到第几条，比一句笼统的保证有用
                session.fireTransactionChanged();
                UiUtils.showError(owner,
                        ordered.size() > 1
                                ? "保存中断。这一批共 " + ordered.size() + " 条语句，"
                                        + "失败之前的那些可能已经生效——刷新后再看一眼"
                                : "保存失败，数据未改变",
                        error);
                onFailed.run();
                return;
            }
            status.accept("已保存 " + affected + " 行（" + describe(batch) + "）"
                    + (session.manualTransaction() ? " · 在事务里，按「提交」才落库" : ""));
            session.fireTransactionChanged();
            buffer.revertAll();
            onSaved.run();
        }));
    }

    private static String describe(EditBuffer.PendingBatch batch) {
        List<String> parts = new ArrayList<>();
        if (!batch.deletes().isEmpty()) {
            parts.add("删 " + batch.deletes().size());
        }
        if (!batch.updates().isEmpty()) {
            parts.add("改 " + batch.updates().size());
        }
        if (!batch.inserts().isEmpty()) {
            parts.add("增 " + batch.inserts().size());
        }
        return String.join(" · ", parts);
    }
}
