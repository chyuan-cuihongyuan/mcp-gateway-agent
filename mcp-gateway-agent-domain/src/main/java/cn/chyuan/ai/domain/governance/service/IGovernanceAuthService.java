package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

/**
 * 统一认证服务（工单 0017 / 0011 认证流程）
 *
 * @author chyuan
 */
public interface IGovernanceAuthService {

    /**
     * 认证入口（过滤器调用）：
     * <ol>
     *   <li>网关未开启强校验 → 匿名主体放行；</li>
     *   <li>强校验 + 无凭证 → AppException(AUTH_REQUIRED / HTTP 401)；</li>
     *   <li>Bearer JWT → 校验 → JWT 主体；</li>
     *   <li>其余凭证 → SHA-256 哈希查虚拟密钥 → 状态/过期/网关授权校验，
     *       失败 AppException(INSUFFICIENT_PERMISSIONS / HTTP 403)。</li>
     * </ol>
     */
    GovernancePrincipal authenticate(String gatewayId, String credential);

    /**
     * 主体复核（case 层节点链防御性复用，缓存命中，幂等）。
     */
    GovernancePrincipal validatePrincipal(String gatewayId, GovernancePrincipal principal);
}
