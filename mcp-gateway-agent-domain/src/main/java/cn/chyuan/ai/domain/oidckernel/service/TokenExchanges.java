package cn.chyuan.ai.domain.oidckernel.service;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * token exchange（工单 1103 ET6，keycloak 思想）。
 * 主体转换颁发新令牌/audience 切换/scope 只可收窄不可扩大/越权（扩大）拒绝。
 */
public final class TokenExchanges {

    private TokenExchanges() {
    }

    /** 交换：subject 保持；audience 切换；requestedScope ⊆ 原 scope（可收窄），扩大拒绝 */
    public static AccessTokens.View exchange(AccessTokens.View source, String targetAudience,
            String requestedScope) {
        if (targetAudience == null || targetAudience.isBlank()) {
            throw new IllegalArgumentException("目标 audience 不能为空");
        }
        Set<String> granted = scopeSet(source.scope());
        Set<String> requested = requestedScope == null || requestedScope.isBlank()
                ? granted
                : scopeSet(requestedScope);
        if (!granted.containsAll(requested)) {
            throw new IllegalStateException("scope 只可收窄不可扩大拒绝");
        }
        return new AccessTokens.View(source.subject(), String.join(" ", requested), targetAudience,
                source.expiresAt());
    }

    private static Set<String> scopeSet(String scope) {
        return new TreeSet<>(Arrays.asList(scope.trim().split("\\s+")));
    }
}
