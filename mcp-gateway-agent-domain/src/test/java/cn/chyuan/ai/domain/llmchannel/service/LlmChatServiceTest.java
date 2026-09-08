package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ChannelScheduler;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * OpenAI 兼容对话服务测试（工单 0063：模型映射/调度故障转移/CEL/models 聚合/用量落账）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAI 兼容对话服务测试")
public class LlmChatServiceTest {

    @Mock
    private ILlmChannelRepository channelRepository;

    @Mock
    private ILlmHttpPort llmHttpPort;

    @Mock
    private ICelEvaluationService celEvaluationService;

    @Mock
    private ChannelScheduler channelScheduler;

    @Mock
    private IUsageLedgerService usageLedger;

    @InjectMocks
    private LlmChatService service;

    private static LlmChannelVO channel(long id, String name, int priority, String models, String mapping) {
        return LlmChannelVO.builder()
                .id(id).name(name).baseUrl("http://upstream-" + name).credential("sk-" + name)
                .models(models).modelMapping(mapping).weight(1).priority(priority)
                .status(LlmChannelVO.STATUS_ENABLED).timeoutMs(5000)
                .build();
    }

    private static GovernancePrincipal principal() {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(7L).apiKeyHash("hash").clientIp("1.2.3.4")
                .build();
    }

    @Test
    @DisplayName("非流式 — 模型名映射后转发、响应透传、usage 落账（LLM 类型 + 渠道维度）")
    public void testChatCompletionWithModelMapping() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "primary", 0, "gpt-4o", "{\"gpt-4o\":\"deepseek-v4-pro\"}")));
        when(llmHttpPort.postJson(eq("http://upstream-primary/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("{\"id\":\"x\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");

        String response = service.chatCompletion(principal(),
                "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertTrue(response.contains("\"usage\""));
        verify(llmHttpPort).postJson(anyString(), argThat(headers ->
                        "Bearer sk-primary".equals(headers.get("Authorization"))),
                argThat(body -> body.contains("\"deepseek-v4-pro\"")), anyInt());
        verify(usageLedger).record(argThat(record -> record != null
                && "LLM".equals(record.getTrafficType())
                && "gpt-4o".equals(record.getToolOrModel())
                && "primary".equals(record.getChannelId())
                && Long.valueOf(10L).equals(record.getPromptTokens())));
    }

    @Test
    @DisplayName("故障转移 — 高优先级渠道 500 后切到次渠道成功；全失败报 -32004")
    public void testFailover() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(channelRepository.findEnabled()).thenReturn(List.of(
                channel(1L, "high", 10, "m", null),
                channel(2L, "low", 0, "m", null)));
        when(llmHttpPort.postJson(eq("http://upstream-high/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(500);
        when(llmHttpPort.postJson(eq("http://upstream-low/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(200);
        when(llmHttpPort.lastResponseBody()).thenReturn("{\"ok\":true}");

        String response = service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}");
        assertTrue(response.contains("\"ok\""));

        // 全失败
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "only", 0, "m", null)));
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt())).thenReturn(503);
        AppException e = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}"));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_EXECUTION_FAILED), e.getCode());
    }

    @Test
    @DisplayName("CEL 拒绝 — 模型不可用 -32006；无渠道供给 -32003；stream=true 明确拒绝；缺 model -32602")
    public void testGovernanceAndValidation() {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), eq("m"), anyString()))
                .thenReturn(false);
        AppException denied = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}"));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.INSUFFICIENT_PERMISSIONS), denied.getCode());

        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelRepository.findEnabled()).thenReturn(List.of());
        AppException noChannel = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}"));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_NOT_FOUND), noChannel.getCode());

        AppException streaming = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"stream\":true,\"messages\":[]}"));
        assertTrue(streaming.getInfo().contains("流式"));

        assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"messages\":[]}"));
    }

    @Test
    @DisplayName("models 聚合 — 启用渠道并集 + CEL 过滤")
    public void testVisibleModels() {
        when(channelRepository.findEnabled()).thenReturn(List.of(
                channel(1L, "a", 0, "m1, m2", null),
                channel(2L, "b", 0, "m2,m3", null)));
        when(celEvaluationService.isToolAllowed(any(), anyString(), eq("models/list"), eq("m2"), anyString()))
                .thenReturn(false);
        when(celEvaluationService.isToolAllowed(any(), anyString(), eq("models/list"), eq("m1"), anyString()))
                .thenReturn(true);
        when(celEvaluationService.isToolAllowed(any(), anyString(), eq("models/list"), eq("m3"), anyString()))
                .thenReturn(true);

        assertEquals(List.of("m1", "m3"), service.visibleModels(principal()));
    }
}
