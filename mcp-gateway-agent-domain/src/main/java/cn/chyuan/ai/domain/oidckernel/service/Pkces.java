package cn.chyuan.ai.domain.oidckernel.service;

/**
 * PKCE（工单 1099 ET2，keycloak 思想）。
 * S256 challenge 派生（BASE64URL(SHA-256(verifier))）/verifier 长度 43-128/
 * 错误 verifier 拒绝/缺 challenge 或 verifier 拒绝。
 */
public final class Pkces {

    private Pkces() {
    }

    /** 由 verifier 派生 S256 challenge */
    public static String challenge(String verifier) {
        requireVerifier(verifier);
        return Hmacs.sha256Base64Url(verifier);
    }

    /** 校验：无 challenge 绑定拒绝/verifier 长度越界拒绝/派生不匹配拒绝 */
    public static void validate(String verifier, String challenge) {
        if (challenge == null || challenge.isBlank()) {
            throw new IllegalStateException("缺 PKCE challenge 拒绝");
        }
        requireVerifier(verifier);
        if (!challenge(verifier).equals(challenge)) {
            throw new IllegalStateException("PKCE verifier 不匹配拒绝");
        }
    }

    private static void requireVerifier(String verifier) {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) {
            throw new IllegalArgumentException("verifier 长度必须 43-128");
        }
    }
}
