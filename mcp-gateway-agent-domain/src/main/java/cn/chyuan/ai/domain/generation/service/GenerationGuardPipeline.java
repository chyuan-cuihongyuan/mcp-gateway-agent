package cn.chyuan.ai.domain.generation.service;

import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationHit;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生成侧治理管线（五期 AA 簇总挂点）——注入 LlmChatService 的唯一接缝，
 * 各能力独立开关、全部默认关，关=零行为变化：
 * <ul>
 *   <li>AA7 标注回复（generation.annotation.enabled）：命中问答对直接返回，零模型调用；</li>
 *   <li>AA6 提示注入检测（generation.injection.enabled）：超阈 -32024 拒绝；</li>
 *   <li>AA5 内容护栏（generation.guard.enabled）：进站脱敏请求 messages + 出站脱敏响应；</li>
 *   <li>AA4 结构化输出守护（generation.structured-output.enabled）：response_format
 *       声明 JSON 时校验+规则兜底修复，仍失败 -32025。</li>
 * </ul>
 *
 * @author chyuan
 */
@Slf4j
@Service
public class GenerationGuardPipeline {

    private final AnnotationReplyService annotationReplyService;

    @Value("${generation.annotation.enabled:false}")
    private boolean annotationEnabled;

    @Value("${generation.injection.enabled:false}")
    private boolean injectionEnabled;

    @Value("${generation.injection.threshold:70}")
    private int injectionThreshold;

    @Value("${generation.guard.enabled:false}")
    private boolean guardEnabled;

    @Value("${generation.guard.sensitive-words:}")
    private String sensitiveWordsCsv;

    @Value("${generation.structured-output.enabled:false}")
    private boolean structuredOutputEnabled;

    /** 注入拦截计数（观测） */
    private final AtomicLong injectionBlockedTotal = new AtomicLong();

    /** 标注回复命中计数（观测） */
    private final AtomicLong annotationReplyHitTotal = new AtomicLong();

    /** 护栏脱敏命中计数（观测） */
    private final AtomicLong guardMaskedTotal = new AtomicLong();

    public GenerationGuardPipeline(AnnotationReplyService annotationReplyService) {
        this.annotationReplyService = annotationReplyService;
    }

    /** AA7：命中标注问答即构造直接回复（未命中/未启用返回 null，调用方继续走模型） */
    public String annotationReplyOrNull(com.alibaba.fastjson.JSONObject request) {
        if (!annotationEnabled || request == null) {
            return null;
        }
        String question = lastUserQuestion(request);
        if (StringUtils.isBlank(question)) {
            return null;
        }
        AnnotationHit hit = annotationReplyService.find(question);
        if (hit == null) {
            return null;
        }
        annotationReplyService.recordHit(hit);
        annotationReplyHitTotal.incrementAndGet();
        com.alibaba.fastjson.JSONObject response = new com.alibaba.fastjson.JSONObject();
        response.put("id", "gateway-annotation-reply");
        response.put("object", "chat.completion");
        com.alibaba.fastjson.JSONArray choices = new com.alibaba.fastjson.JSONArray();
        com.alibaba.fastjson.JSONObject choice = new com.alibaba.fastjson.JSONObject();
        choice.put("index", 0);
        com.alibaba.fastjson.JSONObject message = new com.alibaba.fastjson.JSONObject();
        message.put("role", "assistant");
        message.put("content", hit.answer());
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        choices.add(choice);
        response.put("choices", choices);
        response.put("gateway_annotation_reply", true);
        response.put("gateway_annotation_fuzzy", hit.fuzzy());
        log.info("标注回复命中: fuzzy={} qaId={}", hit.fuzzy(), hit.qaId());
        return response.toJSONString();
    }

    /** AA6：注入检测，超阈 -32024 拒绝（未启用为 no-op） */
    public void assertNoInjection(com.alibaba.fastjson.JSONObject request) {
        if (!injectionEnabled || request == null) {
            return;
        }
        InjectionDetector.DetectionResult result =
                InjectionDetector.detect(InjectionDetector.textOfUserMessages(request), injectionThreshold);
        if (result.blocked()) {
            injectionBlockedTotal.incrementAndGet();
            throw new AppException(McpErrorCodes.PROMPT_INJECTION_BLOCKED,
                    "疑似提示注入（评分 " + result.score() + "，命中 " + result.categoryHits().keySet() + "）");
        }
    }

