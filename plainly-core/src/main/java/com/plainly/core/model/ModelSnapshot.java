package com.plainly.core.model;

import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.ddl.SchemaDiff;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型快照：把某一刻的库结构存下来，以后能跟别的时刻比。
 *
 * <p>比对复用 {@code SchemaDiff}——和结构同步是同一套引擎。
 * 差别只在于那边比的是「两个活着的库」，这边比的是「同一个库的两个时刻」。
 * 再写一套差异算法只会让两边慢慢长歪。
 *
 * <p>存成文本而不是二进制：能进版本库，能用 diff 工具看，也能手工改。
 * 一份看不懂的快照，等到要用的时候没人敢信它。
 */
public final class ModelSnapshot {

    private static final String HEADER = "# Plainly 模型快照 v1";

    private ModelSnapshot() {
    }

    /** 把一个库的结构写成快照文件。 */
    public static void save(DbConnection conn, String schema, Path file) {
        List<TableStructure> structures = read(conn, schema);
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("# 库：").append(schema).append('\n');
        sb.append("# 时间：").append(LocalDateTime.now()).append("\n\n");

        for (TableStructure structure : structures) {
            sb.append("TABLE\t").append(structure.table().name()).append('\n');
            for (ColumnInfo c : structure.columns()) {
                sb.append("COL\t").append(c.name()).append('\t').append(c.nativeType())
                        .append('\t').append(c.category().name())
                        .append('\t').append(c.precision()).append('\t').append(c.scale())
                        .append('\t').append(c.nullable()).append('\t').append(c.primaryKey())
                        .append('\t').append(c.autoIncrement())
                        .append('\t').append(nz(c.defaultValue()))
                        .append('\t').append(nz(c.comment()))
                        .append('\t').append(c.ordinal()).append('\n');
            }
            for (IndexInfo i : structure.indexes()) {
                sb.append("IDX\t").append(i.name()).append('\t')
                        .append(String.join(",", i.columns())).append('\t')
                        .append(i.unique()).append('\t').append(i.primary()).append('\n');
            }
            sb.append('\n');
        }

        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DbException("写快照失败：" + e.getMessage(), e);
        }
    }

    /** 读回一份快照。 */
    public static List<TableStructure> load(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DbException("读快照失败：" + e.getMessage(), e);
        }
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            throw new DbException("这不是 Plainly 的模型快照文件");
        }

        List<TableStructure> out = new ArrayList<>();
        String table = null;
        List<ColumnInfo> columns = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            switch (parts[0]) {
                case "TABLE":
                    if (table != null) {
                        out.add(build(table, columns, indexes));
                        columns = new ArrayList<>();
                        indexes = new ArrayList<>();
                    }
                    table = parts[1];
                    break;
                case "COL":
                    columns.add(new ColumnInfo(parts[1], parts[2],
                            TypeCategory.valueOf(parts[3]),
                            Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                            Boolean.parseBoolean(parts[6]), Boolean.parseBoolean(parts[7]),
                            Boolean.parseBoolean(parts[8]),
                            parts[9].isEmpty() ? null : parts[9], parts[10],
                            Integer.parseInt(parts[11])));
                    break;
                case "IDX":
                    indexes.add(new IndexInfo(parts[1],
                            parts[2].isEmpty() ? List.of() : List.of(parts[2].split(",")),
                            Boolean.parseBoolean(parts[3]), Boolean.parseBoolean(parts[4])));
                    break;
                default:
                    break;
            }
        }
        if (table != null) {
            out.add(build(table, columns, indexes));
        }
        return out;
    }

    /**
     * 比对两份结构，给出把 target 改成 source 的变更。
     *
     * <p>参数顺序和结构同步一致：第一个是「以它为准」的那份。
     */
    public static List<SchemaChange> compare(List<TableStructure> source,
                                             List<TableStructure> target) {
        return SchemaDiff.compute(source, target);
    }

    /** 当前库的结构，用来和快照比。 */
    public static List<TableStructure> read(DbConnection conn, String schema) {
        List<TableStructure> out = new ArrayList<>();
        for (TableInfo table : conn.listTables(schema)) {
            if (table.kind() != ObjectKind.TABLE) {
                continue;
            }
            out.add(conn.describeTable(schema, table.name()));
        }
        return out;
    }

    private static TableStructure build(String name, List<ColumnInfo> columns,
                                        List<IndexInfo> indexes) {
        return new TableStructure(
                new TableInfo("", name, ObjectKind.TABLE, "", -1),
                List.copyOf(columns), List.copyOf(indexes));
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
