package com.plainly.driver.meta;

import com.plainly.driver.TypeCategory;
import com.plainly.driver.TypeNames;

import java.util.List;

/** 库表结构的只读模型。对象树、结构页、DDL 生成共用这一套。 */
public final class DbObjects {

    private DbObjects() {
    }

    /** 数据库 / schema。 */
    public record SchemaInfo(String name, boolean isDefault) {
    }

    public enum ObjectKind {
        TABLE("表"),
        VIEW("视图"),
        /**
         * 物化视图。有列、有数据、能查——所以它在界面上按<b>表</b>的路子走，
         * 只是不能改数据。归为独立的一类而不是并进 VIEW：普通视图每次查都现算，
         * 物化视图查的是一份快照，「这份数据是什么时候的」是使用者必须知道的事。
         */
        MATERIALIZED_VIEW("物化视图"),
        /** 序列。PostgreSQL / Oracle / 达梦 有，MySQL 没有。 */
        SEQUENCE("序列"),
        /** 定时事件。目前只有 MySQL 的 EVENT。 */
        EVENT("事件"),
        FUNCTION("函数"),
        PROCEDURE("存储过程");

        /** 这一类对象是不是「能打开来看数据」的。 */
        public boolean hasRows() {
            return this == TABLE || this == VIEW || this == MATERIALIZED_VIEW;
        }

        private final String label;

        ObjectKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一张表或视图。
     *
     * @param rowEstimate 行数估算，来自统计信息；-1 表示未知。
     *                    刻意不做 {@code COUNT(*)}——大表上那是一次全表扫描。
     */
    public record TableInfo(String schema, String name, ObjectKind kind,
                            String comment, long rowEstimate) {

        public String qualifiedName() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }
    }

    /**
     * 表的一个字段。
     *
     * @param defaultValue 默认值的<b>字面量文本</b>，如 {@code 'CNY'}、{@code CURRENT_TIMESTAMP(6)}
     *                     ——已经可以直接拼进 DDL，字符串默认值<b>自带引号</b>；
     *                     null 表示无默认值。
     *                     <p>这条不变式由驱动层的
     *                     {@code SqlDialect.normalizeDefault} 保证：MySQL 的
     *                     {@code COLUMN_DEF} 给的是原始值而不是字面量，不归一化就会拼出
     *                     {@code DEFAULT http://a/b.png} 这种跑不通的语句
     */
    public record ColumnInfo(String name, String nativeType, TypeCategory category,
                             int precision, int scale, boolean nullable,
                             boolean primaryKey, boolean autoIncrement,
                             String defaultValue, String comment, int ordinal) {

        public String displayType() {
            TypeNames.LengthKind kind = TypeNames.lengthKindOf(nativeType);
            switch (kind) {
                case NONE:
                    // 整型的 precision 各家报的东西不一样（H2 报位宽 64，MySQL 报显示宽度 20），
                    // 对使用者没有意义
                    return nativeType;
                case FRACTIONAL_SECONDS:
                    // 时间类型真正的参数是秒小数位，JDBC 把它放在 DECIMAL_DIGITS（scale）里；
                    // COLUMN_SIZE（precision）是格式化后的字符串宽度，取它就会得到 TIMESTAMP(26) 这种假值
                    return scale > 0 ? nativeType + "(" + scale + ")" : nativeType;
                case NUMERIC_PRECISION:
                    if (precision <= 0) {
                        return nativeType;
                    }
                    return scale > 0
                            ? nativeType + "(" + precision + "," + scale + ")"
                            : nativeType + "(" + precision + ")";
                case STRING_LENGTH:
                default:
                    return precision > 0 ? nativeType + "(" + precision + ")" : nativeType;
            }
        }
    }

    public record IndexInfo(String name, List<String> columns, boolean unique, boolean primary) {
    }

    /**
     * 一次列名搜索的命中。
     *
     * <p>刻意只带四个字段：搜索框上要显示的就这些。想看完整的列定义，
     * 顺着 schema/table 去 {@link TableStructure} 取——那是一次明确的、用户点过的动作，
     * 不该在每敲一个字符时就替他做几百次。
     */
    public record ColumnRef(String schema, String table, String column, String nativeType,
                           String comment) {

        public ColumnRef(String schema, String table, String column, String nativeType) {
            this(schema, table, column, nativeType, "");
        }
    }

    /**
     * 触发器。
     *
     * <p>{@code action} 各家给的东西不一样：MySQL / PostgreSQL 给的是触发器体或调用的函数，
     * H2 给的是实现类名——它的触发器本来就是 Java 类。照原样呈现，不硬凑成一种样子。
     */
    /** 触发时机。Navicat 那套分类的前一半。 */
    public enum TriggerTiming {
        BEFORE,
        AFTER;

