package com.plainly.core.store;

import java.util.Base64;

/**
 * 连接口令的加密存储。
 *
 * <p>口令<b>不以明文落盘</b>，也不长期驻留在 {@code ConnectionConfig} 里，
 * 只在建立连接的那一刻解密取出。
 */
public interface CredentialStore {

    /** 加密。返回可安全写入配置库的 Base64 文本。 */
    String protect(String plaintext);

    /** 解密。密文来自本机同一用户时才可能成功；否则返回 {@code null}。 */
    String unprotect(String ciphertext);

    /** 供界面显示的存储方式说明。 */
    String describe();

    /**
     * 选择本机可用的实现。
     *
     * <p>Windows 上用 DPAPI；其他平台或 DPAPI 不可用时退回可逆编码，
     * 并如实告知用户「未加密」——这里绝不能假装安全。
     */
    static CredentialStore forCurrentPlatform() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                return new DpapiCredentialStore();
            } catch (Throwable t) {
                // JNA 缺失或调用失败，退回下面的兜底实现
            }
        }
        return new UnprotectedCredentialStore();
    }

    /**
     * 兜底实现：仅做 Base64，<b>不提供任何机密性</b>。
     *
     * <p>之所以不在这里随便塞个 AES：密钥必须跟着程序走，等于把锁和钥匙放在一起，
     * 那是「看起来加密了」，比明说未加密更危险。
     */
    class UnprotectedCredentialStore implements CredentialStore {
        @Override
        public String protect(String plaintext) {
            if (plaintext == null) {
                return null;
            }
            return Base64.getEncoder().encodeToString(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public String unprotect(String ciphertext) {
            if (ciphertext == null) {
                return null;
            }
            try {
                return new String(Base64.getDecoder().decode(ciphertext),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        public String describe() {
            return "未加密（当前平台无可用的系统凭据保护）";
        }
    }
}
