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
     * 认证入口（带来源 IP，工单 0045）：在双模认证基础上叠加密钥 IP/CIDR 白名单校验。
     * clientIp 为 null 时视为取不到来源（配了白名单即拒绝，fail-closed）。
     * 默认委托两参版本，保证既有实现/测试桩零改动。
     */
    default GovernancePrincipal authenticate(String gatewayId, String credential, String clientIp) {
        return authenticate(gatewayId, credential);
    }

    /**
     * 全局流量面认证（工单 0063：/v1 与 A2A 面）——不经网关强校验与网关授权，
     * 仅做凭证有效性（vk 状态/过期/宽限/IP 白名单）与 JWT 解析；
     * 治理（CEL/配额/预算）由调用方按 traffic 面标签施加。
     * 默认委托实现，保证既有实现零改动。
     */
    default GovernancePrincipal authenticateGlobal(String credential, String clientIp) {
        return authenticate("GLOBAL", credential, clientIp);
    }

    /**
     * 主体复核（case 层节点链防御性复用，缓存命中，幂等）。
     */
    GovernancePrincipal validatePrincipal(String gatewayId, GovernancePrincipal principal);
}
