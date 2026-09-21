package com.plainly.app.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局 Ctrl+C：把当前焦点控件里选中的东西复制走。
 *
 * <h2>为什么用 handler 而不是 filter</h2>
 * 这是这个类唯一要紧的设计决定。
 *
 * <p>事件先自上而下走一遍<b>过滤器</b>，再自下而上走一遍<b>处理器</b>。
 * 已经自己处理 Ctrl+C 的地方（数据网格挂的是过滤器、文本框是控件自带的行为）
 * 都会在前半程消费掉这个事件——用处理器挂在场景上，它们那份就<b>先到先得</b>，
 * 这里根本不会被触发。
 *
 * <p>反过来如果挂成过滤器，场景这一层会抢在所有控件之前，
 * 数据网格那套按格式复制、文本框那套按选区复制，全都会被这个通用实现顶掉——
 * 而且不报错，只是复制出来的东西变了样。
 *
 * <h2>它只管「没人管的地方」</h2>
 * 树、各种对话框里的表格和列表，这些 JavaFX 默认不支持复制。
 * 用户在上面选中一行按 Ctrl+C，以前什么也不会发生。
 */
public final class CopySelection {

    private static final KeyCombination COPY =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    private CopySelection() {
    }

    /** 控件上挂「这个条目该复制成什么」的那个键。 */
    private static final String PROVIDER_KEY = "plainly.copyText";

    /**
     * 告诉这个控件：它里面的条目该复制成什么。
     *
     * <h2>为什么需要它</h2>
     * 通用办法只能拿到两种东西：条目的 {@code toString()}，或者单元格上渲染出来的字。
     * 前者是给调试看的（{@code TableNode[config=..., schema=..., table=...]}），
     * 后者在复杂的单元格上会把装饰一起带出来——左侧树那一行里有类型色块、
     * 名字、在线角标，整行抓下来是「M 192.168.1.7 在线」，而用户要的只是表名。
     *
     * <p>所以让知道情况的那一方自己说。没声明的控件仍然走通用办法。
     *
     * @param provider 拿到条目（树上是 {@code TreeItem} 的<b>值</b>），返回该复制的文字；
     *                 返回 null 表示「这个我也说不好」，那就退回通用办法
     */
    public static void provideTextFor(Node control, java.util.function.Function<Object, String> provider) {
        control.getProperties().put(PROVIDER_KEY, provider);
    }

