package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.chyuan.ai.domain.auth.model.valobj.enums.AuthStatusEnum;
import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.cache.TtlCache;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import cn.chyuan.ai.types.util.IpCidrUtil;
import cn.chyuan.ai.types.util.KeyHashUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 统一认证服务（工单 0017 / 0011 决议）
 *
 * <p>JWT / vk- 双模认证 + 网关授权校验；元数据 30s TTL 缓存（0011 决策②）。
 * 网关鉴权模式沿用 {@link IAuthRepository#queryGatewayAuthStatus}（NOT_VERIFIED 即开放网关）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class GovernanceAuthService implements IGovernanceAuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    @Resource
    private IAuthRepository authRepository;

    @Resource
    private IVirtualKeyRepository virtualKeyRepository;

    @Resource
    private IJwtCodec jwtCodec;

    /** 元数据缓存 TTL（秒），0011 决策②默认 30s */
    @Value("${governance.cache.ttl-seconds:30}")
    private long cacheTtlSeconds;

    /** 网关鉴权模式缓存（含开放网关判定） */
    private TtlCache<Boolean> gatewayEnforcingCache;

    /** 凭证哈希 → 有效密钥（null 不缓存，用哨兵表达） */
    private TtlCache<VirtualKeyVO> keyCache;

    @jakarta.annotation.PostConstruct
    public void init() {
        gatewayEnforcingCache = new TtlCache<>(cacheTtlSeconds * 1000);
        keyCache = new TtlCache<>(cacheTtlSeconds * 1000);
    }

    @Override
    public GovernancePrincipal authenticate(String gatewayId, String credential) {
        return authenticate(gatewayId, credential, null);
    }

    @Override
    public GovernancePrincipal authenticate(String gatewayId, String credential, String clientIp) {
        try {
            return doAuthenticate(gatewayId, credential, clientIp);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            // fail-closed：认证依赖（库/缓存）不可用时按拒绝处理，不放行也不裸抛 500
            log.error("治理面认证内部错误 gateway:{}", gatewayId, e);
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "认证服务暂不可用，请稍后重试");
        }
    }

    private GovernancePrincipal doAuthenticate(String gatewayId, String credential, String clientIp) {
        if (!isGatewayEnforcing(gatewayId)) {
            return GovernancePrincipal.anonymous();
        }

        if (credential == null || credential.isBlank()) {
            throw new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少调用凭证：请提供 Bearer JWT 或 api_key");
        }

        // Bearer JWT 模式
        String trimmed = credential.trim();
        if (trimmed.startsWith(BEARER_PREFIX)) {
            return authenticateJwt(trimmed.substring(BEARER_PREFIX.length()));
        }

        // 虚拟密钥模式（vk- 或迁移后的 gw-）
        return authenticateVirtualKey(gatewayId, trimmed, clientIp);
    }

    @Override
    public GovernancePrincipal validatePrincipal(String gatewayId, GovernancePrincipal principal) {
        if (principal == null) {
            throw new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少认证主体");
        }
        if (GovernancePrincipal.AuthType.ANONYMOUS.equals(principal.getAuthType())) {
            return principal;
        }
        if (GovernancePrincipal.AuthType.JWT.equals(principal.getAuthType())) {
            return principal;
        }
        // VIRTUAL_KEY：复核状态/过期/授权（缓存命中，幂等；IP 白名单只在认证入口判一次）
        try {
            VirtualKeyVO vo = loadVirtualKey(principal.getApiKeyHash());
            checkUsable(gatewayId, principal.getApiKeyHash(), vo, null, false);
            return principal;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("治理面主体复核内部错误 gateway:{}", gatewayId, e);
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "认证服务暂不可用，请稍后重试");
        }
    }

    private GovernancePrincipal authenticateJwt(String token) {
        IJwtCodec.JwtClaims claims = jwtCodec.verify(token);
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.JWT)
                .ownerUserId(claims.subject())
                .roles(claims.roles())
                .rpmLimit(-1)
                .dailyRequestLimit(-1)
                .dailyToolCallLimit(-1)
                .build();
    }

    private GovernancePrincipal authenticateVirtualKey(String gatewayId, String credential, String clientIp) {
        String hash = KeyHashUtil.sha256Hex(credential);
        VirtualKeyVO vo = loadVirtualKey(hash);
        checkUsable(gatewayId, hash, vo, clientIp, true);
        touchLastActiveDebounced(vo);

        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(vo.getId())
                .apiKeyHash(hash)
                .ownerUserId(vo.getOwnerUserId())
                .tenantId(vo.getTenantId())
                .roles(List.of())
                .rpmLimit(vo.getRpmLimit())
                .dailyRequestLimit(vo.getDailyRequestLimit())
                .dailyToolCallLimit(vo.getDailyToolCallLimit())
                .build();
    }

    private void checkUsable(String gatewayId, String hash, VirtualKeyVO vo, String clientIp, boolean enforceIp) {
        if (vo == null) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "凭证无效或已吊销");
        }
        if (!"ACTIVE".equals(vo.getStatus())) {
            // 四态语义（工单 0045）：禁用/吊销与无效凭证区分错误码
            throw new AppException(McpErrorCodes.KEY_DISABLED, "凭证已禁用或吊销：" + vo.getStatus());
        }
        if (vo.getExpiresAt() != null && new Date().after(vo.getExpiresAt())) {
            throw new AppException(McpErrorCodes.KEY_EXPIRED, "凭证已过期，请联系管理员续期或轮换");
        }
        if (enforceIp && !IpCidrUtil.allows(vo.getIpAllowList(), clientIp)) {
            throw new AppException(McpErrorCodes.IP_NOT_ALLOWED,
                    "来源 IP 不在密钥白名单内" + (clientIp == null ? "（未取到来源 IP）" : "：" + clientIp));
        }
        if (!virtualKeyRepository.existsGrant(vo.getId(), gatewayId)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "凭证未授权访问该网关");
        }
    }

    /** last_active_at 去抖（>60s 才写库），失败不影响认证主链（工单 0045） */
    private void touchLastActiveDebounced(VirtualKeyVO vo) {
        if (vo == null || vo.getId() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastTouchMs.put(vo.getId(), now);
        if (last == null || now - last >= LAST_ACTIVE_DEBOUNCE_MS) {
            try {
                virtualKeyRepository.touchLastActive(vo.getId());
            } catch (Exception e) {
                log.warn("last_active_at 更新失败 keyId:{}：{}", vo.getId(), e.getMessage());
            }
        }
    }

    /** keyId → 上次触达毫秒（去抖窗口） */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastTouchMs = new java.util.concurrent.ConcurrentHashMap<>();

    /** 去抖窗口：60 秒 */
    private static final long LAST_ACTIVE_DEBOUNCE_MS = 60_000L;

    /**
     * 凭证哈希查询（30s TTL 缓存；null 结果用哨兵缓存避免穿透）
     */
    private VirtualKeyVO loadVirtualKey(String hash) {
        VirtualKeyVO vo = keyCache.get(hash, () -> {
            VirtualKeyVO found = virtualKeyRepository.findByHash(hash);
            return found == null ? NOT_FOUND : found;
        });
        return NOT_FOUND.equals(vo) ? null : vo;
    }

    private boolean isGatewayEnforcing(String gatewayId) {
        Boolean enforcing = gatewayEnforcingCache.get("gw:" + gatewayId, () -> {
            try {
                AuthStatusEnum.GatewayConfig status = authRepository.queryGatewayAuthStatus(gatewayId);
                return !AuthStatusEnum.GatewayConfig.NOT_VERIFIED.equals(status);
            } catch (Exception e) {
                // 网关不存在或查询不可用：视为需要认证（fail-closed，交由后续校验给出明确错误）
                log.warn("网关鉴权模式查询失败（按强校验处理）gateway:{}: {}", gatewayId, e.getMessage());
                return true;
            }
        });
        return Boolean.TRUE.equals(enforcing);
    }

    /** 凭证未命中哨兵（缓存 null 结果） */
    private static final VirtualKeyVO NOT_FOUND = VirtualKeyVO.builder().id(-1L).status("NOT_FOUND").build();

    /** 写路径即时失效（管理端变更密钥后调用） */
    public void invalidateKey(String apiKeyHash) {
        if (apiKeyHash != null) {
            keyCache.invalidate(apiKeyHash);
        }
    }

    public void invalidateAll() {
        keyCache.invalidateAll();
        gatewayEnforcingCache.invalidateAll();
    }
}
