package com.plainly.app.view.sql;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 语法着色。
 *
 * <p>正则实现，够用且快。真正需要语法树的是补全（判断光标处该补什么），
 * 那部分在 {@link SqlContext}——着色和补全的难度完全不在一个量级。
 */
public final class SqlHighlighter {

    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "INDEX", "VIEW", "DATABASE", "SCHEMA", "ADD", "COLUMN", "PRIMARY", "KEY",
            "FOREIGN", "REFERENCES", "UNIQUE", "NOT", "NULL", "DEFAULT", "AUTO_INCREMENT",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
            "AND", "OR", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "AS", "DISTINCT",
            "CASE", "WHEN", "THEN", "ELSE", "END", "UNION", "ALL", "ANY", "ASC", "DESC",
            "WITH", "RECURSIVE", "OVER", "PARTITION", "INTERVAL", "DAY", "MONTH", "YEAR",
            "HOUR", "MINUTE", "SECOND", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
            "EXPLAIN", "ANALYZE", "TRUNCATE", "REPLACE", "IF", "COMMENT", "ENGINE", "CHARSET"
    };

    private static final String[] FUNCTIONS = {
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "FLOOR", "CEIL", "ABS", "NOW",
            "CURDATE", "CURTIME", "DATE", "DATE_FORMAT", "CONCAT", "SUBSTRING", "SUBSTR",
            "TRIM", "UPPER", "LOWER", "LENGTH", "CHAR_LENGTH", "COALESCE", "IFNULL", "NULLIF",
            "CAST", "CONVERT", "ROW_NUMBER", "RANK", "DENSE_RANK", "GROUP_CONCAT",
            "TIMESTAMPDIFF", "DATEDIFF", "GREATEST", "LEAST", "JSON_EXTRACT"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>--[^\n]*|/\\*(?:.|\\R)*?\\*/)"
                    + "|(?<STRING>'(?:[^'\\\\]|\\\\.|'')*'|\"(?:[^\"\\\\]|\\\\.)*\")"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<FUNCTION>\\b(?:" + String.join("|", FUNCTIONS) + ")\\b(?=\\s*\\())"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)"
                    + "|(?<OPERATOR>[=<>!+\\-*/%,;()]+)",
            Pattern.CASE_INSENSITIVE);

    private SqlHighlighter() {
    }

    /** 关键字表，补全也用它——着色认得的词，补全就该补得出来。 */
    public static List<String> keywords() {
        return List.of(KEYWORDS);
    }

    /** 函数名。补全时补成 {@code COUNT(} 形式，省一次输入。 */
    public static List<String> functions() {
        return List.of(FUNCTIONS);
    }

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass = styleOf(matcher);
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private static String styleOf(Matcher m) {
        if (m.group("COMMENT") != null) {
            return "sql-comment";
        }
        if (m.group("STRING") != null) {
            return "sql-string";
        }
        if (m.group("FUNCTION") != null) {
            return "sql-function";
        }
        if (m.group("KEYWORD") != null) {
            return "sql-keyword";
        }
        if (m.group("NUMBER") != null) {
            return "sql-number";
        }
        return "sql-operator";
    }
}
