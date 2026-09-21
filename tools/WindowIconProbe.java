import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 直接问 Windows：这个窗口到底有没有图标。
 *
 * <p>{@code Stage.getIcons()} 里有几张，跟标题栏上画的是不是那张，是两回事。
 * 前者只说明我们设过，后者才是用户看到的。所以绕开 JavaFX，
 * 按进程枚举顶层窗口，用 {@code WM_GETICON} 和类图标问系统要真相：
 * 两个都是 0，就说明系统手上没有图标，只能画那个通用的窗口图形。
 *
 * <pre>java -cp "plainly-app/target/deps/*" tools/WindowIconProbe.java &lt;pid&gt;</pre>
 */
public class WindowIconProbe {

    private static final int WM_GETICON = 0x007F;
    private static final int ICON_SMALL = 0;
    private static final int ICON_BIG = 1;
    private static final int ICON_SMALL2 = 2;
    private static final int GCLP_HICON = -14;
    private static final int GCLP_HICONSM = -34;

    public static void main(String[] args) {
        int wanted = args.length > 0 ? Integer.parseInt(args[0]) : -1;
        User32 u = User32.INSTANCE;
        List<String> lines = new ArrayList<>();

        u.EnumWindows((hwnd, data) -> {
            IntByReference pid = new IntByReference();
            u.GetWindowThreadProcessId(hwnd, pid);
            if (wanted > 0 && pid.getValue() != wanted) {
                return true;
            }
            char[] buf = new char[512];
            u.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf);
            if (title.isBlank() || !u.IsWindowVisible(hwnd)) {
                return true;
            }
            lines.add(String.format("%-22s  WM_GETICON small=%s big=%s small2=%s  class hIcon=%s hIconSm=%s",
                    title,
                    hex(u.SendMessage(hwnd, WM_GETICON, new WPARAM(ICON_SMALL), new LPARAM(0))),
                    hex(u.SendMessage(hwnd, WM_GETICON, new WPARAM(ICON_BIG), new LPARAM(0))),
                    hex(u.SendMessage(hwnd, WM_GETICON, new WPARAM(ICON_SMALL2), new LPARAM(0))),
                    hex(classPtr(hwnd, GCLP_HICON)),
                    hex(classPtr(hwnd, GCLP_HICONSM))));
            return true;
        }, null);

        if (lines.isEmpty()) {
            System.out.println("没找到该进程的可见顶层窗口");
        } else {
            lines.forEach(System.out::println);
        }
    }

    private static long classPtr(HWND hwnd, int index) {
        return Pointer.nativeValue(User32.INSTANCE.GetClassLongPtr(hwnd, index).toPointer());
    }

    private static String hex(Object v) {
        long n;
        if (v instanceof Number) {
            n = ((Number) v).longValue();
        } else {
            n = Pointer.nativeValue((Pointer) v);
        }
        return n == 0 ? "0(无)" : "0x" + Long.toHexString(n);
    }

    static {
        // 触发 WinUser 加载，保持与 User32 常量同源
        assert WinUser.SW_SHOW >= 0;
    }
}
