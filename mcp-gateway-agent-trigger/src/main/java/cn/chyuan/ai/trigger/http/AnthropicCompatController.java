package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IBudgetService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.llmchannel.service.LlmChatService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic Messages 兼容前端（工单 0102，new-api/agentgateway 路线）
 *
 * <p>POST /v1/messages：Anthropic 契约 ↔ OpenAI 内部链路双向翻译（system 顶层字段、
 * content blocks、stop_reason/usage 字段映射；tool_use 块记雾项仅透传 text）。
 * 流式：OpenAI SSE chunk → Anthropic 事件序（message_start/content_block_start/
 * content_block_delta/content_block_stop/message_delta/message_stop）。
 * 治理链全复用 LlmChatService（CEL/调度/护栏/配额/预算/账本）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
public class AnthropicCompatController {

    private final LlmChatService llmChatService;
    private final IQuotaService quotaService;
    private final IBudgetService budgetService;

    public AnthropicCompatController(LlmChatService llmChatService,
            org.springframework.beans.factory.ObjectProvider<IQuotaService> quotaServiceProvider,
            org.springframework.beans.factory.ObjectProvider<IBudgetService> budgetServiceProvider) {
        this.llmChatService = llmChatService;
        this.quotaService = quotaServiceProvider.getIfAvailable();
        this.budgetService = budgetServiceProvider.getIfAvailable();
    }

    @PostMapping("/v1/messages")
    public void messages(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String body) throws Exception {
        GovernancePrincipal principal = principalOf(request);
        admit(principal, response);
        JSONObject anthropic = parse(body);
        String model = anthropic.getString("model");
        boolean stream = Boolean.TRUE.equals(anthicStream(anthropic));
        JSONObject openaiRequest = toOpenAiRequest(anthropic, stream);
        if (stream) {
            handleStreaming(principal, model, openaiRequest.toJSONString(), response);
            return;
        }
        try {
            String openaiResponse = llmChatService.chatCompletion(principal, openaiRequest.toJSONString());
            writeCostHeader(response);
            response.setContentType("application/json");
            response.getWriter().write(toAnthropicResponse(openaiResponse, anthropic, model));
            response.getWriter().flush();
        } catch (AppException e) {
            writeAnthropicError(response, httpStatusOf(e), e.getInfo());
        }
    }

    // ---- 请求翻译：Anthropic → OpenAI ----

    static JSONObject toOpenAiRequest(JSONObject anthropic, boolean stream) {
        JSONObject openai = new JSONObject();
        openai.put("model", anthropic.getString("model"));
        if (stream) {
            openai.put("stream", true);
        }
        JSONArray messages = new JSONArray();
        String system = anthropic.getString("system");
        if (StringUtils.isNotBlank(system)) {
            messages.add(message("system", system));
        }
        JSONArray rawMessages = anthropic.getJSONArray("messages");
        if (rawMessages != null) {
            for (int i = 0; i < rawMessages.size(); i++) {
                JSONObject m = rawMessages.getJSONObject(i);
                messages.add(message(m.getString("role"), textOf(m.get("content"))));
            }
        }
        openai.put("messages", messages);
        if (anthicMaxTokens(anthropic) != null) {
            openai.put("max_tokens", anthicMaxTokens(anthropic));
        }
        if (anthropic.get("temperature") != null) {
            openai.put("temperature", anthropic.get("temperature"));
        }
        if (anthropic.get("top_p") != null) {
            openai.put("top_p", anthropic.get("top_p"));
        }
        return openai;
    }

    private static Integer anthicMaxTokens(JSONObject anthropic) {
        // Anthropic max_tokens 必填；OpenAI 面可选透传
        return anthropic.getInteger("max_tokens");
    }

    private static Boolean anthicStream(JSONObject anthropic) {
        return anthropic.getBoolean("stream");
    }

