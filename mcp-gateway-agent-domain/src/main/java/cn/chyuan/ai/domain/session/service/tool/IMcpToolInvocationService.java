package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

import java.util.Map;

/**
 * MCP 工具调用服务（工单 0020）
 *
 * <p>承接原 ToolsCallHandler 的调用链（必填校验 → CEL 拦截 → 协议映射 HTTP 调用），
 * 供官方 McpSyncServer 工具 wrapper 调用。业务失败以 {@code AppException} 携带
 * JSON-RPC 数值码抛出（-32003 不存在 / -32006 无权限），由调用方转协议错误回包。
 *
 * @author chyuan
 */
public interface IMcpToolInvocationService {

    /**
     * 执行工具调用
     *
     * @param gatewayId 网关 ID
     * @param toolName  工具名
     * @param arguments 调用参数
     * @param principal 治理主体（CEL 求值输入；为空时不过滤，与遗留口径一致）
     * @return 下游返回的载荷（文本内容透传）
     */
    Object invoke(String gatewayId, String toolName, Map<String, Object> arguments, GovernancePrincipal principal);

}
