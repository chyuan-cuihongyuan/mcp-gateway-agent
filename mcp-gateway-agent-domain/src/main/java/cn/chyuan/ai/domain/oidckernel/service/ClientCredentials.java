package cn.chyuan.ai.domain.oidckernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * client 凭据模式（工单 1104 ET7，keycloak 思想）。
 * client_id+secret 验证颁发/错误 secret 拒绝/令牌含 client 主体/过期后重新获取。
 */
public final class ClientCredentials {

    private final Map<String, String> clients = new HashMap<>();

    /** 注册 client */
    public void register(String clientId, String secret) {
        if (clientId == null || clientId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("client 凭据不能为空");
        }
        clients.put(clientId, secret);
    }

    /** 校验凭据：返回 client 主体令牌载荷；错误拒绝 */
    public String grant(String clientId, String secret) {
        String expected = clients.get(clientId);
        if (expected == null) {
            throw new IllegalArgumentException("未知 client 拒绝: " + clientId);
        }
        if (!expected.equals(secret)) {
            throw new IllegalStateException("错误 secret 拒绝");
        }
        return clientId;
    }

    public boolean registered(String clientId) {
        return clients.containsKey(clientId);
    }
}
