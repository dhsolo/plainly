package com.plainly.driver;

import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.SchemaChange;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.query.ConflictPolicy;
import com.plainly.driver.query.FilterSpec;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;

/**
 * 各数据库的 SQL 方言差异。
 *
 * <p>所有写回语句一律走参数化。<b>绝不把值拼进 SQL 字符串</b>——
 * 那既是注入问题，也是精度问题（拼接必须先把值转成某种字面量表示，转换途中就可能被截断）。
 */
public interface SqlDialect {

    /** 标识符引用，如 MySQL 的反引号、PostgreSQL 的双引号。 */
    String quote(String identifier);

    /** 分页片段，如 {@code LIMIT 200 OFFSET 400}。 */
    String limitOffset(int limit, int offset);

    /**
     * 绑定占位符表达式。默认是 {@code ?}；
     * 方言可以返回 {@code CAST(? AS timestamp)} 之类来消除服务端隐式转换的歧义。
     */
    default String placeholder(ColumnInfo column) {
        return "?";
    }

    default String qualify(String schema, String table) {
        if (schema == null || schema.isBlank()) {
            return quote(table);
        }
        return quote(schema) + "." + quote(table);
    }

    /** 浏览一张表的分页查询。 */
    default String selectPage(String schema, String table, String orderBy, int limit, int offset) {
        StringBuilder sb = new StringBuilder("SELECT * FROM ").append(qualify(schema, table));
        if (orderBy != null && !orderBy.isBlank()) {
            sb.append(" ORDER BY ").append(orderBy);
        }
        sb.append(' ').append(limitOffset(limit, offset));
        return sb.toString();
    }

    default String countRows(String schema, String table) {
        return "SELECT COUNT(*) FROM " + qualify(schema, table);
    }

    /**
     * 带筛选与排序的分页取数。
     *
     * <p>值全部走占位符：{@code boundColumns} 按顺序给出每个占位符对应的列，
     * 绑定时才按列的类型做唯一一次转换。把值拼进 SQL 文本，长数值要么被驱动按
     * double 解析、要么被数据库按字面量推断类型，两条路都会丢精度。
     *
     * @param columns 表的列，用来给占位符找到类型；找不到的列会被跳过
     */
    default PreparedSql selectPage(String schema, String table, FilterSpec filter,
                                   List<ColumnInfo> columns, int limit, int offset) {
        StringBuilder sb = new StringBuilder("SELECT * FROM ").append(qualify(schema, table));
        List<ColumnInfo> bound = new ArrayList<>();

        List<FilterSpec.Condition> conditions = filter.conditions().stream()
                .filter(c -> lookup(columns, c.column()) != null)
                .toList();
        for (int i = 0; i < conditions.size(); i++) {
            FilterSpec.Condition c = conditions.get(i);
            ColumnInfo info = lookup(columns, c.column());
            sb.append(i == 0 ? " WHERE " : " " + c.combiner().name() + " ");
            sb.append(quote(info.name())).append(' ').append(c.operator().sql());
            if (c.operator().arity() == 1) {
                sb.append(" ").append(placeholder(info));
                bound.add(info);
            } else if (c.operator().arity() == 2) {
                sb.append(" ").append(placeholder(info)).append(" AND ").append(placeholder(info));
                bound.add(info);
                bound.add(info);
            }
        }

        List<FilterSpec.Sort> sorts = filter.sorts().stream()
                .filter(o -> lookup(columns, o.column()) != null)
                .toList();
        for (int i = 0; i < sorts.size(); i++) {
            sb.append(i == 0 ? " ORDER BY " : ", ");
            sb.append(quote(lookup(columns, sorts.get(i).column()).name()));
            if (sorts.get(i).descending()) {
                sb.append(" DESC");
            }
        }

        sb.append(' ').append(limitOffset(limit, offset));
        return new PreparedSql(sb.toString(), bound);
    }

    /** 带筛选的计数。分页器上的总数必须和筛选后的结果一致，否则翻页会翻到空页。 */
    default PreparedSql countRows(String schema, String table, FilterSpec filter,
                                  List<ColumnInfo> columns) {
        PreparedSql page = selectPage(schema, table, new FilterSpec(filter.conditions(), List.of()),
                columns, 0, 0);
        String sql = page.sql();
        int where = sql.indexOf(" WHERE ");
        String tail = where < 0 ? "" : sql.substring(where);
        // limitOffset 可能在末尾追加了分页子句，计数不需要
        int limitAt = tail.toUpperCase(java.util.Locale.ROOT).indexOf(" LIMIT ");
        if (limitAt >= 0) {
            tail = tail.substring(0, limitAt);
        }
        return new PreparedSql("SELECT COUNT(*) FROM " + qualify(schema, table) + tail,
                page.boundColumns());
    }

    private static ColumnInfo lookup(List<ColumnInfo> columns, String name) {
        for (ColumnInfo c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 生成一条 UPDATE。
     *
     * @param setColumns 要写入的列
     * @param keyColumns 定位行的键列，必须非空——取不到键就不该允许编辑
     * @return 参数顺序为：先 setColumns，后 keyColumns
     */
    default PreparedSql buildUpdate(String schema, String table,
                                    List<ColumnInfo> setColumns, List<ColumnInfo> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new DbException("无法生成 UPDATE：该结果集定位不到主键或唯一键");
        }
        StringBuilder sb = new StringBuilder("UPDATE ").append(qualify(schema, table)).append(" SET ");
        List<ColumnInfo> order = new ArrayList<>();
        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ColumnInfo c = setColumns.get(i);
            sb.append(quote(c.name())).append(" = ").append(placeholder(c));
            order.add(c);
        }
        sb.append(" WHERE ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            ColumnInfo c = keyColumns.get(i);
            sb.append(quote(c.name())).append(" = ").append(placeholder(c));
            order.add(c);
        }
        return new PreparedSql(sb.toString(), order);
    }

