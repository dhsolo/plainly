package com.plainly.app.view;

import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.ui.UiUtils;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 索引一览。
 *
 * <h2>为什么和外键分开</h2>
 * 之前这两样挤在同一页里，页签叫「索引/外键」——而那一页<b>其实只显示外键</b>，
 * 索引一条也看不到。名字里写着的东西点进去找不到，比不写还糟。
 *
 * <p>它们本来也是两种东西：索引是性能设施，删了只是变慢；外键是数据约束，
 * 删了数据就可能出现孤儿行。放在一起看，很容易把两种后果混为一谈。
 */
public class IndexPane extends VBox {

    private final AppContext context;
    private final DbSession session;
    private final String schema;
    private final String table;

    private final TableView<IndexInfo> indexes = new TableView<>();
    private boolean loaded;

    public IndexPane(AppContext context, DbSession session, String schema, String table) {
        this.context = context;
        this.session = session;
        this.schema = schema;
        this.table = table;
        setSpacing(0);
        getChildren().add(build());
    }

    /** 切到这一页才去取——大库上读表结构并不便宜。 */
    public void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        reload();
    }

    public void reload() {
        context.queryService().submit(() -> session.structure(schema, table).indexes())
                .whenComplete((list, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        UiUtils.showError(getScene() == null ? null : getScene().getWindow(),
                                "读取索引失败", error);
                        return;
                    }
                    indexes.setItems(FXCollections.observableArrayList(list));
                }));
    }

    /** 当前选中的索引；没选返回 null。 */
    public IndexInfo selectedIndex() {
        return indexes.getSelectionModel().getSelectedItem();
    }

    private VBox build() {
        indexes.getStyleClass().add("data-grid");
        indexes.setPlaceholder(UiUtils.label("这张表没有索引", "hint"));
        indexes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        indexes.getColumns().add(column("名称", 240, IndexInfo::name));
        // 列顺序原样显示，不排序：(a, b) 和 (b, a) 是两个不同的索引
        indexes.getColumns().add(column("列（按顺序）", 300,
                i -> String.join(", ", i.columns())));
        indexes.getColumns().add(column("唯一", 80, i -> i.unique() ? "是" : ""));
        indexes.getColumns().add(column("主键", 80, i -> i.primary() ? "是" : ""));

        Label label = UiUtils.label("索引", "section-label");
        Label note = UiUtils.label(
                "列的顺序有意义：只有最左边那几列参与匹配时索引才用得上", "hint");

        VBox.setVgrow(indexes, Priority.ALWAYS);
        VBox box = UiUtils.column(0, UiUtils.row(10, label, note), indexes);
        box.setPadding(new Insets(8, 10, 8, 10));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private TableColumn<IndexInfo, String> column(
            String title, double width, java.util.function.Function<IndexInfo, String> value) {
        TableColumn<IndexInfo, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }
}
