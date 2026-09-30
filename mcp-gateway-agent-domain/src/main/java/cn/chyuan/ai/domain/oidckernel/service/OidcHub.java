package cn.chyuan.ai.domain.oidckernel.service;

/**
 * 令牌流编排实现（工单 1105 ET8，keycloak 思想）。
 * authorize 登记 PKCE challenge 的授权码；token 校验 PKCE 后颁发访问+refresh 对；
 * refresh 轮换换新对并检测重用；device flow 三态轮询；exchange 收窄换发。
 */
public final class OidcHub implements OidcPort {

    private final String signingSecret;
    private final long codeTtl;
    private final long tokenTtl;
    private final long refreshTtl;
    private final long deviceInterval;
    private final AuthCodes codes = new AuthCodes();
    private final RefreshRotations rotations = new RefreshRotations();
    private final DeviceFlows devices = new DeviceFlows();
    private final ClientCredentials clients = new ClientCredentials();

    public OidcHub(String signingSecret, long codeTtl, long tokenTtl, long refreshTtl,
            long deviceInterval) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("签名密钥不能为空");
        }
        this.signingSecret = signingSecret;
        this.codeTtl = codeTtl;
        this.tokenTtl = tokenTtl;
        this.refreshTtl = refreshTtl;
        this.deviceInterval = deviceInterval;
    }

    @Override
    public String authorize(String clientId, String redirectUri, String scope, String codeVerifier,
            long now) {
        String challenge = codeVerifier == null || codeVerifier.isBlank()
                ? null
                : Pkces.challenge(codeVerifier);
        return codes.issue(clientId, redirectUri, scope, challenge, codeTtl, now);
    }

    @Override
    public String token(String code, String clientId, String redirectUri, String verifier, long now) {
        String scope = codes.exchange(code, clientId, redirectUri, now);
        String challenge = codes.challengeOf(code);
        if (challenge != null) {
            Pkces.validate(verifier, challenge);
        }
        String accessToken = AccessTokens.issue(signingSecret, clientId, scope, clientId, tokenTtl, now);
        String refreshToken = rotations.create(clientId, scope, refreshTtl, now);
        return accessToken + "|" + refreshToken;
    }

    @Override
    public String refresh(String refreshToken, long now) {
        String rotated = rotations.rotate(refreshToken, refreshTtl, now);
        String subject = rotations.subjectOf(rotated);
        String scope = rotations.scopeOf(rotated);
        String accessToken = AccessTokens.issue(signingSecret, subject, scope, subject, tokenTtl, now);
        return accessToken + "|" + rotated;
    }

    @Override
    public String introspect(String accessToken, long now) {
        AccessTokens.View view = AccessTokens.verify(signingSecret, accessToken, now);
        return view.subject() + "|" + view.scope() + "|" + view.audience();
    }

    @Override
    public String deviceBegin() {
        return devices.begin(deviceInterval);
    }

    @Override
    public String devicePoll(String deviceCode, long now) {
        return devices.poll(deviceCode, now);
    }

    @Override
    public void deviceConfirm(String userCode, String subject) {
        devices.confirm(userCode, subject, "openid");
    }

    @Override
    public String exchange(String accessToken, String audience, String scope, long now) {
        AccessTokens.View source = AccessTokens.verify(signingSecret, accessToken, now);
        AccessTokens.View exchanged = TokenExchanges.exchange(source, audience, scope);
        return AccessTokens.issue(signingSecret, exchanged.subject(), exchanged.scope(),
                exchanged.audience(), tokenTtl, now);
    }

    @Override
    public void registerClient(String clientId, String secret) {
        clients.register(clientId, secret);
    }

    @Override
    public String clientCredentials(String clientId, String secret, long now) {
        String subject = clients.grant(clientId, secret);
        return AccessTokens.issue(signingSecret, subject, "client", subject, tokenTtl, now);
    }

    @Override
    public String credentialShape(String clientId) {
        String secret = clientId.length() <= 4 ? clientId : clientId.substring(clientId.length() - 4);
        return "vault:ak-" + "*".repeat(Math.max(0, clientId.length() - 4)) + secret
                + "/sk-" + signingSecret.length() + "B";
    }
}
