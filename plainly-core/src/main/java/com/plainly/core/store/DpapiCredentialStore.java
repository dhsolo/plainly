package com.plainly.core.store;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows DPAPI 实现。
 *
 * <p>{@code CryptProtectData} 用当前用户账户派生的密钥加密，密钥由操作系统托管、
 * 不落盘也不在进程里出现。换台机器或换个 Windows 用户，密文就解不开——
 * 这正是我们想要的：配置库被拷走也拿不到口令。
 */
public class DpapiCredentialStore implements CredentialStore {

    public DpapiCredentialStore() {
        // 构造时就探一次，让不可用的情况在选择实现时立刻暴露，
        // 而不是等用户点了保存才失败
        byte[] probe = Crypt32Util.cryptProtectData("probe".getBytes(StandardCharsets.UTF_8));
        Crypt32Util.cryptUnprotectData(probe);
    }

    @Override
    public String protect(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = Crypt32Util.cryptProtectData(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    @Override
    public String unprotect(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            return new String(Crypt32Util.cryptUnprotectData(raw), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 换了机器或换了用户，解不开是预期行为：当作「没有保存口令」处理
            return null;
        }
    }

    @Override
    public String describe() {
        return "Windows DPAPI · 绑定当前用户账户";
    }
}
