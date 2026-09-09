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

    @Mock
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort> responseCacheProvider;

    @Mock
    private cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort responseCache;

    @InjectMocks
    private LlmChatService service;

    private void stubCacheAvailable() {
        lenient().when(responseCacheProvider.getIfAvailable()).thenReturn(responseCache);
        lenient().when(responseCache.available()).thenReturn(true);
        lenient().when(responseCache.cacheKey(any(), anyString(), anyString())).thenReturn("k-test");
    }

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

    @Test
    @DisplayName("流式（0064）— 行序逐行透传、[DONE] 收尾、末块 usage 计量、TTFT>=0")
    public void testStreamingPassthroughWithUsageAndTtft() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "s", 0, "m", null)));
        java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(llmHttpPort.postJsonStreaming(anyString(), anyMap(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onLine = (java.util.function.Consumer<String>) inv.getArgument(4);
            onLine.accept("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n");
            onLine.accept("data: {\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3}}\n");
            onLine.accept("data: [DONE]\n");
            return 200;
        });

        long ttft = service.chatCompletionStream(principal(), "{\"model\":\"m\",\"stream\":true,\"messages\":[]}",
                received::add);

        org.junit.jupiter.api.Assertions.assertEquals(3, received.size(), "行序逐行透传");
        org.junit.jupiter.api.Assertions.assertTrue(received.get(2).contains("[DONE]"));
        org.junit.jupiter.api.Assertions.assertTrue(ttft >= 0, "TTFT 已计量");
        verify(usageLedger).record(argThat(r -> r != null && "SUCCESS".equals(r.getStatus())
                && Long.valueOf(8L).equals(r.getPromptTokens())
                && Long.valueOf(3L).equals(r.getCompletionTokens())));
    }

    @Test
    @DisplayName("流式故障转移（0064）— 首字节前 500 切换下一渠道；出首字节后失败即断不重放")
    public void testStreamingFailoverBoundaries() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(channelRepository.findEnabled()).thenReturn(List.of(
                channel(1L, "bad", 10, "m", null), channel(2L, "good", 0, "m", null)));
        java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(llmHttpPort.postJsonStreaming(eq("http://upstream-bad/chat/completions"), anyMap(), anyString(), anyInt(), any()))
                .thenReturn(500);
        when(llmHttpPort.postJsonStreaming(eq("http://upstream-good/chat/completions"), anyMap(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<String> onLine = (java.util.function.Consumer<String>) inv.getArgument(4);
                    onLine.accept("data: {\"ok\":true}\n");
                    return 200;
                });

        service.chatCompletionStream(principal(), "{\"model\":\"m\",\"stream\":true,\"messages\":[]}", received::add);
        org.junit.jupiter.api.Assertions.assertEquals(1, received.size(), "坏渠道零出流，好渠道一行");

        // 出首字节后上游断（抛异常）→ 不重放到下一渠道，直接 -32004
        when(llmHttpPort.postJsonStreaming(anyString(), anyMap(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onLine = (java.util.function.Consumer<String>) inv.getArgument(4);
            onLine.accept("data: {\"partial\":1}\n");
            throw new java.io.IOException("upstream reset");
        });
        java.util.List<String> replay = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AppException broken = assertThrows(AppException.class, () -> service.chatCompletionStream(
                principal(), "{\"model\":\"m\",\"stream\":true,\"messages\":[]}", replay::add));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_EXECUTION_FAILED), broken.getCode());
        org.junit.jupiter.api.Assertions.assertEquals(1, replay.size(), "已出流内容不重放");
    }

    @Test
    @DisplayName("精确缓存（0097）：命中零上游调用 + CACHE_HIT 记账；未命中写缓存")
    public void testCacheHitAndMiss() throws Exception {
        stubCacheAvailable();
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "primary", 0, "deepseek-chat", null)));
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt())).thenReturn(200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("{\"id\":\"y\",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");

        // 首次：未命中（get null）→ 上游 1 次 → 写缓存
        when(responseCache.get("k-test")).thenReturn(null);
        String body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        service.chatCompletion(principal(), body);
        verify(llmHttpPort, times(1)).postJson(anyString(), anyMap(), anyString(), anyInt());
        verify(responseCache).put(eq("k-test"), anyString());

        // 二次：命中 → 零上游调用 + CACHE_HIT 账本（零 token）
        when(responseCache.get("k-test"))
                .thenReturn("{\"id\":\"cached\",\"choices\":[]}");
        service.chatCompletion(principal(), body);
        verify(llmHttpPort, times(1)).postJson(anyString(), anyMap(), anyString(), anyInt());
        verify(usageLedger, atLeastOnce()).record(argThat(rec ->
                "CACHE_HIT".equals(rec.getStatus())
                        && Long.valueOf(0L).equals(rec.getPromptTokens())
                        && rec.getCost() == null));
    }

    @Test
    @DisplayName("缓存不可用（Redis 缺省）：直通上游零缓存交互")
    public void testCacheUnavailablePassthrough() throws Exception {
        lenient().when(responseCacheProvider.getIfAvailable()).thenReturn(null);
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "primary", 0, "deepseek-chat", null)));
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt())).thenReturn(200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("{\"id\":\"z\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");

        service.chatCompletion(principal(), "{\"model\":\"deepseek-chat\",\"messages\":[]}");
        verifyNoInteractions(responseCache);
    }
}
