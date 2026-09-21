package com.plainly.app.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 弹窗的标题不要写两遍。
 *
 * <h2>为什么值得一个测试</h2>
 * 每个弹窗都有两处标题：系统标题栏（{@code stage.setTitle}，任务栏和 Alt+Tab 靠它）
 * 和窗口内那条彩色头部（{@code dialog-title}）。两处填同一句话，用户就会在
 * 相隔十几像素的地方连着看到两遍一模一样的字。
 *
 * <p>这件事<b>不会报错、不会崩、截图上也说不出哪里不对</b>——只是看着别扭。
 * 正因为没有任何信号，三十个弹窗才会一路照抄同一个写法。
 *
 * <p>这个测试直接读源码。按说这不体面，但要验的恰恰是「两处字符串是不是同一句」，
 * 而它们分别写在 {@code setTitle} 和一个 Label 里，运行期要把三十个弹窗
 * 都构造出来才看得到——那需要 JavaFX 线程、数据库连接和一堆上下文，
 * 代价远超这件事本身。读源码几毫秒就能给出同样的结论。
 */
@DisplayName("弹窗标题")
class DialogTitleTest {

    /** {@code stage.setTitle("……")} 里的字面量。 */
    private static final Pattern STAGE_TITLE =
            Pattern.compile("setTitle\\(\\s*\"([^\"]+)\"");

    /** {@code UiUtils.label("……", "dialog-title")} 里的字面量。 */
    private static final Pattern HEAD_TITLE =
            Pattern.compile("label\\(\\s*\"([^\"]+)\"\\s*,\\s*\"dialog-title\"");

    /**
     * 有意保留的两个。
     *
     * <ul>
     *   <li>{@code AboutDialog}——关于框里写着产品名和版本，标题栏是「关于 Plainly」。
     *       关于框展示产品名正是它的本分；</li>
     *   <li>{@code ValueViewerDialog}——头部是「表.列」，标题栏是「单元格 · 列」。
     *       两处给的信息不一样，不是重复。</li>
     * </ul>
     */
    private static final Set<String> ALLOWED = Set.of("AboutDialog", "ValueViewerDialog");

    @Test
    @DisplayName("窗口内的标题不和系统标题栏重复")
    void noDuplicateTitles() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String name = file.getFileName().toString().replace(".java", "");
            if (ALLOWED.contains(name)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Set<String> stageTitles = literals(STAGE_TITLE, text);
            for (String head : literals(HEAD_TITLE, text)) {
                for (String stage : stageTitles) {
                    // 一句是另一句的开头也算重复：「分布式锁」对「分布式锁 · 本机」，
                    // 用户看到的仍然是同一个词连着出现两遍
                    if (sameStart(head, stage)) {
                        offenders.add(name + "：标题栏「" + stage + "」，窗口内「" + head + "」");
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("这些弹窗把标题写了两遍，去掉窗口内那一条（保留图标和说明）："
                    + System.lineSeparator() + String.join(System.lineSeparator(), offenders));
        }
    }

    /** 确认这个测试真的扫到了东西——扫了个空目录还报通过，比没有测试更糟。 */
    @Test
    @DisplayName("确实扫到了弹窗源码")
    void scannedSomething() throws IOException {
        List<Path> files = sources();
        assertTrue(files.size() > 20, "只扫到 " + files.size() + " 个文件，路径多半不对");
        boolean anyStageTitle = false;
        for (Path file : files) {
            if (!literals(STAGE_TITLE, Files.readString(file, StandardCharsets.UTF_8)).isEmpty()) {
                anyStageTitle = true;
                break;
            }
        }
        assertTrue(anyStageTitle, "一个 setTitle 都没扫到，正则多半失效了");
    }

    // ------------------------------------------------------------------ 辅助

    private static boolean sameStart(String head, String stage) {
        String a = head.trim().toLowerCase(Locale.ROOT);
        String b = stage.trim().toLowerCase(Locale.ROOT);
        return a.equals(b) || b.startsWith(a) || a.startsWith(b);
    }

    private static Set<String> literals(Pattern pattern, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 所有弹窗源码。
     *
     * <p>测试的工作目录是模块根（{@code plainly-app}），所以是相对路径。
     */
    private static List<Path> sources() throws IOException {
        Path root = Path.of("src/main/java/com/plainly/app/view");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
