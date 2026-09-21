package com.plainly.core.sync;

import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.ddl.SchemaDiff;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 结构同步：比对两个库，生成并执行把目标改成源的 DDL。
 *
 * <h2>方向</h2>
 * 永远是<b>源 → 目标</b>，目标是被改的一方。这一点在服务层写死，
 * 不提供反向参数——一个能被调换方向的 API，迟早会有人在生产库上调错。
 * 要反向就重新选一次源和目标，让用户在界面上重新做一次确认。
 */
public class StructureSyncService {

    /** 一次同步计划。预览与执行共用它，看到的语句就是将要跑的语句。 */
    public record SyncPlan(List<SchemaChange> changes,
                           List<SqlDialect.SchemaChangeSql> statements,
                           List<String> blockers) {

        public boolean isEmpty() {
            return changes.isEmpty();
        }

        public boolean canApply() {
            return !changes.isEmpty() && blockers.isEmpty();
        }

        public boolean hasDestructive() {
            return changes.stream().anyMatch(c -> c.risk() == TableChange.Risk.DESTRUCTIVE);
        }

        public List<String> sql() {
            return statements.stream().map(SqlDialect.SchemaChangeSql::sql).toList();
        }
    }

    /**
     * 比对并生成计划。
     *
     * @param progress 读取进度回调，参数是正在读取的对象名。整库读元数据在大库上要几秒，
     *                 界面需要它来说明自己没卡住
     */
    public SyncPlan plan(DbConnection source, String sourceSchema,
                         DbConnection target, String targetSchema,
                         Consumer<String> progress) {

        List<TableStructure> sourceTables = readAll(source, sourceSchema, progress);
        List<TableStructure> targetTables = readAll(target, targetSchema, progress);

        List<SchemaChange> changes = SchemaDiff.compute(sourceTables, targetTables);

        // 能力检查用目标库的方言：DDL 是要在目标上跑的
        SqlDialect dialect = target.dialect();
        List<String> blockers = new ArrayList<>(dialect.unsupportedIn(changes));

        List<SqlDialect.SchemaChangeSql> statements = blockers.isEmpty()
                ? dialect.ddlForSchema(targetSchema, changes)
                : List.of();

        return new SyncPlan(changes, statements, blockers);
    }

    /** 只保留选中的那些变更，重新生成语句。用户勾选后调用。 */
    public SyncPlan restrictTo(SyncPlan full, DbConnection target, String targetSchema,
                               Set<SchemaChange> selected) {
        List<SchemaChange> kept = full.changes().stream().filter(selected::contains).toList();
        SqlDialect dialect = target.dialect();
        List<String> blockers = new ArrayList<>(dialect.unsupportedIn(kept));
        List<SqlDialect.SchemaChangeSql> statements = blockers.isEmpty()
                ? dialect.ddlForSchema(targetSchema, kept)
                : List.of();
        return new SyncPlan(kept, statements, blockers);
    }

    /**
     * 执行计划。
     *
     * <p>能否整批回滚取决于目标库：PostgreSQL / SQLite 可以，MySQL / H2 的 DDL 隐式提交。
     * 失败时抛出的 {@code DdlBatchException} 会说明库当前处于哪种状态。
     *
     * @return 执行成功的语句条数
     */
    public int apply(DbConnection target, SyncPlan plan) {
        if (!plan.canApply()) {
            throw new DbException("计划不可执行：" + String.join("；", plan.blockers()));
        }
        return target.executeDdlBatch(plan.sql());
    }

    /** 目标库的结构变更能否整体回滚。界面据此决定怎么措辞，别说做不到的话。 */
    public static boolean isAtomic(DbConnection target) {
        return target.dialect().supportsTransactionalDdl();
    }

    /** 读取一个库里所有表的完整结构。 */
    private List<TableStructure> readAll(DbConnection conn, String schema, Consumer<String> progress) {
        List<TableStructure> out = new ArrayList<>();
        for (TableInfo t : conn.listTables(schema)) {
            // 视图的结构由它的定义决定，不能靠 ALTER TABLE 同步，本版跳过
            if (t.kind() != ObjectKind.TABLE) {
                continue;
            }
            if (progress != null) {
                progress.accept(schema + "." + t.name());
            }
            out.add(conn.describeTable(schema, t.name()));
        }
        return out;
    }

    /** 计划里涉及的表名，供界面分组显示。 */
    public static Set<String> touchedTables(SyncPlan plan) {
        Set<String> names = new LinkedHashSet<>();
        for (SchemaChange c : plan.changes()) {
            if (c instanceof SchemaChange.CreateIndex ci) {
                names.add(ci.table());
            } else if (c instanceof SchemaChange.DropIndex di) {
                names.add(di.table());
            } else {
                names.add(c.objectName());
            }
        }
        return names;
    }
}
