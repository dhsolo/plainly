package com.plainly.app.ui;

import com.plainly.core.store.UiState;
import java.io.File;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * 选文件的对话框，记得上次去过哪儿。
 *
 * <h2>为什么值得单开一个类</h2>
 * 这个工具里有八处要选文件：导入、导出、备份、还原、ER 图存图、选库文件……
 * 而它们几乎总是发生在<b>同一个目录</b>——用户把导出、备份、待导入的文件放在一起。
 * 每次都从「文档」开始翻，等于每次都要把同一条路再走一遍。
 *
 * <p>存的是<b>一个</b>全局的「上次目录」，不按用途分开存。分开存看起来更周到，
 * 实际上更差：导出一份 CSV、紧接着想把它导进另一个库，这时导入对话框
 * 该打开的正是刚才导出的那个目录，而不是「上次导入用的目录」。
 */
public final class FileDialogs {

    private FileDialogs() {
    }

    /** 打开文件。选中后记下它所在的目录。 */
    public static File open(Window owner, UiState state, FileChooser chooser) {
        applyLastDirectory(state, chooser);
        return remember(state, chooser.showOpenDialog(owner));
    }

    /** 保存文件。 */
    public static File save(Window owner, UiState state, FileChooser chooser) {
        applyLastDirectory(state, chooser);
        return remember(state, chooser.showSaveDialog(owner));
    }

    /** 选目录。 */
    public static File directory(Window owner, UiState state, DirectoryChooser chooser) {
        if (chooser.getInitialDirectory() == null) {
            chooser.setInitialDirectory(lastDirectory(state));
        }
        File picked = chooser.showDialog(owner);
        if (picked != null && picked.isDirectory()) {
            state.put(UiState.LAST_DIRECTORY, picked.getAbsolutePath());
        }
        return picked;
    }

    /**
     * 调用方已经指定了初始目录就不覆盖它。
     *
     * <p>比如导出对话框会按输入框里那条路径定位——那是用户此刻正看着的目标，
     * 比「上次去过哪儿」更贴近他的意图。
     */
    private static void applyLastDirectory(UiState state, FileChooser chooser) {
        if (chooser.getInitialDirectory() == null) {
            chooser.setInitialDirectory(lastDirectory(state));
        }
    }

    /**
     * 上次那个目录，前提是它还在。
     *
     * <p>存的是 U 盘或网络盘上的路径时，下次它可能已经不在了。
     * 把一个不存在的目录塞给 FileChooser，各平台表现不一：有的忽略、
     * 有的直接开在一个空白位置。返回 null 让系统自己决定，比赌它的行为稳。
     */
    private static File lastDirectory(UiState state) {
        String path = state.get(UiState.LAST_DIRECTORY, null);
        if (path == null || path.isBlank()) {
            return null;
        }
        File dir = new File(path);
        return dir.isDirectory() ? dir : null;
    }

    private static File remember(UiState state, File picked) {
        if (picked != null && picked.getParentFile() != null
                && picked.getParentFile().isDirectory()) {
            state.put(UiState.LAST_DIRECTORY, picked.getParentFile().getAbsolutePath());
        }
        return picked;
    }
}
