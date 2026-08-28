package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.CelEvaluationService;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * MCP 工具调用服务（工单 0020 / 0021 外部挂接透传）
 *
 * <p>协议映射调用链承接原 ToolsCallHandler：参数校验 → 协议配置查询 → CEL 治理拦截
 * （tools/call 生效点，-32006 无权限 / -32003 不存在可区分）→ 必填校验 →
 * 协议映射 HTTP 调用。
 *
 * <p>0021：协议配置未命中且工具来自外部挂接时，CEL 以 tool.source=EXTERNAL
 * 求值后透传上游（上游 isError 结果按 -32004 结构化拒绝）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class McpToolInvocationService implements IMcpToolInvocationService {

    @Resource
    private ISessionRepository repository;

    @Resource
    private ISessionPort port;

    @Resource
    private ICelEvaluationService celEvaluationService;

    /** 外部挂接端口（可选注入：单测/切片可缺省，仅协议映射链生效） */
    @Autowired(required = false)
    private IExternalMcpAttachPort externalMcpAttachPort;

    @Override
    public Object invoke(String gatewayId, String toolName, Map<String, Object> arguments,
            GovernancePrincipal principal) {
        log.info("工具调用请求: gatewayId={}, toolName={}, arguments={}", gatewayId, toolName, arguments);

        // 参数校验
        if (arguments == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工具调用参数不能为空");
        }

        // 查询协议信息
        McpToolProtocolConfigVO mcpToolProtocolConfigVO = repository.queryMcpGatewayProtocolConfig(gatewayId, toolName);
        if (null == mcpToolProtocolConfigVO) {
            // 0021：协议映射未命中 → 外部挂接透传路由
            return invokeExternal(gatewayId, toolName, arguments, principal);
        }

        // CEL 治理拦截（工单 0018：无权限与工具不存在以错误码区分）
        if (!celEvaluationService.isToolAllowed(principal, gatewayId, "tools/call", toolName,
                CelEvaluationService.TOOL_SOURCE_PROTOCOL)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS,
                    "无权限：工具被治理规则拒绝调用: " + toolName);
        }

        // 参数校验：检查必填参数是否存在
        if (mcpToolProtocolConfigVO.getRequestProtocolMappings() != null) {
            for (McpToolProtocolConfigVO.ProtocolMapping mapping : mcpToolProtocolConfigVO.getRequestProtocolMappings()) {
                if (Integer.valueOf(1).equals(mapping.getIsRequired())) {
                    Object value = arguments.get(mapping.getFieldName());
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        log.warn("必填参数缺失: toolName={}, field={}, desc={}", toolName, mapping.getFieldName(),
                                mapping.getMcpDesc());
                        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                                "缺少必填参数: " + mapping.getMcpDesc() + "(" + mapping.getFieldName() + ")");
                    }
                }
            }
        }

        // 调用接口
        try {
            return port.toolCall(mcpToolProtocolConfigVO.getHttpConfig(), arguments);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("工具调用异常: gatewayId={}, toolName={}", gatewayId, toolName, e);
            throw new AppException(McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage());
        }
    }

    /** 外部挂接透传：-32003 不属于任何挂接 / -32006 CEL 拒绝 / -32004 上游执行失败 */
    private Object invokeExternal(String gatewayId, String toolName, Map<String, Object> arguments,
            GovernancePrincipal principal) {
        if (externalMcpAttachPort == null || !externalMcpAttachPort.isExternalTool(gatewayId, toolName)) {
            throw new AppException(McpErrorCodes.TOOL_NOT_FOUND, "工具未找到: " + toolName);
        }
        if (!celEvaluationService.isToolAllowed(principal, gatewayId, "tools/call", toolName,
                CelEvaluationService.TOOL_SOURCE_EXTERNAL)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS,
                    "无权限：工具被治理规则拒绝调用: " + toolName);
        }
        try {
            IExternalMcpAttachPort.ExternalCallResult result =
                    externalMcpAttachPort.callExternalTool(gatewayId, toolName, arguments);
            if (result.toolError()) {
                log.warn("外部挂接上游工具执行失败: gatewayId={}, toolName={}, reason={}",
                        gatewayId, toolName, result.payload());
                throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED,
                        "上游工具执行失败: " + result.payload());
            }
            return result.payload();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("外部挂接工具调用异常: gatewayId={}, toolName={}", gatewayId, toolName, e);
            throw new AppException(McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage());
        }
    }

}
