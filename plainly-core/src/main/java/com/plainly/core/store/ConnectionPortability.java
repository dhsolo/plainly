package com.plainly.core.store;

import com.plainly.core.imports.JsonRows;
import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接配置的导出与导入。
 *
 * <h2>口令为什么不能原样带走</h2>
 * 本机存的那份密文是 DPAPI 加密的，<b>绑定当前 Windows 账户</b>。
 * 把它照抄进导出文件，换台机器、换个用户都解不开——而且解不开的表现不是报错，
 * 是「导入成功了，一连就说密码不对」。用户会去怀疑密码本身。
 *
 * <p>所以只有两种诚实的做法，都在这里：
 * <ul>
 *   <li><b>不带口令</b>（默认）。导入之后每条连接自己补一次密码。
 *       这也是把配置发给同事时唯一负责任的做法——口令不该在聊天工具里流转；</li>
 *   <li><b>用一个口令重新加密</b>。文件里的每条密码各自带着自己的盐和 IV，
 *       用同一个用户口令派生密钥。对方导入时输入同一个口令。</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * 顶层是一个数组，每个元素是一条连接的扁平对象。刻意不做嵌套：
 * 这个项目的 JSON 读取器只认扁平对象（见 {@link JsonRows}），
 * 而为了导出这一个功能引一个 JSON 库、或者给读取器加一套嵌套支持，
 * 都比「把 extraProperties 摊平成 extra.xxx」的代价大。
 */
public final class ConnectionPortability {

    /** 密文的前缀，兼作版本号。将来换算法时靠它认出老文件。 */
    private static final String CIPHER_PREFIX = "plainly-pbkdf2-aesgcm-v1";
    private static final int PBKDF2_ROUNDS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;

    /** {@code extraProperties} 摊平到顶层时的前缀。 */
    private static final String EXTRA_PREFIX = "extra.";

    private ConnectionPortability() {
    }