        /** 从元数据里那个字符串认出时机；认不出返回 null。 */
        public static TriggerTiming parse(String text) {
            if (text == null) {
                return null;
            }
            String upper = text.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("BEFORE")) {
                return BEFORE;
            }
            return upper.contains("AFTER") ? AFTER : null;
        }
    }

    /** 触发事件。分类的后一半。 */
    public enum TriggerEvent {
        INSERT,
        UPDATE,
        DELETE;

        public static TriggerEvent parse(String text) {
            if (text == null) {
                return null;
            }
            String upper = text.toUpperCase(java.util.Locale.ROOT);
            for (TriggerEvent event : values()) {
                if (upper.contains(event.name())) {
                    return event;
                }
            }
            return null;
        }
    }

    /**
     * 一个触发器。
     *
     * @param timing 元数据里的时机字符串。SQLite 给不出这一栏（它只存整条建表语句），
     *               所以取时机要走 {@link #timingOf()}，那里会退回去从语句正文里认
     * @param action MySQL / PostgreSQL 给的是<b>触发器体</b>（{@code BEGIN...END} 或
     *               {@code EXECUTE FUNCTION f()}），SQLite 给的是<b>整条 CREATE 语句</b>。
     *               这个差别决定了「修改」时能不能直接把它当语句发出去
     */
    /**
     * 一次触发器查询的结果。
     *
     * <h2>为什么不能只返回一个 List</h2>
     * 原来 {@code listTriggers} 在查询失败时安静地返回空表。于是「这张表没有触发器」
     * 和「这一家的字典视图查不了 / 权限不够 / 列名对不上」在界面上<b>长得一模一样</b>——
     * 用户刚建完一个触发器，回到列表看到空的，只能怀疑是创建没成功。
     *
     * <p>{@code problem} 非空就是「读不到」，界面必须照原样说出来。
     * {@code query} 是实际发出去的那条语句：列表为空时把它显示出来，
     * 「没有触发器」这件事才是可核对的，而不是一个要靠猜的结论。
     */
    public record TriggerListing(List<TriggerInfo> triggers, String problem, String query) {

        public static TriggerListing of(List<TriggerInfo> triggers, String query) {
            return new TriggerListing(triggers, null, query);
        }

        public static TriggerListing failed(String problem, String query) {
            return new TriggerListing(List.of(), problem, query);
        }

        /** 这一家根本没有触发器视图，连查都没查。 */
        public static TriggerListing unsupported(String reason) {
            return new TriggerListing(List.of(), reason, null);
        }
    }

    public record TriggerInfo(String name, String timing, String event, String action) {

        /** 时机。元数据没给就从语句正文里认（SQLite 走这条）。 */
        public TriggerTiming timingOf() {
            TriggerTiming fromMeta = TriggerTiming.parse(timing);
            return fromMeta != null ? fromMeta : TriggerTiming.parse(headOf(action));
        }

        /** 事件。同上。 */
        public TriggerEvent eventOf() {
            TriggerEvent fromMeta = TriggerEvent.parse(event);
            return fromMeta != null ? fromMeta : TriggerEvent.parse(headOf(action));
        }

        /**
         * 分类：{@code BEFORE INSERT} 这样的一行字。认不出来的归到「未分类」——
         * 硬塞进某一类会让用户在错的分组下面找不到自己的触发器。
         */
        public String category() {
            TriggerTiming t = timingOf();
            TriggerEvent e = eventOf();
            return t == null || e == null ? "未分类" : t.name() + " " + e.name();
        }

        /**
         * 只看语句开头。
         *
         * <p>触发器体里出现 INSERT / UPDATE 是常事（它本来就是去改别的表），
         * 整段拿去匹配必然认错——{@code AFTER INSERT} 的触发器体里写一句 UPDATE，
         * 就会被认成 UPDATE 触发器。所以只认 {@code FOR EACH ROW} / {@code BEGIN} 之前那一截，
         * 那里才是声明。
         */
        private static String headOf(String sql) {
            if (sql == null) {
                return null;
            }
            String upper = sql.toUpperCase(java.util.Locale.ROOT);
            int cut = upper.indexOf("FOR EACH");
            if (cut < 0) {
                cut = upper.indexOf("BEGIN");
            }
            return cut < 0 ? sql : sql.substring(0, cut);
        }
    }

    /**
     * 外键。
     *
     * <p>两个方向都用这一个记录：{@code table} 是持有外键的那张表，
     * {@code refTable} 是被指向的那张。看「本表指向谁」时 table 是当前表，
     * 看「谁指向本表」时 refTable 才是当前表。
     */
    public record ForeignKeyInfo(String name, String schema, String table, List<String> columns,
                                 String refSchema, String refTable, List<String> refColumns,
                                 String onUpdate, String onDelete) {
    }

    /** 表的完整结构。 */
    public record TableStructure(TableInfo table, List<ColumnInfo> columns, List<IndexInfo> indexes) {

        public List<String> primaryKeyColumns() {
            return columns.stream().filter(ColumnInfo::primaryKey).map(ColumnInfo::name).toList();
        }
    }
}
