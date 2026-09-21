import com.plainly.app.AppContext;
import com.plainly.app.DbSession;
import com.plainly.core.search.ObjectSearch;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.meta.DbObjects.ColumnRef;
import com.plainly.driver.meta.DbObjects.SchemaInfo;
import com.plainly.driver.meta.DbObjects.TableInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象搜索到底好不好用。
 *
 * <p>「好不好用」不能靠感觉。这里量三件事：
 * <ul>
 *   <li><b>快不快</b>——每敲一个字符要发多少次元数据查询、耗时多少；</li>
 *   <li><b>找得到吗</b>——几种真实的输入习惯，分别能不能命中；</li>
 *   <li><b>排得对吗</b>——想要的那个是不是排在前面。</li>
 * </ul>
 *
 * <pre>java -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" tools/SearchQualityProbe.java</pre>
 */
public class SearchQualityProbe {

    private static int metadataCalls;

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
            System.out.println("已连接 " + live.size() + " 条\n");

            // 预热：把库列表和表列表读进缓存，模拟用户已经用了一会儿的状态
            for (DbSession s : live) {
                for (SchemaInfo si : s.schemas()) {
                    try {
                        s.tables(si.name());
                    } catch (RuntimeException ignored) {
                        // 权限不足的库跳过
                    }
                }
            }
            System.out.println("=== 缓存预热完毕，下面测的是稳态表现 ===\n");

            for (String needle : List.of("order", "user", "ele", "amount", "创建时间")) {
                measure(live, needle);
            }

            System.out.println("=== 几种输入习惯，能不能找到 ===");
            probe(live, "orderitem", "想找 order_item 这种表：中间少打了下划线");
            probe(live, "oi", "只打首字母缩写");
            probe(live, "USER", "全大写");
            probe(live, "sys_", "按前缀找一类表");
        } finally {
            context.close();
        }
    }

    private static void measure(List<DbSession> live, String needle) {
        metadataCalls = 0;
        long start = System.nanoTime();
        List<Hit> hits = search(live, needle, 40);
        long ms = (System.nanoTime() - start) / 1_000_000L;

        System.out.printf("搜「%s」：%d 条结果，%d 次元数据查询，%d ms%n",
                needle, hits.size(), metadataCalls, ms);
        hits.stream().limit(5).forEach(h -> System.out.println(
                "      " + h.hit.kind().label() + "  " + h.hit.name()
                        + "   (" + h.session.config().name() + " · " + h.hit.path() + ")"));
        System.out.println();
    }

    private static void probe(List<DbSession> live, String needle, String what) {
        List<Hit> hits = search(live, needle, 10);
        System.out.printf("  %-12s %s → %s%n", needle, what,
                hits.isEmpty() ? "【一条都没有】"
                        : hits.size() + " 条，第一条是 " + hits.get(0).hit.name());
    }

    /** 和 ObjectSearchBar.collect 同样的口径。 */
    private static List<Hit> search(List<DbSession> live, String needle, int limit) {
        List<Hit> out = new ArrayList<>();
        for (DbSession s : live) {
            for (SchemaInfo si : safeSchemas(s)) {
                List<TableInfo> tables = safeTables(s, si.name());
                List<ColumnRef> columns = needle.length() >= 2
                        ? safeColumns(s, si.name(), needle) : List.of();
                for (ObjectSearch.Hit h : ObjectSearch.match(needle, tables, columns, limit)) {
                    out.add(new Hit(h, s));
                }
            }
        }
        out.sort((a, b) -> ObjectSearch.ranking().compare(a.hit, b.hit));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private record Hit(ObjectSearch.Hit hit, DbSession session) {
    }

    private static List<SchemaInfo> safeSchemas(DbSession s) {
        try {
            return s.schemas();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<TableInfo> safeTables(DbSession s, String schema) {
        try {
            return s.tables(schema);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<ColumnRef> safeColumns(DbSession s, String schema, String needle) {
        try {
            metadataCalls++;
            return s.connection().searchColumns(schema, needle, 300);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
