package com.plainly.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plainly.driver.ConnectionConfig;
import com.plainly.driver.DbException;
import com.plainly.driver.DbType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 连接配置的导出与导入。
 *
 * <p>这里最要紧的两条：<b>不带口令时文件里不能出现口令</b>（导出文件多半会经过
 * 聊天工具、邮件、网盘），以及 <b>SSH 这类附加参数必须跟着走</b>——
 * 漏掉它们，导过去的连接连不上，而界面上看起来一切正常。
 */
@DisplayName("连接配置 · 导出与导入")
class ConnectionPortabilityTest {

    @TempDir
    Path dir;

    private static ConnectionConfig sample() {
        ConnectionConfig c = new ConnectionConfig()
                .setId("id-1")
                .setName("生产 MySQL")
                .setType(DbType.MYSQL)
                .setHost("10.0.0.9")
                .setPort(3307)
                .setUser("app")
                .setDatabase("orders")
                .setColor("#a0402a")
                .setGroup("生产")
                .setReadOnly(true)
                .setPassword("p@ss:word\"带引号");
        c.extraProperties().put("ssh.host", "jump.example.com");
        c.extraProperties().put("ssh.port", "22");
        return c;
    }

    @Test
    @DisplayName("默认不带口令：文件里搜不到那段密码")
    void exportWithoutPassword() throws Exception {
        Path file = dir.resolve("conn.json");
        ConnectionPortability.export(List.of(sample()), file, null, null);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("p@ss"), "不带口令的导出文件里不该出现口令：" + text);

        ConnectionPortability.ImportResult result = ConnectionPortability.read(file, null);
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(1, result.configs().size());
        assertNull(result.configs().get(0).password(), "没导出口令，导进来也不该有");
    }

    @Test
    @DisplayName("字段一个不落地走一圈，SSH 这类附加参数也要跟着走")
    void roundTripKeepsEveryField() {
        Path file = dir.resolve("conn.json");
        ConnectionPortability.export(List.of(sample()), file, null, null);
        ConnectionConfig back = ConnectionPortability.read(file, null).configs().get(0);

        assertEquals("生产 MySQL", back.name());
        assertEquals(DbType.MYSQL, back.type());
        assertEquals("10.0.0.9", back.host());
        assertEquals(3307, back.port());
        assertEquals("app", back.user());
        assertEquals("orders", back.database());
        assertEquals("#a0402a", back.color());
        assertEquals("生产", back.group());
        assertTrue(back.readOnly());
        assertEquals("jump.example.com", back.extraProperties().get("ssh.host"));
        assertEquals("22", back.extraProperties().get("ssh.port"));
    }

    @Test
    @DisplayName("带口令导出：同一个口令解得开，换一个口令解不开")
    void encryptedPasswordRoundTrip() throws Exception {
        Path file = dir.resolve("conn.json");
        ConnectionPortability.export(List.of(sample()), file, "一个很长的口令", null);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("p@ss"), "密文文件里也不该出现明文口令");

        ConnectionConfig back = ConnectionPortability
                .read(file, "一个很长的口令").configs().get(0);
        assertEquals("p@ss:word\"带引号", back.password(),
                "带冒号和引号的口令也要能原样回来——冒号正是密文自己的分隔符");

        // 口令不对时，得到的是一句人看得懂的话，不是 AEADBadTagException
        ConnectionPortability.ImportResult wrong =
                ConnectionPortability.read(file, "错的口令");
        assertTrue(wrong.configs().isEmpty());
        assertEquals(1, wrong.problems().size());
        assertTrue(wrong.problems().get(0).contains("口令不对"), wrong.problems().get(0));
    }

    @Test
    @DisplayName("两条一样的密码，密文必须不一样——否则不解密也看得出它们相同")
    void sameSecretsGetDifferentCiphertext() throws Exception {
        ConnectionConfig a = sample().setId("a").setName("甲");
        ConnectionConfig b = sample().setId("b").setName("乙");
        Path file = dir.resolve("conn.json");
        ConnectionPortability.export(List.of(a, b), file, "口令", null);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> secrets = text.lines()
                .filter(l -> l.contains("\"password\""))
                .map(l -> l.substring(l.indexOf("\"password\"")))
                .toList();
        assertEquals(2, secrets.size());
        assertNotEquals(secrets.get(0), secrets.get(1));
    }

    @Test
    @DisplayName("有加密口令却没给解密口令时，明说是这个原因")
    void missingPassphraseIsExplained() {
        Path file = dir.resolve("conn.json");
        ConnectionPortability.export(List.of(sample()), file, "口令", null);

        ConnectionPortability.ImportResult result = ConnectionPortability.read(file, null);
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("没有输入解密口令"),
                result.problems().get(0));
    }

    @Test
    @DisplayName("一条读坏了，剩下的照样导得进来")
    void oneBadRecordDoesNotStopTheRest() throws Exception {
        Path file = dir.resolve("conn.json");
        Files.writeString(file, """
                [
                  {"name": "好的", "type": "H2", "filePath": "mem:x"},
                  {"name": "坏的", "type": "NOT_A_DB"},
                  {"name": "也是好的", "type": "SQLITE", "filePath": "a.db"}
                ]
                """, StandardCharsets.UTF_8);

        ConnectionPortability.ImportResult result = ConnectionPortability.read(file, null);
        assertEquals(2, result.configs().size());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("认不出的数据库类型"),
                result.problems().get(0));
    }

    @Test
    @DisplayName("空数组不是「读到了 0 条」，要说清楚文件里没东西")
    void emptyFileIsReported() throws Exception {
        Path file = dir.resolve("conn.json");
        Files.writeString(file, "[]", StandardCharsets.UTF_8);
        ConnectionPortability.ImportResult result = ConnectionPortability.read(file, null);
        assertTrue(result.configs().isEmpty());
        assertEquals(1, result.problems().size());
    }

    @Test
    @DisplayName("密文被改过时不会静静解出一段垃圾")
    void tamperedCiphertextIsRejected() {
        assertThrows(DbException.class, () -> {
            Path file = dir.resolve("conn.json");
            Files.writeString(file,
                    "[{\"name\":\"x\",\"type\":\"H2\",\"password\":\"plainly-pbkdf2-aesgcm-v1:AAAA:BBBB:CCCC\"}]",
                    StandardCharsets.UTF_8);
            ConnectionPortability.ImportResult r = ConnectionPortability.read(file, "口令");
            if (!r.problems().isEmpty()) {
                throw new DbException(r.problems().get(0));
            }
        });
    }
}
