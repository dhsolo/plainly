package com.plainly.core.export;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbException;
import com.plainly.driver.Row;
import com.plainly.driver.TypeCategory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 导出实现。
 *
 * <h2>精度在这里最容易前功尽弃</h2>
 * 前面整条链路都把值当文本传，到了导出这一步只要有一处 {@code Double.parseDouble}，
 * 之前所有的克制就白费了。所以：
 * <ul>
 *   <li>CSV / JSON / SQL 天然写文本，无损；</li>
 *   <li>xlsx 的数值单元格底层就是 IEEE 754 双精度，<b>这是格式本身的限制，与语言无关</b>，
 *       所以由 {@link ExportOptions.LongNumberMode} 把选择权交给用户，默认写文本。</li>
 * </ul>
 */
public final class Exporters {

    /** 超过这个有效位数就认为「double 装不下」，需要按长数值处理。 */
    private static final int DOUBLE_SAFE_DIGITS = 15;

    /** 分批写盘的行数，同时也是进度回调的粒度。 */
    private static final int FLUSH_EVERY = 500;

    private Exporters() {
    }

    /** 导出。返回实际写出的行数。 */
    public static long export(RowSource source, ExportOptions options, LongConsumer progress) {
        Path target = options.target();
        if (target == null) {
            throw new DbException("未指定导出目标文件");
        }
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new DbException("无法创建导出目录：" + e.getMessage(), e);
        }

