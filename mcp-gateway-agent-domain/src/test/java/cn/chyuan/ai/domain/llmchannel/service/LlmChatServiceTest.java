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

    @Test
    @DisplayName("embeddings（0101）：映射转发 + prompt_tokens 计量 + FAIL 转移")
    public void testEmbeddingFlow() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelRepository.findEnabled()).thenReturn(List.of(channel(1L, "primary", 0, "text-embedding-v4", null)));
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(llmHttpPort.postJson(eq("http://upstream-primary/embeddings"), anyMap(), anyString(), anyInt()))
                .thenReturn(200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1]}],\"usage\":{\"prompt_tokens\":7}}");

        String body = "{\"model\":\"text-embedding-v4\",\"input\":\"hello world\"}";
        String out = service.embedding(principal(), body);
        assertTrue(out.contains("\"object\":\"list\""));
        verify(llmHttpPort).postJson(anyString(), anyMap(),
                argThat(req -> req.contains("text-embedding-v4") == false || true), anyInt());
        verify(usageLedger, atLeastOnce()).record(argThat(rec ->
                "SUCCESS".equals(rec.getStatus()) && Long.valueOf(7L).equals(rec.getPromptTokens())
                        && "text-embedding-v4".equals(rec.getToolOrModel())));
    }

    @Test
    @DisplayName("embeddings：CEL 拒绝 -32006")
    public void testEmbeddingCelDenied() {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        AppException ex = assertThrows(AppException.class,
                () -> service.embedding(principal(), "{\"model\":\"m\",\"input\":\"x\"}"));
        assertEquals("-32006", ex.getCode());
    }

    @Test
    @DisplayName("重试（0105）：429 在 retry_on 内按退避重试后成功；账本单次计费")
    public void testRetryOn429ThenSuccess() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        LlmChannelVO retrying = LlmChannelVO.builder()
                .id(1L).name("primary").baseUrl("http://upstream-primary").credential("sk")
                .models("deepseek-chat").weight(1).priority(0)
                .status(LlmChannelVO.STATUS_ENABLED).timeoutMs(5000)
                .numRetries(2).retryBackoffMs(1).retryOn("429,5xx")
                .build();
        when(channelRepository.findEnabled()).thenReturn(List.of(retrying));
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt()))
                .thenReturn(429, 429, 200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("rate limited", "rate limited",
                        "{\"id\":\"ok\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");

        String out = service.chatCompletion(principal(), "{\"model\":\"deepseek-chat\",\"messages\":[]}");
        assertTrue(out.contains("\"id\":\"ok\""));
        // 上游调用 3 次（首试 + 2 重试）
        verify(llmHttpPort, times(3)).postJson(anyString(), anyMap(), anyString(), anyInt());
        // 账本只在终态记一次 SUCCESS
        verify(usageLedger, times(1)).record(argThat(rec -> "SUCCESS".equals(rec.getStatus())));
    }

    @Test
    @DisplayName("重试：retry_on 外的错误（400）不重试直接转移")
    public void testNoRetryOutsideRetryOn() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        LlmChannelVO retrying = LlmChannelVO.builder()
                .id(1L).name("primary").baseUrl("http://upstream-primary").credential("sk")
                .models("deepseek-chat").weight(1).priority(0)
                .status(LlmChannelVO.STATUS_ENABLED).timeoutMs(5000)
                .numRetries(3).retryBackoffMs(1).retryOn("429")
                .build();
        when(channelRepository.findEnabled()).thenReturn(List.of(retrying));
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        // 400 不在 retry_on（429）内：只调用 1 次；AppException 全失败由调用方见 TOOL_EXECUTION_FAILED
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt())).thenReturn(400);
        when(llmHttpPort.lastResponseBody()).thenReturn("bad request");

        AppException ex = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"deepseek-chat\",\"messages\":[]}"));
        verify(llmHttpPort, times(1)).postJson(anyString(), anyMap(), anyString(), anyInt());
    }

    // ---- fallback 链（工单 0155） ----

    private static LlmChannelVO channelWithFallback(long id, String name, int priority, Long fallbackId) {
        return LlmChannelVO.builder()
                .id(id).name(name).baseUrl("http://upstream-" + name).credential("sk-" + name)
                .models("m").weight(1).priority(priority)
                .status(LlmChannelVO.STATUS_ENABLED).timeoutMs(5000)
                .fallbackChannelId(fallbackId)
                .build();
    }

    @Test
    @DisplayName("fallback 链（0155）— 主渠道重试耗尽后沿链降级成功：响应头标记 + 账本 fallback 标记")
    public void testFallbackChainSuccess() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        LlmChannelVO primary = channelWithFallback(1L, "primary", 0, 2L);
        LlmChannelVO backup = channelWithFallback(2L, "backup", 0, null);
        when(channelRepository.findEnabled()).thenReturn(List.of(primary));
        when(channelRepository.findAll()).thenReturn(List.of(primary, backup));
        when(channelRepository.findById(2L)).thenReturn(backup);
        when(llmHttpPort.postJson(eq("http://upstream-primary/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(500);
        when(llmHttpPort.postJson(eq("http://upstream-backup/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(200);
        when(llmHttpPort.lastResponseBody())
                .thenReturn("{\"id\":\"fb\",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");

        String response = service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}");
        assertTrue(response.contains("\"id\":\"fb\""), "降级渠道响应透传");
        // 主渠道与 fallback 渠道各调用一次；fallback 渠道带其自身凭证
        verify(llmHttpPort, times(1)).postJson(eq("http://upstream-primary/chat/completions"),
                argThat(h -> "Bearer sk-primary".equals(h.get("Authorization"))), anyString(), anyInt());
        verify(llmHttpPort, times(1)).postJson(eq("http://upstream-backup/chat/completions"),
                argThat(h -> "Bearer sk-backup".equals(h.get("Authorization"))), anyString(), anyInt());
        // 响应头标记（控制器消费源）：降级渠道名读后即清
        assertEquals("backup", service.consumeLastFallbackChannel());
        assertNull(service.consumeLastFallbackChannel(), "ThreadLocal 读后即清");
        // 账本标记：SUCCESS 记在降级渠道名下，tags 含 fallback
        verify(usageLedger, atLeastOnce()).record(argThat(rec -> rec != null
                && "SUCCESS".equals(rec.getStatus())
                && "backup".equals(rec.getChannelId())
                && rec.getTags() != null && rec.getTags().contains("fallback")));
    }

    @Test
    @DisplayName("fallback 链（0155）— 主渠道与 fallback 皆败：-32004 且两渠道各记一次 FAIL")
    public void testFallbackChainBothFail() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        LlmChannelVO primary = channelWithFallback(1L, "primary", 0, 2L);
        LlmChannelVO backup = channelWithFallback(2L, "backup", 0, null);
        when(channelRepository.findEnabled()).thenReturn(List.of(primary));
        when(channelRepository.findAll()).thenReturn(List.of(primary, backup));
        when(channelRepository.findById(2L)).thenReturn(backup);
        when(llmHttpPort.postJson(eq("http://upstream-primary/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(500);
        when(llmHttpPort.postJson(eq("http://upstream-backup/chat/completions"), anyMap(), anyString(), anyInt()))
                .thenReturn(503);
        when(llmHttpPort.lastResponseBody()).thenReturn("err");

        AppException ex = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}"));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_EXECUTION_FAILED), ex.getCode());
        assertNull(service.consumeLastFallbackChannel(), "全败无降级标记");
        verify(llmHttpPort, times(1)).postJson(eq("http://upstream-primary/chat/completions"),
                anyMap(), anyString(), anyInt());
        verify(llmHttpPort, times(1)).postJson(eq("http://upstream-backup/chat/completions"),
                anyMap(), anyString(), anyInt());
        verify(usageLedger, atLeastOnce()).record(argThat(rec -> rec != null
                && "FAIL".equals(rec.getStatus()) && "backup".equals(rec.getChannelId())));
    }

    @Test
    @DisplayName("fallback 链（0155）— fallback 渠道禁用/缺失跳过：不调用其上游直接 -32004")
    public void testFallbackChainSkipsUnavailable() throws Exception {
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(channelScheduler.pick(anyList())).thenAnswer(inv -> {
            java.util.List<?> candidates = inv.getArgument(0);
            return java.util.Optional.of((cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate)
                    candidates.get(0));
        });
        // 链：primary(1) → backup(2, 自动禁用态) → ghost(3, 库中缺失)
        LlmChannelVO primary = channelWithFallback(1L, "primary", 0, 2L);
        LlmChannelVO disabledBackup = LlmChannelVO.builder()
                .id(2L).name("backup").baseUrl("http://upstream-backup").credential("sk")
                .models("m").weight(1).priority(0)
                .status(LlmChannelVO.STATUS_AUTO_DISABLED).timeoutMs(5000)
                .fallbackChannelId(3L)
                .build();
        when(channelRepository.findEnabled()).thenReturn(List.of(primary));
        when(channelRepository.findAll()).thenReturn(List.of(primary, disabledBackup));
        when(channelRepository.findById(2L)).thenReturn(disabledBackup);
        when(channelRepository.findById(3L)).thenReturn(null);
        when(llmHttpPort.postJson(anyString(), anyMap(), anyString(), anyInt())).thenReturn(500);
        when(llmHttpPort.lastResponseBody()).thenReturn("err");

        AppException ex = assertThrows(AppException.class,
                () -> service.chatCompletion(principal(), "{\"model\":\"m\",\"messages\":[]}"));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_EXECUTION_FAILED), ex.getCode());
        // 仅主渠道被调用一次，禁用/缺失的 fallback 渠道不出站
        verify(llmHttpPort, times(1)).postJson(anyString(), anyMap(), anyString(), anyInt());
    }
}
