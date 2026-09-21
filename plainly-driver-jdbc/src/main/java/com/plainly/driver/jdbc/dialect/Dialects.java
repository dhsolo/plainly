package com.plainly.driver.jdbc.dialect;

import com.plainly.driver.DbType;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.query.ConflictPolicy;
import com.plainly.driver.ddl.ColumnDraft;
import com.plainly.driver.ddl.TableChange;
import com.plainly.driver.meta.DbObjects.ColumnInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 各库方言的实现与工厂。
 *
 * <p>DDL 是差异最大的部分：同一个「把某列改成 DECIMAL(38,10) 并加非空」的意图，
 * MySQL 是一条 {@code MODIFY COLUMN}，PostgreSQL 要拆成三条 {@code ALTER COLUMN}，
 * SQLite 则<b>根本做不到</b>。所以每个方言除了生成语句，还要如实声明自己的能力边界。
 *
 * <p>Oracle 与达梦不在本文件里，各自成文（{@link OracleDialect}、{@link DmDialect}）：
 * 它们要覆盖的方法多，塞进来这个文件就没法读了。
 */
public final class Dialects {

    private Dialects() {
    }

    public static SqlDialect forType(DbType type) {
        switch (type) {
            case MYSQL:
                return new MySqlDialect();
            case POSTGRESQL:
                return new PostgresDialect();
            case SQLITE:
                return new SqliteDialect();
            case SQLSERVER:
                return new SqlServerDialect();
            case H2:
                return new H2Dialect();
            case ORACLE:
                return new OracleDialect();
            case DM:
                return new DmDialect();
            case KINGBASE:
                return new KingbaseDialect();
            case GAUSSDB:
                return new GaussDialect();
            case OCEANBASE:
                return new OceanBaseDialect();
            default:
                return new StandardDialect();
        }
    }

    // ================================================================ 标准兜底

    /**
     * 双引号引用 + 标准 LIMIT/OFFSET + SQL:2003 风格的 ALTER。
     *
     * <p><b>这里只放各家都认的写法。</b>PostgreSQL、SQLite、SQL Server 都继承它，
     * 往这里塞某一家的特殊行为，会安静地漏到另外三家身上——H2 的
     * 「触发器是 Java 类」就这么漏过一次，三个本来支持 SQL 触发器的库
     * 全都被说成不支持。H2 自己的东西放 {@link H2Dialect}。
     */
    public static class StandardDialect implements SqlDialect {

        /**
         * H2 的 {@code INFORMATION_SCHEMA.TRIGGERS} 没有 {@code ACTION_STATEMENT}——
         * 它的触发器是 Java 类，动作那一栏能给的只有类名。
         */


        @Override
        public String quote(String identifier) {
            return '"' + identifier.replace("\"", "\"\"") + '"';
        }

        @Override
        public String limitOffset(int limit, int offset) {
            return offset > 0 ? "LIMIT " + limit + " OFFSET " + offset : "LIMIT " + limit;
        }

        @Override
        public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
            String t = qualify(schema, table);
            List<TableChangeSql> out = new ArrayList<>();