    /** AA5：进站脱敏请求 messages（就地改写；未启用为 no-op）。返回命中次数 */
    public int maskInbound(com.alibaba.fastjson.JSONObject request) {
        if (!guardEnabled || request == null) {
            return 0;
        }
        int masked = ContentGuardrail.sanitizeChatMessages(request, sensitiveWords());
        if (masked > 0) {
            guardMaskedTotal.addAndGet(masked);
        }
        return masked;
    }

    /** AA5+AA4：出站脱敏响应文本 + 结构化输出守护（未启用原样返回） */
    public String outbound(com.alibaba.fastjson.JSONObject request, String response) {
        if (response == null) {
            return null;
        }
        com.alibaba.fastjson.JSONObject parsed;
        try {
            parsed = com.alibaba.fastjson.JSON.parseObject(response);
        } catch (Exception e) {
            return response;
        }
        if (parsed == null) {
            return response;
        }
        boolean changed = false;
        // AA5 出站脱敏（choices[*].message.content）
        if (guardEnabled && parsed.get("choices") instanceof List<?> choices) {
            for (Object item : choices) {
                if (item instanceof com.alibaba.fastjson.JSONObject choice
                        && choice.getJSONObject("message") != null
                        && choice.getJSONObject("message").get("content") instanceof String content) {
                    ContentGuardrail.SanitizeResult result = ContentGuardrail.sanitize(content, sensitiveWords());
                    if (result.totalHits() > 0) {
                        choice.getJSONObject("message").put("content", result.text());
                        guardMaskedTotal.addAndGet(result.totalHits());
                        changed = true;
                    }
                }
            }
        }
        // AA4 结构化输出守护
        if (structuredOutputEnabled) {
            String schemaError = enforceStructuredOutput(request, parsed);
            if (schemaError != null) {
                throw new AppException(McpErrorCodes.STRUCTURED_OUTPUT_INVALID, schemaError);
            }
        }
        return changed ? parsed.toJSONString() : response;
    }

    /** 结构化校验：response_format 声明 JSON 时校验 choices[0].message.content；失败返回错误消息（null=通过） */
    private String enforceStructuredOutput(com.alibaba.fastjson.JSONObject request,
            com.alibaba.fastjson.JSONObject response) {
        com.alibaba.fastjson.JSONObject format = request == null ? null : request.getJSONObject("response_format");
        if (format == null) {
            return null;
        }
        String type = format.getString("type");
        if (!"json_schema".equals(type) && !"json_object".equals(type)) {
            return null;
        }
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        com.alibaba.fastjson.JSONObject first = (com.alibaba.fastjson.JSONObject) choices.get(0);
        com.alibaba.fastjson.JSONObject message = first.getJSONObject("message");
        if (message == null || !(message.get("content") instanceof String content)) {
            return null;
        }
        StructuredOutputGuard.JsonSchemaLite schema = null;
        if ("json_schema".equals(type) && format.getJSONObject("json_schema") != null
                && format.getJSONObject("json_schema").getString("schema") != null) {
            schema = StructuredOutputGuard.parseSchema(
                    format.getJSONObject("json_schema").getString("schema"));
        }
        StructuredOutputGuard.GuardResult result =
                StructuredOutputGuard.enforce(content, schema, null);
        return result.valid() ? null
                : "结构化输出校验失败: " + result.error() + "（原内容前 200 字: "
                        + StringUtils.abbreviate(content, 200) + "）";
    }

    private static String lastUserQuestion(com.alibaba.fastjson.JSONObject request) {
        if (!(request.get("messages") instanceof List<?> messages)) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object item = messages.get(i);
            if (item instanceof com.alibaba.fastjson.JSONObject message
                    && "user".equalsIgnoreCase(message.getString("role"))
                    && message.get("content") instanceof String content) {
                return content;
            }
        }
        return null;
    }

    private List<String> sensitiveWords() {
        if (StringUtils.isBlank(sensitiveWordsCsv)) {
            return List.of();
        }
        return java.util.Arrays.stream(sensitiveWordsCsv.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 观测快照（治理台可接） */
    public Map<String, Long> stats() {
        return Map.of(
                "injectionBlockedTotal", injectionBlockedTotal.get(),
                "annotationReplyHitTotal", annotationReplyHitTotal.get(),
                "guardMaskedTotal", guardMaskedTotal.get());
    }
}
