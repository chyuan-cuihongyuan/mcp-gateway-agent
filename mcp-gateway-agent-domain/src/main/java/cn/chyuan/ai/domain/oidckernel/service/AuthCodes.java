package cn.chyuan.ai.domain.oidckernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 授权码（工单 1098 ET1，keycloak 思想）。
 * 颁发一次性短时效/换取访问令牌载荷/重复消费拒绝/过期拒绝/client 与 redirect 失配拒绝。
 */
public final class AuthCodes {

    private static final class Entry {
        final String clientId;
        final String redirectUri;
        final String scope;
        final long expiresAt;
        final String challenge;
        boolean used;

        Entry(String clientId, String redirectUri, String scope, long expiresAt, String challenge) {
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.scope = scope;
            this.expiresAt = expiresAt;
            this.challenge = challenge;
        }
    }

    private final Map<String, Entry> codes = new HashMap<>();
    private long seq;

    /** 颁发：一次性短时效；challenge 可空（无 PKCE 场景） */
    public String issue(String clientId, String redirectUri, String scope, String challenge,
            long ttlTicks, long nowTick) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("授权要素不能为空");
        }
        if (ttlTicks <= 0) {
            throw new IllegalArgumentException("授权码时效必须为正: " + ttlTicks);
        }
        String code = "AC-" + (++seq);
        codes.put(code, new Entry(clientId, redirectUri, scope == null ? "" : scope, nowTick + ttlTicks,
                challenge));
        return code;
    }

    /** 换取：一次性消费；未知/已消费/过期/失配拒绝 */
    public String exchange(String code, String clientId, String redirectUri, long nowTick) {
        Entry entry = codes.get(code);
        if (entry == null) {
            throw new IllegalArgumentException("未知授权码拒绝: " + code);
        }
        if (entry.used) {
            throw new IllegalStateException("授权码重复消费拒绝: " + code);
        }
        if (nowTick > entry.expiresAt) {
            throw new IllegalStateException("授权码过期拒绝: " + code);
        }
        if (!entry.clientId.equals(clientId) || !entry.redirectUri.equals(redirectUri)) {
            throw new IllegalStateException("client 或 redirect 失配拒绝: " + code);
        }
        entry.used = true;
        return entry.scope;
    }

    /** 绑定的 PKCE challenge（无则为 null） */
    public String challengeOf(String code) {
        Entry entry = codes.get(code);
        return entry == null ? null : entry.challenge;
    }
}
