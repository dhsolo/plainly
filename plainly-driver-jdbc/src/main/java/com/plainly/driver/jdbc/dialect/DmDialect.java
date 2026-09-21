package com.plainly.driver.jdbc.dialect;

/**
 * 达梦 DM8。
 *
 * <p>DM 默认就跑在 Oracle 兼容语法下：双引号引标识符、标识符默认折成大写、
 * {@code MODIFY} 改列、{@code ALL_TRIGGERS} / {@code ALL_VIEWS} 这套数据字典视图，
 * 连 {@code OFFSET ... FETCH} 都认。所以这里继承 {@link OracleDialect}，
 * <b>只写真正不一样的地方</b>——把 Oracle 的整套抄一遍再改几处，
 * 以后 Oracle 那边修了 bug，这边不会跟着修。
 *
 * <h2>和 Oracle 不同的地方</h2>
 * <ul>
 *   <li><b>模式是模式，不是用户。</b>DM 的 {@code CREATE SCHEMA} 真的建模式，
 *       所以「新建库」在这里是能做的，而 Oracle 上做不了；</li>
 *   <li><b>系统模式名不同</b>（SYS / SYSSSO / SYSAUDITOR / SYSJOB），
 *       而且 {@code SYSDBA} <b>不是</b>系统模式——它是默认管理用户的模式，
 *       用户的表多半就建在那里，过滤掉会让人看不见自己的表。
 *       这条判断在 {@code JdbcConnection#isSystemSchema} 里；</li>
 *   <li><b>有自己的 {@code EXPLAIN}</b>，不必像 Oracle 那样先 EXPLAIN PLAN 再查表。</li>
 * </ul>
 */
public class DmDialect extends OracleDialect {

    /**
     * DM 的自增是 {@code IDENTITY(种子, 步长)}，不是 Oracle 那套标准写法。
     *
     * <p>和 {@link #explainQuery} 一样，这条依据的是 DM 的手册，没有在真实实例上跑过。
     * 放出来的理由也一样：写错了，用户看到的是建表时数据库直接回的一条语法错误——
     * 响的、当场可见的失败。而不写，用户拿到的是一张看着正常、
     * 直到第一条不带主键值的 INSERT 才炸的表，那要难查得多。
     */
    @Override
    protected String identityClause() {
        return " IDENTITY(1, 1)";
    }

    /** DM 的 {@code CREATE SCHEMA} 建的就是模式，不像 Oracle 那样等于建用户。 */
    @Override
    public String schemaCreationUnsupportedReason() {
        return null;
    }

    /**
     * DM 有单语句的 {@code EXPLAIN}。
     *
     * <p>这条依据的是 DM 的手册，没有在真实实例上跑过。放出来的理由是：
     * 万一不对，用户看到的是数据库直接回的一条错误——响的、看得见的失败，
     * 和精度那种静默丢失不是一个性质的风险。
     */
    @Override
    public String explainQuery(String sql) {
        return "EXPLAIN " + sql;
    }

    /**
     * DM 的 {@code ALL_TRIGGERS} 里那一列叫 {@code TRIGGERING_TYPE}，不是 Oracle 的
     * {@code TRIGGER_TYPE}。
     *
     * <p>这个差别的代价远大于它看起来的样子：继承 Oracle 那条语句发过去，DM 直接报
     * 「无效的列名」，而 {@code listTriggers} 会把异常吞掉返回空表——
     * 于是用户建完触发器回到列表，看到的是空的，只能怀疑是创建失败了。
     * 实际上触发器好好地建在那儿。
     *
     * <p>取值也和 Oracle 不完全一样：{@code TRIGGERING_TYPE} 是 {@code AFTER ROW}
     * 这种写法（Oracle 是 {@code AFTER EACH ROW}），不过分类只认其中的
     * BEFORE / AFTER，两种都对得上。
     *
     * <p>在 DM 8.1.2 实例上验证过。
     */
    @Override
    public String triggersQuery(String schema, String table) {
        return "SELECT TRIGGER_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, TRIGGER_BODY"
                + " FROM ALL_TRIGGERS"
                + " WHERE TABLE_OWNER = " + literal(schema)
                + " AND TABLE_NAME = " + literal(table)
                + " ORDER BY TRIGGER_NAME";
    }

    /**
     * DM 的 {@code TRIGGER_BODY} 存的是<b>整条</b> {@code CREATE OR REPLACE TRIGGER}
     * 语句，不像 Oracle 那样只存触发器体。
     *
     * <p>不区分的话，「修改触发器」会把这一整条语句当成触发器体、再往外套一层
     * {@code CREATE TRIGGER}，发出去必然是语法错误。
     *
     * <p>在 DM 8.1.2 实例上确认：读回来的是
     * {@code CREATE OR REPLACE TRIGGER "模式"."名字" AFTER ...} 开头的完整语句。
     */
    @Override
    public boolean triggerActionIsFullStatement() {
        return true;
    }

    /**
     * 但<b>新建</b>时不用整条语句——DM 照单全收 Oracle 那套
     * {@code CREATE OR REPLACE TRIGGER}，本工具拼得出来。
     *
     * <p>这两个标志必须分开，否则新建对话框里的「名称」输入框会变成摆设：
     * 整条语句模式下名字是写死在文本里的，改了输入框也不生效，
     * 点创建执行的仍是旧名字——表现为「新建的触发器没出现」，
     * 真相是又把同名那个覆盖了一遍。这一条是在 DM 实例上撞出来的。
     */
    @Override
    public boolean composesFullStatement() {
        return false;
    }

    /**
     * 达梦的驱动<b>会</b>报结果集的来源表名，和 Oracle 相反。
     *
     * <p>在 DM 8.1.2 上跑精度自检时确认：主键识别、结果集可编辑两项都通过，
     * 而 Oracle 那边同样两项不通过。不覆写回来的话，会跟着继承 Oracle 的
     * 那条限制，把达梦的数据网格白白变成只读。
     */
    @Override
    public boolean reportsResultSetTableNames() {
        return true;
    }

    @Override
    public String truncateNote() {
        return "TRUNCATE 会立即清空全表且无法回滚，自增列计数归零；"
                + "被外键引用着的表要先处理约束才能清。";
    }
}
