import com.plainly.core.backup.BackupService;
import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 备份里到底装进去了什么。
 *
 * <h2>为什么要专门验一遍</h2>
 * 备份最危险的失败方式不是报错，是<b>安静地少装点东西</b>——用户以为自己有备份，
 * 等到要还原时才发现视图或存储过程根本不在里面，而那时候原库多半已经没了。
 *
 * <p>所以这里不看「有没有报错」，而是把生成的文件抓出来数：几张表、几个视图、
 * 几个例程、几个触发器，以及各段的<b>顺序</b>——触发器必须排在数据之后，
 * 否则还原时那些 INSERT 会把触发器全触发一遍。
 *
 * <h2>只读</h2>
 * 备份本身只发 SELECT 和元数据查询。文件写到临时目录，不动数据库。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/BackupProbe.java "连接名" 模式名 [类型]
 * </pre>
 */
public class BackupProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：BackupProbe \"连接名\" 模式名 [类型]");
            return;
        }
        try (LocalStore store = new LocalStore()) {
            ConnectionRegistry registry = new ConnectionRegistry(store,
                    CredentialStore.forCurrentPlatform());
            String wantedType = args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : null;
            ConnectionConfig config = registry.listAll().stream()
                    .filter(c -> c.name().equals(args[0]))
                    .filter(c -> wantedType == null || c.type().name().equals(wantedType))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                System.out.println("找不到连接 " + args[0]);
                return;
            }

            Path target = Files.createTempDirectory("plainly-backup-probe").resolve("dump.sql");
            try (DbConnection db = Connections.open(registry.resolvePassword(config))) {
                System.out.println("已连接：" + db.serverVersion());
                // 只备结构，不备数据：这次要验的是「有没有装齐」，
                // 而几十万行数据只会让文件大到没法看
                BackupService.Result result = BackupService.backup(
                        db, args[1], target, false, line -> { });

                System.out.println();
                System.out.println("结果：" + result.describe());
                System.out.println("文件：" + result.file() + "  " + result.bytes() + " 字节");
                if (result.skipped().isEmpty()) {
                    System.out.println("没有遗漏。");
                } else {
                    System.out.println("遗漏：");
                    result.skipped().forEach(one -> System.out.println("  · " + one));
                }

                String text = Files.readString(target, StandardCharsets.UTF_8);
                System.out.println();
                System.out.println("== 各段出现的位置（字符偏移）");
                for (String section : List.of("========== 视图", "========== 存储过程与函数",
                        "========== 触发器")) {
                    int at = text.indexOf(section);
                    System.out.println("  " + section + " → " + (at < 0 ? "（没有这一段）" : at));
                }
                int firstInsert = text.indexOf("INSERT INTO");
                int triggerAt = text.indexOf("========== 触发器");
                if (firstInsert >= 0 && triggerAt >= 0) {
                    System.out.println("  第一条 INSERT 在 " + firstInsert
                            + "，触发器段在 " + triggerAt
                            + " → " + (triggerAt > firstInsert ? "顺序正确" : "顺序错了！"));
                }

                System.out.println();
                System.out.println("== 存储过程那一段的开头（确认是完整 CREATE，不是光秃秃的函数体）");
                int at = text.indexOf("========== 存储过程与函数");
                if (at < 0) {
                    System.out.println("  （这个库里没有存储过程）");
                } else {
                    String tail = text.substring(at, Math.min(text.length(), at + 700));
                    System.out.println(indent(tail));
                }
            }
        }
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }
}
