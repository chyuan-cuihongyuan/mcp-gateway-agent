package cn.chyuan.ai.domain.llm.service.impl;

import cn.chyuan.ai.domain.llm.model.entity.BuildChatModelCommandEntity;
import cn.chyuan.ai.domain.llm.model.valobj.McpConfigVO;
import cn.chyuan.ai.domain.llm.service.ILLMService;
import com.alibaba.fastjson.JSON;
import com.openai.client.OpenAIClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模型服务
 *
 * @author chyuan
 *         2026/4/8 07:03
 */
@Slf4j
@Service
public class LLMService implements ILLMService {

    private final Map<String, ChatModel> chatModelMap = new ConcurrentHashMap<>();

    @Resource
    private OpenAIClient openAIClient;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Override
    public void buildChatModel(BuildChatModelCommandEntity commandEntity) {
        log.info("构建对话模型 gatewayId:{} mcp:{}", commandEntity.getGatewayId(),
                JSON.toJSONString(commandEntity.getMcpConfigVO()));

        // mcp 配置
        McpConfigVO mcpConfigVO = commandEntity.getMcpConfigVO();

        // model 配置 + mcp 服务（Spring AI 2.0：openAiApi() → openAiClient()，defaultOptions() → options()）
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .toolCallbacks(buildToolCallback(mcpConfigVO))
                        .build())
                .build();

        // 写入缓存
        chatModelMap.put(commandEntity.getGatewayId(), chatModel);
    }

    public ToolCallback[] buildToolCallback(McpConfigVO mcpConfigVO) {
        // 工单 0021：SSE 客户端传输下线，改官方 streamable HTTP 客户端
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(mcpConfigVO.getBaseUri())
                .endpoint(mcpConfigVO.getMcpEndpoint());

        // 使用 HTTP 请求头传递 API Key（MCP SDK 2.0：customizeRequest → httpRequestCustomizer）
        if (StringUtils.isNotBlank(mcpConfigVO.getAuthApiKey())) {
            builder.httpRequestCustomizer((request, method, uri, body, context) -> {
                request.header("Authorization", "Bearer " + mcpConfigVO.getAuthApiKey());
            });
        }

        HttpClientStreamableHttpTransport streamableTransport = builder.build();

        McpSyncClient mcpSyncClient = McpClient
                .sync(streamableTransport)
                .requestTimeout(Duration.ofMillis(mcpConfigVO.getTimeout())).build();
        var initialize = mcpSyncClient.initialize();

        log.info("tool streamable http mcp initialize {}", initialize);

        return new SyncMcpToolCallbackProvider(mcpSyncClient).getToolCallbacks();
    }

    @Override
    public ChatModel getChatModel(String gatewayId) {
        return chatModelMap.get(gatewayId);
    }

}
