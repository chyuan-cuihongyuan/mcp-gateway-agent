package cn.chyuan.ai.domain.session.service.message.handler.impl;

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

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {
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
                throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(),
                        "工具未找到: " + toolName);
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
            // 业务异常返回标准 MCP 错误
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION,
                    message.id(),
                    null,
                    new McpSchemaVO.JSONRPCResponse.JSONRPCError(McpErrorCodes.INVALID_PARAMS, e.getMessage(), null));
        } catch (Exception e) {
            log.error("工具调用异常: gatewayId={}", gatewayId, e);
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION,
                    message.id(),
                    null,
                    new McpSchemaVO.JSONRPCResponse.JSONRPCError(McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage(), null));
        }
    }

}
