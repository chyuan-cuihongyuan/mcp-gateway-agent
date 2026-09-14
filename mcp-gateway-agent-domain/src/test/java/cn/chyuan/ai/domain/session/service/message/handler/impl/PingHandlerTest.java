package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-49 ping 保活契约：spec 要求接收方尽快以空结果响应 ping；
 * 此前未注册导致保活探活收到 METHOD_NOT_FOUND。
 */
@DisplayName("PingHandler 保活契约（b-49）")
class PingHandlerTest {

    @Test
    void respondsPromptlyWithEmptyResult() {
        PingHandler handler = new PingHandler();

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_001",
                new McpSchemaVO.JSONRPCRequest("2.0", "ping", 7, Map.of()));

        assertThat((Map<?, ?>) response.result()).isEmpty();
        assertThat(response.id()).isEqualTo(7);
        assertThat(response.error()).isNull();
    }
}