        switch (options.format()) {
            case CSV:
                return exportCsv(source, options, progress);
            case XLSX:
                return exportXlsx(source, options, progress);
            case JSON:
                return exportJson(source, options, progress);
            case SQL_INSERT:
                return exportSqlInsert(source, options, progress);
            default:
                throw new DbException("不支持的导出格式：" + options.format());
        }
    }

    /**
     * 这一列的这个值是否属于「长数值」——即写进 xlsx 数值单元格会失真。
     * 判据是有效位数而不是字符串长度：{@code 0.0000000001} 很长但只有 1 位有效数字。
     */
    public static boolean isLongNumber(ColumnMeta column, String value) {
        if (value == null || !column.category().isExact()) {
            return false;
        }
        try {
            return new BigDecimal(value.trim()).precision() > DOUBLE_SAFE_DIGITS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 结果集中有哪几列属于精确数值，供导出对话框提示用。 */
    public static List<ColumnMeta> exactNumericColumns(List<ColumnMeta> columns) {
        return columns.stream().filter(c -> c.category().isExact()).toList();
    }

    // ------------------------------------------------------------------ CSV

    private static long exportCsv(RowSource source, ExportOptions o, LongConsumer progress) {
        List<ColumnMeta> columns = source.columns();
        AtomicLong written = new AtomicLong();
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(o.target(), o.charset()))) {
            if (o.includeHeader()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        sb.append(o.delimiter());
                    }
                    sb.append(csvEscape(columns.get(i).label(), o.delimiter()));
                }
                w.write(sb.append('\n').toString());
            }
            source.forEach(row -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) {
                            sb.append(o.delimiter());
                        }
                        sb.append(csvCell(columns.get(i), row.get(i), o));
                    }
                    w.write(sb.append('\n').toString());
                    long n = written.incrementAndGet();
                    if (n % FLUSH_EVERY == 0) {
                        progress.accept(n);
                    }
                } catch (IOException e) {
                    throw new DbException("写入 CSV 失败：" + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new DbException("写入 CSV 失败：" + e.getMessage(), e);
        }
        progress.accept(written.get());
        return written.get();
    }

    private static String csvCell(ColumnMeta column, String value, ExportOptions o) {
        if (value == null) {
            return o.nullAsEmpty() ? "" : "NULL";
        }
        // Excel 打开 CSV 时会把长数字转成科学计数法，这是 Excel 的行为不是我们的。
        // ="..." 是让它老实当文本的通行写法。
        if (o.csvGuardLongNumbers() && isLongNumber(column, value)) {
            return "=\"" + value + "\"";
        }
        return csvEscape(value, o.delimiter());
    }

    private static String csvEscape(String value, char delimiter) {
        boolean needQuote = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------ XLSX

    private static long exportXlsx(RowSource source, ExportOptions o, LongConsumer progress) {
        List<ColumnMeta> columns = source.columns();
        AtomicLong written = new AtomicLong();

        // 滑动窗口保证内存占用与总行数无关
        try (SXSSFWorkbook wb = new SXSSFWorkbook(FLUSH_EVERY)) {
            SXSSFSheet sheet = wb.createSheet(safeSheetName(o.tableName()));

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            // 文本样式：把单元格格式设为 @（文本），Excel 就不会自作主张再解析一遍
            CellStyle textStyle = wb.createCellStyle();
            textStyle.setDataFormat(wb.createDataFormat().getFormat("@"));

            int rowIdx = 0;
            if (o.includeHeader()) {
                org.apache.poi.ss.usermodel.Row header = sheet.createRow(rowIdx++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell c = header.createCell(i);
                    c.setCellValue(columns.get(i).label());
                    c.setCellStyle(headerStyle);
                }
            }

            final int[] cursor = {rowIdx};
            source.forEach(row -> {
                org.apache.poi.ss.usermodel.Row r = sheet.createRow(cursor[0]++);
                for (int i = 0; i < columns.size(); i++) {
                    writeXlsxCell(r.createCell(i), columns.get(i), row.get(i), o, textStyle);
                }
                long n = written.incrementAndGet();
                if (n % FLUSH_EVERY == 0) {
                    progress.accept(n);
                }
            });

            try (OutputStream out = Files.newOutputStream(o.target())) {
                wb.write(out);
            }
            wb.dispose();
        } catch (IOException e) {
            throw new DbException("写入 xlsx 失败：" + e.getMessage(), e);
        }
        progress.accept(written.get());
        return written.get();
    }

    private static void writeXlsxCell(Cell cell, ColumnMeta column, String value,
                                      ExportOptions o, CellStyle textStyle) {
        if (value == null) {
            if (!o.nullAsEmpty()) {
                cell.setCellValue("NULL");
            }
            // nullAsEmpty 时留空单元格，与「空字符串」在 Excel 里是两回事
            return;
        }

        boolean numericColumn = column.category().isNumeric();
        boolean lossy = isLongNumber(column, value);

        // 只要用户选了文本，或者这个值写成数值一定会失真，就走文本
        if (!numericColumn
                || o.longNumberMode() == ExportOptions.LongNumberMode.TEXT
                || lossy && o.longNumberMode() == ExportOptions.LongNumberMode.TEXT) {
            cell.setCellValue(value);
            if (numericColumn) {
                cell.setCellStyle(textStyle);
            }
            return;
        }

        // 用户明确选择了数值单元格：照做，失真的后果已在对话框里逐位展示过
        try {
            cell.setCellValue(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            cell.setCellValue(value);
        }
    }

    private static String safeSheetName(String name) {
        String n = name == null || name.isBlank() ? "Sheet1" : name;
        n = n.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }

    // ------------------------------------------------------------------ JSON

    private static long exportJson(RowSource source, ExportOptions o, LongConsumer progress) {
        List<ColumnMeta> columns = source.columns();
        AtomicLong written = new AtomicLong();
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(o.target(), o.charset()))) {
            w.write("[\n");
            source.forEach(row -> {
                try {
                    if (written.get() > 0) {
                        w.write(",\n");
                    }
                    w.write("  {");
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) {
                            w.write(", ");
                        }
                        ColumnMeta c = columns.get(i);
                        w.write(jsonString(c.label()));
                        w.write(": ");
                        w.write(jsonValue(c, row.get(i)));
                    }
                    w.write("}");
                    long n = written.incrementAndGet();
                    if (n % FLUSH_EVERY == 0) {
                        progress.accept(n);
                    }
                } catch (IOException e) {
                    throw new DbException("写入 JSON 失败：" + e.getMessage(), e);
                }
            });
            w.write("\n]\n");
        } catch (IOException e) {
            throw new DbException("写入 JSON 失败：" + e.getMessage(), e);
        }
        progress.accept(written.get());
        return written.get();
    }

    private static String jsonValue(ColumnMeta column, String value) {
        if (value == null) {
            return "null";
        }
        if (column.category() == TypeCategory.BOOLEAN) {
            return "1".equals(value) || "true".equalsIgnoreCase(value) ? "true" : "false";
        }
        // 精确数值一律输出为 JSON 字符串。
        // JSON number 在绝大多数解析器里就是 double，写成裸数字等于把精度扔了。
        if (column.category().isExact()) {
            return jsonString(value);
        }
        if (column.category() == TypeCategory.APPROX_NUMERIC) {
            return value;
        }
        return jsonString(value);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ SQL

    private static long exportSqlInsert(RowSource source, ExportOptions o, LongConsumer progress) {
        List<ColumnMeta> columns = source.columns();
        AtomicLong written = new AtomicLong();

        StringBuilder colList = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                colList.append(", ");
            }
            colList.append(quoteIdentifier(o, columns.get(i).name()));
        }
        String prefix = "INSERT INTO " + quoteIdentifier(o, o.tableName())
                + " (" + colList + ") VALUES (";

        try (Writer w = new BufferedWriter(Files.newBufferedWriter(o.target(), o.charset()))) {
            if (o.includeDdl() && o.ddl() != null && !o.ddl().isBlank()) {
                w.write(o.ddl().strip());
                w.write("\n\n");
            }
            source.forEach(row -> {
                try {
                    StringBuilder sb = new StringBuilder(prefix);
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(sqlLiteral(columns.get(i), row.get(i)));
                    }
                    w.write(sb.append(");\n").toString());
                    long n = written.incrementAndGet();
                    if (n % FLUSH_EVERY == 0) {
                        progress.accept(n);
                    }
                } catch (IOException e) {
                    throw new DbException("写入 SQL 失败：" + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new DbException("写入 SQL 失败：" + e.getMessage(), e);
        }
        progress.accept(written.get());
        return written.get();
    }

    /**
     * 标识符引法交给方言。
     *
     * <p>写死反引号只在 MySQL 上成立：PostgreSQL 和 H2 见到反引号直接语法错误。
     * 建表语句本来就是方言生成的，INSERT 要是另起一套，同一个文件就跑不通。
     */
    private static String quoteIdentifier(ExportOptions o, String name) {
        return o.dialect() == null ? "`" + name + "`" : o.dialect().quote(name);
    }

    private static String sqlLiteral(ColumnMeta column, String value) {
        if (value == null) {
            return "NULL";
        }
        // 数值直接写字面量：这是最无损的导出形式，一位不差
        if (column.category().isNumeric()) {
            return value;
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
