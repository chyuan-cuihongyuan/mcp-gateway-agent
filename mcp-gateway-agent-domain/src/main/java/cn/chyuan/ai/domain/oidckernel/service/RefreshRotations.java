package cn.chyuan.ai.domain.oidckernel.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * refresh 轮换（工单 1100 ET3，keycloak 思想）。
 * 刷新即换新令牌对（同族代数递增）/旧 refresh 重用检测撤销整族/
 * refresh 过期拒绝/旋转链可续（新 refresh 继续可换）。
 */
public final class RefreshRotations {

    private static final class Token {
        final String family;
        final int generation;
        final long expiresAt;
        boolean rotated;

        Token(String family, int generation, long expiresAt) {
            this.family = family;
            this.generation = generation;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Token> tokens = new HashMap<>();
    private final Set<String> revokedFamilies = new HashSet<>();
    private final Map<String, String> subjectByFamily = new HashMap<>();
    private final Map<String, String> scopeByFamily = new HashMap<>();
    private long seq;

    /** 登录建立 refresh 族 */
    public String create(String subject, String scope, long ttlTicks, long nowTick) {
        String family = "F-" + (++seq);
        subjectByFamily.put(family, subject);
        scopeByFamily.put(family, scope == null ? "" : scope);
        return issue(family, 1, ttlTicks, nowTick);
    }

    /** 刷新：旧 refresh 一次性消费换新对；重用/过期/族撤销拒绝 */
    public String rotate(String refreshToken, long ttlTicks, long nowTick) {
        Token token = tokens.get(refreshToken);
        if (token == null) {
            throw new IllegalArgumentException("未知 refresh 拒绝");
        }
        if (revokedFamilies.contains(token.family)) {
            throw new IllegalStateException("refresh 族已撤销拒绝: " + token.family);
        }
        if (token.rotated) {
            revokedFamilies.add(token.family);
            throw new IllegalStateException("旧 refresh 重用检测撤销整族: " + token.family);
        }
        if (nowTick > token.expiresAt) {
            throw new IllegalStateException("refresh 过期拒绝");
        }
        token.rotated = true;
        return issue(token.family, token.generation + 1, ttlTicks, nowTick);
    }

    /** 族主体与 scope（换新访问令牌用） */
    public String subjectOf(String refreshToken) {
        Token token = require(refreshToken);
        return subjectByFamily.get(token.family);
    }

    public String scopeOf(String refreshToken) {
        Token token = require(refreshToken);
        return scopeByFamily.get(token.family);
    }

    public boolean familyRevoked(String refreshToken) {
        Token token = tokens.get(refreshToken);
        return token != null && revokedFamilies.contains(token.family);
    }

    private String issue(String family, int generation, long ttlTicks, long nowTick) {
        String refresh = "RT-" + family + "-" + generation;
        tokens.put(refresh, new Token(family, generation, nowTick + ttlTicks));
        return refresh;
    }

    private Token require(String refreshToken) {
        Token token = tokens.get(refreshToken);
        if (token == null) {
            throw new IllegalArgumentException("未知 refresh 拒绝");
        }
        return token;
    }
}
