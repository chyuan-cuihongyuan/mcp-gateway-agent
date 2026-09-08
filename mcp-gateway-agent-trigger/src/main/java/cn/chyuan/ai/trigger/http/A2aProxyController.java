package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IBudgetService;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * A2A 代理面（工单 0066，0009 调研「手写 Spring 透传代理」路线）
 *
 * <p>GET /.well-known/agent.json：取上游 card 并把 url（与 additional_interfaces）
 * 重写为网关公网基址——A2A 客户端把网关当作 agent 端点直接用（发现免认证）。
 * POST /a2a/**：任务端点 JSON-RPC 透传（message/send、message/stream 等），
 * 治理链全量（vk 全局面认证 → CEL(method=a2a 方法、source=A2A) → 配额/预算 → 账本）。
 *
 * <p>上游配置：governance.a2a.upstream-base-url（单上游 MVP，多 agent registry 立雾项）；
 * 重写基址：governance.a2a.public-base-url。
 *
 * @author chyuan
 */
@Slf4j
@RestController
public class A2aProxyController {

    static final String SOURCE_A2A = "A2A";
    static final String TRAFFIC_A2A_GATEWAY = "__a2a__";

    private final ILlmHttpPort httpPort;
    private final ICelEvaluationService celEvaluationService;
    private final IQuotaService quotaService;
    private final IBudgetService budgetService;
    private final IUsageLedgerService usageLedger;

    @Value("${governance.a2a.upstream-base-url:}")
    private String upstreamBaseUrl;

    @Value("${governance.a2a.public-base-url:http://localhost:8099}")
    private String publicBaseUrl;

    public A2aProxyController(ILlmHttpPort httpPort,
            org.springframework.beans.factory.ObjectProvider<ICelEvaluationService> celProvider,
            org.springframework.beans.factory.ObjectProvider<IQuotaService> quotaProvider,
            org.springframework.beans.factory.ObjectProvider<IBudgetService> budgetProvider,
            org.springframework.beans.factory.ObjectProvider<IUsageLedgerService> ledgerProvider) {
        this.httpPort = httpPort;
        this.celEvaluationService = celProvider.getIfAvailable();
        this.quotaService = quotaProvider.getIfAvailable();
        this.budgetService = budgetProvider.getIfAvailable();
        this.usageLedger = ledgerProvider.getIfAvailable();
    }

    /** agent card 代理：透传上游 card 并把服务地址重写为网关（发现面免认证） */
    @GetMapping("/.well-known/agent.json")
    public String agentCard(HttpServletResponse response) throws Exception {
        requireUpstream();
        response.setContentType("application/json");
        String cardJson = httpPort.getJson(upstreamBaseUrl + "/.well-known/agent.json",
                Map.of(), 10_000);
        if (StringUtils.isBlank(cardJson)) {
            throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED, "上游 agent card 为空");
        }
        return rewriteCard(cardJson, publicBaseUrl);
    }

    /** 任务端点透传：/a2a/**（JSON-RPC message/send、message/stream 等） */
    @PostMapping("/a2a/**")
    public void taskEndpoint(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String body) throws Exception {
        requireUpstream();
        GovernancePrincipal principal = principalOf(request);
        if (principal == null) {
            throw new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少认证主体");
        }
        String method = jsonRpcMethodOf(body);
        admit(principal, response);

        if (celEvaluationService != null && !celEvaluationService.isToolAllowed(
                principal, TRAFFIC_A2A_GATEWAY, method, method, SOURCE_A2A)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "无权调用该 A2A 方法: " + method);
        }

        String upstreamPath = StringUtils.defaultString(request.getRequestURI().substring("/a2a".length()));
        String upstreamUrl = StringUtils.removeEnd(upstreamBaseUrl, "/") + upstreamPath;
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        long start = System.currentTimeMillis();
        try {
            int status = httpPort.postJson(upstreamUrl, headers, StringUtils.defaultString(body), 60_000);
            String upstream = httpPort.lastResponseBody();
            int cost = (int) (System.currentTimeMillis() - start);
            boolean ok = status >= 200 && status < 300;
            response.setStatus(status);
            response.setContentType("application/json");
            if (upstream != null) {
                response.getWriter().write(upstream);
                response.getWriter().flush();
            }
            recordUsage(principal, method, ok ? "SUCCESS" : "FAIL", cost);
        } catch (Exception e) {
            recordUsage(principal, method, "FAIL", (int) (System.currentTimeMillis() - start));
            throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED,
                    "上游 agent 不可达: " + e.getMessage());
        }
    }

    private void requireUpstream() {
        if (StringUtils.isBlank(upstreamBaseUrl)) {
            throw new AppException(McpErrorCodes.METHOD_NOT_FOUND,
                    "A2A 代理未配置上游（governance.a2a.upstream-base-url）");
        }
    }

    private void admit(GovernancePrincipal principal, HttpServletResponse response) {
        if (quotaService != null) {
            IQuotaService.QuotaVerdict verdict = quotaService.checkAndConsume(TRAFFIC_A2A_GATEWAY, principal);
            if (!verdict.allowed()) {
                response.setHeader("Retry-After", String.valueOf(verdict.retryAfterSeconds()));
                throw new AppException(McpErrorCodes.QUOTA_EXCEEDED,
                        "超出配额限制：剩余 " + verdict.remaining());
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

    private static String jsonRpcMethodOf(String body) {
        if (StringUtils.isBlank(body)) {
            return "unknown";
        }
        try {
            String method = JSON.parseObject(body).getString("method");
            return StringUtils.isBlank(method) ? "unknown" : method;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void recordUsage(GovernancePrincipal principal, String method, String status, int costMs) {
        if (usageLedger == null) {
            return;
        }
        try {
            usageLedger.record(UsageRecordVO.builder()
                    .virtualKeyId(principal.getVirtualKeyId())
                    .apiKeyHash(principal.getApiKeyHash())
                    .gatewayId(TRAFFIC_A2A_GATEWAY)
                    .trafficType("A2A")
                    .toolOrModel(method)
                    .status(status)
                    .durationMs(costMs)
                    .clientIp(principal.getClientIp())
                    .build());
        } catch (Exception e) {
            log.debug("A2A 用量落账失败 method={}：{}", method, e.getMessage());
        }
    }

    /** card 重写：url 与 additional_interfaces[].url 指向网关基址（工单 0066，agentgateway 口径） */
    static String rewriteCard(String cardJson, String gatewayBaseUrl) {
        try {
            JSONObject card = JSON.parseObject(cardJson);
            if (card.containsKey("url")) {
                card.put("url", StringUtils.removeEnd(gatewayBaseUrl, "/") + "/a2a");
            }
            var interfaces = card.getJSONArray("additional_interfaces");
            if (interfaces != null) {
                for (int i = 0; i < interfaces.size(); i++) {
                    JSONObject face = interfaces.getJSONObject(i);
                    if (face.containsKey("url")) {
                        face.put("url", StringUtils.removeEnd(gatewayBaseUrl, "/") + "/a2a");
                    }
                }
            }
            return card.toJSONString();
        } catch (Exception e) {
            // 上游 card 非 JSON：原样透传（不因重写失败中断发现）
            return cardJson;
        }
    }
}
