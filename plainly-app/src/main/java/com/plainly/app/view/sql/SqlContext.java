package com.plainly.app.view.sql;

import com.plainly.core.sql.SqlScopes;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 光标处的 SQL 上下文。
 *
 * <h2>这才是补全的难点</h2>
 * 弹窗本身是个 ListView，两小时能写完。真正花时间的是回答
 * 「用户敲下 {@code c.} 的这一刻，{@code c} 指的是哪张表」。
 *
 * <p>作用域分析已经挪到 {@link SqlScopes}——那是纯逻辑，能脱开界面单独测，
 * 而补全对不对几乎全取决于它。这里只留两件界面相关的事：
 * 光标紧邻的那几个字符是什么，以及这个位置该补表名还是补关键字。
 *
 * <p>之前这里用一个正则把整段 SQL 的 FROM/JOIN 一把抓成平表，
 * 子查询一出现就开始安静地给错答案。现在按括号分层，别名在哪层有效就只在哪层补得出来。
 */
public final class SqlContext {

    /** 光标紧邻的 {@code 前缀.部分词} 或 {@code 部分词}。 */
    private static final Pattern AT_CARET = Pattern.compile(
            "(?:(?<qualifier>[\\w$]+)\\s*\\.\\s*)?(?<word>[\\w$]*)$");

    /** 光标正在敲的这个词之前的那个 SQL 词，用于判断该补什么。 */
    private static final Pattern PRECEDING_WORD = Pattern.compile("([\\w$]+)\\s+[\\w$]*$");

    private final String qualifier;
    private final String word;
    private final String precedingWord;
    private final SqlScopes.Scope scope;

    private SqlContext(String qualifier, String word, String precedingWord,
                       SqlScopes.Scope scope) {
        this.qualifier = qualifier;
        this.word = word;
        this.precedingWord = precedingWord;
        this.scope = scope;
    }

    /**
     * 解析出光标处的补全上下文。
     *
     * @param text     整段 SQL
     * @param caretPos 光标位置
     */
    public static SqlContext at(String text, int caretPos) {
        String safe = text == null ? "" : text;
        int caret = Math.max(0, Math.min(caretPos, safe.length()));
        String upToCaret = safe.substring(0, caret);

        String qualifier = null;
        String word = "";
        Matcher m = AT_CARET.matcher(upToCaret);
        if (m.find()) {
            qualifier = m.group("qualifier");
            word = m.group("word") == null ? "" : m.group("word");
        }

        String preceding = "";
        Matcher prev = PRECEDING_WORD.matcher(upToCaret);
        if (prev.find()) {
            preceding = prev.group(1);
        }

        // 整段分析，不只分析光标之前：用户常常先写 SELECT 再回头补 FROM，
        // 只看前文的话刚写的 FROM 就用不上了
        SqlScopes.Scope root = SqlScopes.analyze(safe);
        return new SqlContext(qualifier, word, preceding, SqlScopes.scopeAt(root, caret));
    }

    /**
     * 光标处期待的是一个表名吗？
     *
     * <p>{@code FROM }、{@code JOIN } 之后补表名而不是关键字——
     * 在这个位置把 SELECT、WHERE 之类混进候选里只会碍事。
     */
    public boolean expectsTable() {
        String w = precedingWord.toUpperCase(Locale.ROOT);
        return w.equals("FROM") || w.equals("JOIN") || w.equals("INTO")
                || w.equals("UPDATE") || w.equals("TABLE");
    }

    /**
     * 补上一个表名之后，该不该顺手给它起个别名。
     *
     * <p>只有 {@code FROM} 和 {@code JOIN} 之后才该起。{@link #expectsTable()} 认的
     * 位置比这里多两个，但那两个后面接别名是<b>语法错误</b>：
     * {@code INSERT INTO t x}、{@code CREATE TABLE t x}。
     *
     * <p>{@code UPDATE t} 之后刻意也不起：MySQL 允许 {@code UPDATE t AS a SET ...}，
     * SQL Server 不允许。给一个在半数库上跑不通的东西，不如不给。
     */
    public boolean expectsAlias() {
        String w = precedingWord.toUpperCase(Locale.ROOT);
        return w.equals("FROM") || w.equals("JOIN");
    }

    /** 光标前的限定符（{@code c.} 中的 {@code c}），没有则为 null。 */
    public String qualifier() {
        return qualifier;
    }

    /** 已经敲出的部分词，用于前缀过滤。 */
    public String word() {
        return word;
    }

    /** 是否处于 {@code 别名.} 之后——此时应当补字段而不是表名。 */
    public boolean isQualified() {
        return qualifier != null && !qualifier.isBlank();
    }

    /**
     * 限定符指向的关系。
     *
     * <p>返回 null 表示这个名字在<b>这个位置</b>确实不存在——比如它是另一个子查询里的别名。
     * 这时候界面该说「解析不出来」，而不是从别处捡一个同名的塞给用户。
     */
    public SqlScopes.Relation resolved() {
        return SqlScopes.resolve(scope, qualifier);
    }

    /**
     * 限定符指向的表名（不带库名限定）；指向的不是真实的表时返回 null。
     *
     * <p>派生表和 CTE 走 {@link #resolved()}：它们的字段查不到元数据里去，
     * 得从子查询的 SELECT 列表拿。
     */
    public String resolvedTable() {
        SqlScopes.Relation r = resolved();
        return r == null || r.kind() != SqlScopes.Kind.TABLE ? null : r.simpleTable();
    }

    /** 光标这个位置能看见的所有关系，由内向外。 */
    public List<SqlScopes.Relation> visibleRelations() {
        return SqlScopes.visible(scope);
    }

    /** 光标是不是在一个子查询里（而不是最外层）。用于在弹窗上标一句。 */
    public boolean inSubquery() {
        return scope.parent() != null;
    }
}
