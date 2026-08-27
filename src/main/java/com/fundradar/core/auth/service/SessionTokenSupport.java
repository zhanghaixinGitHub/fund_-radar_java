package com.fundradar.core.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 生成并散列不透明会话令牌；数据库只保存 SHA-256 散列，永不保存原始 Cookie 值。 */
public final class SessionTokenSupport {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private SessionTokenSupport() {
    }

    /** 生成 256 位随机令牌，供 HttpOnly 会话或 CSRF Cookie 使用。 */
    public static String createToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 计算令牌 SHA-256 十六进制摘要，避免原始会话令牌持久化。 */
    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
