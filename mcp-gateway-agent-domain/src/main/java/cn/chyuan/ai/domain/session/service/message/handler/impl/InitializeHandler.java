package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.service.message.handler.IRequestHandler;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;

/**
 * 协议握手，建立客户端与服务器的连接
 *
 * @author chyuan
 *         2025/12/20 11:28
 */
@Slf4j
@Service("initializeHandler")
public class InitializeHandler implements IRequestHandler {

    @Resource
    private ISessionRepository repository;

    @Value("${mcp.protocol.default-version:" + McpSchemaVO.LATEST_PROTOCOL_VERSION + "}")
    private String defaultProtocolVersion;

    @Value("#{'${mcp.protocol.supported-versions:" + McpSchemaVO.LATEST_PROTOCOL_VERSION + "}'.split(',')}")
    private List<String> supportedProtocolVersions;

    /**
     * 对照 io.modelcontextprotocol.spec.McpServerSession
     * <br/>
     * McpServerSession.handle -> McpSchema.JSONRPCRequest -> handleIncomingRequest
     * -> McpSchema.METHOD_INITIALIZE ->
     * McpAsyncServer.asyncInitializeRequestHandler
     * -> result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
     * request.id(), result, null)
     * <br/>
     * {
     * "id": "a355a5f7-0",
     * "jsonrpc": "2.0",
     * "result": {
     * "capabilities": {
     * "completions": {},
     * "logging": {},
     * "prompts": {
     * "listChanged": true
     * },
     * "resources": {
     * "listChanged": true,
     * "subscribe": false
     * },
     * "tools": {
     * "listChanged": true
     * }
     * },
     * "instructions": "This server provides weather information tools and
     * resources",
     * "protocolVersion": "2024-11-05",
     * "serverInfo": {
     * "name": "ai-mcp-gateway-demo-mcp-server-test",
     * "version": "1.0.0"
     * }
     * }
     * }
     */
    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {
        log.info("消息处理服务-initialize gatewayId:{} request.params:{}", gatewayId, JSON.toJSONString(message.params()));

        // 1. 转换参数
        McpSchemaVO.InitializeRequest initializeRequest = McpSchemaVO.unmarshalFrom(message.params(),
                new TypeReference<>() {
                });

        // 2. 查询配置
        McpGatewayConfigVO mcpGatewayConfigVO = repository.queryMcpGatewayConfigByGatewayId(gatewayId);
        if (mcpGatewayConfigVO == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "网关配置不存在: " + gatewayId);
        }

        String protocolVersion = negotiateProtocolVersion(initializeRequest.protocolVersion());

        // 3. 组装信息
        McpSchemaVO.InitializeResult initializeResult = new McpSchemaVO.InitializeResult(
                protocolVersion,
                new McpSchemaVO.ServerCapabilities(new McpSchemaVO.ServerCapabilities.CompletionCapabilities(),
                        new HashMap<>(),
                        new McpSchemaVO.ServerCapabilities.LoggingCapabilities(),
                        new McpSchemaVO.ServerCapabilities.PromptCapabilities(true),
                        new McpSchemaVO.ServerCapabilities.ResourceCapabilities(false, true),
                        new McpSchemaVO.ServerCapabilities.ToolCapabilities(true)),
                new McpSchemaVO.Implementation(mcpGatewayConfigVO.getGatewayName(), mcpGatewayConfigVO.getVersion()),
                mcpGatewayConfigVO.getGatewayDesc());

        // 4. 返回结果
        return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION, message.id(), initializeResult, null);
    }

    private String negotiateProtocolVersion(String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            boolean supported = supportedProtocolVersions.stream()
                    .map(String::trim)
                    .anyMatch(requestedVersion::equals);
            if (supported) {
                return requestedVersion;
            }
            log.info("客户端 MCP 协议版本不在支持列表内，使用默认版本: requested={}, default={}",
                    requestedVersion, defaultProtocolVersion);
        }
        return defaultProtocolVersion;
    }

}