    default PreparedSql buildDelete(String schema, String table, List<ColumnInfo> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new DbException("无法生成 DELETE：该结果集定位不到主键或唯一键");
        }
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(qualify(schema, table)).append(" WHERE ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(quote(keyColumns.get(i).name())).append(" = ").append(placeholder(keyColumns.get(i)));
        }
        return new PreparedSql(sb.toString(), List.copyOf(keyColumns));
    }

    default PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(qualify(schema, table)).append(" (");
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
                vals.append(", ");
            }
            sb.append(quote(columns.get(i).name()));
            vals.append(placeholder(columns.get(i)));
        }
        sb.append(") VALUES (").append(vals).append(')');
        return new PreparedSql(sb.toString(), List.copyOf(columns));
    }

    /**
     * 带冲突策略的插入。
     *
     * <p>各家的写法差得很远：MySQL 是 {@code INSERT IGNORE} 与
     * {@code ON DUPLICATE KEY UPDATE}，PostgreSQL 和 SQLite 是 {@code ON CONFLICT}，
     * H2 走 {@code MERGE}。装作都一样只会生成跑不通的语句，所以由方言各自实现，
     * 做不到的直接返回 null，由调用方明说而不是偷偷降级成普通插入。
     *
     * <p>参数顺序与 {@code columns} 一致——三种写法都不需要额外的占位符。
     *
     * @param keyColumns 判定冲突用的键，通常是主键
     * @return 语句；本方言支持不了这个策略时返回 null
     */
    default PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns,
                                    List<ColumnInfo> keyColumns, ConflictPolicy policy) {
        if (policy == ConflictPolicy.ABORT) {
            return buildInsert(schema, table, columns);
        }
        if (keyColumns.isEmpty()) {
            return null;
        }
        // H2：MERGE ... KEY(...) 就是它的 upsert；SKIP 用标准 MERGE 的 NOT MATCHED 分支
        if (policy == ConflictPolicy.UPDATE) {
            StringBuilder sb = new StringBuilder("MERGE INTO ").append(qualify(schema, table))
                    .append(" (");
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                    vals.append(", ");
                }
                sb.append(quote(columns.get(i).name()));
                vals.append(placeholder(columns.get(i)));
            }
            sb.append(") KEY (");
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(quote(keyColumns.get(i).name()));
            }
            sb.append(") VALUES (").append(vals).append(')');
            return new PreparedSql(sb.toString(), List.copyOf(columns));
        }

        StringBuilder using = new StringBuilder();
        StringBuilder names = new StringBuilder();
        StringBuilder inserts = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                using.append(", ");
                names.append(", ");
                inserts.append(", ");
            }
            using.append(placeholder(columns.get(i)));
            names.append(quote(columns.get(i).name()));
            inserts.append("S.").append(quote(columns.get(i).name()));
        }
        StringBuilder on = new StringBuilder();
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                on.append(" AND ");
            }
            String key = quote(keyColumns.get(i).name());
            on.append("T.").append(key).append(" = S.").append(key);
        }
        String sql = "MERGE INTO " + qualify(schema, table) + " T"
                + " USING (VALUES (" + using + ")) S (" + names + ")"
                + " ON " + on
                + " WHEN NOT MATCHED THEN INSERT (" + names + ") VALUES (" + inserts + ")";
        return new PreparedSql(sql, List.copyOf(columns));
    }

    // ------------------------------------------------------------------ DDL

    /**
     * 本方言是否支持这类结构变更。
     *
     * <p>SQLite 是主要的例外：它只能加列、删列、改名，<b>改不了列定义</b>。
     * 这个方法存在的意义是让界面提前禁用，而不是生成一条注定失败的 SQL
     * 让用户点了「应用」才发现。
     */
    default boolean supports(TableChange change) {
        return true;
    }

    /** 不支持时给出的解释，用于界面提示。 */
    default String unsupportedReason(TableChange change) {
        return "当前数据库不支持该变更";
    }

    /**
     * 字段定义片段，如 {@code `amount` DECIMAL(38,10) NOT NULL DEFAULT 0}。
     * 各家对自增、注释的写法不同，由方言各自决定。
     */
    default String columnDefinition(ColumnDraft column) {
        StringBuilder sb = new StringBuilder(quote(column.name())).append(' ')
                .append(column.fullType());
        sb.append(column.nullable() ? " NULL" : " NOT NULL");
        // 空白也当没有默认值：写出 "DEFAULT " 后面什么都不跟，是一条语法错误的语句，
        // 而且要等发到数据库才炸。真正的「默认空字符串」经过 normalizeDefault
        // 之后是 '' 两个字符，不是空白，不会被这里挡掉
        if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
            sb.append(" DEFAULT ").append(column.defaultValue());
        }
        return sb.toString();
    }

    /**
     * 把驱动给的 {@code COLUMN_DEF} 归一成一段<b>能直接拼进 DDL 的字面量</b>。
     *
     * <h2>为什么必须有这一步</h2>
     * {@link ColumnInfo#defaultValue()} 的契约是「字面量文本」，但各家驱动给的东西
     * 根本不是一回事：
     * <ul>
     *   <li>H2 / PostgreSQL 给的是字面量：{@code 'CNY'}、{@code 'CNY'::character varying}；</li>
     *   <li><b>MySQL 给的是原始值</b>：默认是空串就返回空串，默认是
     *       {@code http://a/b.png} 就原样返回，<b>不带引号</b>。</li>
     * </ul>
     * 直接拼进 {@code ALTER TABLE ... DEFAULT} 就会拼出
     * {@code DEFAULT } 和 {@code DEFAULT http://a/b.png} 这种跑不通的语句——
     * 而且是在结构同步点下「应用」之后才在数据库那边炸。
     *
     * <p>默认实现原样返回（H2 / PostgreSQL / SQLite 本来就给字面量）。
     * 需要加工的那一家自己重写。
     *
     * @param raw      驱动返回的 {@code COLUMN_DEF}，可能为 null
     * @param category 这一列的语义类别，决定要不要加引号
     */
    default String normalizeDefault(String raw, TypeCategory category) {
        return raw;
    }

    /** 生成一条变更对应的 SQL，可能是多条语句。 */
    List<TableChangeSql> ddlFor(String schema, String table, TableChange change);

    /** 按顺序生成整批变更的 SQL。 */
    default List<TableChangeSql> ddlFor(String schema, String table, List<TableChange> changes) {
        List<TableChangeSql> out = new ArrayList<>();
        for (TableChange change : changes) {
            if (!supports(change)) {
                throw new DbException(unsupportedReason(change) + "：" + change.describe());
            }
            out.addAll(ddlFor(schema, table, change));
        }
        return out;
    }

    /** 一条 DDL 语句，连同它来自哪个变更——预览里按变更分组显示。 */
    record TableChangeSql(TableChange change, String sql) {
    }

    // ------------------------------------------------------------- 库级 DDL

    /** 建表。字段定义复用 {@link #columnDefinition}，各家的自增与注释写法自动生效。 */
    default String createTableDdl(String schema, TableStructure table) {
        List<ColumnDraft> drafts = new ArrayList<>();
        for (ColumnInfo c : table.columns()) {
            drafts.add(ColumnDraft.of(c));
        }
        return createTableDdl(schema, table.table().name(), drafts, table.primaryKeyColumns());
    }

    /**
     * 用草稿建表。
     *
     * <p>新建表的时候列还不在数据库里，手上只有 {@link ColumnDraft}——
     * 和「照着现有表生成建表语句」是同一件事的两种入口，所以共用同一段拼装，
     * 免得两处各写各的、慢慢长歪。
     */
    default String createTableDdl(String schema, String table,
                                  List<ColumnDraft> columns, List<String> primaryKey) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ")
                .append(qualify(schema, table)).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            sb.append("  ").append(columnDefinition(columns.get(i)));
            if (i < columns.size() - 1 || !primaryKey.isEmpty()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (!primaryKey.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < primaryKey.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(quote(primaryKey.get(i)));
            }
            sb.append(")\n");
        }
        return sb.append(')').toString();
    }

    /**
     * 把自增计数器推到 {@code next}，让下一条插入从这里开始。不需要时返回 null。
     *
     * <h2>为什么复制完表必须做这一步</h2>
     * 带数据复制时，主键值是<b>连同数据一起显式插入</b>的。多数库的自增计数器
     * 不会因为显式插入而前移——新表里已经有 1..25 号，计数器却仍停在 1。
     * 于是复制出来的表看着一切正常，直到有人往里插第一条新数据，
     * 撞上主键冲突。而那时候没人会想到是复制那一步留下的。
     *
     * <p>默认给 SQL 标准的写法（H2 2.x、PostgreSQL 10+ 都认）。
     *
     * @param next 下一个该发出去的值，通常是「已有最大值 + 1」
     */
    default String restartAutoIncrementDdl(String schema, String table, String column, long next) {
        return "ALTER TABLE " + qualify(schema, table)
                + " ALTER COLUMN " + quote(column) + " RESTART WITH " + next;
    }

    /**
     * 为什么这一家的建表语句写不出自增列；能写就返回 null。
     *
     * <p>存在的理由和 {@code triggerUnsupportedReason} 一样：做不到的事必须说出来。
     * 自增丢了不会当场报错——表建得出来，要等第一条不带主键值的 INSERT 撞上非空约束，
     * 那时用户已经很难把它和建表时的这一步联系起来了。
     */
    default String autoIncrementUnsupportedReason() {
        return null;
    }

    /**
     * 查一个库里各表行数估算的语句：第一列表名，第二列行数。取不到时返回 null。
     *
     * <h2>为什么是估算，不是 COUNT(*)</h2>
     * 树上展开一个库要显示上百张表的行数。逐表 {@code COUNT(*)} 是上百次全表扫描——
     * 在真正需要知道行数的那种库（几千万行）上，这一下能把服务器压垮，
     * 而用户只是想展开看看有哪些表。
     *
     * <p>各家的统计信息本来就摆在那里（{@code information_schema.TABLES.TABLE_ROWS}、
     * {@code pg_class.reltuples}、{@code ALL_TABLES.NUM_ROWS}），一条语句取回整个库，
     * 代价是一次往返。
     *
     * <h2>它有多不准，以及为什么仍然值得显示</h2>
     * InnoDB 的 {@code TABLE_ROWS} 是采样估的，偏差可以到几成；PostgreSQL 的
     * {@code reltuples} 要 ANALYZE 过才有意义，没跑过是 -1。所以它<b>不能</b>用来
     * 做任何计算或判断，只能用来回答「这张表是空的、几百行、还是上千万行」——
     * 而这恰恰是打开一张表之前最想知道的事，且数量级上它几乎总是对的。
     *
     * <p>界面上必须说明这是估算值，否则用户会拿它去对账。
     */
    default String tableRowCountQuery(String schema) {
        return null;
    }

    /**
     * 查序列的语句，按「名称、说明」两列返回；这一家没有序列时返回 null。
     *
     * <p>默认给的是 SQL 标准的 {@code information_schema.sequences}——
     * PostgreSQL、H2 都认。MySQL 没有序列这回事，它的方言里覆写成 null；
     * Oracle 和达梦用各自的数据字典视图。
     *
     * <p>「说明」那一列拼的是当前值和步长这类信息，给树上的那一行做副标题用。
     * 拼不出来就返回空串，不要编。
     */
    default String sequencesQuery(String schema) {
        return "SELECT SEQUENCE_NAME,"
                + " CONCAT('起始 ', START_VALUE, ' · 步长 ', INCREMENT)"
                + " FROM INFORMATION_SCHEMA.SEQUENCES"
                + " WHERE SEQUENCE_SCHEMA = " + stringLiteral(schema)
                + " ORDER BY SEQUENCE_NAME";
    }

    /**
     * 序列的属性，够把它<b>原样重建</b>出来。
     *
     * <p>值一律是字符串。序列可以超出 {@code long}——Oracle 的序列默认上限是
     * 28 位十进制，塞进 long 会溢出，而溢出之后恢复出来的序列会从一个错的数字开始发号。
     *
     * @param start     恢复时的起始值，也就是<b>当前该发的下一个号</b>，
     *                  不是当初建序列时写的那个起始值。见 {@link #createSequenceDdl}
     * @param cycle     到顶之后是否回绕
     */
    record SequenceInfo(String name, String start, String increment,
                        String minValue, String maxValue, boolean cycle) {
    }

    /**
     * 查序列属性的语句，按
     * 「名称、起始值、步长、最小值、最大值、是否回绕」六列返回；这一家没有序列时返回 null。
     *
     * <h2>和 {@link #sequencesQuery} 的分工</h2>
     * 那个是给<b>界面</b>用的，第二列是拼给人看的一句话；这个是给<b>备份</b>用的，
     * 每一列都要能原样填进 DDL。两者查的往往是同一个视图，但取的列和用途完全不同，
     * 合成一个的话，其中一边迟早要去解析另一边拼好的那句话。
     *
     * <p><b>第二列必须是「下一个该发的号」</b>，不是建序列时的起始值。
     * 取错了不会有任何报错：恢复出来的序列会从一个已经用过的数字重新开始发号，
     * 直到某次插入撞上主键冲突——而那时离恢复已经过去很久了。
     */
    default String sequenceAttributesQuery(String schema) {
        return null;
    }

    /**
     * 拼一条重建序列的语句。
     *
     * <p>用 {@code START WITH} 而不是建完再 {@code setval}：一条语句说完一件事，
     * 而且 START WITH 是 SQL 标准里各家都认的写法。
     */
    default String createSequenceDdl(String schema, SequenceInfo seq) {
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE ")
                .append(qualify(schema, seq.name()));
        if (seq.start() != null && !seq.start().isBlank()) {
            sb.append(" START WITH ").append(seq.start());
        }
        if (seq.increment() != null && !seq.increment().isBlank()) {
            sb.append(" INCREMENT BY ").append(seq.increment());
        }
        if (seq.minValue() != null && !seq.minValue().isBlank()) {
            sb.append(" MINVALUE ").append(seq.minValue());
        }
        if (seq.maxValue() != null && !seq.maxValue().isBlank()) {
            sb.append(" MAXVALUE ").append(seq.maxValue());
        }
        sb.append(seq.cycle() ? " CYCLE" : " NOCYCLE");
        return sb.toString();
    }

    /**
     * 查定时事件的语句，按「名称、说明」两列返回；不支持时返回 null。
     *
     * <p>只有 MySQL 有 EVENT。默认返回 null——给一个不存在的对象类别建一个空分组，
     * 用户会以为是没加载出来然后反复刷新。
     */
    default String eventsQuery(String schema) {
        return null;
    }

    /**
     * 一个序列 / 事件的定义文本，用于「查看定义」。不支持时返回 null。
     */
    default String objectDefinitionQuery(com.plainly.driver.meta.DbObjects.ObjectKind kind,
                                         String schema, String name) {
        return null;
    }

    /**
     * 列出存储过程与函数，按「名称、类型」两列返回；不支持时返回 null。
     *
     * <p>{@code 类型} 用于挑出对应的取源码语句，取值是各家自己的写法
     * （MySQL 的 {@code PROCEDURE} / {@code FUNCTION}）。
     */
    default String routineListQuery(String schema) {
        return null;
    }

    /**
     * 取一个例程的<b>完整</b> CREATE 语句。
     *
     * <h2>为什么不能用 information_schema.ROUTINES</h2>
     * 在 MySQL 8.0.22 的实例上验过：{@code ROUTINE_DEFINITION} 给的<b>只有函数体</b>
     * （{@code BEGIN ... END}），没有名字、没有参数列表。拿它写进备份文件，
     * 文件看着有内容，还原时却什么也建不出来——比漏掉这一段更糟，
     * 因为用户以为自己有备份。
     *
     * <p>完整语句要靠 {@code SHOW CREATE PROCEDURE}，一次一个对象。
     *
     * <p>结果可能是<b>多行</b>：Oracle 和达梦的 {@code ALL_SOURCE} 按行存源码，
     * 调用方需要把各行按顺序拼起来。所以这里的约定是
     * 「把结果集里 {@link #routineSourceColumn()} 那一列<b>按行顺序拼接</b>」，
     * 单行和多行两种形状都能用同一段代码处理。
     */
    default String routineSourceQuery(String schema, String name, String routineType) {
        return null;
    }

    /**
     * 例程源码在结果集里的第几列（从 1 数）。
     *
     * <p>多数家是第一列。MySQL 是<b>第三列</b>——{@code SHOW CREATE PROCEDURE}
     * 返回的是「过程名、sql_mode、创建语句、三个字符集列」，源码夹在中间。
     * 写死第一列的话，备份文件里存下来的会是过程名或者 sql_mode，
     * 而这两样都不会报错，只会在还原时莫名其妙。
     */
    default int routineSourceColumn() {
        return 1;
    }

    /**
     * 取回来的源码需不需要自己补上 {@code CREATE OR REPLACE } 前缀。
     *
     * <p>Oracle 和达梦的 {@code ALL_SOURCE} 存的是 {@code PROCEDURE 名字(...)} 开头的正文，
     * <b>不含</b> {@code CREATE} 这个词——直接写进备份文件，还原时是一句语法错误。
     * 而 MySQL 的 {@code SHOW CREATE} 给的本来就是完整语句，再补一个前缀反而错了。
     *
     * <p>这件事必须由方言声明，不能让调用方去猜「这段文本像不像完整语句」——
     * 猜错的两个方向都会产出一个还原不回去的备份。
     */
    default boolean routineSourceNeedsCreatePrefix() {
        return false;
    }

    /**
     * 查触发器的语句，按「名称、时机、事件、动作」四列返回；不支持时返回 null。
     *
     * <p>JDBC 没有触发器的元数据接口，只能各家自己查。这里给的是 SQL 标准的
     * {@code INFORMATION_SCHEMA.TRIGGERS}，MySQL 直接可用。
     */
    default String triggersQuery(String schema, String table) {
        return "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT"
                + " FROM INFORMATION_SCHEMA.TRIGGERS"
                + " WHERE EVENT_OBJECT_SCHEMA = " + stringLiteral(schema)
                + " AND EVENT_OBJECT_TABLE = " + stringLiteral(table)
                + " ORDER BY TRIGGER_NAME";
    }

    /**
     * 取视图定义的语句，结果取第一列。不支持时返回 null。
     *
     * <p>各家都在 {@code information_schema.views} 里放了 {@code view_definition}，
     * 但 MySQL 给的是完整的 SELECT，PostgreSQL 给的也是——这一条难得地一致。
     */
    default String viewDefinitionQuery(String schema, String view) {
        return "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS"
                + " WHERE TABLE_SCHEMA = " + stringLiteral(schema)
                + " AND TABLE_NAME = " + stringLiteral(view);
    }

    /**
     * 建或改一个视图。
     *
     * <p>用 {@code CREATE OR REPLACE}：改视图的语义就是整体替换，
     * 先 DROP 再 CREATE 会在两条语句之间留下一段「视图不存在」的窗口，
     * 那期间别人的查询会直接报错。
     */
    default String createOrReplaceViewDdl(String schema, String view, String selectSql) {
        return "CREATE OR REPLACE VIEW " + qualify(schema, view) + " AS\n" + selectSql;
    }

    default String dropViewDdl(String schema, String view) {
        return "DROP VIEW " + qualify(schema, view);
    }

    /**
     * 取执行计划的语句。
     *
     * <p>各家的 EXPLAIN 输出格式完全不同——MySQL 给一张表，PostgreSQL 给一段文本，
     * H2 给一行字符串。所以这里只负责给出该发什么语句，怎么呈现交给界面按结果集来。
     * 不支持时返回 null。
     */
    default String explainQuery(String sql) {
        return "EXPLAIN " + sql;
    }

    /** 字符串字面量。元数据查询里要把库名表名当值传，不能走标识符引号。 */
    default String stringLiteral(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    default String dropTableDdl(String schema, String table) {
        return "DROP TABLE " + qualify(schema, table);
    }

    /**
     * 清空表。
     *
     * <p>默认用 {@code TRUNCATE TABLE}。它和 {@code DELETE FROM} 不是一回事，
     * 差别都有实际后果，所以 {@link #truncateNote()} 会把这一家的具体行为讲出来，
     * 让界面照实说，而不是笼统写一句「清空数据」。
     */
    default String truncateTableDdl(String schema, String table) {
        return "TRUNCATE TABLE " + qualify(schema, table);
    }

    /** 在这一家上，「清空表」到底会发生什么。界面照原样显示。 */
    default String truncateNote() {
        return "TRUNCATE 会立即清空全表且无法回滚，自增计数器归零，行级触发器不会触发。";
    }

    /**
     * 建库。
     *
     * <p>「库」在各家是不同的东西：MySQL 的 database、PostgreSQL / H2 的 schema，
     * 而 SQLite 一个文件就是一个库，压根建不了（见 {@link #schemaCreationUnsupportedReason}）。
     *
     * @param charset   字符集；null 或空表示用服务端默认。只有 MySQL 会用到
     * @param collation 排序规则；同上
     */
    default String createSchemaDdl(String name, String charset, String collation) {
        return "CREATE SCHEMA " + quote(name);
    }

    /** 为什么这一家建不了库；能建就返回 null。 */
    default String schemaCreationUnsupportedReason() {
        return null;
    }

    /**
     * 这一家建库时能不能挑字符集。
     *
     * <p>只有 MySQL 需要——而且是<b>需要</b>而不是锦上添花：它的 {@code utf8}
     * 其实是最多三字节的阉割版，存不下 emoji 和一部分汉字，建库时选错了，
     * 要等某天插入失败才发现。
     */
    default boolean supportsSchemaCharset() {
        return false;
    }

    /**
     * 加外键。
     *
     * <p>显式给约束起名字：不起名的话各家会自动生成，而自动生成的名字在删的时候
     * 得先查一遍元数据才知道叫什么。
     */
    default String addForeignKeyDdl(String schema, String table, String name,
                                    List<String> columns, String refSchema, String refTable,
                                    List<String> refColumns, String onUpdate, String onDelete) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(qualify(schema, table))
                .append(" ADD CONSTRAINT ").append(quote(name))
                .append(" FOREIGN KEY (").append(quoteList(columns)).append(')')
                .append(" REFERENCES ").append(qualify(refSchema, refTable))
                .append(" (").append(quoteList(refColumns)).append(')');
        if (onUpdate != null && !onUpdate.isBlank() && !"NO ACTION".equals(onUpdate)) {
            sb.append(" ON UPDATE ").append(onUpdate);
        }
        if (onDelete != null && !onDelete.isBlank() && !"NO ACTION".equals(onDelete)) {
            sb.append(" ON DELETE ").append(onDelete);
        }
        return sb.toString();
    }

    /** 删外键。MySQL 用 DROP FOREIGN KEY，其余用标准的 DROP CONSTRAINT。 */
    default String dropForeignKeyDdl(String schema, String table, String name) {
        return "ALTER TABLE " + qualify(schema, table) + " DROP CONSTRAINT " + quote(name);
    }

    private String quoteList(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(names.get(i)));
        }
        return sb.toString();
    }

    default String createIndexDdl(String schema, String table, IndexInfo index) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (index.unique()) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ").append(quote(index.name()))
                .append(" ON ").append(qualify(schema, table)).append(" (");
        for (int i = 0; i < index.columns().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(index.columns().get(i)));
        }
        return sb.append(')').toString();
    }

    /**
     * 删索引。这是各家分歧最大的一条：
     * MySQL 的索引属于<b>表</b>（{@code DROP INDEX x ON t}），
     * PostgreSQL / H2 的索引属于 <b>schema</b>（{@code DROP INDEX s.x}）。
     * 默认按后者，因为漏掉 schema 限定在非默认 schema 下会直接报「索引不存在」。
     */
    default String dropIndexDdl(String schema, String table, String indexName) {
        return schema == null || schema.isBlank()
                ? "DROP INDEX " + quote(indexName)
                : "DROP INDEX " + quote(schema) + "." + quote(indexName);
    }

    /**
     * 删触发器。
     *
     * <p>这条各家分歧比删索引还大：MySQL 和 H2 的触发器挂在 <b>schema</b> 下
     * （{@code DROP TRIGGER s.t}），PostgreSQL 的触发器挂在<b>表</b>上
     * （{@code DROP TRIGGER t ON s.tbl}），SQLite 干脆没有 schema。
     * 默认按 schema 限定那一种。
     */
    default String dropTriggerDdl(String schema, String table, String triggerName) {
        return schema == null || schema.isBlank()
                ? "DROP TRIGGER " + quote(triggerName)
                : "DROP TRIGGER " + quote(schema) + "." + quote(triggerName);
    }

    /**
     * 新建触发器的起手模板。
     *
     * <p>{@code CREATE TRIGGER} 是整个 SQL 里方言差异最大的语句之一：
     * MySQL 要 {@code FOR EACH ROW} 加 {@code BEGIN...END}，PostgreSQL 必须先写一个
     * 返回 {@code trigger} 的函数再挂上去，SQLite 只支持行级且语法又是一套。
     * 与其给用户一个空白框让他自己猜，不如给一份<b>这个库上能跑通的</b>骨架。
     *
     * <p>返回 null 表示这一家没法用 SQL 写触发器（见 {@link #triggerUnsupportedReason}）。
     */
    default String createTriggerTemplate(String schema, String table, String triggerName) {
        return createTriggerDdl(schema, table, triggerName,
                com.plainly.driver.meta.DbObjects.TriggerTiming.AFTER,
                com.plainly.driver.meta.DbObjects.TriggerEvent.INSERT,
                triggerBodyTemplate(com.plainly.driver.meta.DbObjects.TriggerEvent.INSERT));
    }

    /**
     * 按「时机 + 事件」生成整条建触发器语句。
     *
     * <p>界面上让用户选 BEFORE/AFTER 与 INSERT/UPDATE/DELETE，剩下的语法由这里拼——
     * 这六种组合在各家的写法差异，用户没有理由去背。
     *
     * <p>{@code body} 是触发器体：MySQL 是 {@code BEGIN...END}，
     * PostgreSQL 是 {@code EXECUTE FUNCTION f()}（逻辑在函数里），各家自己接。
     *
     * <p>返回 null 表示这一家没法用 SQL 写触发器。
     */
    default String createTriggerDdl(String schema, String table, String triggerName,
                                    com.plainly.driver.meta.DbObjects.TriggerTiming timing,
                                    com.plainly.driver.meta.DbObjects.TriggerEvent event,
                                    String body) {
        return "CREATE TRIGGER " + quote(triggerName) + "\n"
                + timing.name() + " " + event.name() + " ON " + qualify(schema, table) + "\n"
                + "FOR EACH ROW\n"
                + body;
    }

    /**
     * 把 {@link #createTriggerDdl} 生成的那一段，拆成真正要逐条发出去的语句。
     *
     * <h2>为什么不能在外面按分号切</h2>
     * MySQL 的触发器体 {@code BEGIN ... ; ... ; END} 里本来就有分号，
     * 按分号切会把它剁成几段互不成立的碎片。PostgreSQL 那份的函数体也一样，
     * 而且还包在美元引号里。<b>通用的切分器在这里一定会切错。</b>
     *
     * <p>所以拆这件事交给生成它的那个方言自己做：它知道自己在哪里接的缝，
     * 别人只能靠猜。默认不拆——多数家本来就只有一条。
     *
     * @param triggerDdl {@link #createTriggerDdl} 的返回值
     */
    default List<String> triggerStatements(String triggerDdl) {
        return List.of(triggerDdl);
    }

    /** 触发器体的起手样子。用户改的是这一段，外面那圈声明由 {@link #createTriggerDdl} 拼。 */
    default String triggerBodyTemplate(com.plainly.driver.meta.DbObjects.TriggerEvent event) {
        return "BEGIN\n    -- " + rowRefHint(event) + "\nEND";
    }

    /**
     * 这个事件下能引用哪一行。
     *
     * <p>INSERT 只有新行，DELETE 只有旧行，UPDATE 两行都有——写错了不会当场报错，
     * 要到触发器真被触发时才炸，所以模板里就该说清楚。
     */
    default String rowRefHint(com.plainly.driver.meta.DbObjects.TriggerEvent event) {
        switch (event) {
            case INSERT:
                return "NEW.列名 取新插入的值（这个事件没有 OLD）";
            case DELETE:
                return "OLD.列名 取被删掉的值（这个事件没有 NEW）";
            default:
                return "NEW.列名 取改后的值，OLD.列名 取改前的值";
        }
    }

    /**
     * 这一家的触发器元数据给回来的是不是<b>整条</b> CREATE 语句。
     *
     * <p>SQLite 存的就是原始语句，没有单独的时机/事件字段；MySQL 与 PostgreSQL
     * 给的是触发器体，外加分开的时机、事件两栏。修改已有触发器时，前者只能整条改，
     * 后者可以拆开重拼——不区分的话，改一个 MySQL 触发器会发出一条光有
     * {@code BEGIN...END} 的语句，必然报语法错。
     */
    default boolean triggerActionIsFullStatement() {
        return false;
    }

    /**
     * <b>新建</b>触发器时，编辑框里放的是整条语句，还是只放触发器体。
     *
     * <h2>为什么不能和 {@link #triggerActionIsFullStatement()} 共用一个标志</h2>
     * 那一条问的是「<b>读</b>回来的元数据是不是整条语句」，这一条问的是
     * 「<b>写</b>的时候让用户编辑什么」。两件事在达梦上恰好相反：
     * 它的 {@code TRIGGER_BODY} 存的是整条 CREATE 语句（所以改的时候要整条改），
     * 但它完全能接受本工具拼出来的 {@code CREATE OR REPLACE TRIGGER}（所以建的时候不必整条写）。
     *
     * <p>共用一个标志的代价很具体、也很难查：新建时文本框里放的是<b>把名字写死在里面</b>
     * 的整条语句，而上面那个「名称」输入框就成了摆设——用户改了名字点创建，
     * 执行的仍是语句里那个旧名字，于是「新建的触发器没出现」，
     * 实际上是又把同名的那个覆盖了一遍。
     *
     * <p>默认跟随 {@link #triggerActionIsFullStatement()}：对 SQLite 那种
     * 两边确实都是整条语句的，行为不变。
     */
    default boolean composesFullStatement() {
        return triggerActionIsFullStatement();
    }

    /**
     * 查「这个库里还有没有别的触发器叫这个名字」，返回<b>名称、所在表</b>两列；
     * 触发器名只在表内唯一的库返回 null。
     *
     * <h2>为什么这件事必须按家区分</h2>
     * 触发器名的<b>作用域</b>各家不同，而这直接决定一次新建会不会撞名：
     * <ul>
     *   <li>MySQL、Oracle、达梦 —— 名字在<b>整个库/模式</b>里唯一。
     *       A 表上有个 {@code t_audit}，在 B 表上再建一个同名的照样被拒；</li>
     *   <li>PostgreSQL —— 名字挂在<b>表</b>上，两张表各有一个 {@code t_audit} 完全合法。</li>
     * </ul>
     *
     * <p>只在当前表里查重名，在前一类库上会漏掉跨表撞名——用户得到的是数据库退回来的
     * 一句原始报错，而他刚刚在别的表上建过这个名字这件事，界面一个字都没提。
     *
     * <p>返回 null 表示「这一家按表隔离」，调用方退回到只查当前表。
     */
    default String triggerNameConflictQuery(String schema, String triggerName) {
        return null;
    }

    /**
     * 为什么这一家不能在界面里写触发器；能写就返回 null。
     *
     * <p>存在的理由和 {@code unsupportedReason} 一样：把做不到的事灰掉却不说原因，
     * 用户只会以为软件坏了。
     */
    default String triggerUnsupportedReason() {
        return null;
    }

    /**
     * 这一家有没有「表结构」这回事。
     *
     * <p>关系库都有：一张表有列、有类型、有索引、有外键、有触发器，所以表页上那七个
     * 视图（数据 / 表单 / 结构 / DDL / 索引 / 外键 / 触发器）都有内容可显示。
     *
     * <p>键值库没有。给 Redis 摆出一个「结构」页，点进去是一张空表——用户会以为
     * 是没加载出来，然后反复刷新。返回 {@code false} 的话，界面只留下数据视图，
     * <b>不显示的东西就不会引人去点</b>。
     */
    default boolean hasTableStructure() {
        return true;
    }

    /**
     * 该数据库的 DDL 能否参与事务回滚。
     *
     * <p><b>多数数据库不能。</b>MySQL、Oracle、H2 在执行 DDL 时会隐式提交，
     * 一批变更跑到一半失败，前面成功的那些已经生效且无法撤销；
     * 只有 PostgreSQL、SQLite、SQL Server 支持事务性 DDL。
     *
     * <p>这个方法存在的唯一目的是让界面别说假话。默认返回 {@code false}——
     * 承诺一个做不到的回滚，比不承诺危险得多。
     */
    default boolean supportsTransactionalDdl() {
        return false;
    }

    /**
     * 这一家的 JDBC 驱动会不会在结果集元数据里报告<b>来源表名</b>。
     *
     * <h2>为什么这件事要紧</h2>
     * 网格能不能编辑，靠的就是「这一列出自哪张表、是不是主键」。
     * Oracle 的驱动对所有列一律返回空表名——它不支持
     * {@code ResultSetMetaData.getTableName()}，因为那要额外的往返。
     *
     * <p>后果很具体：Oracle 上<b>每一张表</b>的数据网格都是只读的，
     * 而给出的理由是「结果里有不属于任何表的列（表达式、函数或聚合）」——
     * 一句完全对不上号的话，用户会去检查自己的查询，而问题根本不在那儿。
     *
     * <p>达梦虽然继承 Oracle 的方言，但它的驱动<b>是</b>报表名的（在 DM 8.1.2 上验过），
     * 所以它要覆写回 true。这正是「能力要一家一家如实声明」的例子——
     * 跟着继承走会把一个不存在的限制安到它头上。
     */
    default boolean reportsResultSetTableNames() {
        return true;
    }

    /** 一条库级 DDL 语句及其来源。 */
    record SchemaChangeSql(SchemaChange change, String sql) {
    }

    /** 生成一项库级变更对应的 SQL。 */
    default List<SchemaChangeSql> ddlFor(String schema, SchemaChange change) {
        List<SchemaChangeSql> out = new ArrayList<>();
        if (change instanceof SchemaChange.CreateTable c) {
            out.add(new SchemaChangeSql(change, createTableDdl(schema, c.table())));
            // 新表自带的非主键索引一并建出来
            for (IndexInfo idx : c.table().indexes()) {
                if (!idx.primary()) {
                    out.add(new SchemaChangeSql(change,
                            createIndexDdl(schema, c.table().table().name(), idx)));
                }
            }
        } else if (change instanceof SchemaChange.DropTable c) {
            out.add(new SchemaChangeSql(change, dropTableDdl(schema, c.table().name())));
        } else if (change instanceof SchemaChange.AlterTable c) {
            for (TableChangeSql s : ddlFor(schema, c.table(), c.changes())) {
                out.add(new SchemaChangeSql(change, s.sql()));
            }
        } else if (change instanceof SchemaChange.CreateIndex c) {
            out.add(new SchemaChangeSql(change, createIndexDdl(schema, c.table(), c.index())));
        } else if (change instanceof SchemaChange.DropIndex c) {
            out.add(new SchemaChangeSql(change, dropIndexDdl(schema, c.table(), c.index().name())));
        }
        return out;
    }

    /** 按顺序生成整批库级变更的 SQL。 */
    default List<SchemaChangeSql> ddlForSchema(String schema, List<SchemaChange> changes) {
        List<SchemaChangeSql> out = new ArrayList<>();
        for (SchemaChange change : changes) {
            out.addAll(ddlFor(schema, change));
        }
        return out;
    }

    /** 本方言做不到的那些变更；非空时界面应当禁用「应用」。 */
    default List<String> unsupportedIn(List<SchemaChange> changes) {
        List<String> problems = new ArrayList<>();
        for (SchemaChange change : changes) {
            if (change instanceof SchemaChange.AlterTable alter) {
                for (TableChange tc : alter.changes()) {
                    if (!supports(tc)) {
                        problems.add(alter.table() + "：" + tc.describe()
                                + " —— " + unsupportedReason(tc));
                    }
                }
            }
        }
        return problems;
    }

    /** 一条参数化语句：SQL 文本 + 按顺序对应的绑定列。 */
    record PreparedSql(String sql, List<ColumnInfo> boundColumns) {
    }
}