    @SuppressWarnings("unchecked")
    private static String fromProvider(Node control, Object item) {
        Object p = control.getProperties().get(PROVIDER_KEY);
        if (!(p instanceof java.util.function.Function)) {
            return null;
        }
        try {
            return ((java.util.function.Function<Object, String>) p).apply(item);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 挂到一个场景上。重复挂同一个场景是无害的，但没必要。 */
    public static void install(Scene scene) {
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (!COPY.match(e)) {
                return;
            }
            String text = textOf(scene.getFocusOwner());
            if (text == null || text.isEmpty()) {
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            e.consume();
        });
    }

    /**
     * 当前焦点处选中的内容，取不出来就返回 null。
     *
     * <p>从焦点节点往上找：焦点常常落在单元格上，而选区挂在它所属的那个控件上。
     *
     * <p>公开出来是为了能直接验：剪贴板是进程外的共享资源，读写都可能被别的程序
     * 干扰，拿它当断言的落点会得到时有时无的结果。把「该复制出什么」单独拎出来，
     * 这一半就是确定的。
     */
    public static String textOf(Node focusOwner) {
        for (Node n = focusOwner; n != null; n = n.getParent()) {
            if (n instanceof TableView<?> t) {
                return fromTable(t);
            }
            if (n instanceof TreeTableView<?> t) {
                return fromTreeTable(t);
            }
            if (n instanceof ListView<?> l) {
                return fromList(l);
            }
            if (n instanceof TreeView<?> t) {
                return fromTree(t);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 表格

    /**
     * 表格：按列取值，制表符分隔。
     *
     * <p>走列的取值函数而不是读单元格上显示的字，是因为<b>没滚动到的行没有单元格</b>——
     * JavaFX 只为可见的那些行建控件。读显示文字的话，选中一百行只能复制出可见的十几行，
     * 而且不会有任何提示。
     */
    private static String fromTable(TableView<?> table) {
        List<?> selected = table.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            return null;
        }
        List<TableColumn<?, ?>> columns = new ArrayList<>();
        for (TableColumn<?, ?> c : table.getColumns()) {
            if (c.isVisible()) {
                columns.add(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : selected) {
            if (sb.length() > 0) {
                sb.append(LF);
            }
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(TAB);
                }
                sb.append(valueOf(columns.get(i), item));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueOf(TableColumn<?, ?> column, Object item) {
        try {
            javafx.beans.value.ObservableValue<?> value =
                    ((TableColumn) column).getCellObservableValue(item);
            return value == null || value.getValue() == null ? "" : String.valueOf(value.getValue());
        } catch (RuntimeException e) {
            return "";
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String fromTreeTable(TreeTableView<?> table) {
        List<? extends TreeItem<?>> selected = table.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TreeItem<?> item : selected) {
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(LF);
            }
            boolean first = true;
            for (TreeTableColumn<?, ?> column : table.getColumns()) {
                if (!column.isVisible()) {
                    continue;
                }
                if (!first) {
                    sb.append(TAB);
                }
                first = false;
                try {
                    javafx.beans.value.ObservableValue<?> v =
                            ((TreeTableColumn) column).getCellObservableValue(item);
                    sb.append(v == null || v.getValue() == null ? "" : String.valueOf(v.getValue()));
                } catch (RuntimeException e) {
                    // 这一列取不出来就留空，不要让整次复制失败
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 列表与树

    /**
     * 列表和树：优先取<b>屏幕上显示的那行字</b>。
     *
     * <h2>为什么这里和表格相反</h2>
     * 表格的每一列都有取值函数，拿得到干净的数据。而列表和树里放的常常是
     * 领域对象（连接节点、一条订阅消息），它们的 {@code toString()} 是
     * {@code NodeData[config=..., kind=...]} 这种给调试看的东西——
     * 复制出来对用户毫无用处。
     *
     * <p>这些控件的内容是靠 cell factory 渲染的，只有渲染出来的那行字
     * 才是用户认得的。取不到（行没渲染出来）时才退回 {@code toString()}。
     */
    private static String fromList(ListView<?> listView) {
        List<?> selected = listView.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : selected) {
            if (sb.length() > 0) {
                sb.append(LF);
            }
            String declared = fromProvider(listView, item);
            sb.append(declared != null ? declared
                    : renderedText(listView, item, ".list-cell"));
        }
        return sb.toString();
    }

    private static String fromTree(TreeView<?> tree) {
        List<? extends TreeItem<?>> selected = tree.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TreeItem<?> item : selected) {
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(LF);
            }
            // 交给 provider 的是 TreeItem 的<b>值</b>，不是 TreeItem 本身：
            // 声明方关心的是自己那个领域对象，不该被迫去拆 JavaFX 的包装
            String declared = fromProvider(tree, item.getValue());
            sb.append(declared != null ? declared
                    : renderedText(tree, item, ".tree-cell"));
        }
        return sb.toString();
    }

    /**
     * 找到装着这个条目的那个单元格，取它显示的字。
     *
     * <p>树和列表要分开认：{@code TreeCell.getItem()} 返回的是<b>值</b>，
     * 不是 {@code TreeItem}；拿 TreeItem 去比它，永远匹配不上，
     * 于是每次都悄悄退回 toString()，而那正是这里要避免的东西。
     */
    private static String renderedText(Parent control, Object item, String cellClass) {
        for (Node node : control.lookupAll(cellClass)) {
            if (!(node instanceof IndexedCell<?> cell)) {
                continue;
            }
            boolean match = cell instanceof javafx.scene.control.TreeCell<?> treeCell
                    ? treeCell.getTreeItem() == item
                    : cell.getItem() == item;
            if (match) {
                String text = cell.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
                // 文字是空的，多半是整块用 graphic 画的
                String fromGraphic = graphicText(cell.getGraphic());
                if (!fromGraphic.isEmpty()) {
                    return fromGraphic;
                }
            }
        }
        if (item instanceof TreeItem<?> treeItem) {
            return String.valueOf(treeItem.getValue());
        }
        return String.valueOf(item);
    }

    /**
     * 单元格没有文字时，从它的图形里把字抠出来。
     *
     * <h2>为什么需要这一层</h2>
     * 复杂的单元格常常是 {@code setText(null)} 加 {@code setGraphic(整块布局)}——
     * 左侧连接树就是这么画的。只看 {@code getText()} 的话永远是空，
     * 于是一路退到 {@code toString()}，复制出来是
     * {@code TableNode[config=..., schema=..., table=...]}，对用户毫无用处。
     *
     * <p>这一层是兜底，不是首选：它会把装饰性的文字（类型色块上的字母、
     * 「在线」角标）一起带出来。要精确的结果，用 {@link #provideTextFor} 声明。
     */
    private static String graphicText(Node graphic) {
        if (graphic == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collectText(graphic, sb);
        return sb.toString().trim();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.control.Labeled labeled) {
            append(sb, labeled.getText());
        } else if (node instanceof javafx.scene.text.Text text) {
            append(sb, text.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, sb);
            }
        }
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(text.trim());
    }

    /** 写成常量，避开源码里的转义在多层脚本传递中被吃掉。 */
    private static final String LF = String.valueOf((char) 10);
    private static final String TAB = String.valueOf((char) 9);
}
