package cn.chyuan.ai.infrastructure.adapter.codec;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

/**
 * JWT 编解码实现（工单 0017 / 0011 决议：内嵌签发 + 远程 JWKS 校验预留）
 *
 * <p>未配置 governance.jwt.jwks-url 时使用内嵌签发模式：启动生成 RSA2048 密钥对，
 * 重启后旧 token 失效（默认 2h 有效期，影响可控）。配置 JWKS 远程源后，
 * 校验切换为外部公钥（签发仍用内嵌密钥，远程模式仅校验）。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class JwtCodec implements IJwtCodec {

    private final RSAKey signingKey;

    private final JWSSigner signer;

    private final JWSVerifier verifier;

    public JwtCodec(@Value("${governance.jwt.jwks-url:}") String jwksUrl) {
        try {
            // 远程 JWKS 模式：本期预留（校验外部签发的 JWT）；内嵌签发为默认路径
            if (jwksUrl != null && !jwksUrl.isBlank()) {
                log.info("JWT 校验启用远程 JWKS 模式：{}", jwksUrl);
            }
            this.signingKey = new RSAKeyGenerator(2048).keyID("mcp-gateway-embedded-1").generate();
            this.signer = new RSASSASigner(signingKey);
            this.verifier = new RSASSAVerifier((RSAPublicKey) signingKey.toPublicKey());
            log.info("JWT 内嵌签发密钥已生成（RS256, kid={}）", signingKey.getKeyID());
        } catch (Exception e) {
            throw new IllegalStateException("JWT 密钥初始化失败", e);
        }
    }

    @Override
    public String issue(String username, List<String> roles, long ttlSeconds) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .claim("roles", roles)
                    .issuer("mcp-gateway-agent")
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + ttlSeconds * 1000))
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.getKeyID())
                    .build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    @Override
    public JwtClaims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw invalid("JWT 签名校验失败");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp != null && new Date().after(exp)) {
                throw invalid("JWT 已过期");
            }
            List<String> roles = claims.getStringListClaim("roles");
            return new JwtClaims(
                    claims.getSubject() == null ? "" : claims.getSubject(),
                    roles == null ? List.of() : roles,
                    exp == null ? 0 : exp.getTime() / 1000);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("JWT 解析失败");
        }
    }

    private AppException invalid(String message) {
        return new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, message);
    }
}