    private static JSONObject message(String role, String content) {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** content 字符串或 blocks 数组 → 纯文本拼接（tool_use 等非 text 块跳过——雾项） */
    static String textOf(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof JSONArray blocks) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                JSONObject block = blocks.getJSONObject(i);
                if (block != null && "text".equals(block.getString("type"))
                        && block.getString("text") != null) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(block.getString("text"));
                }
            }
            return text.toString();
        }
        return "";
    }

    // ---- 响应翻译：OpenAI → Anthropic ----

    static String toAnthropicResponse(String openaiResponse, JSONObject anthropicRequest, String model) {
        JSONObject openai = JSON.parseObject(openaiResponse);
        JSONObject choice = openai.getJSONArray("choices") == null
                || openai.getJSONArray("choices").isEmpty()
                ? new JSONObject() : openai.getJSONArray("choices").getJSONObject(0);
        JSONObject message = choice.getJSONObject("message") == null
                ? new JSONObject() : choice.getJSONObject("message");
        String text = message.getString("content") == null ? "" : message.getString("content");
        String finishReason = choice.getString("finish_reason");

        JSONObject usage = openai.getJSONObject("usage") == null ? new JSONObject() : openai.getJSONObject("usage");

        JSONObject out = new JSONObject(true);
        out.put("id", openai.getString("id") == null ? "msg_gateway" : "msg_" + openai.getString("id"));
        out.put("type", "message");
        out.put("role", "assistant");
        JSONArray content = new JSONArray();
        JSONObject textBlock = new JSONObject(true);
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
        out.put("content", content);
        out.put("model", model);
        out.put("stop_reason", stopReasonOf(finishReason));
        out.put("stop_sequence", null);
        JSONObject anthropicUsage = new JSONObject(true);
        anthropicUsage.put("input_tokens", usage.get("prompt_tokens") == null ? 0 : usage.getIntValue("prompt_tokens"));
        anthropicUsage.put("output_tokens", usage.get("completion_tokens") == null ? 0 : usage.getIntValue("completion_tokens"));
        out.put("usage", anthropicUsage);
        return out.toJSONString();
    }

    /** finish_reason → stop_reason 映射（Anthropic 枚举） */
    static String stopReasonOf(String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "length" -> "max_tokens";
            case "stop_sequence" -> "stop_sequence";
            default -> "end_turn";
        };
    }

    // ---- 流式：OpenAI chunk → Anthropic 事件序 ----

    private void handleStreaming(GovernancePrincipal principal, String model,
            String openaiBody, HttpServletResponse response) throws Exception {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        AnthropicSseTranslator translator = new AnthropicSseTranslator(response, model);
        try {
            llmChatService.chatCompletionStream(principal, openaiBody, translator::onLine);
            translator.finish();
            llmChatService.consumeLastCost();
        } catch (AppException e) {
            // 出首字节前的治理拒绝（护栏/配额/预算）
            writeAnthropicError(response, httpStatusOf(e), e.getInfo());
        }
    }

    /** OpenAI SSE 行 → Anthropic 事件状态机（单请求实例，非线程安全；首字节前完成治理判定——0064 口径） */
    static final class AnthropicSseTranslator {
        private final HttpServletResponse response;
        private final String model;
        private boolean started;
        private boolean finished;
        private long inputTokens;
        private long outputTokens;
        private String finishReason;

        AnthropicSseTranslator(HttpServletResponse response, String model) {
            this.response = response;
            this.model = model;
        }

        void onLine(String line) {
            String payload = line.trim();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            JSONObject chunk;
            try {
                chunk = JSON.parseObject(payload);
            } catch (Exception e) {
                return;
            }
            if (!started) {
                started = true;
                JSONObject message = new JSONObject(true);
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("model", model);
                message.put("content", new JSONArray());
                JSONObject startData = new JSONObject(true);
                startData.put("type", "message_start");
                JSONObject startMessage = new JSONObject(true);
                startMessage.put("type", "message_start");
                startMessage.put("message", message);
                JSONObject startUsage = new JSONObject(true);
                startUsage.put("output_tokens", 0);
                message.put("usage", startUsage);
                write(sse("message_start", startData.toJSONString()));
                write(sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            }
            JSONArray choices = chunk.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);
                JSONObject delta = choice.getJSONObject("delta");
                if (delta != null && delta.getString("content") != null
                        && !delta.getString("content").isEmpty()) {
                    JSONObject event = new JSONObject(true);
                    event.put("type", "content_block_delta");
                    event.put("index", 0);
                    JSONObject textDelta = new JSONObject(true);
                    textDelta.put("type", "text_delta");
                    textDelta.put("text", delta.getString("content"));
                    event.put("delta", textDelta);
                    outputTokens += delta.getString("content").length();
                    write(sse("content_block_delta", event.toJSONString()));
                }
                if (choice.getString("finish_reason") != null) {
                    finishReason = choice.getString("finish_reason");
                }
            }
            JSONObject usage = chunk.getJSONObject("usage");
            if (usage != null) {
                if (usage.getLong("prompt_tokens") != null) {
                    inputTokens = usage.getLong("prompt_tokens");
                }
                if (usage.getLong("completion_tokens") != null) {
                    outputTokens = usage.getLong("completion_tokens");
                }
            }
        }

        /** 收尾：content_block_stop + message_delta（stop_reason/usage）+ message_stop */
        void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (!started) {
                // 上游零行（异常中断已由异常路径处理）；此处输出最小合法序列
                write(sse("message_start", "{\"type\":\"message_start\",\"message\":{\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"" + model + "\",\"content\":[],"
                        + "\"usage\":{\"output_tokens\":0}}}"));
                write(sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            }
            write(sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
            JSONObject deltaData = new JSONObject(true);
            deltaData.put("type", "message_delta");
            JSONObject delta = new JSONObject(true);
            delta.put("stop_reason", stopReasonOf(finishReason));
            delta.put("stop_sequence", null);
            deltaData.put("delta", delta);
            JSONObject usageOut = new JSONObject(true);
            usageOut.put("output_tokens", outputTokens);
            deltaData.put("usage", usageOut);
            write(sse("message_delta", deltaData.toJSONString()));
            write(sse("message_stop", "{\"type\":\"message_stop\"}"));
        }

        private void write(String payload) {
            try {
                response.getWriter().write(payload);
                response.getWriter().flush();
            } catch (Exception e) {
                throw new RuntimeException("客户端断开：上游取消传播", e);
            }
        }

        static String sse(String event, String data) {
            return "event: " + event + "\ndata: " + data + "\n\n";
        }
    }

    // ---- 通用 ----

    private void admit(GovernancePrincipal principal, HttpServletResponse response) throws Exception {
        if (principal == null) {
            throw new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少认证主体");
        }
        if (quotaService != null) {
            IQuotaService.QuotaVerdict verdict = quotaService.checkAndConsume(
                    LlmChatService.TRAFFIC_LLM_GATEWAY, principal);
            if (!verdict.allowed()) {
                throw new AppException(McpErrorCodes.QUOTA_EXCEEDED,
                        "超出配额限制：剩余 " + verdict.remaining() + "，请 " + verdict.retryAfterSeconds() + " 秒后重试");
            }
            IQuotaService.QuotaVerdict tpm = quotaService.admitTokens(LlmChatService.TRAFFIC_LLM_GATEWAY, principal);
            if (!tpm.allowed()) {
                response.setHeader("Retry-After", String.valueOf(tpm.retryAfterSeconds()));
                throw new AppException(McpErrorCodes.QUOTA_EXCEEDED,
                        "超出每分钟 token 限额（TPM），请 " + tpm.retryAfterSeconds() + " 秒后重试");
            }
        }
        if (budgetService != null) {
            IBudgetService.BudgetVerdict budget = budgetService.admit(principal);
            if (!budget.allowed()) {
                throw new AppException(McpErrorCodes.BUDGET_EXCEEDED,
                        "预算耗尽：" + budget.used() + "/" + budget.hard());
            }
            if (budget.softWarning()) {
                response.setHeader("X-Budget-Warning",
                        "soft budget crossed: " + budget.used() + "/" + budget.hard());
            }
            IBudgetService.CostVerdict cost = budgetService.admitCost(principal);
            if (!cost.allowed()) {
                throw new AppException(McpErrorCodes.COST_LIMIT_EXCEEDED,
                        "金额预算耗尽：" + cost.usedCost().toPlainString() + "/" + cost.hard().toPlainString());
            }
        }
    }

    private void writeCostHeader(HttpServletResponse response) {
        if (llmChatService.consumeLastCacheHit()) {
            response.setHeader("X-Gateway-Cache", "HIT");
        }
        llmChatService.consumeLastCostWarning();
        llmChatService.consumeLastCost();
    }

    private void writeAnthropicError(HttpServletResponse response, int httpStatus, String message)
            throws Exception {
        response.setStatus(httpStatus);
        response.setContentType("application/json");
        JSONObject error = new JSONObject(true);
        error.put("type", "error");
        JSONObject body = new JSONObject(true);
        body.put("type", httpStatus == 429 ? "rate_limit_error"
                : httpStatus == 401 ? "authentication_error"
                : httpStatus == 403 ? "permission_error"
                : httpStatus == 404 ? "not_found_error" : "invalid_request_error");
        body.put("message", message);
        error.put("error", body);
        response.getWriter().write(error.toJSONString());
        response.getWriter().flush();
    }

    private int httpStatusOf(AppException e) {
        return switch (e.getCode()) {
            case "-32008" -> 401;
            case "-32006" -> 403;
            case "-32003", "-32004" -> 404;
            case "-32009", "-32014", "-32017" -> 429;
            default -> 400;
        };
    }

    private GovernancePrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(GovernancePrincipal.REQUEST_ATTR);
        return principal instanceof GovernancePrincipal governancePrincipal ? governancePrincipal : null;
    }

    private JSONObject parse(String body) {
        try {
            return JSON.parseObject(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "请求体非合法 JSON");
        }
    }
}
