package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * M3: StreamableHttp 传输的 MCP 工具构建服务（MCP 协议 2025-03-26 规范）。
 * <p>
 * 与 {@link SSEToolMcpCreateService} 的区别：使用 POST 到单一 MCP 端点（默认 /mcp）的 StreamableHttp 传输，
 * 替代 SSE 的 long-lived 事件流。是 MCP 官方推荐的新传输方式，现代 MCP 服务端（如 Spring AI MCP Server 1.0+）默认提供。
 *
 * @author chyuan
 * @since 2026-06-14
 */
@Slf4j
@Service
public class StreamableHttpToolMcpCreateService implements ToolMcpCreateService {

    private static final String DEFAULT_MCP_ENDPOINT = "/mcp";

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StreamableHttpServerParameters config = toolMcp.getStreamableHttp();

        String baseUri = config.getBaseUri();
        String mcpEndpoint = StringUtils.isBlank(config.getMcpEndpoint()) ? DEFAULT_MCP_ENDPOINT : config.getMcpEndpoint();
        long requestTimeoutMs = config.getRequestTimeout() == null ? 3000L : config.getRequestTimeout();

        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(baseUri)
                .endpoint(mcpEndpoint);

        if (StringUtils.isNotBlank(config.getApiKey())) {
            builder.customizeRequest(request -> {
                request.header("Authorization", "Bearer " + config.getApiKey());
            });
        }

        HttpClientStreamableHttpTransport transport = builder.build();

        McpSyncClient mcpSyncClient = McpClient
                .sync(transport)
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        log.info("tool streamable-http mcp initialize {}", initialize);

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }
}
