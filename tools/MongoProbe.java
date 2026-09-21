import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机那个 MongoDB 到底是什么。
 *
 * <h2>为什么先探再写</h2>
 * 「加 MongoDB 支持」这句话里，有一半的工作量取决于对面是什么：要不要认证、
 * 是单机还是副本集、版本多少（决定驱动能不能用新协议）、库里有没有数据
 * 可以拿来验证展示。这些全靠猜的话，写完第一次连就会撞上，
 * 而那时候已经写了上千行了。
 *
 * <p>只读，不建库不写数据。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "&lt;mongo 三个 jar&gt;" tools/MongoProbe.java [连接串]
 * </pre>
 */
public class MongoProbe {

    public static void main(String[] args) {
        String uri = args.length > 0 ? args[0] : "mongodb://127.0.0.1:27017";
        System.out.println("连接串：" + uri);

        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase admin = client.getDatabase("admin");

            Document build = admin.runCommand(new Document("buildInfo", 1));
            System.out.println("服务端版本：" + build.get("version"));
            System.out.println("存储引擎相关：" + build.get("modules"));

            try {
                Document status = admin.runCommand(new Document("isMaster", 1));
                System.out.println("副本集：" + (status.get("setName") == null
                        ? "单机" : status.get("setName")));
                System.out.println("最大文档字节：" + status.get("maxBsonObjectSize"));
            } catch (RuntimeException e) {
                System.out.println("isMaster 失败：" + e.getMessage());
            }

            System.out.println();
            System.out.println("=== 数据库");
            List<String> names = new ArrayList<>();
            client.listDatabaseNames().forEach(names::add);
            for (String db : names) {
                System.out.println("  " + db);
            }

            for (String db : names) {
                if (db.equals("admin") || db.equals("local") || db.equals("config")) {
                    continue;
                }
                System.out.println();
                System.out.println("=== " + db + " 的集合");
                MongoDatabase d = client.getDatabase(db);
                for (Document c : d.listCollections()) {
                    String name = c.getString("name");
                    long count = d.getCollection(name).estimatedDocumentCount();
                    System.out.println("  " + name + "   类型=" + c.get("type")
                            + "   约 " + count + " 篇");
                }
                // 挑一个集合看看文档长什么样——字段是不是齐的，直接决定网格怎么列列
                Document sample = d.listCollections().first();
                if (sample != null) {
                    String name = sample.getString("name");
                    System.out.println("  ---- " + name + " 的头两篇：");
                    for (Document doc : d.getCollection(name).find().limit(2)) {
                        System.out.println("    " + doc.toJson());
                    }
                }
            }
        } catch (RuntimeException e) {
            System.out.println("连不上或命令被拒：" + e);
            System.out.println("（要认证的话，用 mongodb://用户:密码@127.0.0.1:27017/?authSource=admin 再跑一次）");
        }
    }
}
