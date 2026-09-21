package com.plainly.driver.jdbc;

import com.plainly.driver.TypeCategory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 全项目<b>唯一</b>的「数据库值 ↔ 文本」转换点。
 *
 * <p>精度保真在这里一次性做对，上层就再也碰不到有损路径。
 * 反过来说，任何绕开本类直接调 {@code rs.getXxx()} 的代码都是缺陷，
 * 无论它看起来多么无害。
 *
 * <h2>为什么精确数值走 getBigDecimal 而不是 getString</h2>
 * 两者按 JDBC 规范都是无损的，但 {@code getString()} 在部分驱动上会受会话本地化设置影响
 * （Oracle 的 NLS_NUMERIC_CHARACTERS 能把小数点变成逗号）。
 * {@code getBigDecimal().toPlainString()} 与区域设置无关，且永不输出科学计数法——
 * {@code toString()} 会，这是个真实的坑。
 */
public final class CellReader {

    private CellReader() {
    }

    /** BLOB 摘要里最多显示多少字节的十六进制。 */
    private static final int BLOB_PREVIEW_BYTES = 8;

    /**
     * 读取一个单元格为无损文本。
     *
     * @param index 1 起始的列序号
     * @return 原始文本；SQL NULL 返回 {@code null}
     */
    public static String read(ResultSet rs, int index, TypeCategory category) throws SQLException {
        switch (category) {
            case EXACT_NUMERIC:
            case INTEGER: {
                BigDecimal value = rs.getBigDecimal(index);
                if (rs.wasNull() || value == null) {
                    return null;
                }
                // toPlainString 而非 toString：后者对大指数会输出 1.2E+19
                return value.toPlainString();
            }

            case BINARY: {
                byte[] bytes = rs.getBytes(index);
                if (rs.wasNull() || bytes == null) {
                    return null;
                }
                return describeBlob(bytes);
            }

            case APPROX_NUMERIC:
            default: {
                String value = rs.getString(index);
                return rs.wasNull() ? null : value;
            }
        }
    }

    /**
     * 把文本值绑定回 {@code PreparedStatement}。
     *
     * <p>精确数值绑定为 {@link BigDecimal}，由服务端按目标列类型做十进制解析——
     * 全程不经过 {@code double}，与读取路径对称。
     */
    public static void bind(PreparedStatement ps, int index, String value, TypeCategory category)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, sqlTypeFor(category));
            return;
        }
        switch (category) {
            case EXACT_NUMERIC:
            case INTEGER:
            case APPROX_NUMERIC:
                try {
                    ps.setBigDecimal(index, new BigDecimal(value.trim()));
                } catch (NumberFormatException e) {
                    // 让服务端去报错，而不是在这里吞掉用户输入
                    ps.setString(index, value);
                }
                return;

            case BOOLEAN:
                ps.setBoolean(index, parseBoolean(value));
                return;

            default:
                ps.setString(index, value);
        }
    }

    private static boolean parseBoolean(String value) {
        String v = value.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v)
                || "t".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    private static int sqlTypeFor(TypeCategory category) {
        switch (category) {
            case EXACT_NUMERIC:
                return Types.DECIMAL;
            case INTEGER:
                return Types.INTEGER;
            case APPROX_NUMERIC:
                return Types.DOUBLE;
            case BOOLEAN:
                return Types.BOOLEAN;
            case BINARY:
                return Types.VARBINARY;
            case TEMPORAL:
                return Types.TIMESTAMP;
            default:
                return Types.VARCHAR;
        }
    }

    /**
     * 二进制列在网格里只显示摘要，不把整个 BLOB 拉进内存。
     * 需要完整内容时由单元格面板按需再取。
     */
    private static String describeBlob(byte[] bytes) {
        StringBuilder sb = new StringBuilder("[BLOB ").append(bytes.length).append(" B");
        if (bytes.length > 0) {
            sb.append(" 0x");
            int n = Math.min(BLOB_PREVIEW_BYTES, bytes.length);
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02X", bytes[i]));
            }
            if (bytes.length > n) {
                sb.append('…');
            }
        }
        return sb.append(']').toString();
    }
}
