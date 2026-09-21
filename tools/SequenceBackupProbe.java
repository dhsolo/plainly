import com.plainly.core.backup.BackupService;
import com.plainly.core.db.Connections;
import com.plainly.core.store.ConnectionRegistry;
import com.plainly.core.store.CredentialStore;
import com.plainly.core.store.LocalStore;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbConnection;
import com.plainly.driver.DbType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 备份里的序列，起始值到底对不对。
 *
 * <h2>为什么必须真跑</h2>
 * 「下一个该发的号」各家的说法完全不同，而且都藏在数据字典的一个列名里：
 * PostgreSQL 的 {@code last_value} 是<b>刚发出去的那个</b>（没用过时还是 NULL），
 * Oracle 的 {@code LAST_NUMBER} 已经是<b>下一个</b>，H2 的 {@code BASE_VALUE} 也是下一个。
 * 取错一个，备份文件照样生成、照样能还原，只是还原出来的序列会把用过的号再发一遍——
 * 直到某天一条 INSERT 撞上主键冲突。
 *
 * <p>所以这里的判据不是「有没有 CREATE SEQUENCE」，而是
 * <b>START WITH 的那个数字，等不等于库里下一次真的会发出来的号</b>。
 *
 * <p>会建一个自己的序列，跑完就删。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "plainly-app/target/plainly.jar;plainly-app/target/deps/*" ^
 *      tools/SequenceBackupProbe.java "连接名" 模式名 [类型]
 * </pre>
 */
public class SequenceBackupProbe {

    private static final String SEQ = "PLAINLY_SEQ_PROBE";
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        if (args.length >= 2) {
            try (LocalStore store = new LocalStore()) {
                ConnectionRegistry registry =
                        new ConnectionRegistry(store, CredentialStore.forCurrentPlatform());
                String wanted = args.length > 2 ? args[2].toUpperCase(java.util.Locale.ROOT) : null;
                ConnectionConfig cfg = registry.listAll().stream()
                        .filter(c -> c.name().equals(args[0]))
                        .filter(c -> wanted == null || c.type().name().equals(wanted))
                        .findFirst().orElse(null);
                if (cfg == null) {
                    System.out.println("找不到连接 " + args[0]);
                    return;
                }
                try (DbConnection db = Connections.open(registry.resolvePassword(cfg))) {
                    run(db, args[1]);
                }
            }
        } else {
            ConnectionConfig cfg = new ConnectionConfig()
                    .setName("seq-probe").setType(DbType.H2)
                    .setFilePath("mem:plainly_seq_probe;DB_CLOSE_DELAY=-1")
                    .setUser("sa").setPassword("");
            try (DbConnection db = Connections.open(cfg)) {
                run(db, "PUBLIC");
            }
        }
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
    }

    private static void run(DbConnection db, String schema) throws Exception {
        System.out.println("数据库：" + db.serverVersion());
        String qualified = db.dialect().qualify(schema, SEQ);

        drop(db, qualified);
        db.execute("CREATE SEQUENCE " + qualified + " START WITH 100 INCREMENT BY 7", 0);

        // 取三次，让当前值离起始值明显拉开：100、107、114，下一个应当是 121
        for (int i = 0; i < 3; i++) {
            nextValue(db, schema, qualified);
        }
        String expected = "121";
        System.out.println("已取三次，库里下一个该发的号应当是 " + expected);

        Path file = Files.createTempDirectory("plainly-seq").resolve("dump.sql");
        BackupService.Result result = BackupService.backup(
                db, schema, file, true, m -> { });

        String sql = Files.readString(file);
        System.out.println("备份统计：" + result.describe());

        String line = null;
        for (String l : sql.split("\n")) {
            if (l.toUpperCase(java.util.Locale.ROOT).contains("CREATE SEQUENCE")
                    && l.toUpperCase(java.util.Locale.ROOT).contains(SEQ)) {
                line = l.trim();
            }
        }
        check("备份里有这个序列", line != null, "没找到 CREATE SEQUENCE");
        if (line == null) {
            drop(db, qualified);
            return;
        }
        System.out.println("生成的语句：" + line);

        /*
         * 真正要守的不变式是「不重发」，不是「精确相等」。
         *
         * Oracle 的 LAST_NUMBER 是已经写进磁盘的那个号，默认 CACHE 20 会预分配
         * 一整段（100…233）并把段尾之后的 240 记下来。于是恢复出来会从 240 开始，
         * 跳过 121…233 —— 那一段号在原库上只存在于实例的内存里，实例一重启就没了。
         *
         * 跳号只是浪费几个数字；重发一个用过的号，则是下一次插入直接主键冲突。
         * 两个方向的代价差着数量级，所以断言写成「不小于」。
         */
        long actual = startValueOf(line);
        check("起始值不小于真实的下一个号（" + expected + "），不会重发已用过的号",
                actual >= Long.parseLong(expected),
                "语句里的起始值是 " + actual);
        if (actual > Long.parseLong(expected)) {
            System.out.println("    （跳过了 " + (actual - Long.parseLong(expected))
                    + " 个号，是缓存预分配造成的，安全）");
        }
        check("步长带过去了", line.contains("INCREMENT BY 7"), "没有 INCREMENT BY 7");
        check("统计里算上了序列", result.sequences() >= 1,
                "sequences=" + result.sequences());
        check("没有把序列记成「跳过」", result.skipped().stream()
                .noneMatch(x -> x.contains("序列")), String.valueOf(result.skipped()));

        drop(db, qualified);
    }

    /** 从生成的语句里把 START WITH 后面那个数字抠出来。 */
    private static long startValueOf(String ddl) {
        String upper = ddl.toUpperCase(java.util.Locale.ROOT);
        int at = upper.indexOf("START WITH");
        if (at < 0) {
            return Long.MIN_VALUE;
        }
        // 手动扫一遍，省得为了一个探针跟正则里的反斜杠较劲
        int i = at + "START WITH".length();
        while (i < upper.length() && upper.charAt(i) == ' ') {
            i++;
        }
        int start = i;
        while (i < upper.length() && (Character.isDigit(upper.charAt(i))
                || (i == start && upper.charAt(i) == '-'))) {
            i++;
        }
        return i > start ? Long.parseLong(upper.substring(start, i)) : Long.MIN_VALUE;
    }

    /** 取一个号。各家语法不同。 */
    private static void nextValue(DbConnection db, String schema, String qualified) {
        DbType type = db.config().type();
        String sql = switch (type) {
            case ORACLE, DM -> "SELECT " + qualified + ".NEXTVAL FROM DUAL";
            // 限定名原样塞进去：建的时候用的是带引号的大写名，
            // 这里写小写会找不到（PG 不带引号时会折成小写）
            case POSTGRESQL -> "SELECT nextval('" + qualified.replace("'", "''") + "')";
            default -> "SELECT NEXT VALUE FOR " + qualified;
        };
        db.execute(sql, 1);
    }

    private static void drop(DbConnection db, String qualified) {
        try {
            db.execute("DROP SEQUENCE " + qualified, 0);
        } catch (RuntimeException ignored) {
            // 本来就没有
        }
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  " + what);
        } else {
            fail++;
            System.out.println("× " + what + "   " + detail);
        }
    }
}
