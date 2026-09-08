package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IBudgetService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.llmchannel.service.LlmChatService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容前端控制器（工单 0063：/v1/chat/completions 非流式 + /v1/models）
 *
 * <p>认证经 GlobalTrafficAuthFilter（无网关维度）；配额/预算在控制器内施加
 * （流量面标签 __llm__）；CEL/调度/故障转移/账本在 LlmChatService。
 *
 * @author chyuan
 */
@Slf4j
@RestController
public class OpenAiCompatController {

    private final LlmChatService llmChatService;
    private final IQuotaService quotaService;
    private final IBudgetService budgetService;

    public OpenAiCompatController(LlmChatService llmChatService,
            org.springframework.beans.factory.ObjectProvider<IQuotaService> quotaServiceProvider,
            org.springframework.beans.factory.ObjectProvider<IBudgetService> budgetServiceProvider) {
        this.llmChatService = llmChatService;
        this.quotaService = quotaServiceProvider.getIfAvailable();
        this.budgetService = budgetServiceProvider.getIfAvailable();
    }

    @PostMapping(value = "/v1/chat/completions")
    public String chatCompletions(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String body) throws Exception {
        GovernancePrincipal principal = principalOf(request);

        // 准入：配额（RPM/日配额，流量面标签）+ 预算（软线头/硬线阻断）
        admit(principal, response);

        long start = System.currentTimeMillis();
        try {
            String upstream = llmChatService.chatCompletion(principal, body);
            response.setContentType("application/json");
            return upstream;
        } catch (AppException e) {
            int httpStatus = switch (e.getCode()) {
                case "-32008" -> 401;
                case "-32006" -> 403;
                case "-32009", "-32014" -> 429;
                case "-32003", "-32004" -> 404;
                default -> 400;
            };
            response.setStatus(httpStatus);
            response.setContentType("application/json");
            return "{\"error\":{\"message\":" + quote(e.getInfo()) + ",\"type\":\""
                    + (httpStatus == 429 ? "rate_limit_error" : "invalid_request_error") + "\"}}";
        }
    }

    @GetMapping("/v1/models")
    public Map<String, Object> models(HttpServletRequest request) {
        GovernancePrincipal principal = principalOf(request);
        List<String> models = llmChatService.visibleModels(principal);
        List<Map<String, Object>> data = models.stream()
                .map(m -> Map.<String, Object>of("id", m, "object", "model", "owned_by", "mcp-gateway"))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    private void admit(GovernancePrincipal principal, HttpServletResponse response) {
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
        }
    }

    private GovernancePrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(GovernancePrincipal.REQUEST_ATTR);
        return principal instanceof GovernancePrincipal governancePrincipal ? governancePrincipal : null;
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
