package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-61 resources/list 空资源契约：网关声明了 resources 能力但无资源目录
 * （聚合网关语义：资源在下游 MCP server，不在网关）——空列表形状即对外
 * 契约，客户端依赖 resources 键存在性，防结构漂移。
 */
@DisplayName("ResourcesListHandler 空资源契约（b-61）")
class ResourcesListHandlerTest {

    @Test
    void returnsEmptyResourcesShape() {
        ResourcesListHandler handler = new ResourcesListHandler();

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_001",
                new McpSchemaVO.JSONRPCRequest("2.0", "resources/list", 3, Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result).containsKey("resources");

        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("resources");
        assertThat((Object[]) inner.get("resources")).isEmpty();
        assertThat(response.error()).isNull();
    }
}
