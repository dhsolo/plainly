import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.core.search.ObjectIndex;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * 换成索引之后，搜索到底快了多少、找得到了没有。
 *
 * <p>对照的是改版前实测到的三条失败：
 * 搜「创建时间」零结果、少打下划线零结果、每敲一字 13 次元数据往返约 150ms。
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/IndexSearchProbe.java</pre>
 */
public class IndexSearchProbe {

    public static void main(String[] args) {
        AppContext context = new AppContext();
        try {
            List<DbSession> live = new ArrayList<>();
            for (ConnectionConfig c : context.registry().listAll()) {
                try {
                    DbSession s = context.openSession(c);
                    s.schemas();
                    live.add(s);
                } catch (RuntimeException e) {
                    System.out.println("连不上 " + c.name() + "：" + e.getMessage());
                }
            }

            // ---- 建索引（一次性的代价）
            long t0 = System.nanoTime();
            List<ObjectIndex.Entry> index = new ArrayList<>();
            int schemas = 0;
            for (DbSession s : live) {
                for (SchemaInfo si : s.schemas()) {
                    try {
                        index.addAll(ObjectIndex.fromTables(s.tables(si.name())));
                        index.addAll(ObjectIndex.fromColumns(
                                s.connection().listColumns(si.name())));
                        schemas++;
                    } catch (RuntimeException ignored) {
                        // 权限不足的库跳过
                    }
                }
            }
            long buildMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("索引：%d 个库、%d 条目，建了 %d ms（只建这一次）%n%n",
                    schemas, index.size(), buildMs);

            // ---- 之后每次搜索的代价
            for (String needle : List.of("创建时间", "订单", "order", "dishstep",
                    "createtime", "枚举")) {
                long t = System.nanoTime();
                List<ObjectIndex.Hit> hits = ObjectIndex.search(index, needle, 60);
                long us = (System.nanoTime() - t) / 1000L;
                System.out.printf("搜「%s」：%d 条，%d μs%n", needle, hits.size(), us);
                hits.stream().limit(3).forEach(h -> System.out.println(
                        "      " + h.entry().kind().label() + " " + h.entry().name()
                                + "   " + h.entry().path()
                                + (h.matchedComment() ? "   ← 注释「"
                                        + h.entry().comment() + "」" : "")));
                System.out.println();
            }
        } finally {
            context.close();
        }
    }
}