    /** 一次导入的结果：读出来的连接，加上不至于中止整件事的那些毛病。 */
    public record ImportResult(List<ConnectionConfig> configs, List<String> problems) {
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 导出若干条连接。
     *
     * @param passphrase 非空则用它加密口令；为 null 或空表示不带口令
     * @param registry   用来取出明文口令；不带口令时可以传 null
     */
    public static void export(List<ConnectionConfig> configs, Path file,
                              String passphrase, ConnectionRegistry registry) {
        boolean withPasswords = passphrase != null && !passphrase.isEmpty();
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < configs.size(); i++) {
            ConnectionConfig c = configs.get(i);
            Map<String, String> fields = toFields(c);
            if (withPasswords && c.savePassword()) {
                String plain = plainPassword(c, registry);
                if (plain != null && !plain.isEmpty()) {
                    fields.put("password", encrypt(plain, passphrase));
                }
            }
            sb.append("  ").append(writeObject(fields));
            if (i < configs.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("]\n");
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DbException("写出 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 取一条连接的明文口令。
     *
     * <p>解不开（换过机器或换过 Windows 账户）时返回空串而不是抛异常：
     * 那条连接不带口令导出去就是了，不该让整个导出失败。
     */
    private static String plainPassword(ConnectionConfig config, ConnectionRegistry registry) {
        if (config.password() != null && !config.password().isEmpty()) {
            return config.password();
        }
        if (registry == null) {
            return null;
        }
        try {
            return registry.resolvePassword(config).password();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, String> toFields(ConnectionConfig c) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", c.name());
        f.put("type", c.type().name());
        f.put("host", c.host());
        f.put("port", String.valueOf(c.port()));
        f.put("user", c.user());
        f.put("database", c.database());
        f.put("filePath", c.filePath());
        f.put("timezone", c.timezone());
        f.put("charset", c.charset());
        f.put("color", c.color());
        f.put("group", c.group());
        f.put("readOnly", String.valueOf(c.readOnly()));
        f.put("savePassword", String.valueOf(c.savePassword()));
        // SSH 隧道、附加驱动参数都在这里。漏掉它们，导过去的连接连不上，
        // 而界面上看起来一切正常——这正是最难查的那类问题
        c.extraProperties().forEach((k, v) -> f.put(EXTRA_PREFIX + k, v));
        return f;
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 读一个导出文件。
     *
     * <p>不落库、不改任何东西——调用方拿到结果之后自己决定存哪些。
     * 一条读坏了就记一条毛病继续读下一条：十条里有一条格式不对，
     * 不该让另外九条也进不来。
     */
    public static ImportResult read(Path file, String passphrase) {
        List<ConnectionConfig> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int[] index = {0};
        JsonRows.forEachObject(file, StandardCharsets.UTF_8, obj -> {
            index[0]++;
            try {
                out.add(fromFields(obj, passphrase));
            } catch (RuntimeException e) {
                problems.add("第 " + index[0] + " 条读不出来："
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
        if (out.isEmpty() && problems.isEmpty()) {
            problems.add("文件里一条连接也没有。导出文件的顶层应当是一个数组。");
        }
        return new ImportResult(out, problems);
    }

    private static ConnectionConfig fromFields(Map<String, String> f, String passphrase) {
        String name = f.get("name");
        if (name == null || name.isBlank()) {
            throw new DbException("没有 name 字段");
        }
        ConnectionConfig c = new ConnectionConfig()
                .setName(name)
                .setType(parseType(f.get("type")))
                .setHost(nz(f.get("host")))
                .setPort(parseInt(f.get("port")))
                .setUser(nz(f.get("user")))
                .setDatabase(nz(f.get("database")))
                .setFilePath(nz(f.get("filePath")))
                .setColor(nz(f.get("color")))
                .setGroup(nz(f.get("group")))
                .setReadOnly(Boolean.parseBoolean(f.get("readOnly")))
                .setSavePassword(!"false".equals(f.get("savePassword")));
        // 时区和字符集有默认值，文件里没写就别把它们清成空串
        if (f.get("timezone") != null && !f.get("timezone").isBlank()) {
            c.setTimezone(f.get("timezone"));
        }
        if (f.get("charset") != null && !f.get("charset").isBlank()) {
            c.setCharset(f.get("charset"));
        }
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (e.getKey().startsWith(EXTRA_PREFIX) && e.getValue() != null) {
                c.extraProperties().put(e.getKey().substring(EXTRA_PREFIX.length()), e.getValue());
            }
        }

        String encrypted = f.get("password");
        if (encrypted != null && !encrypted.isBlank()) {
            if (passphrase == null || passphrase.isEmpty()) {
                throw new DbException("「" + name + "」带着加密的口令，但没有输入解密口令");
            }
            c.setPassword(decrypt(encrypted, passphrase));
        }
        return c;
    }

    private static DbType parseType(String name) {
        if (name == null || name.isBlank()) {
            throw new DbException("没有 type 字段");
        }
        try {
            return DbType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbException("认不出的数据库类型：" + name);
        }
    }

    private static int parseInt(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 口令加解密

    /**
     * 加密一条口令。
     *
     * <p>每条各带自己的盐和 IV，串成 {@code 前缀:盐:IV:密文}。
     * 为什么不共用一份盐：共用之后，同一个文件里两条一样的密码会得到一样的密文，
     * 拿到文件的人不用解密就知道「这两个库是同一个密码」。
     */
    private static String encrypt(String plaintext, String passphrase) {
        try {
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return CIPHER_PREFIX + ":" + b64.encodeToString(salt) + ":"
                    + b64.encodeToString(iv) + ":" + b64.encodeToString(ct);
        } catch (Exception e) {
            throw new DbException("加密口令失败：" + e.getMessage(), e);
        }
    }

    private static String decrypt(String encoded, String passphrase) {
        String[] parts = encoded.split(":");
        if (parts.length != 4 || !CIPHER_PREFIX.equals(parts[0])) {
            throw new DbException("口令密文的格式认不出来（应当是 " + CIPHER_PREFIX + " 开头）");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(parts[1]);
            byte[] iv = b64.decode(parts[2]);
            byte[] ct = b64.decode(parts[3]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (DbException e) {
            throw e;
        } catch (Exception e) {
            // GCM 的校验失败和「口令不对」是同一个异常。直说是口令不对——
            // 这是绝大多数情况，而 AEADBadTagException 这个词对用户没有意义
            throw new DbException("解密失败：口令不对，或者文件被改过");
        }
    }

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ROUNDS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ JSON 写出

    /** 一个扁平对象。值一律写成字符串——读取端本来也只认字符串。 */
    private static String writeObject(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** JSON 字符串字面量。控制字符要转义成 \\uXXXX，否则出来的文件根本不是合法 JSON。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
