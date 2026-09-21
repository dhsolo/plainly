package com.plainly.core.export;

import com.plainly.driver.ColumnMeta;

import java.util.List;

/**
 * 把选中的格子变成一段可以粘出去的文本。
 *
 * <p>和 {@link Exporters} 的分工：那边面向文件、面向几十万行、要走磁盘和进度回调；
 * 这边面向剪贴板、面向手工选中的一小块，全部在内存里拼完就完事。
 * 硬凑成一套只会让两边都别扭——文件导出用不上「制表符分隔」，剪贴板用不上流式写盘。
 *
 * <h2>NULL 和空字符串必须分得开</h2>
 * 这是本类唯一真正要小心的地方。CSV / TSV 里把 NULL 写成空、把空字符串写成 {@code ""}
 * （带引号的空），粘到别处两者仍然是两样东西。要是都写成空，
 * 一次「复制—粘贴—再导入」就能把一列 NULL 悄悄变成一列空串，而没有任何提示。
 */
public final class ClipboardFormats {

    /** 复制成什么样子。 */
    public enum Format {
        TSV("制表符分隔", "贴进 Excel / 表格软件"),
        CSV("CSV", "逗号分隔，按 RFC4180 加引号"),
        MARKDOWN("Markdown 表格", "贴进文档或工单，换行会被压成一行"),
        JSON("JSON", "长数值写成字符串，避免读的人用 double 解析"),
        SQL_INSERT("INSERT 语句", "字面量，完全无损");

        private final String label;
        private final String note;

        Format(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 超过这个有效位数，JSON 里就写成字符串而不是裸数字。 */
    private static final int DOUBLE_SAFE_DIGITS = 15;

    private ClipboardFormats() {
    }

    /**
     * 渲染。
     *
     * @param columns   选中的列（顺序即输出顺序）
     * @param rows      选中的行，每行的长度与 {@code columns} 一致
     * @param header    是否输出表头；JSON 与 INSERT 忽略此项，它们本来就带字段名
     * @param tableName INSERT 用的表名；其余格式忽略
     */
    public static String render(Format format, List<ColumnMeta> columns, List<String[]> rows,
                                boolean header, String tableName) {
        switch (format) {
            case TSV:
                return delimited(columns, rows, header, '\t');
            case CSV:
                return delimited(columns, rows, header, ',');
            case MARKDOWN:
                return markdown(columns, rows);
            case JSON:
                return json(columns, rows);
            case SQL_INSERT:
                return insert(columns, rows, tableName);
            default:
                throw new IllegalArgumentException("未知格式：" + format);
        }
    }

    // ------------------------------------------------------------------ 分隔符

    /**
     * TSV / CSV。
     *
     * <p>制表符分隔也照 RFC4180 加引号，不是画蛇添足：值里本来就可能有制表符或换行，
     * 不加引号粘进 Excel 会当场错位，一列数据挪到下一列去还看不出来。
     * Excel 的剪贴板解析认双引号，所以加了反而是对的。
     */
    private static String delimited(List<ColumnMeta> columns, List<String[]> rows,
                                    boolean header, char delimiter) {
        StringBuilder sb = new StringBuilder();
        if (header) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(delimiter);
                }
                sb.append(quoteIfNeeded(columns.get(i).label(), delimiter));
            }
            sb.append('\n');
        }
        for (String[] row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(delimiter);
                }
                String v = i < row.length ? row[i] : null;
                // NULL → 空；空字符串 → ""。两者必须写得不一样
                sb.append(v == null ? "" : quoteIfNeeded(v, delimiter));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String quoteIfNeeded(String value, char delimiter) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean needs = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needs ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    // ------------------------------------------------------------------ Markdown

    /**
     * Markdown 表格。
     *
     * <p>这是唯一一个<b>有损</b>的格式：Markdown 的表格单元格放不下换行，
     * 只能压成一行。它的用途是贴进工单或文档给人看，不是搬运数据；
     * 界面上把这句写在选项旁边，选它的人应当知道自己在选什么。
     */
    private static String markdown(List<ColumnMeta> columns, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (ColumnMeta c : columns) {
            sb.append(' ').append(mdEscape(c.label())).append(" |");
        }
        sb.append('\n').append('|');
        for (ColumnMeta c : columns) {
            // 数值列右对齐，长数字才对得起来
            sb.append(c.category().isNumeric() ? " ---: |" : " --- |");
        }
        sb.append('\n');
        for (String[] row : rows) {
            sb.append('|');
            for (int i = 0; i < columns.size(); i++) {
                String v = i < row.length ? row[i] : null;
                sb.append(' ').append(v == null ? "*(NULL)*" : mdEscape(v)).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String mdEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    // ------------------------------------------------------------------ JSON

    /**
     * JSON 数组。
     *
     * <p>数值写不写引号按<b>列</b>决定，不按行：同一列一会儿是数字一会儿是字符串，
     * 下游解析器会当场翻脸。判据是这一列可能超出 double 的精确范围
     * （见 {@code ColumnMeta.riskyInDouble}）——会超的写成字符串，
     * 因为大部分 JSON 解析器读到裸数字就直接塞进 double 了。
     */
    private static String json(List<ColumnMeta> columns, List<String[]> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            sb.append("  {");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                ColumnMeta meta = columns.get(i);
                String v = i < row.length ? row[i] : null;
                sb.append(jsonString(meta.label())).append(": ");
                if (v == null) {
                    sb.append("null");
                } else if (rawNumber(meta, v)) {
                    sb.append(v);
                } else {
                    sb.append(jsonString(v));
                }
            }
            sb.append('}');
            if (r < rows.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        return sb.append("]\n").toString();
    }

    /** 这一列的值能不能安全地写成裸 JSON 数字。 */
    private static boolean rawNumber(ColumnMeta meta, String value) {
        if (!meta.category().isNumeric() || meta.riskyInDouble()) {
            return false;
        }
        // 值本身也得像个 JSON 数字：数据库里的 Infinity、NaN 写成裸的就不是合法 JSON 了
        return value.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")
                && digitsOf(value) <= DOUBLE_SAFE_DIGITS;
    }

    private static int digitsOf(String value) {
        int n = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ INSERT

    /**
     * INSERT 语句。
     *
     * <p>数值一律写成裸字面量，一位不差——这是所有格式里最无损的一种。
     * 引号用双写（{@code ''}）而不是反斜杠：双写是标准写法，各家都认。
     */
    private static String insert(List<ColumnMeta> columns, List<String[]> rows, String tableName) {
        String table = tableName == null || tableName.isBlank() ? "表名" : tableName;
        StringBuilder head = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                head.append(", ");
            }
            head.append(columns.get(i).name());
        }
        head.append(") VALUES (");

        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(head);
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(sqlLiteral(columns.get(i), i < row.length ? row[i] : null));
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private static String sqlLiteral(ColumnMeta column, String value) {
        if (value == null) {
            return "NULL";
        }
        if (column.category().isNumeric()) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
