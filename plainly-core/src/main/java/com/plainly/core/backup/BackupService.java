package com.plainly.core.backup;

import com.plainly.core.export.ExportOptions;
import com.plainly.core.export.Exporters;
import com.plainly.core.export.RowSource;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.QueryResult;
import com.plainly.driver.Row;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.SqlScript;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 逻辑备份与还原。
 *
 * <p>写的是一份 SQL 脚本：建表语句 + 数据的 INSERT。这不是物理备份——
 * 不含用户、权限、存储过程、二进制日志位点，也不保证一致性快照
 * （备份过程中被改的表，导出的是各表各自读到的那一刻）。
 * 需要那些就得用 {@code mysqldump}、{@code pg_dump} 这类官方工具，这里不假装能替代它们。
 *
 * <p>能保证的是：数值一位不差。数据走的是导出那条已经验证过的路径，
 * 数字以字面量写出，还原时再原样读回。
 */
public final class BackupService {

    /**
     * 备份结果。
     *
     * @param skipped 没能备份进去的东西，每条一句人话。<b>空表才代表齐全</b>——
     *                一个备份最危险的失败方式是「少了点什么但没人说」，
     *                等到还原时才发现视图或存储过程不见了
     */
    public record Result(Path file, int tables, long rows, long bytes,
                         int views, int routines, int triggers, int foreignKeys, int events,
                         int sequences, List<String> skipped) {

        public Result(Path file, int tables, long rows, long bytes) {
            this(file, tables, rows, bytes, 0, 0, 0, 0, 0, 0, List.of());
        }

        /** 界面上那一句总结。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(tables).append(" 张表 · ").append(rows).append(" 行");
            if (views > 0) {
                sb.append(" · ").append(views).append(" 个视图");
            }
            if (routines > 0) {
                sb.append(" · ").append(routines).append(" 个存储过程/函数");
            }
            if (triggers > 0) {
                sb.append(" · ").append(triggers).append(" 个触发器");
            }
            if (foreignKeys > 0) {
                sb.append(" · ").append(foreignKeys).append(" 个外键");
            }
            if (events > 0) {
                sb.append(" · ").append(events).append(" 个事件");
            }
            if (sequences > 0) {
                sb.append(" · ").append(sequences).append(" 个序列");
            }
            return sb.toString();
        }
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BackupService() {
    }

    /**
     * 备份整个库到一个 .sql 文件。
     *
     * @param includeData false 则只备份结构
     */
    public static Result backup(DbConnection conn, String schema, Path target,
                                boolean includeData, Consumer<String> progress) {
        List<TableInfo> tables = conn.listTables(schema).stream()
                .filter(t -> t.kind() == ObjectKind.TABLE)
                .toList();

        long rows = 0;
        int views = 0;
        int sequences = 0;
        int routines = 0;
        int triggers = 0;
        int keys = 0;
        int events = 0;
        List<String> skipped = new ArrayList<>();
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new DbException("无法创建备份目录：" + e.getMessage(), e);
        }

        try (Writer w = new BufferedWriter(
                Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {
            w.write("-- Plainly 逻辑备份\n");
            w.write("-- 库：" + schema + "\n");
            w.write("-- 时间：" + LocalDateTime.now().format(STAMP) + "\n");
            w.write("-- 含：序列、表结构与数据、外键、视图、存储过程与函数、触发器、定时事件。\n");
            w.write("-- 不含：用户与权限、二进制日志位点。\n");
            w.write("-- 注意：不是一致性快照，备份期间被改动的表以各自读到的那一刻为准。\n");
            w.write("--       例程里的 DEFINER 原样保留——那是定义的一部分；"
                    + "还原到别的实例时那个账号必须存在。\n\n");

            sequences = dumpSequences(conn, schema, w, progress, skipped);

            for (TableInfo table : tables) {
                progress.accept("正在备份 " + table.name());
                TableStructure structure = conn.describeTable(schema, table.name());

                w.write("-- ---------- " + table.name() + " ----------\n");
                w.write(conn.dialect().createTableDdl(schema, structure));
                w.write(";\n");
                for (IndexInfo index : structure.indexes()) {
                    if (index.primary()) {
                        continue;
                    }
                    w.write(conn.dialect().createIndexDdl(schema, table.name(), index));
                    w.write(";\n");
                }
                w.write("\n");

                if (includeData) {
                    rows += dumpData(conn, schema, table.name(), w);
                }
            }

            /*
             * 顺序不是随便排的，它决定了这份文件能不能一路跑下来：
             *
             *   表 -> 数据 -> 视图 -> 例程 -> 触发器
             *
             * 视图排在表后面：它引用的表得先存在。
             *
             * 触发器排在<b>最后</b>，这一条最容易被忽略而且后果很实在——
             * 触发器要是先建出来，上面那些 INSERT 会把它们统统触发一遍：
             * 审计表被灌进一堆假记录、计数字段被重复累加。还原出来的库
             * 表面上数据对得上，附带的那些副作用却全发生了一次。
             */
            keys = dumpForeignKeys(conn, schema, tables, w, progress, skipped);
            views = dumpViews(conn, schema, w, progress, skipped);
            routines = dumpRoutines(conn, schema, w, progress, skipped);
            triggers = dumpTriggers(conn, schema, tables, w, progress, skipped);
            events = dumpEvents(conn, schema, w, progress, skipped);
        } catch (IOException e) {
            throw new DbException("写备份文件失败：" + e.getMessage(), e);
        }

        long bytes;
        try {
            bytes = Files.size(target);
        } catch (IOException e) {
            bytes = -1;
        }
        return new Result(target, tables.size(), rows, bytes,
                views, routines, triggers, keys, events, sequences, List.copyOf(skipped));
    }

    // ------------------------------------------------------------------ 其它对象

    /**
     * 序列。
     *
     * <h2>为什么排在所有建表之前</h2>
     * 表的列可能写着 {@code DEFAULT nextval(\'seq\')}（PostgreSQL 的 serial 就是这么实现的）。
     * 序列还不存在时，那条建表语句直接失败。
     *
     * <h2>起始值取的是「下一个该发的号」，不是当初的起始值</h2>
     * 这一条决定了还原出来的库能不能用。照原始起始值恢复，序列会把已经发过的号
     * <b>再发一遍</b>——而这不会在还原时报错，要等到某次插入撞上主键冲突才暴露，
     * 那时离还原往往已经过去很久。取值的口径由各家方言负责，见
     * {@code SqlDialect.sequenceAttributesQuery}。
     *
     * <h2>取不到就如实记一笔</h2>
     * 这一家没有序列（MySQL、SQLite）时安静跳过；有序列但查失败，
     * 则把原因记进 {@code skipped}——备份声称完整却少了东西，
     * 是最不该安静发生的一类事。
     */
    private static int dumpSequences(DbConnection conn, String schema, Writer w,
                                     Consumer<String> progress, List<String> skipped)
            throws IOException {
        String query = conn.dialect().sequenceAttributesQuery(schema);
        if (query == null) {
            return 0;
        }
        List<SqlDialect.SequenceInfo> found = new ArrayList<>();
        try {
            QueryResult r = conn.execute(query, 0);
            for (Row row : r.rows()) {
                String name = row.get(0);
                if (name == null || name.isBlank()) {
                    continue;
                }
                found.add(new SqlDialect.SequenceInfo(name, row.get(1), row.get(2),
                        row.get(3), row.get(4), isTrue(row.get(5))));
            }
        } catch (RuntimeException e) {
            skipped.add("序列没有备份到：" + e.getMessage());
            return 0;
        }
        if (found.isEmpty()) {
            return 0;
        }
        progress.accept("正在备份 " + found.size() + " 个序列");
        w.write("-- ---------- 序列 ----------\n");
        w.write("-- 起始值取的是当前「下一个该发的号」，不是当初建序列时的起始值；\n");
        w.write("-- 否则还原之后会把已经用过的号再发一遍。\n");
        for (SqlDialect.SequenceInfo seq : found) {
            w.write(conn.dialect().createSequenceDdl(schema, seq));
            w.write(";\n");
        }
        w.write("\n");
        return found.size();
    }

    /** 各家对布尔的说法不一：true/false、1/0、Y/N、YES/NO。 */
    private static boolean isTrue(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("y") || v.equals("yes");
    }

    /**
     * 外键。
     *
     * <h2>为什么必须单独一段，而且排在所有表之后</h2>
     * 建表语句里<b>不含</b>外键——{@code createTableDdl} 只写列和主键。
     * 原来的备份因此把外键整个丢了：还原出来的库表和数据都在，
     * 参照完整性却一条不剩，而且从头到尾没有任何报错。这种「看着成功了的失败」
     * 要等到某天出现一条本该被外键挡住的脏数据才会暴露。
     *
     * <p>排在所有表建完之后：外键指向的表未必在自己前面，先建必然失败。
     * 也排在数据之后——先加约束的话，插入顺序稍有不对就会被外键挡回来。
     */
    private static int dumpForeignKeys(DbConnection conn, String schema, List<TableInfo> tables,
                                       Writer w, Consumer<String> progress, List<String> skipped)
            throws IOException {
        List<String> statements = new ArrayList<>();
        for (TableInfo table : tables) {
            List<com.plainly.driver.meta.DbObjects.ForeignKeyInfo> keys;
            try {
                keys = conn.listForeignKeys(schema, table.name());
            } catch (RuntimeException e) {
                skipped.add(table.name() + " 的外键：" + e.getMessage());
                continue;
            }
            for (var key : keys) {
                progress.accept("正在备份外键 " + key.name());
                statements.add(conn.dialect().addForeignKeyDdl(schema, table.name(), key.name(),
                        key.columns(), key.refSchema(), key.refTable(), key.refColumns(),
                        key.onUpdate(), key.onDelete()) + ";");
            }
        }
        if (statements.isEmpty()) {
            return 0;
        }
        w.write("\n-- ========== 外键 ==========\n");
        w.write("-- 排在所有表和数据之后：外键指向的表未必在自己前面，"
                + "而且先加约束会挡住插入。\n");
        for (String ddl : statements) {
            w.write(ddl);
            w.write("\n");
        }
        w.write("\n");
        return statements.size();
    }

    /**
     * 定时事件（目前只有 MySQL 的 EVENT）。
     *
     * <p>排在最后：它和触发器一样是「会自己跑起来的东西」，
     * 而且可能引用前面那些表。
     */
    private static int dumpEvents(DbConnection conn, String schema, Writer w,
                                  Consumer<String> progress, List<String> skipped)
            throws IOException {
        String listSql = conn.dialect().eventsQuery(schema);
        if (listSql == null) {
            return 0;   // 这一家没有事件这回事，不算遗漏
        }
        List<String> names = new ArrayList<>();
        try {
            conn.execute(listSql, 500).rows().forEach(r -> names.add(r.get(0)));
        } catch (RuntimeException e) {
            skipped.add("定时事件：读不到清单（" + e.getMessage() + "）");
            return 0;
        }
        if (names.isEmpty()) {
            return 0;
        }
        int done = 0;
        w.write("\n-- ========== 定时事件 ==========\n");
        for (String name : names) {
            progress.accept("正在备份事件 " + name);
            String sql = conn.dialect().objectDefinitionQuery(
                    com.plainly.driver.meta.DbObjects.ObjectKind.EVENT, schema, name);
            String body = null;
            if (sql != null) {
                try {
                    body = conn.scalar(sql);
                } catch (RuntimeException e) {
                    body = null;
                }
            }
            if (body == null || body.isBlank()) {
                skipped.add("事件 " + name + "：读不到定义");
                continue;
            }
            w.write("-- ---------- " + name + " ----------\n");
            w.write(body.strip());
            w.write("\n\n");
            done++;
        }
        return done;
    }

    /** 视图。定义取自方言的 viewDefinitionQuery，和「编辑视图定义」看到的是同一份。 */
    private static int dumpViews(DbConnection conn, String schema, Writer w,
                                 Consumer<String> progress, List<String> skipped)
            throws IOException {
        List<TableInfo> views = conn.listTables(schema).stream()
                .filter(t -> t.kind() == ObjectKind.VIEW)
                .toList();
        if (views.isEmpty()) {
            return 0;
        }
        int done = 0;
        w.write("\n-- ========== 视图 ==========\n");
        for (TableInfo view : views) {
            progress.accept("正在备份视图 " + view.name());
            String sql = conn.dialect().viewDefinitionQuery(schema, view.name());
            if (sql == null) {
                skipped.add("视图 " + view.name() + "：这个数据库读不出视图定义");
                continue;
            }
            String definition;
            try {
                definition = conn.scalar(sql);
            } catch (RuntimeException e) {
                skipped.add("视图 " + view.name() + "：" + e.getMessage());
                continue;
            }
            if (definition == null || definition.isBlank()) {
                skipped.add("视图 " + view.name() + "：读到的定义是空的");
                continue;
            }
            w.write(conn.dialect().createOrReplaceViewDdl(schema, view.name(), definition.strip()));
            w.write(";\n\n");
            done++;
        }
        return done;
    }

    /** 存储过程与函数。 */
    private static int dumpRoutines(DbConnection conn, String schema, Writer w,
                                    Consumer<String> progress, List<String> skipped)
            throws IOException {
        String listSql = conn.dialect().routineListQuery(schema);
        if (listSql == null) {
            return 0;   // 这一家没有存储过程这回事（SQLite、H2），不算遗漏
        }
        List<String[]> routines = new ArrayList<>();
        try {
            conn.execute(listSql, 2000).rows()
                    .forEach(r -> routines.add(new String[]{r.get(0), r.get(1)}));
        } catch (RuntimeException e) {
            skipped.add("存储过程与函数：读不到清单（" + e.getMessage() + "）");
            return 0;
        }
        if (routines.isEmpty()) {
            return 0;
        }

        int done = 0;
        w.write("\n-- ========== 存储过程与函数 ==========\n");
        for (String[] routine : routines) {
            progress.accept("正在备份 " + routine[0]);
            String source = routineSource(conn, schema, routine[0], routine[1]);
            if (source == null) {
                skipped.add("例程 " + routine[0] + "：读不到源码（多半是权限不够）");
                continue;
            }
            w.write("-- ---------- " + routine[0] + " ----------\n");
            w.write(source);
            // 例程体里本来就有分号，还原那一侧要整段执行，不能按分号切
            w.write("\n\n");
            done++;
        }
        return done;
    }

    /**
     * 取一个例程的完整源码。
     *
     * <p>结果按行拼接：Oracle 和达梦的 ALL_SOURCE 是一行一条记录，
     * 而 MySQL 的 SHOW CREATE 一行给全。同一段代码覆盖两种形状。
     */
    private static String routineSource(DbConnection conn, String schema,
                                        String name, String type) {
        String sql = conn.dialect().routineSourceQuery(schema, name, type);
        if (sql == null) {
            return null;
        }
        try {
            var result = conn.execute(sql, 20000);
            int column = conn.dialect().routineSourceColumn() - 1;
            if (result.rows().isEmpty() || column < 0 || column >= result.columns().size()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (var row : result.rows()) {
                String part = row.get(column);
                if (part != null) {
                    sb.append(part);
                }
            }
            String source = sb.toString().strip();
            if (source.isEmpty()) {
                return null;
            }
            // Oracle / 达梦给的正文以 PROCEDURE 开头，没有 CREATE——直接写进去是语法错
            return conn.dialect().routineSourceNeedsCreatePrefix()
                    ? "CREATE OR REPLACE " + source : source;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 触发器。放在最后，理由见 backup 里那段关于顺序的说明。 */
    private static int dumpTriggers(DbConnection conn, String schema, List<TableInfo> tables,
                                    Writer w, Consumer<String> progress, List<String> skipped)
            throws IOException {
        List<String> statements = new ArrayList<>();
        for (TableInfo table : tables) {
            var listing = conn.listTriggersDetailed(schema, table.name());
            if (listing.problem() != null) {
                skipped.add(table.name() + " 的触发器：" + listing.problem());
                continue;
            }
            for (var trigger : listing.triggers()) {
                progress.accept("正在备份触发器 " + trigger.name());
                String ddl = triggerDdl(conn, schema, table.name(), trigger);
                if (ddl == null) {
                    skipped.add("触发器 " + trigger.name() + "：这个数据库拼不出建触发器语句");
                    continue;
                }
                statements.add(ddl);
            }
        }
        if (statements.isEmpty()) {
            return 0;
        }
        w.write("\n-- ========== 触发器 ==========\n");
        w.write("-- 刻意排在数据之后：先建触发器的话，上面那些 INSERT 会把它们全触发一遍。\n");
        for (String ddl : statements) {
            w.write(ddl);
            w.write("\n\n");
        }
        return statements.size();
    }

    private static String triggerDdl(DbConnection conn, String schema, String table,
                                     com.plainly.driver.meta.DbObjects.TriggerInfo trigger) {
        // 元数据本来就是整条语句的（SQLite、达梦）：原样写出去
        if (conn.dialect().triggerActionIsFullStatement()) {
            String action = trigger.action();
            return action == null || action.isBlank() ? null : action.strip();
        }
        var timing = trigger.timingOf();
        var event = trigger.eventOf();
        if (timing == null || event == null) {
            return null;   // 认不出时机或事件就拼不出来，宁可如实记一笔
        }
        return conn.dialect().createTriggerDdl(schema, table, trigger.name(),
                timing, event, trigger.action() == null ? "" : trigger.action().strip());
    }

    /**
     * 数据段。
     *
     * <p>借用导出那条路径写到临时文件再拼进来：INSERT 的字面量与转义规则
     * 已经在那边验证过，重写一遍只会多一处能出错的地方。
     */
    private static long dumpData(DbConnection conn, String schema, String table, Writer w)
            throws IOException {
        Path temp = Files.createTempFile("plainly-backup-", ".sql");
        try {
            ExportOptions options = new ExportOptions()
                    .setFormat(ExportOptions.Format.SQL_INSERT)
                    .setTableName(table)
                    .setDialect(conn.dialect())
                    .setTarget(temp);
            long rows = Exporters.export(
                    RowSource.ofTable(conn, schema, table, null, 1000, -1), options, n -> { });
            if (rows > 0) {
                w.write(Files.readString(temp, StandardCharsets.UTF_8));
                w.write("\n");
            }
            return rows;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 还原。
     *
     * <p>逐条执行，任一条失败即停并如实报出是第几条——还原到一半的库比没还原更危险，
     * 用户必须知道停在哪里。
     */
    public static int restore(DbConnection conn, String schema, Path file,
                              Consumer<String> progress) {
        String script;
        try {
            script = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DbException("读备份文件失败：" + e.getMessage(), e);
        }
        conn.useSchema(schema);
        List<String> statements = SqlScript.split(script,
                conn.config().type() == com.plainly.driver.DbType.MYSQL);

        List<String> failures = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < statements.size(); i++) {
            try {
                conn.execute(statements.get(i), 0);
                done++;
                if (done % 200 == 0) {
                    progress.accept("已执行 " + done + " / " + statements.size() + " 条");
                }
            } catch (RuntimeException e) {
                failures.add("第 " + (i + 1) + " 条失败：" + e.getMessage());
                throw new DbException("还原停在第 " + (i + 1) + " 条（共 " + statements.size()
                        + " 条），前 " + done + " 条已经执行且已生效。\n" + e.getMessage(), e);
            }
        }
        return done;
    }
}