            if (change instanceof TableChange.AddColumn c) {
                out.add(new TableChangeSql(change,
                        "ALTER TABLE " + t + " ADD COLUMN " + columnDefinition(c.column())));
                addComment(out, change, t, c.column());

            } else if (change instanceof TableChange.DropColumn c) {
                out.add(new TableChangeSql(change,
                        "ALTER TABLE " + t + " DROP COLUMN " + quote(c.column().name())));

            } else if (change instanceof TableChange.RenameColumn c) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN "
                        + quote(c.from().name()) + " RENAME TO " + quote(c.to().name())));
                if (c.definitionAlsoChanged()) {
                    alterInPlace(out, change, t, c.from(), c.to());
                }

            } else if (change instanceof TableChange.ModifyColumn c) {
                alterInPlace(out, change, t, c.from(), c.to());

            } else if (change instanceof TableChange.ChangePrimaryKey c) {
                if (!c.from().isEmpty()) {
                    out.add(new TableChangeSql(change, "ALTER TABLE " + t + " DROP PRIMARY KEY"));
                }
                if (!c.to().isEmpty()) {
                    out.add(new TableChangeSql(change, "ALTER TABLE " + t
                            + " ADD PRIMARY KEY (" + quoteAll(c.to()) + ")"));
                }

            } else if (change instanceof TableChange.RenameTable c) {
                out.add(new TableChangeSql(change,
                        "ALTER TABLE " + t + " RENAME TO " + quote(c.to())));
            }
            return out;
        }

        /** 就地改定义：类型、可空、默认值、注释各是一条独立语句。 */
        protected void alterInPlace(List<TableChangeSql> out, TableChange change, String t,
                                    ColumnInfo from, ColumnDraft to) {
            String col = quote(to.name());
            if (!from.displayType().equalsIgnoreCase(to.fullType())) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN "
                        + col + " SET DATA TYPE " + to.fullType()));
            }
            if (from.nullable() != to.nullable()) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN "
                        + col + (to.nullable() ? " SET NULL" : " SET NOT NULL")));
            }
            if (!sameDefault(from.defaultValue(), to.defaultValue())) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN " + col
                        + (to.defaultValue() == null
                        ? " DROP DEFAULT" : " SET DEFAULT " + to.defaultValue())));
            }
            addComment(out, change, t, to);
        }

        /** 注释在标准 SQL 里是独立的 COMMENT ON 语句。 */
        protected void addComment(List<TableChangeSql> out, TableChange change,
                                  String t, ColumnDraft column) {
            if (column.comment() != null && !column.comment().isBlank()) {
                out.add(new TableChangeSql(change, "COMMENT ON COLUMN " + t + "."
                        + quote(column.name()) + " IS " + literal(column.comment())));
            }
        }

        protected String quoteAll(List<String> names) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(quote(names.get(i)));
            }
            return sb.toString();
        }

        protected static boolean sameDefault(String a, String b) {
            String x = a == null || a.isBlank() ? null : a.trim();
            String y = b == null || b.isBlank() ? null : b.trim();
            return x == null ? y == null : x.equals(y);
        }

        protected static String literal(String s) {
            return "'" + s.replace("'", "''") + "'";
        }
    }

    // ================================================================ H2

    /**
     * H2。
     *
     * <p>大部分写法和标准一致，所以继承 {@link StandardDialect}；这里只放它真正
     * 与众不同的地方。反过来做——把 H2 的特例塞进 StandardDialect——会漏到
     * PostgreSQL、SQLite、SQL Server 身上，而它们并不知道自己被改了。
     */
    public static class H2Dialect extends StandardDialect {

        /**
         * H2 的 {@code BASE_VALUE} 就是「下一个该发的号」。
         *
         * <p>实测（起始 5、步长 3、取过一次）：{@code START_VALUE=5}、{@code BASE_VALUE=8}。
         * 备份要的是后者——照 START_VALUE 恢复，序列会把 5 再发一遍。
         *
         * <p>放在 H2 自己这儿而不是默认实现里：{@code BASE_VALUE} 不是 SQL 标准的列，
         * 别家的 information_schema 没有它。默认实现返回 null，宁可某一家没备份到序列
         * 并如实报出来，也不要发一条在那家跑不通的语句。
         */
        @Override
        public String sequenceAttributesQuery(String schema) {
            return "SELECT SEQUENCE_NAME, CAST(BASE_VALUE AS VARCHAR),"
                    + " CAST(INCREMENT AS VARCHAR), CAST(MINIMUM_VALUE AS VARCHAR),"
                    + " CAST(MAXIMUM_VALUE AS VARCHAR),"
                    + " CASE WHEN CYCLE_OPTION = 'YES' THEN TRUE ELSE FALSE END"
                    + " FROM INFORMATION_SCHEMA.SEQUENCES"
                    + " WHERE SEQUENCE_SCHEMA = " + literal(schema)
                    + " ORDER BY SEQUENCE_NAME";
        }

        /**
         * H2 的自增。
         *
         * <p>不写这一段，「复制表」和「新建表」生成出来的列就只是一个普通 BIGINT——
         * 语句能跑通，表也建得出来，然后第一条不带 id 的 INSERT 撞上非空约束。
         * 失败发生在离建表很远的地方，很难联想到是建表时漏了自增。
         *
         * <p>H2 2.x 支持 SQL 标准的写法，和 PostgreSQL 那边一致。
         */
        @Override
        public String columnDefinition(ColumnDraft c) {
            String base = super.columnDefinition(c);
            return c.autoIncrement() ? base + " GENERATED BY DEFAULT AS IDENTITY" : base;
        }

        /** H2 2.x 把估算值直接摆在 INFORMATION_SCHEMA 里。 */
        @Override
        public String tableRowCountQuery(String schema) {
            return "SELECT TABLE_NAME, ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE TABLE_SCHEMA = " + literal(schema);
        }

        /**
         * H2 的 {@code INFORMATION_SCHEMA.TRIGGERS} 没有 {@code ACTION_STATEMENT} 这一列，
         * 因为它的触发器体压根不是语句，是一个 Java 类名，放在 {@code JAVA_CLASS} 里。
         */
        @Override
        public String triggersQuery(String schema, String table) {
            return "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, JAVA_CLASS"
                    + " FROM INFORMATION_SCHEMA.TRIGGERS"
                    + " WHERE EVENT_OBJECT_SCHEMA = " + literal(schema)
                    + " AND EVENT_OBJECT_TABLE = " + literal(table)
                    + " ORDER BY TRIGGER_NAME";
        }

        /**
         * 同上：触发器体是实现 {@code org.h2.api.Trigger} 的 Java 类。
         *
         * <p>在数据库工具里写一段 SQL 再编译成类，是它做不到的事。给一个能打字
         * 但保存必然失败的框，比直说做不到糟糕得多。删除仍然可以。
         */
        @Override
        public String triggerUnsupportedReason() {
            return "H2 的触发器体是一个 Java 类（实现 org.h2.api.Trigger），不是 SQL，"
                    + "没法在这里写。已有的触发器仍然可以删除。";
        }

        @Override
        public String createTriggerTemplate(String schema, String table, String triggerName) {
            return null;
        }

        @Override
        public String createTriggerDdl(String schema, String table, String triggerName,
                                       com.plainly.driver.meta.DbObjects.TriggerTiming timing,
                                       com.plainly.driver.meta.DbObjects.TriggerEvent event,
                                       String body) {
            return null;
        }
    }

    // ================================================================ MySQL

    public static class MySqlDialect implements SqlDialect {

        /**
         * MySQL 的计数器写在表属性上。
         *
         * <p>InnoDB 其实会在显式插入更大的值时自己把计数器抬上去，这一条多半是多余的；
         * 但它是幂等的，而「多发一条无害的语句」比「赌某个版本的行为」可靠。
         */
        @Override
        public String restartAutoIncrementDdl(String schema, String table, String column,
                                              long next) {
            return "ALTER TABLE " + qualify(schema, table) + " AUTO_INCREMENT = " + next;
        }

        /**
         * InnoDB 的 {@code TABLE_ROWS} 是从索引里采样估出来的，几成的偏差很常见；
         * MyISAM 上则是准确值。视图这一行是 NULL，读的时候会被当成「未知」。
         */
        @Override
        public String tableRowCountQuery(String schema) {
            return "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES"
                    + " WHERE TABLE_SCHEMA = " + stringLiteral(schema);
        }

        /**
         * MySQL 的 {@code COLUMN_DEF} 是<b>原始值</b>，不是字面量。
         *
         * <p>{@code DEFAULT ''} 读回来是空串，{@code DEFAULT 'http://a/b.png'} 读回来是
         * 不带引号的 {@code http://a/b.png}。照原样拼进 DDL 就是语法错误。
         *
         * <p>加引号的边界在于：{@code CURRENT_TIMESTAMP} 这类是<b>表达式</b>，
         * 加了引号就从「取当前时间」变成了「存字符串 CURRENT_TIMESTAMP」——
         * 那不是报错，是安静地改掉了表的语义，比报错糟糕得多。所以先认表达式，
         * 认不出来的才当字面量引起来。
         */
        @Override
        public String normalizeDefault(String raw, TypeCategory category) {
            if (raw == null) {
                return null;
            }
            if (category.isNumeric() || category == TypeCategory.BOOLEAN) {
                // 数值默认值本来就不带引号；空白说明这一列其实没有默认值
                return raw.isBlank() ? null : raw.trim();
            }
            String trimmed = raw.trim();
            if (isExpressionDefault(trimmed)) {
                return trimmed;
            }
            // 注意引的是 raw 不是 trimmed：默认值就是一个空格时，那个空格是有意义的
            return "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'";
        }

        /** 这些是表达式，不能加引号。 */
        private static boolean isExpressionDefault(String value) {
            if (value.startsWith("(")) {
                return true; // MySQL 8 的表达式默认值存成带括号的形式
            }
            String upper = value.toUpperCase(java.util.Locale.ROOT);
            return upper.equals("NULL")
                    || upper.equals("CURRENT_DATE")
                    || upper.equals("CURRENT_TIME")
                    || upper.equals("LOCALTIME")
                    || upper.equals("LOCALTIMESTAMP")
                    || upper.equals("UUID()")
                    || upper.equals("NOW()")
                    || upper.matches("CURRENT_TIMESTAMP(\\(\\d+\\))?");
        }

        /**
         * 例程清单。类型那一列直接给 {@code PROCEDURE} / {@code FUNCTION}，
         * 正好是 {@code SHOW CREATE} 要拼的那个词。
         */
        @Override
        public String routineListQuery(String schema) {
            return "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES"
                    + " WHERE ROUTINE_SCHEMA = " + stringLiteral(schema)
                    + " ORDER BY ROUTINE_TYPE, ROUTINE_NAME";
        }

        /**
         * 完整语句只能靠 {@code SHOW CREATE}。
         *
         * <p>在 MySQL 8.0.22 上验过：{@code information_schema.ROUTINES.ROUTINE_DEFINITION}
         * 给的只有 {@code BEGIN ... END} 那一段，没有名字也没有参数列表，还原不回去。
         *
         * <p>返回的语句里带着 {@code DEFINER=`root`@`%`}。刻意<b>不</b>删它：
         * 那是这个对象真实定义的一部分，改掉就不是备份了。代价是还原到别的实例时
         * 那个账号必须存在——这一点写在备份文件的头部说明里。
         */
        @Override
        public String routineSourceQuery(String schema, String name, String routineType) {
            String kind = "FUNCTION".equalsIgnoreCase(routineType) ? "FUNCTION" : "PROCEDURE";
            return "SHOW CREATE " + kind + " " + quote(schema) + "." + quote(name);
        }

        /** {@code SHOW CREATE PROCEDURE} 的第三列才是创建语句，前两列是过程名和 sql_mode。 */
        @Override
        public int routineSourceColumn() {
            return 3;
        }

        /**
         * MySQL 的触发器名在<b>整个 database</b> 里唯一，不是每张表唯一。
         *
         * <p>所以在 A 表上建过 {@code t_audit}，再去 B 表上建同名的会被
         * {@code ERROR 1359 (HY000): Trigger already exists} 拒掉。
         * 只查当前表的话，用户拿到的就是这句原始报错，
         * 而「它已经挂在 A 表上」这个真正有用的信息一个字都没有。
         */
        @Override
        public String triggerNameConflictQuery(String schema, String triggerName) {
            return "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM INFORMATION_SCHEMA.TRIGGERS"
                    + " WHERE TRIGGER_SCHEMA = " + stringLiteral(schema)
                    + " AND TRIGGER_NAME = " + stringLiteral(triggerName);
        }

        /**
         * MySQL 没有序列。
         *
         * <p>返回 null 而不是让它去查 {@code information_schema.SEQUENCES}——
         * 那张表在 MySQL 上不存在，查过去是一条报错，而树上的表现会是
         * 「展开一个库就弹个错」。
         */
        @Override
        public String sequencesQuery(String schema) {
            return null;
        }

        /** 定时事件是 MySQL 独有的。 */
        @Override
        public String eventsQuery(String schema) {
            return "SELECT EVENT_NAME,"
                    + " CONCAT(STATUS, ' · ', IFNULL(EVENT_TYPE, ''),"
                    + " IFNULL(CONCAT(' 每 ', INTERVAL_VALUE, ' ', INTERVAL_FIELD), ''))"
                    + " FROM information_schema.EVENTS"
                    + " WHERE EVENT_SCHEMA = " + stringLiteral(schema)
                    + " ORDER BY EVENT_NAME";
        }

        @Override
        public String objectDefinitionQuery(com.plainly.driver.meta.DbObjects.ObjectKind kind,
                                            String schema, String name) {
            if (kind == com.plainly.driver.meta.DbObjects.ObjectKind.EVENT) {
                return "SELECT EVENT_DEFINITION FROM information_schema.EVENTS"
                        + " WHERE EVENT_SCHEMA = " + stringLiteral(schema)
                        + " AND EVENT_NAME = " + stringLiteral(name);
            }
            return null;
        }

        /** MySQL 的「库」是 database，不是 schema。 */
        @Override
        public String createSchemaDdl(String name, String charset, String collation) {
            StringBuilder sb = new StringBuilder("CREATE DATABASE ").append(quote(name));
            if (charset != null && !charset.isBlank()) {
                sb.append(" DEFAULT CHARACTER SET ").append(charset.trim());
            }
            if (collation != null && !collation.isBlank()) {
                sb.append(" COLLATE ").append(collation.trim());
            }
            return sb.toString();
        }

        @Override
        public boolean supportsSchemaCharset() {
            return true;
        }

        @Override
        public String truncateNote() {
            return "TRUNCATE 在 MySQL 里是 DDL：立即生效、无法回滚，AUTO_INCREMENT 归零，"
                    + "触发器不会触发。被别的表用外键引用着时会直接失败。";
        }

        /** MySQL 删外键的写法和别家不同：DROP FOREIGN KEY，不是 DROP CONSTRAINT。 */
        @Override
        public String dropForeignKeyDdl(String schema, String table, String name) {
            return "ALTER TABLE " + qualify(schema, table) + " DROP FOREIGN KEY " + quote(name);
        }

        /**
         * MySQL 的触发器体直接跟在声明后面。
         *
         * <p>提示写成随事件变的：DELETE 触发器里没有 NEW，INSERT 触发器里没有 OLD，
         * 给一句放之四海皆准的注释，等于让用户自己去试哪个能用。
         */
        @Override
        public String triggerBodyTemplate(com.plainly.driver.meta.DbObjects.TriggerEvent event) {
            return "BEGIN\n    -- " + rowRefHint(event) + "\nEND";
        }

        /** MySQL 8 的 EXPLAIN ANALYZE 会真的执行语句，这里只用 FORMAT=TRADITIONAL 的估算。 */
        @Override
        public String explainQuery(String sql) {
            return "EXPLAIN " + sql;
        }

        /** MySQL：跳过用 INSERT IGNORE，覆盖用 ON DUPLICATE KEY UPDATE，都不需要额外占位符。 */
        @Override
        public PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns,
                                       List<ColumnInfo> keyColumns, ConflictPolicy policy) {
            if (policy == ConflictPolicy.ABORT) {
                return buildInsert(schema, table, columns);
            }
            PreparedSql plain = buildInsert(schema, table, columns);
            if (policy == ConflictPolicy.SKIP) {
                return new PreparedSql(plain.sql().replaceFirst("(?i)^INSERT INTO", "INSERT IGNORE INTO"),
                        plain.boundColumns());
            }
            StringBuilder sb = new StringBuilder(plain.sql()).append(" ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String col = quote(columns.get(i).name());
                sb.append(col).append(" = VALUES(").append(col).append(')');
            }
            return new PreparedSql(sb.toString(), plain.boundColumns());
        }


        @Override
        public String quote(String identifier) {
            return '`' + identifier.replace("`", "``") + '`';
        }

        @Override
        public String limitOffset(int limit, int offset) {
            return offset > 0 ? "LIMIT " + offset + ", " + limit : "LIMIT " + limit;
        }

        /** MySQL 的子句顺序是固定的：类型 → 可空 → 默认值 → 自增 → 注释。 */
        @Override
        public String columnDefinition(ColumnDraft c) {
            StringBuilder sb = new StringBuilder(quote(c.name())).append(' ').append(c.fullType());
            sb.append(c.nullable() ? " NULL" : " NOT NULL");
            if (c.defaultValue() != null) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            if (c.autoIncrement()) {
                sb.append(" AUTO_INCREMENT");
            }
            if (c.comment() != null && !c.comment().isBlank()) {
                sb.append(" COMMENT '").append(c.comment().replace("'", "''")).append('\'');
            }
            return sb.toString();
        }

        /** MySQL 的索引属于表，删除时必须带 ON <表>。 */
        @Override
        public String dropIndexDdl(String schema, String table, String indexName) {
            return "DROP INDEX " + quote(indexName) + " ON " + qualify(schema, table);
        }

        @Override
        public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
            String t = qualify(schema, table);
            List<TableChangeSql> out = new ArrayList<>();

            if (change instanceof TableChange.AddColumn c) {
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(t)
                        .append(" ADD COLUMN ").append(columnDefinition(c.column()));
                if (c.afterColumn() != null && !c.afterColumn().isBlank()) {
                    sb.append(" AFTER ").append(quote(c.afterColumn()));
                }
                out.add(new TableChangeSql(change, sb.toString()));

            } else if (change instanceof TableChange.DropColumn c) {
                out.add(new TableChangeSql(change,
                        "ALTER TABLE " + t + " DROP COLUMN " + quote(c.column().name())));

            } else if (change instanceof TableChange.RenameColumn c) {
                // CHANGE 一条同时完成改名与改定义，且在 5.7 上也可用
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " CHANGE COLUMN "
                        + quote(c.from().name()) + " " + columnDefinition(c.to())));

            } else if (change instanceof TableChange.ModifyColumn c) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " MODIFY COLUMN "
                        + columnDefinition(c.to())));

            } else if (change instanceof TableChange.ChangePrimaryKey c) {
                if (!c.from().isEmpty()) {
                    out.add(new TableChangeSql(change, "ALTER TABLE " + t + " DROP PRIMARY KEY"));
                }
                if (!c.to().isEmpty()) {
                    StringBuilder sb = new StringBuilder("ALTER TABLE ").append(t)
                            .append(" ADD PRIMARY KEY (");
                    for (int i = 0; i < c.to().size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(quote(c.to().get(i)));
                    }
                    out.add(new TableChangeSql(change, sb.append(')').toString()));
                }

            } else if (change instanceof TableChange.RenameTable c) {
                out.add(new TableChangeSql(change,
                        "ALTER TABLE " + t + " RENAME TO " + quote(c.to())));
            }
            return out;
        }
    }

    // ================================================================ PostgreSQL

    /**
     * 人大金仓 KingBase ES V8：基于 PostgreSQL，方言整套继承。
     *
     * <p>单独开一个类而不是直接复用 {@link PostgresDialect}，是为了让「它和 PG
     * 到底哪里不一样」有个能写下来的地方。目前<b>没有发现需要覆写的差异</b>——
     * 而这句话本身就是信息：下一个人不必再去查一遍。
     *
     * <p>真机验证之后如果发现差异，覆写在这里，并写清是哪一条。
     *
     * <p><b>已知的一处风险，但没有实例可验，所以没动：</b>自增列用的是 PG 10 的
     * {@code GENERATED BY DEFAULT AS IDENTITY}。{@link GaussDialect} 那边实测发现
     * openGauss 的内核停在 PG 9.2，这句话它不认。金仓各版本的内核基线也不一样，
     * 老基线上很可能同样要换成 {@code serial}。等有真实实例时先跑一次建表，
     * 报 {@code syntax error at or near "BY"} 就照 Gauss 那边的写法覆写。
     */
    public static class KingbaseDialect extends PostgresDialect {
    }

    /**
     * GaussDB / openGauss：PostgreSQL 的血统，但分叉点在 <b>PG 9.2</b>。
     *
     * <h2>它不是「新版 PG 的一个分支」</h2>
     * openGauss 5.0.0 自报的服务端版本就是 {@code 9.2.4}。PG 10 之后加进去的东西
     * 它一概没有——自增列首当其冲：{@code GENERATED BY DEFAULT AS IDENTITY}
     * 是 PG 10 才有的写法，在这里会报 {@code syntax error at or near "BY"}。
     * 建一张带自增主键的表就会撞上，这是最常见的一步操作。
     *
     * <p>能用的是老写法 {@code serial} / {@code bigserial}，从 PG 8 一直到今天都在。
     *
     * <p>要当心的是<b>驱动</b>而不是方言：必须用 {@code -og} 后缀的那个变体，
     * 理由见 {@code DbType.GAUSSDB}。
     */
    public static class GaussDialect extends PostgresDialect {

        /**
         * 自增列用 {@code serial} 系列，而不是 PG 10 的 identity。
         *
         * <h2>serial 是「伪类型」，它替换类型，而不是加在类型后面</h2>
         * 这是和 identity 最要紧的形状差异。{@code BIGINT GENERATED BY DEFAULT AS IDENTITY}
         * 是类型 + 修饰，而 {@code bigserial} 本身就是类型——服务端把它展开成
         * {@code bigint NOT NULL DEFAULT nextval(...)}，也就是说
         * <b>非空和默认值都已经含在里面了</b>。于是后面两个子句都不能再写：
         *
         * <ul>
         *   <li>再写 {@code NULL} → {@code conflicting NULL/NOT NULL declarations}</li>
         *   <li>再写 {@code DEFAULT x} → {@code multiple default values specified}</li>
         * </ul>
         *
         * <p>两条都是实测（openGauss 5.0.0）。父类那段会无条件补上这两个子句，
         * 所以这里不能只换个类型名了事，整段得自己拼。
         *
         * <p>回读那一侧不用管：驱动看到默认值是 {@code nextval(...)} 就报
         * {@code IS_AUTOINCREMENT=YES}，和 identity 列读出来是一样的，结构能对上。
         *
         * <p><b>映射不了的类型仍然交给父类</b>（于是发出 identity 写法、在服务端报错）。
         * 那种输入在真正的 PostgreSQL 上也一样建不出来——identity 只能加在整数列上——
         * 与其在这里安静地把「自增」这个勾吞掉，不如让它响亮地失败。
         */
        @Override
        public String columnDefinition(ColumnDraft c) {
            String serial = c.autoIncrement() ? serialType(c.nativeType()) : null;
            if (serial == null) {
                return super.columnDefinition(c);
            }
            // 非空与默认值已经含在 serial 里，这里只剩名字和类型
            return quote(c.name()) + " " + serial;
        }

        /**
         * 触发器挂载语句用 {@code EXECUTE PROCEDURE}。
         *
         * <p>{@code EXECUTE FUNCTION} 是 <b>PG 11</b> 才引入的同义写法，
         * 在 9.2 内核上会报 {@code syntax error at or near "FUNCTION"}。
         * 老写法 {@code EXECUTE PROCEDURE} 从 PG 7 一直沿用至今，
         * 连 PG 16 也仍然接受——所以这里不是「退而求其次」，而是两边都认的那一个。
         *
         * <p>只改最后那一行：前面建函数的部分（{@code $$} 引号、{@code plpgsql}、
         * {@code RETURNS trigger}）openGauss 都认，实测通过。
         */
        @Override
        public String createTriggerDdl(String schema, String table, String triggerName,
                                       com.plainly.driver.meta.DbObjects.TriggerTiming timing,
                                       com.plainly.driver.meta.DbObjects.TriggerEvent event,
                                       String body) {
            return super.createTriggerDdl(schema, table, triggerName, timing, event, body)
                    .replace("EXECUTE FUNCTION", "EXECUTE PROCEDURE");
        }

        /**
         * 建函数和挂触发器必须分两次发。
         *
         * <p>openGauss 的驱动走扩展协议，一次只肯执行一条命令，
         * 两条拼在一起会被服务端挡回来：
         * {@code cannot insert multiple commands into a prepared statement}。
         * 真正的 PostgreSQL 上 pgjdbc 会替我们处理掉，所以那边一直没露出来。
         *
         * <p>缝在哪里是确定的——这段脚本是{@link PostgresDialect#createTriggerDdl}
         * 自己拼的，函数收尾之后空一行才是 {@code CREATE TRIGGER}。
         * 找不到这个缝就原样返回一条，宁可让服务端报错，也不瞎切。
         */
        @Override
        public List<String> triggerStatements(String triggerDdl) {
            String seam = "\n\nCREATE TRIGGER ";
            int at = triggerDdl.indexOf(seam);
            if (at < 0) {
                return List.of(triggerDdl);
            }
            return List.of(triggerDdl.substring(0, at),
                    triggerDdl.substring(at + 2));
        }

        /**
         * 序列列表：{@code pg_sequences} 这个视图是 <b>PG 10</b> 才加的，这里没有。
         *
         * <p>9.2 上要自己从 {@code pg_class} 里按 {@code relkind = 'S'} 捞，
         * 属性走函数 {@code pg_sequence_parameters(oid)}、当前值走
         * {@code pg_sequence_last_value(oid)}。两个函数都实测可用。
         */
        @Override
        public String sequencesQuery(String schema) {
            return "SELECT c.relname,"
                    + " '当前 ' || (pg_sequence_last_value(c.oid)).last_value::text"
                    + " || ' · 步长 ' || (pg_sequence_parameters(c.oid)).increment::text"
                    + sequenceFrom(schema) + " ORDER BY c.relname";
        }

        /**
         * 序列属性，用于备份重建。第二列必须是<b>下一个该发的号</b>。
         *
         * <h2>这里有一个拿不准的地方，取的是安全那一侧</h2>
         * PG 10+ 的 {@code pg_sequences} 在序列没被用过时把 {@code last_value} 报成 NULL，
         * 于是父类能用 {@code COALESCE(last_value + increment, start_value)} 精确区分。
         * 而 openGauss 的 {@code pg_sequence_last_value} 返回的是
         * {@code (cache_value, last_value)}——<b>没有 {@code is_called}</b>。
         * 一个 {@code START WITH 5} 的序列，没用过和用过一次，
         * {@code last_value} 都是 5，单条查询里分不开。
         *
         * <p>所以一律按「用过」算，即 {@code last_value + increment}：
         *
         * <ul>
         *   <li>用过的序列——<b>算得准</b>（实测：START 10 步长 3 发两次，
         *       算出 16，正是下一个该发的号）</li>
         *   <li>没用过的序列——多跳一个步长（START 5 步长 2 会算成 7）</li>
         * </ul>
         *
         * <p>方向不能反。多跳只是白白空掉两个号，没有任何后果；
         * 而少算会让恢复出来的序列<b>重新发已经用过的号</b>，
         * 直到某次插入撞上主键冲突——那时离恢复已经很远了。
         * 同样的取舍在 Oracle 的 CACHE 上也做过一次。
         */
        @Override
        public String sequenceAttributesQuery(String schema) {
            return "SELECT c.relname,"
                    + " ((pg_sequence_last_value(c.oid)).last_value"
                    + " + (pg_sequence_parameters(c.oid)).increment)::text,"
                    + " (pg_sequence_parameters(c.oid)).increment::text,"
                    + " (pg_sequence_parameters(c.oid)).minimum_value::text,"
                    + " (pg_sequence_parameters(c.oid)).maximum_value::text,"
                    + " (pg_sequence_parameters(c.oid)).cycle_option"
                    + sequenceFrom(schema) + " ORDER BY c.relname";
        }

        /** 序列的详情面板，同样不能用 {@code pg_sequences}。 */
        @Override
        public String objectDefinitionQuery(com.plainly.driver.meta.DbObjects.ObjectKind kind,
                                            String schema, String name) {
            if (kind != com.plainly.driver.meta.DbObjects.ObjectKind.SEQUENCE) {
                return super.objectDefinitionQuery(kind, schema, name);
            }
            String nl = " || E'" + (char) 92 + "n' || ";
            return "SELECT 'start_value=' || (pg_sequence_parameters(c.oid)).start_value" + nl
                    + "'min_value=' || (pg_sequence_parameters(c.oid)).minimum_value" + nl
                    + "'max_value=' || (pg_sequence_parameters(c.oid)).maximum_value" + nl
                    + "'increment_by=' || (pg_sequence_parameters(c.oid)).increment" + nl
                    + "'cycle=' || (pg_sequence_parameters(c.oid)).cycle_option" + nl
                    + "'last_value=' || (pg_sequence_last_value(c.oid)).last_value"
                    + sequenceFrom(schema) + " AND c.relname = " + literal(name);
        }

        /** 三条序列查询共用的取数范围：本模式下的序列。 */
        private String sequenceFrom(String schema) {
            return " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE c.relkind = 'S' AND n.nspname = " + literal(schema);
        }

        /** 整数列对应的 serial 伪类型；不是整数列就返回 null。 */
        private static String serialType(String nativeType) {
            if (nativeType == null) {
                return null;
            }
            switch (nativeType.trim().toLowerCase(Locale.ROOT)) {
                case "smallint":
                case "int2":
                    return "smallserial";
                case "integer":
                case "int":
                case "int4":
                    return "serial";
                case "bigint":
                case "int8":
                    return "bigserial";
                default:
                    return null;
            }
        }

        /**
         * 推进自增计数器：只能走 {@code setval}。
         *
         * <p>父类那条 {@code ALTER TABLE ... ALTER COLUMN ... RESTART WITH} 同样是
         * PG 10 的 identity 语法，这里不认。退一步的 {@code ALTER SEQUENCE ... RESTART}
         * 也不行——openGauss 5.0.0 直接答 {@code ALTER SEQUENCE is not yet supported}。
         * 剩下能用的只有函数 {@code setval}，实测可用。
         *
         * <p>第三个参数 {@code false} 不能省。两参数的 {@code setval(seq, n)} 是
         * 「已经发到 n 了」，下一个发 n+1；而这里的 {@code next} 的含义是
         * <b>下一个要发的值</b>（调用方传的是 {@code 最大值 + 1}）。写成两参数形式
         * 会整整跳过一个值——不会报错，也不会丢数据，只是白白空一个号，
         * 正因为不报错才更难发现。
         *
         * <p>序列名用 {@code pg_get_serial_sequence} 现查，不去拼
         * {@code 表名_列名_seq}：那套命名在表名过长被截断、或表改过名之后就对不上了。
         */
        @Override
        public String restartAutoIncrementDdl(String schema, String table, String column,
                                              long next) {
            String qualified = literal(qualify(schema, table));
            // 第二个参数按 attname 原样比对，不做大小写折叠，所以这里传原始列名
            return "SELECT setval(pg_get_serial_sequence(" + qualified + ", "
                    + literal(column) + "), " + next + ", false)";
        }

        /**
         * 往已有表上加一个自增列——openGauss 上唯一走得通的办法。
         *
         * <h2>它连 serial 列都不让加</h2>
         * {@code ALTER TABLE ... ADD COLUMN id bigserial} 会被直接挡回来：
         * {@code It's not supported to alter table add serial column}。
         * identity 写法就更不用说了，连语法都过不去。
         *
         * <p>能走通的是把 serial 自己拆开——它本来就是「序列 + 默认值」的语法糖：
         * <ol>
         *   <li>{@code CREATE SEQUENCE}</li>
         *   <li>{@code ADD COLUMN} 一个普通整数列</li>
         *   <li>{@code SET DEFAULT nextval(序列)}</li>
         *   <li><b>回填已有行</b></li>
         *   <li>要求非空的话再 {@code SET NOT NULL}</li>
         *   <li>{@code OWNED BY}，让列被删时序列跟着走</li>
         * </ol>
         *
         * <h2>第 4 步不能省</h2>
         * 默认值只对<b>之后</b>插入的行生效，已有行拿到的是 NULL。而在真正的
         * PostgreSQL 上，加一个 identity 列会重写整张表、把已有行也编上号。
         * 少了回填，同一个操作在两家上结果不一样：这边留下一列半空的值，
         * 紧接着的 {@code SET NOT NULL} 会失败，或者（如果这列可空）安静地
         * 留下一堆 NULL，等到有人拿它当主键才炸。
         *
         * <h2>为什么敢一次发六条</h2>
         * openGauss 的 DDL 是事务性的（实测：中途故意写错一条，列和序列都没留下）。
         * 六条要么全成、要么全不成，不会卡在半路。
         *
         * <p>第 6 步的 {@code OWNED BY} 能用，而 {@code ALTER SEQUENCE ... RESTART}
         * 不能——同一个 ALTER SEQUENCE，它只实现了一部分，所以两处都得分别实测。
         */
        @Override
        public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
            if (change instanceof TableChange.AddColumn c
                    && c.column().autoIncrement()
                    && serialType(c.column().nativeType()) != null) {
                return addAutoIncrementColumn(schema, table, change, c.column());
            }
            return super.ddlFor(schema, table, change);
        }

        private List<TableChangeSql> addAutoIncrementColumn(String schema, String table,
                                                            TableChange change, ColumnDraft col) {
            String t = qualify(schema, table);
            String column = quote(col.name());
            // 跟 serial 自己的命名习惯一致，这样和「建表时就带自增」长出来的东西一个样
            String sequence = qualify(schema, table + "_" + col.name() + "_seq");
            String nextval = "nextval(" + literal(sequence) + ")";

            List<TableChangeSql> out = new ArrayList<>();
            out.add(new TableChangeSql(change, "CREATE SEQUENCE " + sequence));
            out.add(new TableChangeSql(change,
                    "ALTER TABLE " + t + " ADD COLUMN " + column + " " + col.fullType()));
            out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN "
                    + column + " SET DEFAULT " + nextval));
            out.add(new TableChangeSql(change, "UPDATE " + t + " SET " + column
                    + " = " + nextval + " WHERE " + column + " IS NULL"));
            if (!col.nullable()) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN "
                        + column + " SET NOT NULL"));
            }
            out.add(new TableChangeSql(change, "ALTER SEQUENCE " + sequence
                    + " OWNED BY " + t + "." + column));
            addComment(out, change, t, col);
            return out;
        }
    }

    /**
     * OceanBase：按 <b>MySQL 兼容模式</b>接入，方言整套继承 MySQL。
     *
     * <p>OceanBase 的租户可以建成 MySQL 或 Oracle 两种模式。这里只支持前者——
     * 那是默认模式，也是绝大多数部署用的。Oracle 模式的租户连上来之后，
     * 这里生成的语句（反引号、LIMIT、information_schema）全都对不上，
     * 报的会是一堆看不出根因的语法错。
     *
     * <p>真要支持 Oracle 模式，正确的做法是连上之后问一次
     * {@code SHOW VARIABLES LIKE 'ob_compatibility_mode'}，再据此挑方言——
     * 而不是让用户在界面上自己选：选错了不会立刻报错，
     * 只会在某条语句上莫名其妙地失败。
     */
    public static class OceanBaseDialect extends MySqlDialect {
    }

    public static class PostgresDialect extends StandardDialect {

        /**
         * {@code reltuples} 由 ANALYZE / autovacuum 维护。
         *
         * <p>PostgreSQL 14 起，从没被分析过的表这里是 -1（更早的版本是 0）——
         * 那是「不知道」而不是「空表」，读的时候按未知处理，宁可不显示也别显示一个假的 0。
         *
         * <p>{@code relkind} 只取普通表和分区表：索引、序列、TOAST 表也在 pg_class 里，
         * 混进来就会在树上冒出一堆用户从没建过的名字。
         */
        @Override
        public String tableRowCountQuery(String schema) {
            return "SELECT c.relname, c.reltuples::bigint FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = " + literal(schema)
                    + " AND c.relkind IN ('r','p')";
        }

        /**
         * 不加 ANALYZE：那会真的把语句跑一遍。
         *
         * <p>对一条 UPDATE 求执行计划时，这个区别就是「看一眼」和「改了数据」。
         */
        @Override
        public String explainQuery(String sql) {
            return "EXPLAIN (VERBOSE, COSTS) " + sql;
        }

        /** PostgreSQL：ON CONFLICT，覆盖时用 EXCLUDED 取本次要写的值。 */
        @Override
        public PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns,
                                       List<ColumnInfo> keyColumns, ConflictPolicy policy) {
            if (policy == ConflictPolicy.ABORT) {
                return buildInsert(schema, table, columns);
            }
            if (keyColumns.isEmpty()) {
                return null;
            }
            PreparedSql plain = buildInsert(schema, table, columns);
            StringBuilder keys = new StringBuilder();
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) {
                    keys.append(", ");
                }
                keys.append(quote(keyColumns.get(i).name()));
            }
            StringBuilder sb = new StringBuilder(plain.sql())
                    .append(" ON CONFLICT (").append(keys).append(") DO ");
            if (policy == ConflictPolicy.SKIP) {
                sb.append("NOTHING");
            } else {
                sb.append("UPDATE SET ");
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    String col = quote(columns.get(i).name());
                    sb.append(col).append(" = EXCLUDED.").append(col);
                }
            }
            return new PreparedSql(sb.toString(), plain.boundColumns());
        }


        /**
         * 改回 SQL 标准的写法，并且第四列取的是<b>真正的触发器体</b>。
         *
         * <p>这个类继承自给 H2 用的 StandardDialect，不显式覆盖就会去查 H2 才有的
         * {@code JAVA_CLASS} 列，在 PostgreSQL 上直接报列不存在。
         *
         * <h2>而 PostgreSQL 的 {@code action_statement} 不是触发器体</h2>
         *
         * 那一栏只有一句 {@code EXECUTE FUNCTION f()}——逻辑在函数里，不在触发器上。照搬那一栏的后果是：点「修改触发器」，
         * 编辑框里出现的是 {@code EXECUTE FUNCTION "public"."xx_fn"()}，
         * 而不是用户当初写的那段 plpgsql。
         *
         * <p>更糟的是<b>存回去</b>：编辑框里的内容会被当成函数体塞进
         * {@code CREATE OR REPLACE FUNCTION ... AS $$ ... $$}，
         * 于是用户原来的逻辑被一句 {@code EXECUTE FUNCTION} 顶掉——
         * 一次「打开看看又关掉」就足以让触发器失效，而且不会报错。
         *
         * <p>所以顺着 {@code pg_trigger.tgfoid} 找到那个函数，取它的
         * {@code prosrc}——那才是 {@code createTriggerDdl} 放进 {@code $$} 里的东西，
         * 读出来和写回去对得上。函数万一找不到就退回原来那一栏，不至于空着。
         *
         * <p>时机和事件仍然从 {@code information_schema.triggers} 取：
         * 那边一个事件一行，和界面上单选的形状一致；
         * 换成 {@code pg_trigger.tgtype} 的位运算就得自己拆位，多一处会错的地方。
         *
         * <p>{@code pg_class} 那一跳把模式一起限定死。只按表名连的话，
         * 别的模式里有同名表就会连出多行——同一个触发器名会重复出现。
         */
        @Override
        public String triggersQuery(String schema, String table) {
            return "SELECT t.trigger_name, t.action_timing, t.event_manipulation,"
                    + " COALESCE(p.prosrc, t.action_statement)"
                    + " FROM information_schema.triggers t"
                    + " LEFT JOIN pg_class c ON c.relname = " + literal(table)
                    + " AND c.relnamespace ="
                    + " (SELECT oid FROM pg_namespace WHERE nspname = " + literal(schema) + ")"
                    + " LEFT JOIN pg_trigger g ON g.tgrelid = c.oid"
                    + " AND g.tgname = t.trigger_name"
                    + " LEFT JOIN pg_proc p ON p.oid = g.tgfoid"
                    + " WHERE t.event_object_schema = " + literal(schema)
                    + " AND t.event_object_table = " + literal(table)
                    + " ORDER BY t.trigger_name";
        }

        /**
         * PostgreSQL 的例程。
         *
         * <p>排除聚合与窗口函数（{@code prokind} 只留 f/p）：那两类不是用户写的存储过程，
         * 而且 {@code pg_get_functiondef} 对聚合函数会直接报错。
         */
        @Override
        public String routineListQuery(String schema) {
            return "SELECT p.proname, p.oid::text FROM pg_proc p"
                    + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = " + literal(schema)
                    + " AND p.prokind IN ('f', 'p')"
                    + " ORDER BY p.proname";
        }

        /**
         * {@code pg_get_functiondef} 直接给出整条 {@code CREATE OR REPLACE FUNCTION}。
         *
         * <p>按 oid 取而不是按名字：PostgreSQL 允许函数重载，同名的可能有好几个，
         * 按名字取会漏掉除第一个之外的全部。清单那一步已经把 oid 带出来了，
         * 这里当作「类型」参数传回来用。
         */
        @Override
        public String routineSourceQuery(String schema, String name, String routineType) {
            return "SELECT pg_get_functiondef(" + literal(routineType) + "::oid)";
        }

        /**
         * PostgreSQL 的序列。
         *
         * <p>用 {@code pg_sequences}（10 起）而不是 {@code information_schema.sequences}：
         * 后者不带当前值，而「现在数到几了」正是看序列时最想知道的一件事。
         */
        @Override
        public String sequencesQuery(String schema) {
            return "SELECT sequencename,"
                    + " '当前 ' || COALESCE(last_value::text, '未使用')"
                    + " || ' · 步长 ' || increment_by"
                    + " FROM pg_sequences WHERE schemaname = " + literal(schema)
                    + " ORDER BY sequencename";
        }

        /**
         * {@code last_value} 是<b>刚发出去的那个号</b>，而且序列没被用过时是 NULL。
         * 所以下一个该发的是它加一个步长；没用过就还是原本的起始值。
         */
        @Override
        public String sequenceAttributesQuery(String schema) {
            return "SELECT sequencename,"
                    + " COALESCE((last_value + increment_by)::text, start_value::text),"
                    + " increment_by::text, min_value::text, max_value::text, cycle"
                    + " FROM pg_sequences WHERE schemaname = " + literal(schema)
                    + " ORDER BY sequencename";
        }

        @Override
        public String objectDefinitionQuery(com.plainly.driver.meta.DbObjects.ObjectKind kind,
                                            String schema, String name) {
            if (kind == com.plainly.driver.meta.DbObjects.ObjectKind.SEQUENCE) {
                return "SELECT 'start_value=' || start_value || E'\n'"
                        + " || 'min_value=' || min_value || E'\n'"
                        + " || 'max_value=' || max_value || E'\n'"
                        + " || 'increment_by=' || increment_by || E'\n'"
                        + " || 'cycle=' || cycle || E'\n'"
                        + " || 'last_value=' || COALESCE(last_value::text, '(未使用)')"
                        + " FROM pg_sequences WHERE schemaname = " + literal(schema)
                        + " AND sequencename = " + literal(name);
            }
            return null;
        }

        @Override
        public String truncateNote() {
            return "TRUNCATE 会立即清空全表，序列不自动重置，语句级触发器会触发、"
                    + "行级触发器不会。被别的表用外键引用着时会失败（除非一并 CASCADE）。"
                    + "PostgreSQL 的 DDL 可以回滚，放在事务里执行时能撤销。";
        }

        /** PostgreSQL 的触发器挂在<b>表</b>上，不是挂在 schema 下。 */
        @Override
        public String dropTriggerDdl(String schema, String table, String triggerName) {
            return "DROP TRIGGER " + quote(triggerName) + " ON " + qualify(schema, table);
        }

        /**
         * PostgreSQL 不能把逻辑直接写在触发器里：必须先建一个返回 {@code trigger} 的函数，
         * 再把触发器挂上去。所以模板给的是<b>两条语句</b>——少给一条，用户照着改完
         * 一执行就是「function does not exist」。
         */
        /**
         * PostgreSQL 要两条语句：先建返回 {@code trigger} 的函数，再把触发器挂上去。
         *
         * <p>所以这里连函数一起生成。少给一条，用户照着改完一执行就是
         * 「function does not exist」。DELETE 触发器要 {@code RETURN OLD}——
         * 返回 NEW 在 DELETE 上是空值，等于把这一行放过去。
         */
        @Override
        public String createTriggerDdl(String schema, String table, String triggerName,
                                       com.plainly.driver.meta.DbObjects.TriggerTiming timing,
                                       com.plainly.driver.meta.DbObjects.TriggerEvent event,
                                       String body) {
            String fn = quote(schema) + "." + quote(triggerName + "_fn");
            String fnBody = body == null || body.isBlank()
                    ? triggerBodyTemplate(event) : body.trim();
            return "CREATE OR REPLACE FUNCTION " + fn + "() RETURNS trigger AS " + TAG + "\n"
                    + fnBody + "\n"
                    + TAG + " LANGUAGE plpgsql;\n\n"
                    + "CREATE TRIGGER " + quote(triggerName) + "\n"
                    + timing.name() + " " + event.name() + " ON " + qualify(schema, table) + "\n"
                    + "FOR EACH ROW EXECUTE FUNCTION " + fn + "();";
        }

        /**
         * 函数体的美元引号用具名标签，不用光秃秃的 {@code $$}。
         *
         * <p>函数体是用户写的。里面一旦出现 {@code $$}（比如某段字符串常量），
         * 光秃秃的 {@code $$} 会在那里提前收口，剩下半截变成语法错——
         * 而报错位置指向的是用户没写过的地方，根本看不出是引号收早了。
         */
        private static final String TAG = "$plainly_trigger$";

        /**
         * PostgreSQL 的触发器体是一段 plpgsql 函数，<b>必须有 RETURN</b>。
         *
         * <p>默认模板只给 {@code BEGIN ... END}，那是 MySQL 的形状。用在这里，
         * 函数会返回 NULL——而 BEFORE 触发器返回 NULL 的含义是<b>丢弃这一行</b>，
         * 于是一个本意只想记条日志的触发器，会安静地把插入的数据吃掉。
         */
        @Override
        public String triggerBodyTemplate(com.plainly.driver.meta.DbObjects.TriggerEvent event) {
            String returned = event == com.plainly.driver.meta.DbObjects.TriggerEvent.DELETE
                    ? "OLD" : "NEW";
            return "BEGIN\n"
                    + "    -- " + rowRefHint(event) + "\n"
                    + "    RETURN " + returned + ";\n"
                    + "END;";
        }


        /**
         * PostgreSQL 的类型检查比 MySQL 严格得多：把文本绑到 timestamp 列会直接报
         * “column is of type timestamp but expression is of type character varying”。
         * 显式 CAST 让服务端按目标类型解析，同时保持参数化。
         */
        @Override
        public String placeholder(ColumnInfo column) {
            switch (column.category()) {
                case TEMPORAL:
                case JSON:
                case OTHER:
                    return "CAST(? AS " + column.nativeType() + ")";
                default:
                    return "?";
            }
        }

        /** PG 没有 AUTO_INCREMENT，自增靠 GENERATED AS IDENTITY；注释是独立语句。 */
        @Override
        public String columnDefinition(ColumnDraft c) {
            StringBuilder sb = new StringBuilder(quote(c.name())).append(' ').append(c.fullType());
            if (c.autoIncrement()) {
                sb.append(" GENERATED BY DEFAULT AS IDENTITY");
            }
            sb.append(c.nullable() ? " NULL" : " NOT NULL");
            if (c.defaultValue() != null) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            return sb.toString();
        }

        /** PostgreSQL 是少数支持事务性 DDL 的数据库，结构变更可以整体回滚。 */
        @Override
        public boolean supportsTransactionalDdl() {
            return true;
        }

        @Override
        public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
            String t = qualify(schema, table);

            if (change instanceof TableChange.RenameColumn c) {
                List<TableChangeSql> out = new ArrayList<>();
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " RENAME COLUMN "
                        + quote(c.from().name()) + " TO " + quote(c.to().name())));
                if (c.definitionAlsoChanged()) {
                    alterInPlace(out, change, t, c.from(), c.to());
                }
                return out;
            }

            if (change instanceof TableChange.ChangePrimaryKey c) {
                List<TableChangeSql> out = new ArrayList<>();
                if (!c.from().isEmpty()) {
                    // PG 的主键是具名约束，按惯例叫 <表名>_pkey。
                    // 建表时显式命名过的表会对不上，此时这条语句会报错而不是误删别的约束。
                    out.add(new TableChangeSql(change, "ALTER TABLE " + t
                            + " DROP CONSTRAINT " + quote(table + "_pkey")));
                }
                if (!c.to().isEmpty()) {
                    out.add(new TableChangeSql(change, "ALTER TABLE " + t
                            + " ADD PRIMARY KEY (" + quoteAll(c.to()) + ")"));
                }
                return out;
            }

            return super.ddlFor(schema, table, change);
        }

        /** PG 改类型要带 USING，否则跨类型转换会被拒。 */
        @Override
        protected void alterInPlace(List<TableChangeSql> out, TableChange change, String t,
                                    ColumnInfo from, ColumnDraft to) {
            String col = quote(to.name());
            if (!from.displayType().equalsIgnoreCase(to.fullType())) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN " + col
                        + " TYPE " + to.fullType() + " USING " + col + "::" + to.fullType()));
            }
            if (from.nullable() != to.nullable()) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN " + col
                        + (to.nullable() ? " DROP NOT NULL" : " SET NOT NULL")));
            }
            if (!sameDefault(from.defaultValue(), to.defaultValue())) {
                out.add(new TableChangeSql(change, "ALTER TABLE " + t + " ALTER COLUMN " + col
                        + (to.defaultValue() == null
                        ? " DROP DEFAULT" : " SET DEFAULT " + to.defaultValue())));
            }
            addComment(out, change, t, to);
        }
    }

    // ================================================================ SQLite

    /**
     * SQLite 的 ALTER 能力极其有限：只能加列、删列、改列名、改表名，
     * <b>改不了列定义，也改不了主键</b>——那需要「建新表 → 拷数据 → 删旧表 → 改名」四步重建。
     *
     * <p>这里选择如实拒绝而不是偷偷帮用户重建表：重建会丢索引、触发器和外键，
     * 属于用户必须知情后自己决定的事。
     */
    public static class SqliteDialect extends StandardDialect {

        /** 建表时就没写出自增，自然也没有计数器可推。 */
        @Override
        public String restartAutoIncrementDdl(String schema, String table, String column,
                                              long next) {
            return null;
        }

        /**
         * SQLite 的自增表达不出来，所以如实说，而不是安静地少写一段。
         *
         * <p>它的自增只有一种写法：{@code INTEGER PRIMARY KEY AUTOINCREMENT}，
         * 而且必须写在列定义里、类型必须<b>正好</b>是 INTEGER、还不能再另外声明
         * 一个 {@code PRIMARY KEY (...)} 子句——本类生成建表语句的方式恰好是后者。
         * 硬凑要改的是整条建表语句的形状，而 SQLite 上「复制表」本来就少用。
         */
        @Override
        public String autoIncrementUnsupportedReason() {
            return "SQLite 的自增必须写成 INTEGER PRIMARY KEY AUTOINCREMENT，"
                    + "本工具生成的建表语句表达不了，复制出来的表要自己补一次";
        }

        /** SQLite 的写法是 EXPLAIN QUERY PLAN，光 EXPLAIN 给的是虚拟机字节码。 */
        @Override
        public String explainQuery(String sql) {
            return "EXPLAIN QUERY PLAN " + sql;
        }

        /** SQLite：INSERT OR IGNORE，或者 ON CONFLICT DO UPDATE（excluded 是小写的）。 */
        @Override
        public PreparedSql buildInsert(String schema, String table, List<ColumnInfo> columns,
                                       List<ColumnInfo> keyColumns, ConflictPolicy policy) {
            if (policy == ConflictPolicy.ABORT) {
                return buildInsert(schema, table, columns);
            }
            PreparedSql plain = buildInsert(schema, table, columns);
            if (policy == ConflictPolicy.SKIP) {
                return new PreparedSql(
                        plain.sql().replaceFirst("(?i)^INSERT INTO", "INSERT OR IGNORE INTO"),
                        plain.boundColumns());
            }
            if (keyColumns.isEmpty()) {
                return null;
            }
            StringBuilder keys = new StringBuilder();
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) {
                    keys.append(", ");
                }
                keys.append(quote(keyColumns.get(i).name()));
            }
            StringBuilder sb = new StringBuilder(plain.sql())
                    .append(" ON CONFLICT (").append(keys).append(") DO UPDATE SET ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String col = quote(columns.get(i).name());
                sb.append(col).append(" = excluded.").append(col);
            }
            return new PreparedSql(sb.toString(), plain.boundColumns());
        }


        /** SQLite 没有序列，也没有 information_schema。 */
        @Override
        public String sequencesQuery(String schema) {
            return null;
        }

        @Override
        public String sequenceAttributesQuery(String schema) {
            return null;
        }

        /** SQLite 的视图定义也在 sqlite_master 里，而且给的是整条 CREATE VIEW。 */
        @Override
        public String viewDefinitionQuery(String schema, String view) {
            return "SELECT sql FROM sqlite_master WHERE type = 'view' AND name = "
                    + literal(view);
        }

        /** SQLite 没有 CREATE OR REPLACE VIEW，只能先删后建。 */
        @Override
        public String createOrReplaceViewDdl(String schema, String view, String selectSql) {
            return "CREATE VIEW " + qualify(schema, view) + " AS\n" + selectSql;
        }

        /** SQLite 没有 information_schema，触发器躺在 sqlite_master 里，只有一条建表语句。 */
        @Override
        public String triggersQuery(String schema, String table) {
            return "SELECT name, '', '', sql FROM sqlite_master"
                    + " WHERE type = 'trigger' AND tbl_name = " + literal(table)
                    + " ORDER BY name";
        }

        /**
         * SQLite 没有 {@code TRUNCATE}。
         *
         * <p>不带 WHERE 的 {@code DELETE} 会走它内部的截断优化，速度接近，
         * 但语义仍是 DELETE：会触发触发器，而且<b>不会重置 AUTOINCREMENT</b>。
         * 这个差别写在 {@link #truncateNote()} 里，不假装两者一样。
         */
        @Override
        public String truncateTableDdl(String schema, String table) {
            return "DELETE FROM " + quote(table);
        }

        @Override
        public String truncateNote() {
            return "SQLite 没有 TRUNCATE，这里执行的是不带 WHERE 的 DELETE："
                    + "速度接近，但会触发触发器，且 AUTOINCREMENT 计数不会归零。"
                    + "它在事务里，可以回滚。";
        }

        @Override
        public String schemaCreationUnsupportedReason() {
            return "SQLite 一个文件就是一个库，没有「在库里再建库」这回事。"
                    + "要新建就新建一个数据库文件，然后加一条连接。";
        }

        /** SQLite 只有一个库，触发器名不做 schema 限定。 */
        @Override
        public String dropTriggerDdl(String schema, String table, String triggerName) {
            return "DROP TRIGGER " + quote(triggerName);
        }

        /** SQLite 的触发器只有行级，且必须以 {@code END;} 收尾。 */
        /** SQLite 的触发器只有行级，体里每条语句都要以分号结束，整段以 {@code END;} 收尾。 */
        @Override
        public String triggerBodyTemplate(com.plainly.driver.meta.DbObjects.TriggerEvent event) {
            return "BEGIN\n    -- " + rowRefHint(event) + "，每条语句都要以分号结束\nEND;";
        }

        /**
         * SQLite 的元数据里存的就是整条建表语句（sqlite_master.sql），
         * 没有单独的时机、事件两栏——改已有触发器时只能整条改。
         */
        @Override
        public boolean triggerActionIsFullStatement() {
            return true;
        }


        @Override
        public String qualify(String schema, String table) {
            // SQLite 只有一个库，不做 schema 限定
            return quote(table);
        }

        @Override
        public String dropIndexDdl(String schema, String table, String indexName) {
            return "DROP INDEX " + quote(indexName);
        }

        /** SQLite 的 DDL 参与事务，可以回滚。 */
        @Override
        public boolean supportsTransactionalDdl() {
            return true;
        }

        @Override
        public boolean supports(TableChange change) {
            if (change instanceof TableChange.ModifyColumn
                    || change instanceof TableChange.ChangePrimaryKey) {
                return false;
            }
            if (change instanceof TableChange.RenameColumn c) {
                return !c.definitionAlsoChanged();
            }
            return true;
        }

        @Override
        public String unsupportedReason(TableChange change) {
            if (change instanceof TableChange.ChangePrimaryKey) {
                return "SQLite 无法修改已有表的主键，需要重建表（会丢失索引与触发器）";
            }
            return "SQLite 无法修改已有列的定义，需要重建表（会丢失索引与触发器）";
        }

        @Override
        public List<TableChangeSql> ddlFor(String schema, String table, TableChange change) {
            if (change instanceof TableChange.RenameColumn c) {
                return List.of(new TableChangeSql(change, "ALTER TABLE " + qualify(schema, table)
                        + " RENAME COLUMN " + quote(c.from().name())
                        + " TO " + quote(c.to().name())));
            }
            // SQLite 不支持 COMMENT ON，注释只能写在建表语句里
            if (change instanceof TableChange.AddColumn c) {
                return List.of(new TableChangeSql(change, "ALTER TABLE " + qualify(schema, table)
                        + " ADD COLUMN " + columnDefinition(c.column())));
            }
            return super.ddlFor(schema, table, change);
        }

        @Override
        protected void addComment(List<TableChangeSql> out, TableChange change,
                                  String t, ColumnDraft column) {
            // SQLite 没有列注释
        }
    }

    // =========================================================== SQL Server

    /**
     * SQL Server。
     *
     * <p>三处和别家差得最远：标识符用方括号、分页要 {@code OFFSET ... FETCH}
     * （而且必须先有 ORDER BY）、以及删索引的 {@code DROP INDEX x ON t} 写法。
     *
     * <p>精度这一块用 {@code DECIMAL(38,10)}，与其余方言一致；
     * 但这套映射还没在真实实例上验证过，见 {@link com.plainly.driver.DbType#SQLSERVER}。
     */
    public static class SqlServerDialect extends StandardDialect {

        /**
         * SQL Server 的序列在 {@code sys.sequences} 里，不在 information_schema。
         *
         * <p>{@code current_value} 是 sql_variant，得转一下才能拼进字符串。
         */
        @Override
        public String sequencesQuery(String schema) {
            return "SELECT s.name,"
                    + " '当前 ' + CONVERT(varchar(40), s.current_value)"
                    + " + ' · 步长 ' + CONVERT(varchar(40), s.increment)"
                    + " FROM sys.sequences s"
                    + " JOIN sys.schemas c ON c.schema_id = s.schema_id"
                    + " WHERE c.name = " + literal(schema)
                    + " ORDER BY s.name";
        }

        /** {@code current_value} 是刚发出去的那个，下一个要加一个步长。 */
        @Override
        public String sequenceAttributesQuery(String schema) {
            return "SELECT s.name,"
                    + " CONVERT(varchar(40), CONVERT(bigint, s.current_value) + s.increment),"
                    + " CONVERT(varchar(40), s.increment),"
                    + " CONVERT(varchar(40), s.minimum_value),"
                    + " CONVERT(varchar(40), s.maximum_value),"
                    + " s.is_cycling"
                    + " FROM sys.sequences s"
                    + " JOIN sys.schemas c ON c.schema_id = s.schema_id"
                    + " WHERE c.name = " + literal(schema)
                    + " ORDER BY s.name";
        }

        /**
         * SQL Server 的种子只能用 DBCC 改，且 RESEED 给的是<b>当前值</b>而不是下一个，
         * 所以要减一。给了下一个值，就会白白跳过一个号。
         */
        @Override
        public String restartAutoIncrementDdl(String schema, String table, String column,
                                              long next) {
            return "DBCC CHECKIDENT ('" + schema + "." + table + "', RESEED, " + (next - 1) + ")";
        }

        /**
         * SQL Server 的自增是 {@code IDENTITY(种子, 步长)}，写在类型后面。
         *
         * <p>理由同 H2 那条：漏了不会报错，只会让第一条 INSERT 在很久以后失败。
         */
        @Override
        public String columnDefinition(ColumnDraft c) {
            if (!c.autoIncrement()) {
                return super.columnDefinition(c);
            }
            // IDENTITY 必须紧跟在类型后面，不能放到 NOT NULL 之后
            return quote(c.name()) + " " + c.fullType() + " IDENTITY(1,1)"
                    + (c.nullable() ? " NULL" : " NOT NULL");
        }

        /**
         * 行数从分区元数据里取。
         *
         * <p>{@code index_id IN (0,1)} 是堆表（0）或聚集索引（1）——一张表这两者只会有一个；
         * 不限定的话每个非聚集索引都会再贡献一份同样的行数，加出来是好几倍。
         */
        @Override
        public String tableRowCountQuery(String schema) {
            return "SELECT t.name, SUM(p.rows) FROM sys.tables t"
                    + " JOIN sys.schemas s ON s.schema_id = t.schema_id"
                    + " JOIN sys.partitions p ON p.object_id = t.object_id"
                    + " AND p.index_id IN (0,1)"
                    + " WHERE s.name = " + literal(schema)
                    + " GROUP BY t.name";
        }

        /** 触发器查回标准的 information_schema，别继承到 H2 那套 JAVA_CLASS。 */
        @Override
        public String triggersQuery(String schema, String table) {
            return "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT"
                    + " FROM INFORMATION_SCHEMA.TRIGGERS"
                    + " WHERE EVENT_OBJECT_SCHEMA = " + literal(schema)
                    + " AND EVENT_OBJECT_TABLE = " + literal(table);
        }

        @Override
        public String quote(String identifier) {
            return "[" + identifier.replace("]", "]]") + "]";
        }

        /**
         * 分页。
         *
         * <p>{@code OFFSET ... FETCH} 要求语句里有 ORDER BY，没有的话数据库直接报错。
         * 所以没给排序时补一个恒定表达式——它不改变结果，只为满足语法。
         */
        @Override
        public String selectPage(String schema, String table, String orderBy,
                                 int limit, int offset) {
            StringBuilder sb = new StringBuilder("SELECT * FROM ").append(qualify(schema, table));
            sb.append(" ORDER BY ");
            sb.append(orderBy == null || orderBy.isBlank() ? "(SELECT NULL)" : orderBy);
            sb.append(" OFFSET ").append(Math.max(0, offset)).append(" ROWS");
            if (limit > 0) {
                sb.append(" FETCH NEXT ").append(limit).append(" ROWS ONLY");
            }
            return sb.toString();
        }

        @Override
        public String limitOffset(int limit, int offset) {
            StringBuilder sb = new StringBuilder("OFFSET ").append(Math.max(0, offset))
                    .append(" ROWS");
            if (limit > 0) {
                sb.append(" FETCH NEXT ").append(limit).append(" ROWS ONLY");
            }
            return sb.toString();
        }

        /** SQL Server 的 DROP INDEX 要带表名。 */
        @Override
        public String dropIndexDdl(String schema, String table, String index) {
            return "DROP INDEX " + quote(index) + " ON " + qualify(schema, table);
        }

        /** 没有 CREATE OR REPLACE VIEW，用 CREATE OR ALTER（2016 SP1 起）。 */
        @Override
        public String createOrReplaceViewDdl(String schema, String view, String selectSql) {
            return "CREATE OR ALTER VIEW " + qualify(schema, view) + " AS\n" + selectSql;
        }

        @Override
        public String explainQuery(String sql) {
            // SET SHOWPLAN_ALL 需要单独一条语句和会话状态，放在这里不合适；
            // 估算计划用 EXPLAIN 的等价物在 SQL Server 上没有单语句写法，
            // 所以这里明确不支持，而不是发一条跑不通的语句
            return null;
        }
    }
}
