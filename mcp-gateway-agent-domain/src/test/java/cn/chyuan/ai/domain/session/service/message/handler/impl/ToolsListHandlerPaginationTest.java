package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * tools/list cursor 分页（SELFLOOP3 loop-326，工单 0450/0451）
 */
class ToolsListHandlerPaginationTest {

    private ToolsListHandler handlerWith(int pageSize, int toolCount) {
        ISessionRepository repository = mock(ISessionRepository.class);
        List<McpToolConfigVO> configs = new ArrayList<>();
        for (int i = 1; i <= toolCount; i++) {
            configs.add(McpToolConfigVO.builder()
                    .toolName("tool_" + i)
                    .toolDescription("工具 " + i)
                    .mcpToolProtocolConfigVO(new McpToolProtocolConfigVO())
                    .build());
        }
        when(repository.queryMcpGatewayToolConfigListByGatewayId(anyString())).thenReturn(configs);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "listPageSize", pageSize);
        return handler;
    }

    private McpSchemaVO.JSONRPCRequest requestWithCursor(String cursor) {
        return new McpSchemaVO.JSONRPCRequest(McpSchemaVO.JSONRPC_VERSION, "req-1", "tools/list",
                cursor != null ? Map.of("cursor", cursor) : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultOf(McpSchemaVO.JSONRPCResponse resp) {
        return (Map<String, Object>) resp.result();
    }

    @Test
    void smallCatalogReturnsSinglePageWithoutCursor() {
        ToolsListHandler handler = handlerWith(50, 3);

        Map<String, Object> result = resultOf(handler.handle("g1", requestWithCursor(null)));

        assertThat((List<?>) result.get("tools")).hasSize(3);
        assertThat(result).doesNotContainKey("nextCursor");
    }

    @Test
    void largeCatalogPagesWithNextCursor() {
        ToolsListHandler handler = handlerWith(2, 5);

        Map<String, Object> first = resultOf(handler.handle("g1", requestWithCursor(null)));
        assertThat((List<?>) first.get("tools")).hasSize(2);
        assertThat(first.get("nextCursor")).isEqualTo("tool_2");

        Map<String, Object> second = resultOf(handler.handle("g1",
                requestWithCursor((String) first.get("nextCursor"))));
        assertThat((List<?>) second.get("tools")).hasSize(2);
        assertThat(second.get("nextCursor")).isEqualTo("tool_4");

        Map<String, Object> last = resultOf(handler.handle("g1",
                requestWithCursor((String) second.get("nextCursor"))));
        assertThat((List<?>) last.get("tools")).hasSize(1);
        assertThat(last).doesNotContainKey("nextCursor");
    }

    @Test
    void invalidCursorIsRejected() {
        ToolsListHandler handler = handlerWith(50, 3);

        assertThatThrownBy(() -> handler.handle("g1", requestWithCursor("nonexistent")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getInfo()).contains("无效的 tools/list 游标"));
    }
}
