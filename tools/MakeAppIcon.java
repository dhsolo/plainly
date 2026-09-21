import com.plainly.app.ui.Icons;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * 把窗口图标导成一个 .ico，给安装包和 exe 用。
 *
 * <h2>为什么要生成，而不是找一张图放进仓库</h2>
 * 应用的图标是 {@link Icons#appIcons()} 在运行时画出来的（SVG 路径 → 快照）。
 * 另外手工做一张 .ico 意味着从此有两份图标：改了代码里那份，安装包上那份不会跟着变，
 * 而且没有任何提示——直到某天有人发现开始菜单里的图标和窗口里的不是一个。
 * 从同一处生成，两者就不会走散。
 *
 * <h2>ICO 的格式，以及为什么不能全用 PNG</h2>
 * 一个 6 字节的头，加每张图 16 字节的目录项，后面跟着各图的数据。
 * Vista 起允许直接内嵌 PNG，写起来比老式的 BMP + AND 掩码省事得多。
 *
 * <p><b>但不能全用 PNG。</b>第一版就是那么写的，Windows 资源管理器认，
 * 而 {@code System.Drawing.Icon.ToBitmap()} 直接抛
 * 「Requested range extends past the end of the array」——它只认 BMP 条目。
 * 这类消费者不止一个（老的安装器界面、部分截图与打包工具），
 * 而失败的样子是「图标没了」或者一个异常，不是一句「格式不支持」。
 *
 * <p>所以按惯例来：<b>128 及以下写 BMP 条目，256 写 PNG</b>。
 * 256 的 BMP 条目要 256KB，PNG 只要几 KB，而能读 256 档的消费者本来就都是新的。
 *
 * <p>BMP 条目有两处写错了不会报错、只会画歪：
 * <ul>
 *   <li>{@code biHeight} 要填<b>两倍</b>高度（XOR 图 + AND 掩码各一份），
 *       填成实际高度的话，图会被从中间截掉一半；</li>
 *   <li>像素行是<b>自下而上</b>存的。顺着写图就是倒的。</li>
 * </ul>
 *
 * <p>256 那一档在目录项里的宽高字段必须写 <b>0</b>：那个字段只有一个字节，
 * 装不下 256。写别的值 Windows 就挑不出这一档，高分屏上于是拿小图去拉，糊的。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/MakeAppIcon.java build/plainly.ico</pre>
 */
public class MakeAppIcon extends Application {

    /**
     * 要放进 .ico 的尺寸。
     *
     * <p>和 {@link Icons#appIcons()} 那张表一致但更短：.ico 里每多一档就多一份 PNG，
     * 而 Windows 在安装包、开始菜单、任务栏、资源管理器上实际会挑的就是这几个。
     */
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    private static String target = "build/plainly.ico";

    public static void main(String[] args) {
        if (args.length > 0) {
            target = args[0];
        }
        launch(MakeAppIcon.class, args);
        System.exit(0);
    }

    @Override
    public void start(Stage stage) throws Exception {
        List<byte[]> payloads = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();

        // appIcons() 按固定的一串尺寸画好并缓存，从里面挑需要的那几档；
        // 重画一遍会得到同样的结果，但那就等于把渲染逻辑抄了第二份
        List<Image> icons = Icons.appIcons();
        for (int px : SIZES) {
            Image match = null;
            for (Image image : icons) {
                if ((int) image.getWidth() == px) {
                    match = image;
                    break;
                }
            }
            if (match == null) {
                System.out.println("  跳过 " + px + "px：appIcons() 里没有这一档");
                continue;
            }
            BufferedImage buffer = toBuffered(match, px);
            payloads.add(px >= 256 ? toPng(buffer) : toBmpEntry(buffer));
            sizes.add(px);
        }

        Path out = Path.of(target);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (OutputStream stream = Files.newOutputStream(out)) {
            writeIco(stream, sizes, payloads);
        }

        System.out.println("已生成 " + out.toAbsolutePath() + "  "
                + Files.size(out) + " 字节");
        System.out.println("  含尺寸 " + sizes);
        Platform.exit();
    }

    private static BufferedImage toBuffered(Image image, int px) {
        BufferedImage buffer = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                buffer.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return buffer;
    }

    private static byte[] toPng(BufferedImage buffer) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(buffer, "png", bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * 一个 BMP 形式的图标条目：BITMAPINFOHEADER + 32 位 BGRA 像素 + AND 掩码。
     *
     * <p>掩码整片留零。32 位条目的透明由 alpha 通道决定，掩码只是格式要求的一段，
     * 但<b>不能省</b>——省掉之后 {@code biHeight} 声明的两倍高度就对不上数据长度，
     * 读的一方会越界。
     */
    private static byte[] toBmpEntry(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        int maskRow = ((w + 31) / 32) * 4; // 掩码每行按 4 字节对齐

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe32(out, 40);      // biSize
        writeLe32(out, w);       // biWidth
        writeLe32(out, h * 2);   // biHeight：XOR 图 + AND 掩码，所以是两倍
        writeLe16(out, 1);       // biPlanes
        writeLe16(out, 32);      // biBitCount
        writeLe32(out, 0);       // biCompression = BI_RGB
        writeLe32(out, 0);       // biSizeImage：BI_RGB 下允许为 0
        writeLe32(out, 0);       // biXPelsPerMeter
        writeLe32(out, 0);       // biYPelsPerMeter
        writeLe32(out, 0);       // biClrUsed
        writeLe32(out, 0);       // biClrImportant

        // 像素自下而上、每像素 BGRA
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                out.write(argb & 0xFF);          // B
                out.write((argb >> 8) & 0xFF);   // G
                out.write((argb >> 16) & 0xFF);  // R
                out.write((argb >> 24) & 0xFF);  // A
            }
        }
        // AND 掩码：全零表示「都不透明」，透明交给 alpha
        for (int y = 0; y < h; y++) {
            out.write(new byte[maskRow]);
        }
        return out.toByteArray();
    }

    /**
     * 写出 ICO。
     *
     * <p>ICO 里所有多字节字段都是<b>小端</b>，而 {@code DataOutputStream} 写的是大端。
     * 用它的 writeShort 不会报错，只会得到一个 Windows 认不出的文件，所以这里自己拆字节。
     */
    private static void writeIco(OutputStream out, List<Integer> sizes, List<byte[]> payloads)
            throws IOException {
        int count = payloads.size();
        // 头 6 字节 + 每张图 16 字节的目录项
        int offset = 6 + count * 16;

        writeLe16(out, 0);      // 保留
        writeLe16(out, 1);      // 类型：1 = 图标
        writeLe16(out, count);

        for (int i = 0; i < count; i++) {
            int px = sizes.get(i);
            byte[] payload = payloads.get(i);
            // 256 要写成 0：这个字段只有一个字节
            out.write(px >= 256 ? 0 : px);
            out.write(px >= 256 ? 0 : px);
            out.write(0);       // 调色板颜色数：真彩色写 0
            out.write(0);       // 保留
            writeLe16(out, 1);  // 色彩平面
            writeLe16(out, 32); // 位深
            writeLe32(out, payload.length);
            writeLe32(out, offset);
            offset += payload.length;
        }
        for (byte[] payload : payloads) {
            out.write(payload);
        }
    }

    private static void writeLe16(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe32(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
