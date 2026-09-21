import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.app.view.DataGridPane;
import com.plainly.app.view.GridWriteBack;
import com.plainly.app.view.TableTabPane;
import com.plainly.core.db.Connections;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbType;
import com.plainly.driver.QueryResult;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 查询结果改了之后，到底写没写进库。
 *
 * <h2>为什么要真写一遍</h2>
 * 这条路上每一环都可能悄悄断掉：结果集判成可编辑了吗、写回目标找对了吗、
 * 生成的 UPDATE 用哪一列定位、执行完值真的变了吗。任何一环错了，界面上
 * 都只表现为「点了保存，没报错」——而库里什么也没发生，或者改错了行。
 * 所以必须查改完之后<b>从库里重新读一遍</b>。
 *
 * <p>建一张自己的 {@code PLAINLY_WB_PROBE}，跑完就删，不碰演示库里已有的数据。
 *
 * <p>顺带验一件收拾残局的事：关系库的表标签页不能挂上键值库那条键名搜索——
 * 那条路会把连接强转成键值存储接口。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SqlWriteBackProbe.java
 * </pre>
 */
public class SqlWriteBackProbe extends Application {

    private static final String NL = System.lineSeparator();
    private static final StringBuilder LOG = new StringBuilder();
    private static final String TABLE = "PLAINLY_WB_PROBE";

    private AppContext context;
    private DbSession session;

    @Override
    public void start(Stage stage) {
        context = new AppContext();
        ConnectionConfig cfg = new ConnectionConfig()
                .setName("writeback-probe")
                .setType(DbType.H2)
                .setFilePath(System.getProperty("plainly.home", ".") + "/demo/plainly-demo")
                .setUser("sa")
                .setPassword("");
        session = new DbSession(cfg, Connections.open(cfg));

        stage.setScene(new Scene(new StackPane(), 400, 260));
        stage.show();

        setUp();
        keySearchGuard(stage);
        writeBack(stage);
    }

    private void setUp() {
        exec("DROP TABLE IF EXISTS \"PUBLIC\".\"" + TABLE + "\"");
        exec("CREATE TABLE \"PUBLIC\".\"" + TABLE + "\" ("
                + "\"id\" BIGINT NOT NULL PRIMARY KEY, \"note\" VARCHAR(64))");
        exec("INSERT INTO \"PUBLIC\".\"" + TABLE + "\" VALUES (1, '原来的值')");
        exec("INSERT INTO \"PUBLIC\".\"" + TABLE + "\" VALUES (2, '别动我')");
    }

    /**
     * 关系库的表标签页不该出现键名搜索条。
     *
     * <p>之所以专门验：那一段的守卫是我改坏之后重建的，不是原文。
     */
    private void keySearchGuard(Stage stage) {
        LOG.append("== 关系库的表标签页").append(NL);
        try {
            TableTabPane tab = new TableTabPane(context, session, "PUBLIC", TABLE);
            new Scene(new StackPane(tab), 900, 500);
            boolean hasKeyBar = false;
            for (Node n : all(tab)) {
                if (n instanceof TextField f && f.getPromptText() != null
                        && f.getPromptText().contains("搜索键名")) {
                    hasKeyBar = true;
                }
            }
            LOG.append(hasKeyBar ? "× 关系库上挂出了键名搜索条" : "  没有键名搜索条，正确")
                    .append(NL);
        } catch (RuntimeException e) {
            LOG.append("× 建表标签页就抛异常了：").append(e).append(NL);
        }
    }

    /** 改一个格子，走真正的写回，再从库里读回来对。 */
    private void writeBack(Stage stage) {
        LOG.append(NL).append("== 写回").append(NL);
        String sql = "SELECT * FROM \"PUBLIC\".\"" + TABLE + "\" ORDER BY \"id\"";
        QueryResult r = session.connection().execute(sql, 10);

        LOG.append("可编辑：").append(r.isEditable() ? "  是" : "× 否").append(NL);
        if (!r.isEditable()) {
            LOG.append("理由：").append(r.readOnlyReason()).append(NL);
            finish();
            return;
        }
        QueryResult.Source src = r.source();
        LOG.append("写回目标：").append(src.schema()).append('.').append(src.table())
                .append(src.table().equalsIgnoreCase(TABLE) ? "    对得上" : "  × 对不上").append(NL);

        DataGridPane grid = new DataGridPane();
        grid.setResult(r);
        // 第 0 行的 note 列改掉；第 1 行一个字不动，用来看写回有没有波及别的行
        grid.editBuffer().set(0, 1, "改过的值");

        GridWriteBack.commit(context, session,
                src.schema().isBlank() ? "PUBLIC" : src.schema(), src.table(),
                grid, stage, m -> LOG.append("状态栏：").append(m).append(NL),
                this::verify,
                () -> {
                    LOG.append("× 写回没执行（校验没过或被取消）").append(NL);
                    finish();
                });
    }

    private void verify() {
        String changed = session.connection().scalar(
                "SELECT \"note\" FROM \"PUBLIC\".\"" + TABLE + "\" WHERE \"id\" = 1");
        String untouched = session.connection().scalar(
                "SELECT \"note\" FROM \"PUBLIC\".\"" + TABLE + "\" WHERE \"id\" = 2");

        LOG.append("库里第 1 行现在是：[").append(changed).append("]").append(NL);
        LOG.append("结论：").append("改过的值".equals(changed)
                ? "  改动真的写进库了" : "× 库里没变，保存是假的").append(NL);
        LOG.append("库里第 2 行现在是：[").append(untouched).append("]").append(NL);
        LOG.append("结论：").append("别动我".equals(untouched)
                ? "  没波及别的行" : "× 把不该动的行也改了").append(NL);
        finish();
    }

    private void finish() {
        exec("DROP TABLE IF EXISTS \"PUBLIC\".\"" + TABLE + "\"");
        System.out.print(LOG);
        context.close();
        Platform.exit();
    }

    private void exec(String sql) {
        try {
            session.connection().execute(sql, 0);
        } catch (RuntimeException e) {
            LOG.append("准备语句失败：").append(sql).append(" -> ").append(e.getMessage()).append(NL);
        }
    }

    private static List<Node> all(Parent root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            out.add(child);
            if (child instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    public static void main(String[] args) {
        launch(SqlWriteBackProbe.class, args);
    }
}
