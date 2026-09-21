package com.plainly.core.imports;

import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.query.ConflictPolicy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * 把文件写进表。
 *
 * <p>值全程是字符串，直到绑定那一刻才按目标列的类型转换一次——和网格、导出走的是同一条路。
 * 「按 double 解析」是用户可以选的另一条路，选了就会在解析阶段丢掉超过 15 位有效数字的部分；
 * 那是他的决定，但必须是明确的决定，所以这个选项在界面上写着后果。
 *
 * <p>逐行推送、分批提交，内存占用与文件行数无关：导入常常是几十万行起步。
 */
public final class Importer {

    /**
     * 导入结果。
     *
     * @param read 从文件里读出的数据行数（不含表头）
     * @param written 实际写进库的行数
     * @param failed 因为值转换或数据库拒绝而没写进去的行数
     * @param errorFile 失败行落盘的位置；没有失败行时为 null
     */
    public record Result(long read, long written, long failed, Path errorFile,
                         List<String> problems) {
    }

    private Importer() {
    }

    public static Result run(DbConnection conn, String schema, String table,
                             List<ColumnInfo> targetColumns, ImportOptions options,
                             LongConsumer progress) {
        List<ImportOptions.ColumnMapping> live = options.mappings().stream()
                .filter(m -> !m.skipped())
                .toList();
        if (live.isEmpty()) {
            throw new DbException("没有一列被映射到目标字段，无从写起");
        }

        List<ColumnInfo> columns = new ArrayList<>();
        for (ImportOptions.ColumnMapping m : live) {
            ColumnInfo info = lookup(targetColumns, m.targetColumn());
            if (info == null) {
                throw new DbException("目标表没有字段 " + m.targetColumn());
            }
            columns.add(info);
        }

        List<ColumnInfo> keys = targetColumns.stream().filter(ColumnInfo::primaryKey).toList();
        SqlDialect dialect = conn.dialect();
        SqlDialect.PreparedSql insert = dialect.buildInsert(
                schema, table, columns, keys, options.conflictPolicy());
        if (insert == null) {
            throw new DbException("当前数据库无法用「" + options.conflictPolicy().label()
                    + "」的方式处理主键冲突"
                    + (keys.isEmpty() ? "：目标表没有主键，冲突无从判定" : ""));
        }

        List<String> problems = new ArrayList<>();
        List<List<String>> batch = new ArrayList<>();
        List<List<String>> failedRows = new ArrayList<>();
        long[] counters = new long[3]; // read, written, failed

        Path errorFile = options.writeErrorFile() ? errorFileFor(options.source()) : null;

        boolean[] header = {options.headerRowPresent()};
        forEachRow(options, raw -> {
            if (header[0]) {
                header[0] = false;
                return;
            }
            if (raw.size() == 1 && raw.get(0).isBlank()) {
                return;
            }
            counters[0]++;

            List<String> values = new ArrayList<>(live.size());
            try {
                for (int i = 0; i < live.size(); i++) {
                    String cell = cell(raw, live.get(i).sourceIndex());
                    values.add(convert(cell, columns.get(i), options.numberMode()));
                }
            } catch (RuntimeException e) {
                counters[2]++;
                failedRows.add(raw);
                if (problems.size() < 20) {
                    problems.add("第 " + counters[0] + " 行：" + e.getMessage());
                }
                return;
            }

            batch.add(values);
            if (batch.size() >= options.batchSize()) {
                counters[1] += flush(conn, insert, batch, options, failedRows, problems, counters);
                progress.accept(counters[0]);
            }
        });

        if (!batch.isEmpty()) {
            counters[1] += flush(conn, insert, batch, options, failedRows, problems, counters);
        }
        progress.accept(counters[0]);

        Path written = null;
        if (errorFile != null && !failedRows.isEmpty()) {
            written = writeErrors(errorFile, failedRows, options);
        }
        return new Result(counters[0], counters[1], counters[2], written, problems);
    }

