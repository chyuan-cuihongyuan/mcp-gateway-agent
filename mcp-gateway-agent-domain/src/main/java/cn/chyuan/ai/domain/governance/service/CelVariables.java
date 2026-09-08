package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CEL 求值变量工厂（工单 0048 抽取共享）
 *
 * <p>运行期求值（CelEvaluationService）与在线试跑（dry-run）共用同一绑定逻辑，
 * 保证 playground 试出的行为与线上一致。变量字典见 docs/03-mcp-gateway-agent/15。
 *
 * <p>缺省语义（缺主体/缺值不抛错，与线上 fail-closed 求值契约兼容）：
 * 字符串缺省 ""，列表缺省 []，数字缺省 -1（未知）。
 *
 * @author chyuan
 */
public final class CelVariables {

    private CelVariables() {
    }

    /** 绑定全部顶层变量：auth / key / mcp / jwt / client（0048 扩展后两个） */
    public static Map<String, Object> of(GovernancePrincipal principal, String gatewayId,
            String method, String toolName, String toolSource) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("auth", Map.of(
                "key_id", principal != null && principal.getVirtualKeyId() != null
                        ? principal.getVirtualKeyId() : 0L,
                "owner_user_id", orEmpty(principal == null ? null : principal.getOwnerUserId()),
                "tenant_id", orEmpty(principal == null ? null : principal.getTenantId()),
                "roles", principal != null && principal.getRoles() != null
                        ? principal.getRoles() : List.of(),
                "auth_type", principal == null || principal.getAuthType() == null
                        ? "ANONYMOUS" : principal.getAuthType().name()));
        variables.put("key", Map.of("quota", Map.of(
                "rpm_limit", principal != null && principal.getRpmLimit() != null
                        ? (long) principal.getRpmLimit() : -1L,
                "daily_request_remaining", -1L,
                "daily_tool_call_remaining", -1L)));
        variables.put("mcp", Map.of(
                "gateway", Map.of("id", orEmpty(gatewayId)),
                "method", orEmpty(method),
                "tool", Map.of(
                        "name", orEmpty(toolName),
                        "source", orEmpty(toolSource),
                        "target", targetOf(toolName, toolSource))));
        // 0048 身份变量：JWT 主体携带 sub/roles；vk/匿名主体为空形（不抛错）
        variables.put("jwt", Map.of(
                "sub", orEmpty(principal == null ? null : principal.getOwnerUserId()),
                "roles", principal != null && principal.getRoles() != null
                        ? principal.getRoles() : List.of()));
        // 0048 来源变量：认证过滤器提取的客户端 IP
        variables.put("client", Map.of(
                "ip", orEmpty(principal == null ? null : principal.getClientIp())));
        return variables;
    }

    /**
     * mcp.tool.target：工具来源渠道标识。
     * EXTERNAL → 挂接渠道名（前缀约定 {@code attachName_tool} 取首个下划线前段；
     * 挂接名含下划线时取到最短前缀，属已知边界）；LOCAL/PROTOCOL → 来源本身；其余 LOCAL。
     */
    static String targetOf(String toolName, String toolSource) {
        if (toolSource == null || toolSource.isBlank()) {
            return "LOCAL";
        }
        if (CelEvaluationService.TOOL_SOURCE_EXTERNAL.equals(toolSource)
                && toolName != null && toolName.indexOf('_') > 0) {
            return toolName.substring(0, toolName.indexOf('_'));
        }
        return toolSource;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
