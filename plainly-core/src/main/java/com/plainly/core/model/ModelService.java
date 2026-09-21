package com.plainly.core.model;

import com.plainly.driver.DbConnection;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects.ObjectKind;
import com.plainly.driver.meta.DbObjects.TableInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 由现有库逆向出数据模型。
 *
 * <p>关系有两个来源：外键约束是确定的；此外还按命名推测——{@code order_id} 指向 {@code orders}。
 * 推测出来的关系单独标记，画成虚线。历史库里漏建外键很常见，
 * 把这类关系指出来有用；但把推测说成事实就有害了，所以两者从数据结构上就分开。
 */
public final class ModelService {

    /** 模型里的一张表。 */
    public record Entity(String name, long rows, List<ColumnInfo> columns,
                         List<String> primaryKey) {
    }

    /**
     * 一条关系。
     *
     * @param confirmed true 表示有外键约束支撑；false 表示只是按命名推测
     */
    public record Relation(String fromTable, List<String> fromColumns,
                           String toTable, List<String> toColumns,
                           boolean confirmed, String note) {
    }

    /** 一份模型。 */
    public record Model(String schema, List<Entity> entities, List<Relation> relations) {

        public long guessed() {
            return relations.stream().filter(r -> !r.confirmed()).count();
        }
    }

    private ModelService() {
    }

    /**
     * 逆向整个库。
     *
     * @param limit 最多纳入多少张表；表太多时图就没法看了，超出的留给用户自己挑
     */
    public static Model reverse(DbConnection conn, String schema, int limit) {
        return reverse(conn, schema, limit, List.of());
    }

    /** 逆向整个库，并带上本机标注的虚拟外键。 */
    public static Model reverse(DbConnection conn, String schema, int limit,
                                List<ForeignKeyInfo> virtualKeys) {
        List<TableInfo> tables = conn.listTables(schema).stream()
                .filter(t -> t.kind() == ObjectKind.TABLE)
                .limit(limit)
                .toList();
        return reverse(conn, schema, tables.stream().map(TableInfo::name).toList(), virtualKeys);
    }

    /** 逆向指定的几张表。 */
    public static Model reverse(DbConnection conn, String schema, List<String> tableNames) {
        return reverse(conn, schema, tableNames, List.of());
    }

