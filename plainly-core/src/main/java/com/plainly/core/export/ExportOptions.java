package com.plainly.core.export;

import com.plainly.driver.SqlDialect;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** 导出参数。 */
public class ExportOptions {

    /** 导出格式。 */
    public enum Format {
        CSV("CSV", "csv", "纯文本，无精度风险"),
        XLSX("Excel .xlsx", "xlsx", "数值单元格为双精度"),
        JSON("JSON", "json", "可选数值保真模式"),
        SQL_INSERT("SQL INSERT", "sql", "字面量，完全无损");

        private final String label;
        private final String extension;
        private final String note;

        Format(String label, String extension, String note) {
            this.label = label;
            this.extension = extension;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String extension() {
            return extension;
        }

        public String note() {
            return note;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 长数值的落盘方式。
     *
     * <p>这是导出环节唯一真正会毁掉数据的选择，所以必须显式呈现给用户，
     * 而不是替他默默决定。
     */
    public enum LongNumberMode {
        /** 写成文本，完整保留全部有效位。 */
        TEXT("写为文本单元格", "完整保留全部有效位，Excel 中该列为文本格式，不参与公式计算"),
        /** 写成数值，超过 15 位有效数字会被 IEEE 754 截断且不可恢复。 */
        NUMERIC("写为数值单元格", "xlsx 数值底层是 IEEE 754 双精度，超过 15 位有效数字将被截断且不可恢复");

        private final String label;
        private final String note;

        LongNumberMode(String label, String note) {
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

    private Format format = Format.CSV;
    private LongNumberMode longNumberMode = LongNumberMode.TEXT;
    private boolean includeHeader = true;
    private boolean nullAsEmpty = true;
    private char delimiter = ',';
    private Charset charset = StandardCharsets.UTF_8;
    /** CSV：给长数值加上 Excel 的 ="..." 包裹，避免打开时被转成科学计数法。 */
    private boolean csvGuardLongNumbers = true;
    private Path target;
    private String tableName = "exported";
    /**
     * 建表语句。由调用方备好——生成 DDL 要有表结构和方言，那是驱动层的事，
     * 导出这一层只管把它写在 INSERT 前面。
     */
    private String ddl;
    private boolean includeDdl;
    /** 标识符引法。缺省时退回反引号，那只在 MySQL 上是对的。 */
    private SqlDialect dialect;

    public Format format() {
        return format;
    }

    public ExportOptions setFormat(Format format) {
        this.format = format;
        return this;
    }

    public LongNumberMode longNumberMode() {
        return longNumberMode;
    }

    public ExportOptions setLongNumberMode(LongNumberMode mode) {
        this.longNumberMode = mode;
        return this;
    }

    public boolean includeHeader() {
        return includeHeader;
    }

    public ExportOptions setIncludeHeader(boolean includeHeader) {
        this.includeHeader = includeHeader;
        return this;
    }

    public boolean nullAsEmpty() {
        return nullAsEmpty;
    }

    public ExportOptions setNullAsEmpty(boolean nullAsEmpty) {
        this.nullAsEmpty = nullAsEmpty;
        return this;
    }

    public char delimiter() {
        return delimiter;
    }

    public ExportOptions setDelimiter(char delimiter) {
        this.delimiter = delimiter;
        return this;
    }

    public Charset charset() {
        return charset;
    }

    public ExportOptions setCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    public boolean csvGuardLongNumbers() {
        return csvGuardLongNumbers;
    }

    public ExportOptions setCsvGuardLongNumbers(boolean guard) {
        this.csvGuardLongNumbers = guard;
        return this;
    }

    public Path target() {
        return target;
    }

    public ExportOptions setTarget(Path target) {
        this.target = target;
        return this;
    }

    public String tableName() {
        return tableName;
    }

    public ExportOptions setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String ddl() {
        return ddl;
    }

    public ExportOptions setDdl(String ddl) {
        this.ddl = ddl;
        return this;
    }

    public boolean includeDdl() {
        return includeDdl;
    }

    public ExportOptions setIncludeDdl(boolean includeDdl) {
        this.includeDdl = includeDdl;
        return this;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public ExportOptions setDialect(SqlDialect dialect) {
        this.dialect = dialect;
        return this;
    }
}
