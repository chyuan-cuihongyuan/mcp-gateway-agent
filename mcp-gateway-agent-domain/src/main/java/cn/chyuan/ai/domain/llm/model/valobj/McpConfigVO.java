package cn.chyuan.ai.domain.llm.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * mcp 配置值对象（工单 0021：SSE 端点随传输下线，改 streamable HTTP 端点）
 *
 * @author chyuan
 *         2026/4/8 07:18
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpConfigVO {

    private String baseUri;

    /** streamable HTTP 端点路径（如 /api-gateway/{gatewayId}/mcp） */
    private String mcpEndpoint;

    private String authApiKey;

    private Integer timeout;

}
