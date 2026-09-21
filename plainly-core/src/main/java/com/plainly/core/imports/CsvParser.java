package com.plainly.core.imports;

import com.plainly.driver.DbException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CSV 解析。
 *
 * <p>不用 {@code split(",")}：逗号可以合法地待在引号里，换行也可以。
 * 按字面切会把一行拆成几行、把一个字段拆成几个，而且不报错——
 * 错位之后每一列都对不上，等到入库才发现已经晚了。
 *
 * <p>按 RFC 4180 处理：字段可用双引号包起来，引号内的双引号写成两个，
 * 引号内允许出现分隔符与换行。BOM 会被吃掉——Excel 存的 UTF-8 默认带 BOM，
 * 不处理的话第一列列名会莫名其妙多个不可见字符，映射就永远匹配不上。
 *
 * <p>值一律是字符串。CSV 里是什么字符，读出来就是什么字符，
 * 是不是数字、精度多少，那是绑定那一步的事。
 */
public final class CsvParser {

    private CsvParser() {
    }

    /** 只读前若干行，用来做映射预览。 */
    public static List<List<String>> head(Path file, char delimiter, Charset charset, int rows) {
        List<List<String>> out = new ArrayList<>();
        forEach(file, delimiter, charset, row -> {
            if (out.size() < rows) {
                out.add(row);
            }
        });
        return out;
    }

    /** 整份逐行推送。大文件不会全进内存。 */
    public static void forEach(Path file, char delimiter, Charset charset,
                               Consumer<List<String>> consumer) {
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            parse(reader, delimiter, consumer);
        } catch (IOException e) {
            throw new DbException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    static void parse(BufferedReader reader, char delimiter, Consumer<List<String>> consumer)
            throws IOException {
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;
        boolean atStart = true;

        int c;
        while ((c = reader.read()) >= 0) {
            char ch = (char) c;
            if (atStart) {
                atStart = false;
                if (ch == '﻿') {
                    continue;
                }
            }

            if (inQuotes) {
                if (ch == '"') {
                    int peek = reader.read();
                    if (peek == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (peek >= 0) {
                            // 退不回去就在这里就地处理掉这个字符
                            char after = (char) peek;
                            if (after == delimiter) {
                                row.add(field.toString());
                                field.setLength(0);
                                rowStarted = true;
                            } else if (after == '\n') {
                                row.add(field.toString());
                                consumer.accept(row);
                                row = new ArrayList<>();
                                field.setLength(0);
                                rowStarted = false;
                            } else if (after != '\r') {
                                field.append(after);
                            }
                        }
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            if (ch == '"' && field.length() == 0) {
                inQuotes = true;
                rowStarted = true;
            } else if (ch == delimiter) {
                row.add(field.toString());
                field.setLength(0);
                rowStarted = true;
            } else if (ch == '\n') {
                row.add(field.toString());
                consumer.accept(row);
                row = new ArrayList<>();
                field.setLength(0);
                rowStarted = false;
            } else if (ch != '\r') {
                field.append(ch);
                rowStarted = true;
            }
        }

        // 末尾没有换行的最后一行也要交出去
        if (rowStarted || field.length() > 0) {
            row.add(field.toString());
            consumer.accept(row);
        }
    }
}