    /**
     * 逆向指定的几张表，并把本机标注的虚拟外键一起算进来。
     *
     * <p>虚拟外键算<b>确定</b>的关系（实线）而不是推测：它是人明确标下来的，
     * 和「按命名猜出来的」不是一回事。图上把两者混在一起，
     * 用户就没法区分「我确认过」和「工具猜的」。
     *
     * @param virtualKeys 本机标注的关系，由调用方从 {@code VirtualKeyStore} 取来。
     *                    core 的模型层不认识连接注册表，所以由外面传进来
     */
    public static Model reverse(DbConnection conn, String schema, List<String> tableNames,
                                List<ForeignKeyInfo> virtualKeys) {
        List<Entity> entities = new ArrayList<>();
        Map<String, TableStructure> structures = new LinkedHashMap<>();

        for (String name : tableNames) {
            TableStructure structure = conn.describeTable(schema, name);
            structures.put(name.toLowerCase(Locale.ROOT), structure);
            long rows = -1;
            entities.add(new Entity(name, rows, structure.columns(),
                    structure.primaryKeyColumns()));
        }

        List<Relation> relations = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();

        for (String name : tableNames) {
            for (ForeignKeyInfo fk : conn.listForeignKeys(schema, name)) {
                if (!structures.containsKey(fk.refTable().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                relations.add(new Relation(name, fk.columns(), fk.refTable(), fk.refColumns(),
                        true, "外键约束 " + fk.name()));
                fk.columns().forEach(c -> covered.add(key(name, c)));
            }
        }

        for (ForeignKeyInfo fk : virtualKeys) {
            if (!structures.containsKey(fk.table().toLowerCase(Locale.ROOT))
                    || !structures.containsKey(fk.refTable().toLowerCase(Locale.ROOT))) {
                continue;   // 标注指向的表这次没纳进来，画不出这条线
            }
            relations.add(new Relation(fk.table(), fk.columns(), fk.refTable(), fk.refColumns(),
                    true, fk.name()));
            // 标注过的列不必再去猜一遍——猜出来的那条会和标注的重叠，图上画成两根线
            fk.columns().forEach(c -> covered.add(key(fk.table(), c)));
        }

        relations.addAll(guessRelations(structures, covered));
        return new Model(schema, entities, relations);
    }

    /**
     * 按命名推测关系。
     *
     * <p>只认最常见的一种写法：列名以 {@code _id} 结尾，去掉后缀之后能对上某张表
     * （单复数都试一下），而且那张表有单列主键。规则刻意保守——
     * 猜错一条关系比漏掉一条更糟，图上画出来的东西会被当成事实。
     */
    static List<Relation> guessRelations(Map<String, TableStructure> structures,
                                         Set<String> covered) {
        List<Relation> out = new ArrayList<>();
        for (Map.Entry<String, TableStructure> entry : structures.entrySet()) {
            String table = entry.getValue().table().name();
            for (ColumnInfo column : entry.getValue().columns()) {
                String lower = column.name().toLowerCase(Locale.ROOT);
                if (!lower.endsWith("_id") && !lower.endsWith("id")) {
                    continue;
                }
                if (covered.contains(key(table, column.name()))) {
                    continue;
                }
                String stem = lower.endsWith("_id")
                        ? lower.substring(0, lower.length() - 3)
                        : lower.substring(0, lower.length() - 2);
                if (stem.isEmpty()) {
                    continue;
                }
                TableStructure target = findTable(structures, stem);
                if (target == null || target.table().name().equalsIgnoreCase(table)) {
                    continue;
                }
                List<String> pk = target.primaryKeyColumns();
                if (pk.size() != 1) {
                    continue;
                }
                out.add(new Relation(table, List.of(column.name()),
                        target.table().name(), pk, false,
                        "按命名推测：" + column.name() + " → " + target.table().name()
                                + "，库里没有对应外键约束"));
            }
        }
        return out;
    }

    /** 单复数都试：{@code user_id} 可能指向 {@code user}，也可能指向 {@code users}。 */
    private static TableStructure findTable(Map<String, TableStructure> structures, String stem) {
        TableStructure exact = structures.get(stem);
        if (exact != null) {
            return exact;
        }
        TableStructure plural = structures.get(stem + "s");
        if (plural != null) {
            return plural;
        }
        if (stem.endsWith("s")) {
            return structures.get(stem.substring(0, stem.length() - 1));
        }
        return null;
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    /**
     * 由模型生成建库脚本。
     *
     * <p>先全部建表，再统一加外键——建表顺序无法保证被引用的表先建好，
     * 分两段走就绕开了这个问题。
     */
    public static List<String> buildScript(DbConnection conn, Model model) {
        List<String> out = new ArrayList<>();
        for (Entity entity : model.entities()) {
            TableStructure structure = conn.describeTable(model.schema(), entity.name());
            out.add(conn.dialect().createTableDdl(model.schema(), structure));
        }
        for (Relation relation : model.relations()) {
            if (!relation.confirmed()) {
                // 推测出来的关系不写进脚本：那是给人看的线索，不是库里真有的约束
                continue;
            }
            out.add("ALTER TABLE " + conn.dialect().qualify(model.schema(), relation.fromTable())
                    + " ADD FOREIGN KEY (" + quoteAll(conn, relation.fromColumns()) + ")"
                    + " REFERENCES " + conn.dialect().qualify(model.schema(), relation.toTable())
                    + " (" + quoteAll(conn, relation.toColumns()) + ")");
        }
        return out;
    }

    private static String quoteAll(DbConnection conn, List<String> names) {
        List<String> quoted = new ArrayList<>();
        names.forEach(n -> quoted.add(conn.dialect().quote(n)));
        return String.join(", ", quoted);
    }
}
