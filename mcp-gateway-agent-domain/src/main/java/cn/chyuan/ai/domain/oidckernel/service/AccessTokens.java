package cn.chyuan.ai.domain.oidckernel.service;

import java.nio.charset.StandardCharsets;

/**
 * access token（工单 1101 ET4，keycloak 思想）。
 * 颁发含 scope·exp 的 HMAC 签名令牌/过期校验拒绝/签名校验/篡改拒绝。
 */
public final class AccessTokens {

    /** 令牌视图 */
    public record View(String subject, String scope, String audience, long expiresAt) {
    }

    private AccessTokens() {
    }

    /** 颁发：payload=subject|scope|audience|exp 的 HMAC-SHA256 签名令牌 */
    public static String issue(String secret, String subject, String scope, String audience,
            long ttlTicks, long nowTick) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("主体不能为空");
        }
        if (ttlTicks <= 0) {
            throw new IllegalArgumentException("时效必须为正: " + ttlTicks);
        }
        String payload = subject + "|" + (scope == null ? "" : scope) + "|"
                + (audience == null ? "" : audience) + "|" + (nowTick + ttlTicks);
        return "AT." + Hmacs.base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + Hmacs.sign(secret, payload);
    }

    /** 校验：格式/签名/时效三关，返回视图；篡改拒绝 */
    public static View verify(String secret, String token, long nowTick) {
        if (token == null || !token.startsWith("AT.")) {
            throw new IllegalArgumentException("令牌格式拒绝");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("令牌格式拒绝");
        }
        String payload = decode(parts[1]);
        if (!Hmacs.sign(secret, payload).equals(parts[2])) {
            throw new IllegalStateException("签名校验拒绝");
        }
        String[] fields = payload.split("\\|");
        long expiresAt = Long.parseLong(fields[3]);
        if (nowTick > expiresAt) {
            throw new IllegalStateException("令牌过期拒绝");
        }
        return new View(fields[0], fields[1], fields[2], expiresAt);
    }

    private static String decode(String base64Url) {
        return new String(java.util.Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
