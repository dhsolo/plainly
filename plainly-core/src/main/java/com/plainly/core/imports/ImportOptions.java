package com.plainly.core.imports;

import com.plainly.driver.query.ConflictPolicy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 导入参数。 */
public class ImportOptions {

    /** 源文件格式。 */
    public enum Format {
        CSV("CSV", "csv", "文本，无精度损失"),
        JSON("JSON", "json", "裸数字按文本读取"),
        SQL("SQL 脚本", "sql", "直接执行，不走映射");

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
     * 数值的读入方式。
     *
     * <p>这是导入环节唯一真正会毁掉数据的选择，和导出那边的长数值处理是同一件事的两头，
     * 所以同样必须摆到用户面前，而不是替他默默决定。
     */
    public enum NumberMode {
        TEXT("按文本读入，绑定为 BigDecimal",
                "与网格、导出走同一条路径。文件里是什么字符，进库就是什么值。"),
        DOUBLE("按 double 解析后再写入",
                "超过 15 位有效数字的值会在解析阶段就被截断，写进库的已经是错的。");

        private final String label;
        private final String note;

        NumberMode(String label, String note) {
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

    /**
     * 一列的去向。
     *
     * @param sourceIndex 文件里的第几列
     * @param targetColumn 目标字段名；null 表示不导入
     */
    public record ColumnMapping(int sourceIndex, String sourceName, String targetColumn) {

        public boolean skipped() {
            return targetColumn == null || targetColumn.isBlank();
        }
    }

    private Format format = Format.CSV;
    private Path source;
    private boolean hasHeader = true;
    private char delimiter = ',';
    private Charset charset = StandardCharsets.UTF_8;
    private NumberMode numberMode = NumberMode.TEXT;
    private ConflictPolicy conflictPolicy = ConflictPolicy.SKIP;
    private int batchSize = 2000;
    private boolean writeErrorFile = true;
    private boolean dryRun;
    private List<ColumnMapping> mappings = new ArrayList<>();

    /**
     * 这份文件的第一行是不是字段名。
     *
     * <p>CSV 上这是用户勾的——文件里没有任何东西能告诉我们首行是表头还是数据。
     * JSON 上没得选：字段名写在每个对象里，读出来的第一行必然是它们的并集。
     * 把这个差别收在一处，导入管线那边就不必分两套走。
     */
    public boolean headerRowPresent() {
        return format == Format.JSON || hasHeader;
    }

    public Format format() {
        return format;
    }

    public ImportOptions setFormat(Format format) {
        this.format = format;
        return this;
    }

    public Path source() {
        return source;
    }

    public ImportOptions setSource(Path source) {
        this.source = source;
        return this;
    }

    public boolean hasHeader() {
        return hasHeader;
    }

    public ImportOptions setHasHeader(boolean hasHeader) {
        this.hasHeader = hasHeader;
        return this;
    }

    public char delimiter() {
        return delimiter;
    }

    public ImportOptions setDelimiter(char delimiter) {
        this.delimiter = delimiter;
        return this;
    }

    public Charset charset() {
        return charset;
    }

    public ImportOptions setCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    public NumberMode numberMode() {
        return numberMode;
    }

    public ImportOptions setNumberMode(NumberMode numberMode) {
        this.numberMode = numberMode;
        return this;
    }

    public ConflictPolicy conflictPolicy() {
        return conflictPolicy;
    }

    public ImportOptions setConflictPolicy(ConflictPolicy policy) {
        this.conflictPolicy = policy;
        return this;
    }

    public int batchSize() {
        return batchSize;
    }

    public ImportOptions setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        return this;
    }

    public boolean writeErrorFile() {
        return writeErrorFile;
    }

    public ImportOptions setWriteErrorFile(boolean write) {
        this.writeErrorFile = write;
        return this;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public ImportOptions setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public List<ColumnMapping> mappings() {
        return mappings;
    }

    public ImportOptions setMappings(List<ColumnMapping> mappings) {
        this.mappings = new ArrayList<>(mappings);
        return this;
    }
}
