package com.plainly.core.export;

import com.plainly.driver.ColumnMeta;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbException;
import com.plainly.driver.Row;
import com.plainly.driver.TypeCategory;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.ForeignKeyInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 数据字典：把库结构导成一份能交出去的文档。
 *
 * <h2>为什么值得单独做</h2>
 * 表结构这些信息工具里到处都是——结构页、DDL 页、ER 图——但它们都只能<b>看</b>。
 * 评审、交付、对接第三方时要的是一份能发出去的文件，而现在唯一的办法是
 * 一张表一张表地截图。
 *
 * <h2>三种格式各有各的用处</h2>
 * <ul>
 *   <li><b>HTML</b>：带目录、能直接在浏览器里翻，发给人看的就是它；</li>
 *   <li><b>Markdown</b>：进 wiki、进代码仓库、进 PR 描述；</li>
 *   <li><b>xlsx / CSV</b>：一列一行的扁平表，给需要在表格软件里筛选统计的人。</li>
 * </ul>
 * 前两种在这里直接渲染成文本，后两种交给 {@link Exporters}——
 * 那边已经处理好了长数值、编码这些事，不该再抄一遍。
 */
public final class DataDictionary {

    /** 一张表在字典里的全部内容。 */
    public record TableDoc(TableStructure structure, List<ForeignKeyInfo> foreignKeys) {
    }

    private DataDictionary() {
    }

    /**
     * 收集若干张表的结构。
     *
     * <p>外键读不到不算失败：权限不足或驱动不支持时那一栏空着就是了，
     * 而整份字典的主体——字段清单——仍然是完整的。
     */
    public static List<TableDoc> collect(DbConnection conn, String schema, List<String> tables,
                                         Consumer<String> progress) {
        List<TableDoc> out = new ArrayList<>(tables.size());
        for (String table : tables) {
            if (progress != null) {
                progress.accept(table);
            }
            TableStructure structure = conn.describeTable(schema, table);
            List<ForeignKeyInfo> keys;
            try {
                keys = conn.listForeignKeys(schema, table);
            } catch (RuntimeException e) {
                keys = List.of();
            }
            out.add(new TableDoc(structure, keys));
        }
        return out;
    }

    // ------------------------------------------------------------------ Markdown