    /**
     * 按格式挑读取器，其余的路完全一样。
     *
     * <p>两种格式的差别到这里为止：往下的值转换、批量提交、失败行落盘、试运行，
     * 都是同一段代码。分叉越靠外，两条路各自长歪的可能就越小。
     */
    private static void forEachRow(ImportOptions options, java.util.function.Consumer<List<String>> consumer) {
        if (options.format() == ImportOptions.Format.JSON) {
            JsonRows.forEach(options.source(), options.charset(), consumer);
        } else {
            CsvParser.forEach(options.source(), options.delimiter(), options.charset(), consumer);
        }
    }

    /**
     * 一批写进去。
     *
     * <p>试运行只走到这里为止：语句拼好了、值也转换过了，就是不发出去。
     * 「试运行」如果真去写库，那它就不是试运行。
     */
    private static long flush(DbConnection conn, SqlDialect.PreparedSql insert,
                              List<List<String>> batch, ImportOptions options,
                              List<List<String>> failedRows, List<String> problems,
                              long[] counters) {
        int size = batch.size();
        if (options.dryRun()) {
            batch.clear();
            return size;
        }
        try {
            int affected = conn.executeBatch(insert, batch);
            batch.clear();
            // 跳过策略下受影响行数会小于提交行数，那是正常的
            return Math.max(affected, 0);
        } catch (RuntimeException e) {
            if (options.conflictPolicy() == ConflictPolicy.ABORT) {
                throw e;
            }
            // 整批失败时退回逐行，好把出错的那几行单独挑出来而不是丢掉一整批
            long ok = 0;
            for (List<String> row : batch) {
                try {
                    conn.executeBatch(insert, List.of(row));
                    ok++;
                } catch (RuntimeException single) {
                    counters[2]++;
                    failedRows.add(row);
                    if (problems.size() < 20) {
                        problems.add(single.getMessage());
                    }
                }
            }
            batch.clear();
            return ok;
        }
    }

    /**
     * 单元格 → 要绑定的值。
     *
     * <p>空串对可空列当作 NULL：CSV 里没有办法区分「空字符串」和「没有值」，
     * 而多数导出工具写出来的 NULL 就是空。列不可空时保留空串，让数据库自己拒绝，
     * 好过我们替它猜。
     */
    static String convert(String cell, ColumnInfo column, ImportOptions.NumberMode mode) {
        if (cell == null) {
            return null;
        }
        String trimmed = cell.trim();
        if (trimmed.isEmpty()) {
            return column.nullable() ? null : "";
        }
        if (!column.category().isNumeric()) {
            return cell;
        }
        if (mode == ImportOptions.NumberMode.DOUBLE) {
            // 用户选了这条路：先过一遍 double，超过 15 位有效数字的部分就在这里丢掉
            return BigDecimal.valueOf(Double.parseDouble(trimmed)).toPlainString();
        }
        // 只做一次合法性检查，值本身原样传下去
        new BigDecimal(trimmed);
        return trimmed;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : null;
    }

    private static ColumnInfo lookup(List<ColumnInfo> columns, String name) {
        for (ColumnInfo c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    static Path errorFileFor(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return source.resolveSibling(stem + ".err.csv");
    }

    /** 失败行原样写回一个 .err.csv，改完可以直接拿它再导一次。 */
    private static Path writeErrors(Path target, List<List<String>> rows, ImportOptions options) {
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(target, options.charset()))) {
            for (List<String> row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        sb.append(options.delimiter());
                    }
                    sb.append(quote(row.get(i), options.delimiter()));
                }
                w.write(sb.append('\n').toString());
            }
        } catch (IOException e) {
            throw new DbException("写失败行文件失败：" + e.getMessage(), e);
        }
        return target;
    }

    private static String quote(String value, char delimiter) {
        if (value == null) {
            return "";
        }
        boolean needs = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needs ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
