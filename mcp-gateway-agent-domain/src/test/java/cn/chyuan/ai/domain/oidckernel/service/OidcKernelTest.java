package cn.chyuan.ai.domain.oidckernel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 令牌流内核测试（工单 1098-1105 ET1-ET8，keycloak 思想）。
 * 授权码/PKCE/refresh 轮换/access 校验/device flow/token exchange/client 凭据/端口组合管线。
 */
class OidcKernelTest {

    @Test
    void authorizationCodes() {
        AuthCodes codes = new AuthCodes();
        String code = codes.issue("app", "https://cb", "read", null, 10, 0);
        assertEquals("read", codes.exchange(code, "app", "https://cb", 5));
        assertThrows(IllegalStateException.class,
                () -> codes.exchange(code, "app", "https://cb", 6), "授权码一次性重复消费拒绝");
        String expired = codes.issue("app", "https://cb", "read", null, 10, 0);
        assertThrows(IllegalStateException.class,
                () -> codes.exchange(expired, "app", "https://cb", 11), "过期拒绝");
        String mismatch = codes.issue("app", "https://cb", "read", null, 10, 0);
        assertThrows(IllegalStateException.class,
                () -> codes.exchange(mismatch, "app", "https://other", 5), "redirect 失配拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> codes.exchange("nope", "app", "https://cb", 0));
        assertThrows(IllegalArgumentException.class,
                () -> codes.issue(" ", "https://cb", "read", null, 10, 0));
    }

    @Test
    void pkceValidation() {
        String verifier = "v".repeat(43);
        String challenge = Pkces.challenge(verifier);
        assertEquals(challenge, Pkces.challenge(verifier), "S256 派生确定性");
        assertDoesNotThrow(() -> Pkces.validate(verifier, challenge));
        assertThrows(IllegalStateException.class, () -> Pkces.validate("w".repeat(43), challenge),
                "错误 verifier 拒绝");
        assertThrows(IllegalStateException.class, () -> Pkces.validate(verifier, null),
                "缺 challenge 拒绝");
        assertThrows(IllegalArgumentException.class, () -> Pkces.challenge("short"), "长度下界拒绝");
        assertThrows(IllegalArgumentException.class, () -> Pkces.challenge("x".repeat(129)),
                "长度上界拒绝");
    }

    @Test
    void refreshRotation() {
        RefreshRotations rotations = new RefreshRotations();
        String first = rotations.create("user", "read write", 100, 0);
        assertEquals("user", rotations.subjectOf(first));
        String second = rotations.rotate(first, 100, 10);
        assertNotEquals(first, second, "刷新即换新");
        assertEquals("read write", rotations.scopeOf(second));
        String third = rotations.rotate(second, 100, 20);
        assertThrows(IllegalStateException.class, () -> rotations.rotate(first, 100, 30),
                "旧 refresh 重用检测撤销整族");
        assertTrue(rotations.familyRevoked(third), "族内后续令牌全失效");
        assertThrows(IllegalStateException.class, () -> rotations.rotate(third, 100, 40));
        RefreshRotations fresh = new RefreshRotations();
        String refresh = fresh.create("u", "s", 10, 0);
        assertThrows(IllegalStateException.class, () -> fresh.rotate(refresh, 10, 11), "过期拒绝");
        assertThrows(IllegalArgumentException.class, () -> fresh.rotate("nope", 10, 0));
    }

    @Test
    void accessTokens() {
        String token = AccessTokens.issue("secret", "user", "read", "api", 100, 0);
        assertTrue(token.startsWith("AT."));
        AccessTokens.View view = AccessTokens.verify("secret", token, 50);
        assertEquals("user", view.subject());
        assertEquals("read", view.scope());
        assertEquals("api", view.audience());
        assertThrows(IllegalStateException.class, () -> AccessTokens.verify("secret", token, 101),
                "过期拒绝");
        assertThrows(IllegalStateException.class, () -> AccessTokens.verify("other", token, 50),
                "签名密钥不符拒绝");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(IllegalStateException.class, () -> AccessTokens.verify("secret", tampered, 50),
                "篡改拒绝");
        assertThrows(IllegalArgumentException.class, () -> AccessTokens.verify("secret", "BAD", 0));
        assertThrows(IllegalArgumentException.class,
                () -> AccessTokens.issue("secret", " ", "r", "a", 10, 0));
    }

    @Test
    void deviceFlowStates() {
        DeviceFlows devices = new DeviceFlows();
        String[] codes = devices.begin(5).split("\\|");
        assertEquals(2, codes.length);
        assertEquals("PENDING", devices.poll(codes[0], 0));
        assertEquals("SLOW_DOWN", devices.poll(codes[0], 3), "快于间隔节流");
        assertEquals("PENDING", devices.poll(codes[0], 8), "间隔满足恢复 pending");
        devices.confirm(codes[1], "user-9", "openid");
        assertTrue(devices.confirmed(codes[1]));
        assertTrue(devices.poll(codes[0], 13).startsWith("TOKEN:user-9|"), "确认后互换令牌");
        assertThrows(IllegalArgumentException.class, () -> devices.poll("DC-999", 0));
        assertThrows(IllegalArgumentException.class, () -> devices.confirm("UC-9999", "u", "s"));
        assertThrows(IllegalArgumentException.class, () -> devices.begin(0));
    }

    @Test
    void tokenExchangeNarrowing() {
        AccessTokens.View source = new AccessTokens.View("user", "read write", "api-a", 100);
        AccessTokens.View exchanged = TokenExchanges.exchange(source, "api-b", "read");
        assertEquals("user", exchanged.subject(), "主体保持");
        assertEquals("api-b", exchanged.audience(), "audience 切换");
        assertEquals("read", exchanged.scope(), "scope 收窄");
        assertThrows(IllegalStateException.class,
                () -> TokenExchanges.exchange(source, "api-b", "read admin"), "扩大拒绝");
        assertEquals("read write", TokenExchanges.exchange(source, "api-c", null).scope(),
                "缺省请求取全量");
        assertThrows(IllegalArgumentException.class,
                () -> TokenExchanges.exchange(source, " ", "read"));
    }

    @Test
    void clientCredentialsGrant() {
        ClientCredentials clients = new ClientCredentials();
        clients.register("svc", "s3cret");
        assertEquals("svc", clients.grant("svc", "s3cret"), "令牌含 client 主体");
        assertThrows(IllegalStateException.class, () -> clients.grant("svc", "wrong"),
                "错误 secret 拒绝");
        assertThrows(IllegalArgumentException.class, () -> clients.grant("ghost", "s3cret"));
        assertThrows(IllegalArgumentException.class, () -> clients.register(" ", "x"));
        assertTrue(clients.registered("svc"));
    }

    @Test
    void oidcPortPipeline() {
        OidcPort port = OidcPort.inMemory("hub-secret", 10, 100, 200, 5);
        String verifier = "v".repeat(43);
        String code = port.authorize("app", "https://cb", "read write", verifier, 0);
        assertTrue(code.startsWith("AC-"));
        String[] pair = port.token(code, "app", "https://cb", verifier, 1).split("\\|");
        assertEquals(2, pair.length);
        assertEquals("app|read write|app", port.introspect(pair[0], 2), "访问令牌校验");

        String badVerifierCode = port.authorize("app", "https://cb", "read", verifier, 0);
        assertThrows(IllegalStateException.class,
                () -> port.token(badVerifierCode, "app", "https://cb", "w".repeat(43), 1),
                "PKCE 不匹配拒绝");
        assertThrows(IllegalStateException.class,
                () -> port.token(code, "app", "https://cb", verifier, 2), "授权码重复消费拒绝");

        String thirdCode = port.authorize("app", "https://cb", "read", verifier, 0);
        String[] thirdPair = port.token(thirdCode, "app", "https://cb", verifier, 1).split("\\|");
        String rotated = port.refresh(thirdPair[1], 5);
        assertNotEquals(thirdPair[1], rotated.split("\\|")[1], "刷新即换新对");
        assertThrows(IllegalStateException.class, () -> port.refresh(thirdPair[1], 6),
                "旧 refresh 重用撤销族");

        String[] device = port.deviceBegin().split("\\|");
        assertEquals("PENDING", port.devicePoll(device[0], 0));
        port.deviceConfirm(device[1], "device-user");
        assertTrue(port.devicePoll(device[0], 5).startsWith("TOKEN:device-user"));

        String exchanged = port.exchange(pair[0], "api-b", "read", 3);
        assertEquals("app|read|api-b", port.introspect(exchanged, 4), "交换收窄换发");
        assertThrows(IllegalStateException.class, () -> port.exchange(pair[0], "api-c", "admin", 4),
                "scope 扩大拒绝");

        port.registerClient("svc", "sec");
        String clientToken = port.clientCredentials("svc", "sec", 0);
        assertEquals("svc|client|svc", port.introspect(clientToken, 1));
        assertThrows(IllegalStateException.class, () -> port.clientCredentials("svc", "bad", 1));
        String reissued = port.clientCredentials("svc", "sec", 1);
        assertDoesNotThrow(() -> port.introspect(reissued, 2), "过期后重新获取");

        assertEquals("vault:ak-app/sk-10B", port.credentialShape("app"),
                "vaultkernel 凭据掩码形状只读联动");
        assertThrows(IllegalStateException.class, () -> port.introspect(pair[0], 102), "过期拒绝");
    }
}