    public static String renderMarkdown(String title, String schema, List<TableDoc> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("库：`").append(schema).append("`  ·  共 ").append(docs.size())
                .append(" 张表  ·  生成于 ")
                .append(java.time.LocalDateTime.now().withNano(0)).append("\n\n");

        sb.append("## 目录\n\n");
        for (TableDoc doc : docs) {
            sb.append("- [").append(doc.structure().table().name()).append("](#")
                    .append(anchor(doc.structure().table().name())).append(')');
            String comment = doc.structure().table().comment();
            if (comment != null && !comment.isBlank()) {
                sb.append(" —— ").append(oneLine(comment));
            }
            sb.append('\n');
        }
        sb.append('\n');

        for (TableDoc doc : docs) {
            sb.append("## ").append(doc.structure().table().name()).append("\n\n");
            String comment = doc.structure().table().comment();
            if (comment != null && !comment.isBlank()) {
                sb.append(oneLine(comment)).append("\n\n");
            }
            if (doc.structure().table().rowEstimate() >= 0) {
                sb.append("行数估算：约 ").append(doc.structure().table().rowEstimate())
                        .append("（来自统计信息，不是 COUNT(*)）\n\n");
            }

            sb.append("| 字段 | 类型 | 可空 | 主键 | 默认值 | 说明 |\n");
            sb.append("| --- | --- | --- | --- | --- | --- |\n");
            for (ColumnInfo c : doc.structure().columns()) {
                sb.append("| `").append(c.name()).append("` | `").append(c.displayType())
                        .append("` | ").append(c.nullable() ? "是" : "否")
                        .append(" | ").append(c.primaryKey() ? "是" : "")
                        .append(" | ").append(mdCell(c.defaultValue()))
                        .append(" | ").append(mdCell(c.comment()))
                        .append(" |\n");
            }
            sb.append('\n');

            if (!doc.structure().indexes().isEmpty()) {
                sb.append("**索引**\n\n");
                for (IndexInfo idx : doc.structure().indexes()) {
                    sb.append("- `").append(idx.name()).append("`（")
                            .append(String.join(", ", idx.columns())).append("）")
                            .append(idx.primary() ? " · 主键" : (idx.unique() ? " · 唯一" : ""))
                            .append('\n');
                }
                sb.append('\n');
            }
            if (!doc.foreignKeys().isEmpty()) {
                sb.append("**外键**\n\n");
                for (ForeignKeyInfo fk : doc.foreignKeys()) {
                    sb.append("- ").append(String.join(", ", fk.columns()))
                            .append(" → `").append(fk.refTable()).append("`(")
                            .append(String.join(", ", fk.refColumns())).append(")\n");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Markdown 表格里的一个格子。
     *
     * <p>竖线必须转义，换行必须压掉——一个带竖线的注释会把整行表格撑散，
     * 而那一行看起来像是「渲染坏了」，没人会想到是注释里有个 {@code |}。
     */
    private static String mdCell(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return oneLine(text).replace("|", "\\|");
    }

    private static String oneLine(String text) {
        return text.replace("\r", " ").replace("\n", " ").trim();
    }

    /** GitHub 风格的锚点：小写、空格换连字符、去掉别的符号。 */
    private static String anchor(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (c == ' ' || c == '_' || c == '-') {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTML

    public static String renderHtml(String title, String schema, List<TableDoc> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n<title>").append(escape(title))
                .append("</title>\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");
        sb.append("<h1>").append(escape(title)).append("</h1>\n");
        sb.append("<p class=\"meta\">库 <code>").append(escape(schema)).append("</code> · 共 ")
                .append(docs.size()).append(" 张表 · 生成于 ")
                .append(java.time.LocalDateTime.now().withNano(0)).append("</p>\n");

        sb.append("<h2>目录</h2>\n<ul class=\"toc\">\n");
        for (TableDoc doc : docs) {
            String name = doc.structure().table().name();
            sb.append("<li><a href=\"#t-").append(escape(anchor(name))).append("\">")
                    .append(escape(name)).append("</a>");
            String comment = doc.structure().table().comment();
            if (comment != null && !comment.isBlank()) {
                sb.append(" <span class=\"muted\">").append(escape(oneLine(comment)))
                        .append("</span>");
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");

        for (TableDoc doc : docs) {
            String name = doc.structure().table().name();
            sb.append("<h2 id=\"t-").append(escape(anchor(name))).append("\">")
                    .append(escape(name)).append("</h2>\n");
            String comment = doc.structure().table().comment();
            if (comment != null && !comment.isBlank()) {
                sb.append("<p>").append(escape(oneLine(comment))).append("</p>\n");
            }
            sb.append("<table>\n<thead><tr><th>字段</th><th>类型</th><th>可空</th>")
                    .append("<th>主键</th><th>默认值</th><th>说明</th></tr></thead>\n<tbody>\n");
            for (ColumnInfo c : doc.structure().columns()) {
                sb.append("<tr><td><code>").append(escape(c.name())).append("</code></td>")
                        .append("<td><code>").append(escape(c.displayType())).append("</code></td>")
                        .append("<td>").append(c.nullable() ? "是" : "否").append("</td>")
                        .append("<td>").append(c.primaryKey() ? "●" : "").append("</td>")
                        .append("<td>").append(escape(nz(c.defaultValue()))).append("</td>")
                        .append("<td>").append(escape(nz(c.comment()))).append("</td></tr>\n");
            }
            sb.append("</tbody>\n</table>\n");

            if (!doc.structure().indexes().isEmpty()) {
                sb.append("<p class=\"sub\">索引</p>\n<ul>\n");
                for (IndexInfo idx : doc.structure().indexes()) {
                    sb.append("<li><code>").append(escape(idx.name())).append("</code>（")
                            .append(escape(String.join(", ", idx.columns()))).append("）")
                            .append(idx.primary() ? " · 主键" : (idx.unique() ? " · 唯一" : ""))
                            .append("</li>\n");
                }
                sb.append("</ul>\n");
            }
            if (!doc.foreignKeys().isEmpty()) {
                sb.append("<p class=\"sub\">外键</p>\n<ul>\n");
                for (ForeignKeyInfo fk : doc.foreignKeys()) {
                    sb.append("<li>").append(escape(String.join(", ", fk.columns())))
                            .append(" → <code>").append(escape(fk.refTable())).append("</code>(")
                            .append(escape(String.join(", ", fk.refColumns()))).append(")</li>\n");
                }
                sb.append("</ul>\n");
            }
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * HTML 转义。
     *
     * <p>注释和默认值里出现 {@code <} 或 {@code &} 完全正常
     * （{@code price < 100}、{@code a&b}），不转义的话整份文档从那里开始就乱了。
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static final String CSS = """
            body { font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
                   margin: 32px auto; max-width: 1100px; color: #1c1c1a; line-height: 1.6; }
            h1 { font-size: 22px; border-bottom: 2px solid #0f6f70; padding-bottom: 8px; }
            h2 { font-size: 16px; margin-top: 28px; color: #0a4f50; }
            p.meta, .muted { color: #8a8a84; font-size: 12px; }
            p.sub { font-weight: bold; margin-bottom: 4px; font-size: 13px; }
            ul.toc { columns: 3; font-size: 13px; }
            table { border-collapse: collapse; width: 100%; font-size: 13px; }
            th, td { border: 1px solid #d9d9d3; padding: 5px 8px; text-align: left;
                     vertical-align: top; }
            th { background: #f0f0ed; font-weight: bold; }
            tr:nth-child(even) td { background: #fafaf8; }
            code { font-family: Consolas, "Cascadia Mono", monospace; font-size: 12px; }
            a { color: #0f6f70; }
            """;

    // ------------------------------------------------------------------ 扁平表

    /**
     * 一列一行的扁平表，供 xlsx / CSV 导出。
     *
     * <p>形状刻意做成「一行一个字段」而不是「一行一张表」：在表格软件里，
     * 用得最多的就是「按类型筛一遍」「找出所有没有注释的字段」这类操作，
     * 而那要求每个字段自己占一行。
     */
    public static RowSource asRows(String schema, List<TableDoc> docs) {
        List<ColumnMeta> columns = List.of(
                meta("库"), meta("表"), meta("表说明"), meta("序号"), meta("字段"),
                meta("类型"), meta("可空"), meta("主键"), meta("自增"),
                meta("默认值"), meta("字段说明"));

        List<Row> rows = new ArrayList<>();
        for (TableDoc doc : docs) {
            String table = doc.structure().table().name();
            String tableComment = nz(doc.structure().table().comment());
            int ordinal = 0;
            for (ColumnInfo c : doc.structure().columns()) {
                ordinal++;
                rows.add(new Row(new String[]{
                        schema, table, tableComment, String.valueOf(ordinal), c.name(),
                        c.displayType(), c.nullable() ? "是" : "否",
                        c.primaryKey() ? "是" : "", c.autoIncrement() ? "是" : "",
                        nz(c.defaultValue()), nz(c.comment())}));
            }
        }
        if (rows.isEmpty()) {
            throw new DbException("一张表也没有，没什么可导的");
        }

        return new RowSource() {
            @Override
            public List<ColumnMeta> columns() {
                return columns;
            }

            @Override
            public void forEach(Consumer<Row> consumer) {
                rows.forEach(consumer);
            }

            @Override
            public long estimatedTotal() {
                return rows.size();
            }
        };
    }

    /**
     * 字典自己那几列的元数据。
     *
     * <p>一律声明成字符串：这里的「序号」看着是数字，但它是文档的一部分，
     * 不该在 xlsx 里变成一个能参与求和的数值单元格。
     */
    private static ColumnMeta meta(String name) {
        return new ColumnMeta(name, name, "VARCHAR", TypeCategory.STRING, 0, 0,
                true, "", "", false, false);
    }
}
