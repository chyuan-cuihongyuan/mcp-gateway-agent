package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ChannelScheduler;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容对话服务（工单 0063 非流式；0064 流式）
 *
 * <p>治理链：vk 全局面认证（controller 侧）→ CEL（method=chat/completions、
 * mcp.tool.name=模型名、source=LLM）→ 渠道调度（priority+weight）→ 模型名映射 →
 * JDK HttpClient 转发 → 失败故障转移（下一可用渠道，非流式安全重试）→
 * 用量账本（traffic_type=LLM，channel 维度）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class LlmChatService {

    /** CEL 变量面：LLM 流量（经 tools 域变量承载） */
    public static final String SOURCE_LLM = "LLM";

    /** 用量/配额的流量面标签 */
    public static final String TRAFFIC_LLM_GATEWAY = "__llm__";

    @Resource
    private ILlmChannelRepository channelRepository;

    @Resource
    private ILlmHttpPort llmHttpPort;

    @Resource
    private ChannelScheduler channelScheduler;

    @Resource
    private ICelEvaluationService celEvaluationService;

    @Resource
    private IUsageLedgerService usageLedger;

    @Resource
    private cn.chyuan.ai.domain.governance.service.IQuotaService quotaService;

    /** 计价（工单 0086）：切片测试上下文可缺省（cost 记 null） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.governance.service.PricingService> pricingServiceProvider;

    /** 本次请求线程的成本（工单 0086 响应头消费；读后即清） */
    private static final ThreadLocal<java.math.BigDecimal> LAST_COST = new ThreadLocal<>();

    /** 本次请求是否命中缓存（工单 0097 响应头 X-Gateway-Cache 消费；读后即清） */
    private static final ThreadLocal<Boolean> LAST_CACHE_HIT = new ThreadLocal<>();

    /** 金额软线告警标记（工单 0087 响应头消费；读后即清） */
    private static final ThreadLocal<Boolean> LAST_COST_WARNING = new ThreadLocal<>();

    /** 预算（工单 0087 金额软线上报；切片测试上下文可缺省） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.governance.service.IBudgetService> budgetServiceProvider;

    /** 内容护栏链（工单 0091；切片测试上下文可缺省） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.governance.service.GuardrailChain> guardrailChainProvider;

    /** 响应精确缓存（工单 0097；切片测试上下文可缺省=缓存直通） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort> responseCacheProvider;

    /** 审计（工单 0095 跳过留痕；切片测试上下文可缺省） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.governance.service.IAuditService> auditServiceProvider;

    /**
     * 非流式 chat/completions：响应体原样透传（OpenAI 契约）。
     *
     * @return 上游响应 JSON 字符串
     */
    public String chatCompletion(GovernancePrincipal principal, String requestBody) {
        JSONObject request = parse(requestBody);
        mergeBodyTags(principal, request);
        String model = request.getString("model");
        if (StringUtils.isBlank(model)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "缺少 model 字段");
        }
        if (Boolean.TRUE.equals(request.getBoolean("stream"))) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS,
                    "流式请求请走 chatCompletionStream（工单 0064 路径）");
        }
        if (!celEvaluationService.isToolAllowed(principal, TRAFFIC_LLM_GATEWAY,
                "chat/completions", model, SOURCE_LLM)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "无权使用该模型: " + model);
        }

        // 精确缓存（工单 0097）：命中即原样返回（CACHE_HIT 记账零 token 零成本），不触上游
        String cacheKey = cacheKeyOf(principal, model, request);
        String cached = cacheKey == null ? null : cacheGet(cacheKey);
        if (cached != null) {
            recordCacheHit(principal, model, cached);
            LAST_CACHE_HIT.set(Boolean.TRUE);
            return cached;
        }

        List<LlmChannelVO> candidates = candidatesFor(model);
        if (candidates.isEmpty()) {
            throw new AppException(McpErrorCodes.TOOL_NOT_FOUND, "无可用渠道供给模型: " + model);
        }

        // 渠道按调度顺序尝试，失败转移下一个（非流式安全重试）
        List<cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate> ordered =
                orderCandidates(candidates);
        String lastError = null;
        for (cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate pick : ordered) {
            LlmChannelVO channel = byId(candidates, Long.parseLong(pick.id()));
            try {
                String upstreamModel = mapModel(channel, model);
                String upstreamBody = applyGuardrails(principal, rewriteModel(request, upstreamModel));
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                if (StringUtils.isNotBlank(channel.getCredential())) {
                    headers.put("Authorization", "Bearer " + channel.getCredential());
                }
                long start = System.currentTimeMillis();
                int status = llmHttpPort.postJson(channel.getBaseUrl() + "/chat/completions",
                        headers, upstreamBody, channel.getTimeoutMs() == null ? 60_000 : channel.getTimeoutMs());
                String response = llmHttpPort.lastResponseBody();
                int cost = (int) (System.currentTimeMillis() - start);
                if (status >= 200 && status < 300 && response != null) {
                    // 响应侧护栏 POST_CALL（工单 0094）：阻断丢弃响应；脱敏改写后返回调用方
                    response = applyResponseGuardrails(response);
                    recordUsage(principal, model, channel.getName(), "SUCCESS", cost, response);
                    consumeTpmQuietly(principal, response);
                    if (cacheKey != null) {
                        cachePut(cacheKey, response);
                    }
                    return response;
                }
                lastError = "渠道 " + channel.getName() + " 返回 " + status;
                recordUsage(principal, model, channel.getName(), "FAIL", cost, null);
                log.warn("LLM 渠道失败转移: channel={} status={}", channel.getName(), status);
            } catch (Exception e) {
                lastError = "渠道 " + channel.getName() + " 传输失败: " + e.getMessage();
                recordUsage(principal, model, channel.getName(), "FAIL", 0, null);
                log.warn("LLM 渠道传输失败转移: channel={} reason={}", channel.getName(), e.getMessage());
            }
        }
        throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED,
                "全部渠道失败，最后错误：" + StringUtils.defaultString(lastError));
    }

    /** /v1/models：聚合启用渠道供给的客户端可见模型名 */
    public List<String> visibleModels(GovernancePrincipal principal) {
        Set<String> models = new LinkedHashSet<>();
        for (LlmChannelVO channel : channelRepository.findEnabled()) {
            for (String model : channel.getModels().split(",")) {
                String trimmed = model.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (celEvaluationService.isToolAllowed(principal, TRAFFIC_LLM_GATEWAY,
                        "models/list", trimmed, SOURCE_LLM)) {
                    models.add(trimmed);
                }
            }
        }
        return new ArrayList<>(models);
    }


    /**
     * 流式 chat/completions（工单 0064）：SSE 逐行透传；首字节前允许渠道故障转移，
     * 出首字节后失败即断（不重放）；末块 usage 解析计量；TTFT 经返回值交付。
     *
     * @param onLine 上游行回调（控制器写响应并逐行 flush）
     * @return 首字节延迟毫秒（TTFT；未产出任何行时为 -1）
     */
    public long chatCompletionStream(GovernancePrincipal principal, String requestBody,
            java.util.function.Consumer<String> onLine) {
        JSONObject request = parse(requestBody);
        mergeBodyTags(principal, request);
        String model = request.getString("model");
        if (StringUtils.isBlank(model)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "缺少 model 字段");
        }
        if (!celEvaluationService.isToolAllowed(principal, TRAFFIC_LLM_GATEWAY,
                "chat/completions", model, SOURCE_LLM)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "无权使用该模型: " + model);
        }
        List<LlmChannelVO> candidates = candidatesFor(model);
        if (candidates.isEmpty()) {
            throw new AppException(McpErrorCodes.TOOL_NOT_FOUND, "无可用渠道供给模型: " + model);
        }
        List<cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate> ordered =
                orderCandidates(candidates);

        String lastError = null;
        for (cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate pick : ordered) {
            LlmChannelVO channel = byId(candidates, Long.parseLong(pick.id()));
            StreamForwarder forwarder = new StreamForwarder(onLine, this::responseGuardrailGate);
            try {
                String upstreamBody = applyGuardrails(principal, rewriteModel(request, mapModel(channel, model)));
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "text/event-stream");
                if (StringUtils.isNotBlank(channel.getCredential())) {
                    headers.put("Authorization", "Bearer " + channel.getCredential());
                }
                long start = System.currentTimeMillis();
                int status = llmHttpPort.postJsonStreaming(channel.getBaseUrl() + "/chat/completions",
                        headers, upstreamBody, channel.getTimeoutMs() == null ? 60_000 : channel.getTimeoutMs(),
                        forwarder);
                int cost = (int) (System.currentTimeMillis() - start);
                if (status >= 200 && status < 300) {
                    recordUsageTokens(principal, model, channel.getName(), "SUCCESS", cost,
                            forwarder.usagePromptTokens(), forwarder.usageCompletionTokens());
                    consumeTpmQuietly(principal, forwarder.usagePromptTokens(), forwarder.usageCompletionTokens());
                    return forwarder.ttftMs();
                }
                if (forwarder.firstByteSent()) {
                    // 已向客户端出流：不可重放，按失败断流
                    recordUsageTokens(principal, model, channel.getName(), "FAIL", cost,
                            forwarder.usagePromptTokens(), forwarder.usageCompletionTokens());
                    throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED,
                            "上游流式中断（httpStatus=" + status + "）");
                }
                lastError = "渠道 " + channel.getName() + " 返回 " + status;
                recordUsage(principal, model, channel.getName(), "FAIL", cost, null);
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                if (forwarder.firstByteSent()) {
                    recordUsageTokens(principal, model, channel.getName(), "FAIL", 0,
                            forwarder.usagePromptTokens(), forwarder.usageCompletionTokens());
                    throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED, "上游流式中断: " + e.getMessage());
                }
                lastError = "渠道 " + channel.getName() + " 传输失败: " + e.getMessage();
                recordUsage(principal, model, channel.getName(), "FAIL", 0, null);
            }
        }
        throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED,
                "全部渠道失败，最后错误：" + StringUtils.defaultString(lastError));
    }

    /** 流式转发器：行透传 + TTFT + 末块 usage 解析（工单 0064） */
    static final class StreamForwarder implements java.util.function.Consumer<String> {

        private final java.util.function.Consumer<String> sink;
        /** 响应护栏门（工单 0094）：阻断抛 AppException；脱敏改写；null=直通 */
        private final java.util.function.UnaryOperator<String> responseGate;
        private volatile long ttftMs = -1;
        private volatile long start = System.currentTimeMillis();
        private volatile boolean firstByteSent = false;
        private Long usagePromptTokens;
        private Long usageCompletionTokens;

        StreamForwarder(java.util.function.Consumer<String> sink,
                java.util.function.UnaryOperator<String> responseGate) {
            this.sink = sink;
            this.responseGate = responseGate == null ? l -> l : responseGate;
        }

        @Override
        public void accept(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            // 响应侧护栏 POST_CALL（工单 0094）：首字节前完成判定——阻断中止（无字节已出，
            // 控制器按 -32018 落错误结构）；脱敏改写首行后续写
            String guarded = responseGate.apply(line);
            firstByteSent = true;
            if (ttftMs < 0) {
                ttftMs = System.currentTimeMillis() - start;
            }
            parseUsage(guarded);
            sink.accept(guarded);
        }

        private void parseUsage(String line) {
            String payload = line.trim();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            try {
                JSONObject chunk = JSON.parseObject(payload);
                JSONObject usage = chunk.getJSONObject("usage");
                if (usage != null) {
                    Long prompt = usage.getLong("prompt_tokens");
                    Long completion = usage.getLong("completion_tokens");
                    if (prompt != null) {
                        usagePromptTokens = prompt;
                    }
                    if (completion != null) {
                        usageCompletionTokens = completion;
                    }
                }
            } catch (Exception ignore) {
                // 非 JSON 行（注释/心跳）跳过
            }
        }

        long ttftMs() {
            return ttftMs;
        }

        boolean firstByteSent() {
            return firstByteSent;
        }

        Long usagePromptTokens() {
            return usagePromptTokens;
        }

        Long usageCompletionTokens() {
            return usageCompletionTokens;
        }
    }

    /** TPM 计量（非流式：从响应体解析 usage；工单 0065） */
    private void consumeTpmQuietly(GovernancePrincipal principal, String responseBody) {
        long tokens = 0;
        if (responseBody != null) {
            try {
                JSONObject usage = JSON.parseObject(responseBody).getJSONObject("usage");
                if (usage != null) {
                    tokens += usage.getLongValue("prompt_tokens");
                    tokens += usage.getLongValue("completion_tokens");
                }
            } catch (Exception ignore) {
                // 无 usage 不计量
            }
        }
        consumeTpmQuietly(principal, tokens > 0 ? tokens : 0L, 0L);
    }

    private void consumeTpmQuietly(GovernancePrincipal principal, Long promptTokens, Long completionTokens) {
        long tokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        if (tokens <= 0) {
            return;
        }
        try {
            quotaService.consumeTokens(TRAFFIC_LLM_GATEWAY, principal, tokens);
        } catch (Exception e) {
            log.debug("TPM 计量提交失败：{}", e.getMessage());
        }
    }

    private void recordUsageTokens(GovernancePrincipal principal, String model, String channel,
            String status, int costMs, Long promptTokens, Long completionTokens) {
        try {
            usageLedger.record(UsageRecordVO.builder()
                    .virtualKeyId(principal == null ? null : principal.getVirtualKeyId())
                    .apiKeyHash(principal == null ? null : principal.getApiKeyHash())
                    .gatewayId(TRAFFIC_LLM_GATEWAY)
                    .trafficType("LLM")
                    .toolOrModel(model)
                    .channelId(channel)
                    .status(status)
                    .durationMs(costMs)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .cost(costOf(principal, model, promptTokens, completionTokens))
                    .tags(tagStorageOf(principal))
                    .clientIp(principal == null ? null : principal.getClientIp())
                    .build());
        } catch (Exception e) {
            log.debug("LLM 流式用量落账失败 model={}：{}", model, e.getMessage());
        }
    }

    /** 计价（工单 0086）：命中即落账并暂存线程上下文供响应头；未定价返回 null。
     *  金额软线（工单 0087）：越过即事件 + 暂存告警供响应头。 */
    private java.math.BigDecimal costOf(GovernancePrincipal principal, String model,
            Long promptTokens, Long completionTokens) {
        cn.chyuan.ai.domain.governance.service.PricingService pricing =
                pricingServiceProvider == null ? null : pricingServiceProvider.getIfAvailable();
        if (pricing == null) {
            return null;
        }
        java.math.BigDecimal cost = pricing.costOf(model, promptTokens, completionTokens);
        if (cost != null) {
            LAST_COST.set(cost);
            reportCostQuietly(principal, cost);
        }
        return cost;
    }

    /** 金额软线上报（工单 0087）：告警暂存线程上下文（X-Budget-Warning: cost 消费） */
    private void reportCostQuietly(GovernancePrincipal principal, java.math.BigDecimal cost) {
        try {
            cn.chyuan.ai.domain.governance.service.IBudgetService budgetService =
                    budgetServiceProvider == null ? null : budgetServiceProvider.getIfAvailable();
            if (budgetService != null && budgetService.reportCost(principal, cost)) {
                LAST_COST_WARNING.set(true);
            }
        } catch (Exception e) {
            log.debug("金额软线上报失败（不阻断）：{}", e.getMessage());
        }
    }

    /** 取出并清除本次请求的金额软线告警标记（无则 false） */
    public boolean consumeLastCostWarning() {
        boolean warning = Boolean.TRUE.equals(LAST_COST_WARNING.get());
        LAST_COST_WARNING.remove();
        return warning;
    }

    /** 取出并清除本次请求线程的成本（响应头 X-Gateway-Cost 消费；无则 null） */
    public java.math.BigDecimal consumeLastCost() {
        java.math.BigDecimal cost = LAST_COST.get();
        LAST_COST.remove();
        return cost;
    }

    /** 标签落库形（工单 0088）：principal 头来源 + 请求体 metadata.tags 已并入 */
    private static String tagStorageOf(GovernancePrincipal principal) {
        return principal == null ? null
                : cn.chyuan.ai.types.util.TagParser.toStorage(principal.getTags());
    }

    /** 跳过审计（工单 0095：SECURITY 型，尽力而为） */
    private void auditGuardrailSkipQuietly(GovernancePrincipal principal) {
        try {
            auditServiceProvider.stream().findFirst().ifPresent(audit ->
                    audit.record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity.builder()
                            .actor("system")
                            .action("GUARDRAIL_SKIP")
                            .resourceType("VIRTUAL_KEY")
                            .resourceId(String.valueOf(principal.getVirtualKeyId()))
                            .build()));
        } catch (Exception e) {
            log.debug("护栏跳过审计失败（不阻断）：{}", e.getMessage());
        }
    }

    // ---- 精确缓存（工单 0097；0098 扩展字段白名单与请求级覆盖） ----

    /** 缓存键：vk 隔离 + model + 参与字段规范化（messages/temperature/top_p 原样序列化） */
    private String cacheKeyOf(GovernancePrincipal principal, String model, JSONObject request) {
        cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort cache =
                responseCacheProvider == null ? null : responseCacheProvider.getIfAvailable();
        if (cache == null || !cache.available()) {
            return null;
        }
        JSONObject participation = new JSONObject();
        participation.put("messages", request.get("messages"));
        participation.put("temperature", request.get("temperature"));
        participation.put("top_p", request.get("top_p"));
        return cache.cacheKey(principal == null ? null : principal.getVirtualKeyId(),
                model, participation.toJSONString());
    }

    private String cacheGet(String cacheKey) {
        cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort cache =
                responseCacheProvider == null ? null : responseCacheProvider.getIfAvailable();
        return cache == null ? null : cache.get(cacheKey);
    }

    private void cachePut(String cacheKey, String response) {
        cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort cache =
                responseCacheProvider == null ? null : responseCacheProvider.getIfAvailable();
        if (cache != null) {
            cache.put(cacheKey, response);
        }
    }

    /** 命中记账（工单 0097）：CACHE_HIT + 零 token 零成本 */
    private void recordCacheHit(GovernancePrincipal principal, String model, String cached) {
        recordUsageTokens(principal, model, "cache", "CACHE_HIT", 0, 0L, 0L);
        log.debug("LLM 缓存命中 model={} bytes={}", model, cached.length());
    }

    /** 取出并清除缓存命中标记（响应头消费；无则 false） */
    public boolean consumeLastCacheHit() {
        boolean hit = Boolean.TRUE.equals(LAST_CACHE_HIT.get());
        LAST_CACHE_HIT.remove();
        return hit;
    }

    /** 护栏跳过（工单 0095）：admin 授权密钥 + X-Gateway-Skip-Guardrails 头才生效（标记由认证过滤器写入 principal） */
    private static boolean skipGuardrails(GovernancePrincipal principal) {
        return principal != null
                && Boolean.TRUE.equals(principal.getSkipGuardrail())
                && Boolean.TRUE.equals(principal.getSkipGuardrailAllowed());
    }

    /** 响应侧护栏（工单 0094）：非流式整响应判定（阻断 -32018；脱敏改写） */
    private String applyResponseGuardrails(String response) {
        cn.chyuan.ai.domain.governance.service.GuardrailChain chain =
                guardrailChainProvider == null ? null : guardrailChainProvider.getIfAvailable();
        if (chain == null) {
            return response;
        }
        cn.chyuan.ai.domain.governance.service.GuardrailChain.GuardrailOutcome outcome = chain.evaluate(
                "LLM", cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO.MODE_POST_CALL, response);
        if (outcome.blocked()) {
            throw new AppException(McpErrorCodes.CONTENT_BLOCKED, "响应命中安全护栏：" + outcome.hitRule());
        }
        return outcome.masked() ? outcome.text() : response;
    }

    /** 流式首行护栏门（静态上下文：链实例经 holder 注入，未装配直通） */
    private String responseGuardrailGate(String line) {
        cn.chyuan.ai.domain.governance.service.GuardrailChain chain =
                guardrailChainProvider == null ? null : guardrailChainProvider.getIfAvailable();
        if (chain == null) {
            return line;
        }
        cn.chyuan.ai.domain.governance.service.GuardrailChain.GuardrailOutcome outcome = chain.evaluate(
                "LLM", cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO.MODE_POST_CALL, line);
        if (outcome.blocked()) {
            throw new AppException(McpErrorCodes.CONTENT_BLOCKED, "响应命中安全护栏：" + outcome.hitRule());
        }
        return outcome.masked() ? outcome.text() : line;
    }

    /** 内容护栏 PRE_CALL（工单 0091）：阻断抛 -32018；脱敏改写后转发（链未装配=直通） */
    private String applyGuardrails(GovernancePrincipal principal, String upstreamBody) {
        cn.chyuan.ai.domain.governance.service.GuardrailChain chain =
                guardrailChainProvider == null ? null : guardrailChainProvider.getIfAvailable();
        if (chain == null) {
            return upstreamBody;
        }
        if (skipGuardrails(principal)) {
            auditGuardrailSkipQuietly(principal);
            return upstreamBody;
        }
        cn.chyuan.ai.domain.governance.service.GuardrailChain.GuardrailOutcome outcome = chain.evaluate(
                "LLM", cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO.MODE_PRE_CALL, upstreamBody);
        if (outcome.blocked()) {
            throw new AppException(McpErrorCodes.CONTENT_BLOCKED, "内容命中安全护栏：" + outcome.hitRule());
        }
        return outcome.masked() ? outcome.text() : upstreamBody;
    }

    /** 请求体 metadata.tags 并入 principal（工单 0088：LLM 面第二来源，body 优先） */
    private static void mergeBodyTags(GovernancePrincipal principal, JSONObject request) {
        if (principal == null || request == null) {
            return;
        }
        JSONObject metadata = request.getJSONObject("metadata");
        if (metadata == null) {
            return;
        }
        java.util.List<String> bodyTags = new java.util.ArrayList<>();
        for (Object item : metadata.getJSONArray("tags")) {
            if (item != null) {
                bodyTags.add(String.valueOf(item));
            }
        }
        principal.setTags(cn.chyuan.ai.types.util.TagParser.merge(principal.getTags(), bodyTags));
    }

    /** 供给某模型的启用渠道（models 清单含该名，或映射目标含该名） */
    List<LlmChannelVO> candidatesFor(String model) {
        return channelRepository.findEnabled().stream()
                .filter(ch -> suppliesModel(ch, model))
                .toList();
    }

    static boolean suppliesModel(LlmChannelVO channel, String model) {
        for (String m : channel.getModels().split(",")) {
            if (model.equals(m.trim())) {
                return true;
            }
        }
        if (StringUtils.isNotBlank(channel.getModelMapping())) {
            try {
                return JSON.parseObject(channel.getModelMapping()).containsKey(model);
            } catch (Exception ignore) {
                // 映射非法按不含
            }
        }
        return false;
    }

    static String mapModel(LlmChannelVO channel, String model) {
        if (StringUtils.isBlank(channel.getModelMapping())) {
            return model;
        }
        try {
            String mapped = JSON.parseObject(channel.getModelMapping()).getString(model);
            return StringUtils.isBlank(mapped) ? model : mapped;
        } catch (Exception e) {
            return model;
        }
    }

    static String rewriteModel(JSONObject request, String upstreamModel) {
        JSONObject copy = new JSONObject(new HashMap<>(request));
        copy.put("model", upstreamModel);
        return copy.toJSONString();
    }

    private List<cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate> orderCandidates(
            List<LlmChannelVO> candidates) {
        List<cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate> ordered = new ArrayList<>();
        List<LlmChannelVO> remaining = new ArrayList<>(candidates);
        while (!remaining.isEmpty()) {
            var pick = channelScheduler.pick(remaining.stream()
                    .map(ch -> new cn.chyuan.ai.domain.governance.service.ChannelScheduler.Candidate(
                            String.valueOf(ch.getId()),
                            ch.getPriority() == null ? 0 : ch.getPriority(),
                            ch.getWeight() == null ? 1 : ch.getWeight()))
                    .toList());
            if (pick.isEmpty()) {
                break;
            }
            ordered.add(pick.get());
            long id = Long.parseLong(pick.get().id());
            remaining.removeIf(ch -> ch.getId() == id);
        }
        return ordered;
    }

    private static LlmChannelVO byId(List<LlmChannelVO> candidates, long id) {
        return candidates.stream().filter(ch -> ch.getId() == id).findFirst().orElseThrow();
    }

    private static JSONObject parse(String body) {
        try {
            JSONObject parsed = JSON.parseObject(body);
            if (parsed == null) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS, "请求体非法 JSON");
            }
            return parsed;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "请求体非法 JSON");
        }
    }

    /** 用量落账（非流式：usage 从响应体解析；流式由 0064 补） */
    private void recordUsage(GovernancePrincipal principal, String model, String channel,
            String status, int costMs, String responseBody) {
        Long promptTokens = null;
        Long completionTokens = null;
        if (responseBody != null) {
            try {
                JSONObject usage = JSON.parseObject(responseBody).getJSONObject("usage");
                if (usage != null) {
                    promptTokens = usage.getLong("prompt_tokens");
                    completionTokens = usage.getLong("completion_tokens");
                }
            } catch (Exception ignore) {
                // 响应非 JSON 或无 usage：不计量
            }
        }
        try {
            usageLedger.record(UsageRecordVO.builder()
                    .virtualKeyId(principal == null ? null : principal.getVirtualKeyId())
                    .apiKeyHash(principal == null ? null : principal.getApiKeyHash())
                    .gatewayId(TRAFFIC_LLM_GATEWAY)
                    .trafficType("LLM")
                    .toolOrModel(model)
                    .channelId(channel)
                    .status(status)
                    .durationMs(costMs)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .clientIp(principal == null ? null : principal.getClientIp())
                    .build());
        } catch (Exception e) {
            log.debug("LLM 用量落账失败 model={}：{}", model, e.getMessage());
        }
    }
}
