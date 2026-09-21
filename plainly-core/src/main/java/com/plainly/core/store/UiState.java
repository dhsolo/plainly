package com.plainly.core.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 界面自己的记事本：窗口多大、侧栏多宽、上次在哪个目录选的文件。
 *
 * <h2>为什么这些也要存</h2>
 * 它们都属于「用户已经告诉过工具一次的事」。窗口拖大一点、侧栏拉宽一点、
 * 文件存到 D:\导出——每次启动都退回默认值，等于每天早上都要再讲一遍。
 * 这类摩擦单次很小，但它是每天都在发生的那一种。
 *
 * <h2>为什么和别的表分开</h2>
 * 连接、历史、标签页都是<b>用户的数据</b>：丢了要紧，所以各有各的表结构和迁移。
 * 这里存的是界面偏好，丢了只是回到默认值。因此用最简单的键值表，
 * 读写都不抛异常——为了记不住侧栏宽度而弹一个错误框，是把主次弄反了。
 */
public class UiState {

    /** 主窗口的位置与大小。 */
    public static final String WINDOW = "window";

    /** 侧栏与主区之间那条分隔线的位置，0–1。 */
    public static final String SIDEBAR_DIVIDER = "sidebar.divider";

    /** 上一次在文件对话框里用过的目录。 */
    public static final String LAST_DIRECTORY = "dir.last";

    /** 界面主题：{@code light} 或 {@code dark}。缺省是亮色。 */
    public static final String THEME = "theme";

    private final LocalStore store;

    public UiState(LocalStore store) {
        this.store = store;
        migrate();
    }

    private void migrate() {
        try (java.sql.Statement st = store.connection().createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ui_state (
                      key   TEXT PRIMARY KEY,
                      value TEXT
                    )""");
        } catch (SQLException ignored) {
            // 建不出来就当没有这张表：下面每个读写都会安静失败，界面回到默认值
        }
    }

    /** 读一个值；没有或读不出来时返回 {@code fallback}。 */
    public String get(String key, String fallback) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("SELECT value FROM ui_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    return value == null ? fallback : value;
                }
            }
        } catch (SQLException ignored) {
            // 见类注释
        }
        return fallback;
    }

    /** 写一个值。{@code null} 表示删掉这一项。 */
    public void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        try (PreparedStatement ps = store.connection().prepareStatement(
                "INSERT INTO ui_state (key, value) VALUES (?,?)"
                        + " ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 见类注释
        }
    }

    private void remove(String key) {
        try (PreparedStatement ps = store.connection()
                .prepareStatement("DELETE FROM ui_state WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 见类注释
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void putDouble(String key, double value) {
        put(key, String.valueOf(value));
    }

    // ------------------------------------------------------------ 窗口几何

    /**
     * 窗口的位置与大小。
     *
     * @param maximized 最大化时其余四个值记的是<b>还原后</b>的几何，
     *                  这样用户取消最大化会回到他自己拉过的那个尺寸，而不是出厂默认
     */
    public record Geometry(double x, double y, double width, double height, boolean maximized) {

        private static final String SEP = ",";

        String encode() {
            return x + SEP + y + SEP + width + SEP + height + SEP + maximized;
        }

        /** 解析存下来的那一行；格式不对（换过版本、被手改过）就返回 null。 */
        static Geometry decode(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] parts = text.split(SEP);
            if (parts.length != 5) {
                return null;
            }
            try {
                return new Geometry(
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                        Boolean.parseBoolean(parts[4]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 存下来的窗口几何；没有或坏了返回 null。 */
    public Geometry geometry() {
        return Geometry.decode(get(WINDOW, null));
    }

    public void setGeometry(Geometry geometry) {
        put(WINDOW, geometry == null ? null : geometry.encode());
    }
}
