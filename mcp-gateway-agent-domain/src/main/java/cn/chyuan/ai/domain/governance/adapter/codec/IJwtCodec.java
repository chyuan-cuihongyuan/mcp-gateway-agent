package cn.chyuan.ai.domain.governance.adapter.codec;

import java.util.List;

/**
 * JWT 编解码端口（工单 0017；infrastructure 以 Nimbus JOSE+JWT 落地）
 *
 * <p>内嵌签发模式：启动时生成 RSA 密钥对（或配置 JWKS 远程源仅校验）。
 *
 * @author chyuan
 */
public interface IJwtCodec {

    /**
     * 签发 JWT（内嵌签发模式）
     *
     * @param username sub claim
     * @param roles    角色 claim
     * @param ttlSeconds 有效期（秒）
     */
    String issue(String username, List<String> roles, long ttlSeconds);

    /**
     * 校验并解析 JWT
     *
     * @return 解析结果；无效/过期抛 AppException(INSUFFICIENT_PERMISSIONS)
     */
    JwtClaims verify(String token);

    /** 解析出的 claims */
    record JwtClaims(String subject, List<String> roles, long expiresAtEpochSeconds) {
    }
}
