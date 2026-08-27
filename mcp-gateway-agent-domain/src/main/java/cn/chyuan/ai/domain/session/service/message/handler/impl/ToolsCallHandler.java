package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.CelEvaluationService;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.service.message.handler.IRequestHandler;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 执行指定的工具调用
 *
 * <p>工单 0018：tools/call 生效点——CEL 治理规则不放行的调用返回结构化拒绝，
 * 错误码 -32006（无权限）与 -32003（工具不存在）可区分。
 *
 * @author chyuan
 *         2025/12/20 11:30
 */
@Slf4j
@Service("toolsCallHandler")
public class ToolsCallHandler implements IRequestHandler {

    @Resource
    private ISessionRepository repository;

    @Resource
    private ISessionPort port;

    @Resource
    private ICelEvaluationService celEvaluationService;

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message,
            GovernancePrincipal principal) {
        try {
            // 1. 转换参数
            McpSchemaVO.CallToolRequest callToolRequest = McpSchemaVO.unmarshalFrom(message.params(),
                    new TypeReference<>() {
                    });

            Object argumentsObj = callToolRequest.arguments();
            String toolName = callToolRequest.name();

            log.info("工具调用请求: gatewayId={}, toolName={}, arguments={}", gatewayId, toolName, argumentsObj);

            // 参数校验
            if (argumentsObj == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工具调用参数不能为空");
            }

            // 2. 查询协议信息
            McpToolProtocolConfigVO mcpToolProtocolConfigVO = repository.queryMcpGatewayProtocolConfig(gatewayId,
                    toolName);
            if (null == mcpToolProtocolConfigVO) {
                throw new AppException(McpErrorCodes.TOOL_NOT_FOUND, "工具未找到: " + toolName);
            }

            // 2.5 CEL 治理拦截（工单 0018：无权限与工具不存在以错误码区分）
            if (!celEvaluationService.isToolAllowed(principal, gatewayId, message.method(), toolName,
                    CelEvaluationService.TOOL_SOURCE_PROTOCOL)) {
                throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS,
                        "无权限：工具被治理规则拒绝调用: " + toolName);
            }

            // 参数校验：检查必填参数是否存在
            if (mcpToolProtocolConfigVO.getRequestProtocolMappings() != null) {
                for (McpToolProtocolConfigVO.ProtocolMapping mapping : mcpToolProtocolConfigVO.getRequestProtocolMappings()) {
                    if (Integer.valueOf(1).equals(mapping.getIsRequired()) && argumentsObj instanceof Map<?, ?> args) {
                        Object value = args.get(mapping.getFieldName());
                        if (value == null || (value instanceof String s && s.isBlank())) {
                            log.warn("必填参数缺失: toolName={}, field={}, desc={}", toolName, mapping.getFieldName(), mapping.getMcpDesc());
                            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                                    "缺少必填参数: " + mapping.getMcpDesc() + "(" + mapping.getFieldName() + ")");
                        }
                    }
                }
            }

            // 3. 调用接口
            Object result = port.toolCall(mcpToolProtocolConfigVO.getHttpConfig(), argumentsObj);

            // 返回成功响应 - isError 使用布尔值 false
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION, message.id(), Map.of(
                    "content", new Object[] {
                            Map.of(
                                    "type", "text",
                                    "text", result),
                    },
                    "isError", false), null);

        } catch (AppException e) {
            // 业务异常返回标准 MCP 错误；数值码（McpErrorCodes）原样透传以区分错误类型
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION,
                    message.id(),
                    null,
                    new McpSchemaVO.JSONRPCResponse.JSONRPCError(jsonRpcErrorCode(e), e.getMessage(), null));
        } catch (Exception e) {
            log.error("工具调用异常: gatewayId={}", gatewayId, e);
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION,
                    message.id(),
                    null,
                    new McpSchemaVO.JSONRPCResponse.JSONRPCError(McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage(), null));
        }
    }

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {
        return handle(gatewayId, message, null);
    }

    /** AppException 携带 JSON-RPC 数值码时透传（-32006 无权限 / -32003 不存在），否则按非法参数 */
    private static int jsonRpcErrorCode(AppException e) {
        try {
            return Integer.parseInt(e.getCode());
        } catch (NumberFormatException ignore) {
            return McpErrorCodes.INVALID_PARAMS;
        }
    }

}
