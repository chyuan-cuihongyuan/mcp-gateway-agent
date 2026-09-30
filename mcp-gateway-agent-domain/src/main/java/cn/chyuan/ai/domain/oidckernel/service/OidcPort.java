package cn.chyuan.ai.domain.oidckernel.service;

import java.util.List;

/**
 * 令牌流端口（工单 1105 ET8，keycloak 思想）。
 * authorize·token·refresh 入口统一编排：授权码 PKCE/refresh 轮换/access 校验/
 * device flow/token exchange/client 凭据组合管线；vaultkernel 凭据掩码形状只读联动
 * （掩码串形态对齐，不 import vaultkernel）/oidc-kernel.enabled 默认关（开启才改变行为）。
 */
public interface OidcPort {

    // —— 授权码 + PKCE（ET1/ET2/ET4）——
    String authorize(String clientId, String redirectUri, String scope, String codeVerifier, long now);

    String token(String code, String clientId, String redirectUri, String verifier, long now);

    // —— refresh 轮换（ET3）——
    String refresh(String refreshToken, long now);

    // —— 校验（ET4）——
    String introspect(String accessToken, long now);

    // —— device flow（ET5）——
    String deviceBegin();

    String devicePoll(String deviceCode, long now);

    void deviceConfirm(String userCode, String subject);

    // —— token exchange（ET6）——
    String exchange(String accessToken, String audience, String scope, long now);

    // —— client 凭据（ET7）——
    void registerClient(String clientId, String secret);

    String clientCredentials(String clientId, String secret, long now);

    // —— vaultkernel 凭据掩码形状只读联动（ET8）——
    String credentialShape(String clientId);

    static OidcPort inMemory(String signingSecret, long codeTtl, long tokenTtl, long refreshTtl,
            long deviceInterval) {
        return new OidcHub(signingSecret, codeTtl, tokenTtl, refreshTtl, deviceInterval);
    }
}
