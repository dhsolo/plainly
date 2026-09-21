package com.plainly.app.view;

import com.plainly.app.ui.UiUtils;
import com.plainly.driver.SqlDialect;
import com.plainly.driver.meta.DbObjects.ColumnInfo;
import com.plainly.driver.meta.DbObjects.IndexInfo;
import com.plainly.driver.meta.DbObjects.TableStructure;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 由当前结构反向生成 CREATE TABLE，可直接复制进迁移脚本。 */
public class DdlPane extends VBox {

    private final TextArea area = new TextArea();

    public DdlPane() {
        area.setEditable(false);
        area.getStyleClass().add("mono");
        area.setStyle("-fx-font-family:'Cascadia Mono',Consolas,monospace;-fx-font-size:12.5px;");
        VBox.setVgrow(area, Priority.ALWAYS);

        Button copy = UiUtils.toolButton("复制 SQL", null);
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        HBox head = UiUtils.row(12, UiUtils.label("DDL", "section-label"),
                UiUtils.label("由当前结构生成，可复制到迁移脚本", "hint"),
                UiUtils.hSpacer(), copy);
        head.getStyleClass().add("grid-toolbar");

        getChildren().addAll(head, area);
    }

    public void setStructure(SqlDialect dialect, String schema, TableStructure structure) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ")
                .append(dialect.qualify(schema, structure.table().name()))
                .append(" (\n");

        for (int i = 0; i < structure.columns().size(); i++) {
            ColumnInfo c = structure.columns().get(i);
            sb.append("  ").append(dialect.quote(c.name())).append(' ').append(c.displayType());
            if (!c.nullable()) {
                sb.append(" NOT NULL");
            }
            if (c.autoIncrement()) {
                sb.append(" AUTO_INCREMENT");
            }
            if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            if (c.comment() != null && !c.comment().isBlank()) {
                sb.append(" COMMENT '").append(c.comment().replace("'", "''")).append('\'');
            }
            if (i < structure.columns().size() - 1 || !structure.primaryKeyColumns().isEmpty()) {
                sb.append(',');
            }
            sb.append('\n');
        }

        if (!structure.primaryKeyColumns().isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < structure.primaryKeyColumns().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(dialect.quote(structure.primaryKeyColumns().get(i)));
            }
            sb.append(")\n");
        }
        sb.append(");\n");

        for (IndexInfo idx : structure.indexes()) {
            if (idx.primary()) {
                continue;
            }
            sb.append('\n').append("CREATE ").append(idx.unique() ? "UNIQUE " : "")
                    .append("INDEX ").append(dialect.quote(idx.name()))
                    .append(" ON ").append(dialect.qualify(schema, structure.table().name()))
                    .append(" (");
            for (int i = 0; i < idx.columns().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(dialect.quote(idx.columns().get(i)));
            }
            sb.append(");\n");
        }

        area.setText(sb.toString());
    }
}
