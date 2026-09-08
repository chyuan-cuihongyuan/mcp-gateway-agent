package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import dev.cel.runtime.CelRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * CEL 工具治理求值服务（工单 0018 / 0011 决议）
 *
 * <p>生效点两处（0015 决策 6）：tools/list 结果过滤（隐藏）与
 * tools/call 拦截（结构化拒绝）。规则求值异常 fail-closed（拒绝）。
 * 拦截计数：governance.cel.denied{gateway, method}。
 *
 * <p>key.quota.daily_*_remaining 在 0019 落地前按 0011 约定暴露 -1（未知）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class CelEvaluationService implements ICelEvaluationService {

    /** 配额剩余未知占位（0011 约定 NULL→-1；0019 接入真实计数） */
    static final int QUOTA_UNKNOWN = -1;

    /** 工具来源：数据库协议映射配置的工具（LOCAL 由 0022 引入） */
    public static final String TOOL_SOURCE_PROTOCOL = "PROTOCOL";

    /** 工具来源：外部 MCP 挂接透传的工具（工单 0021，CEL 变量 mcp.tool.source=EXTERNAL） */
    public static final String TOOL_SOURCE_EXTERNAL = "EXTERNAL";

    /** CEL 程序求值适配（cel-java 运行时 API 唯一触点；Program 不可变线程安全，可重复求值） */
    @FunctionalInterface
    interface ProgramRunner {
        Object run(Map<String, Object> variables) throws Exception;
    }

    @Resource
    private ICelRuleService celRuleService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Override
    public boolean isToolAllowed(GovernancePrincipal principal, String gatewayId, String method,
            String toolName, String toolSource) {
        if (principal == null) {
            // 无统一认证上下文（遗留直连/单测路径，与 0017 VerifyNode 兜底约定一致）
            return true;
        }

        List<CompiledCelRule> rules = applicableRules(principal, gatewayId);
        if (rules.isEmpty()) {
            return true;
        }

        Map<String, Object> variables = buildVariables(principal, gatewayId, method, toolName, toolSource);
        for (CompiledCelRule rule : rules) {
            if (!rulePasses(rule, variables)) {
                countDenial(gatewayId, method);
                log.info("CEL 治理拦截 gateway={} method={} tool={} rule={}({}) auth={}",
                        gatewayId, method, toolName, rule.ruleName(), rule.id(), principal.getAuthType());
                return false;
            }
        }
        return true;
    }

    private boolean rulePasses(CompiledCelRule rule, Map<String, Object> variables) {
        try {
            if (rule.program() == null) {
                // 快照构建时编译失败的规则：fail-closed
                return false;
            }
            Object result = runnerOf(rule.program()).run(variables);
            if (result instanceof Boolean b) {
                return b;
            }
            // 表达式结果非 bool：视为规则故障，fail-closed
            log.warn("CEL 规则结果非布尔（按拒绝处理）ruleId={} name={} result={}",
                    rule.id(), rule.ruleName(), result);
            return false;
        } catch (Exception e) {
            // 求值异常（变量缺失/运行时故障）：fail-closed（0011 决议）
            log.warn("CEL 规则求值异常（按拒绝处理）ruleId={} name={}: {}",
                    rule.id(), rule.ruleName(), e.getMessage());
            return false;
        }
    }

    private static ProgramRunner runnerOf(CelRuntime.Program program) {
        return program::eval;
    }

    /** 三层作用域取适用规则：GLOBAL + 本 GATEWAY + 本 VIRTUAL_KEY */
    private List<CompiledCelRule> applicableRules(GovernancePrincipal principal, String gatewayId) {
        Long keyId = principal.getVirtualKeyId();
        return celRuleService.activeRuleSnapshot().stream()
                .filter(rule -> {
                    switch (rule.scopeType()) {
                        case "GLOBAL":
                            return true;
                        case "GATEWAY":
                            return gatewayId != null && gatewayId.equals(rule.gatewayId());
                        case "VIRTUAL_KEY":
                            return keyId != null && keyId.equals(rule.virtualKeyId());
                        default:
                            return false;
                    }
                })
                .toList();
    }

    /** 0011 CEL 变量面绑定 + 0048 扩展（jwt.* / client.ip / mcp.tool.target） */
    private Map<String, Object> buildVariables(GovernancePrincipal principal, String gatewayId,
            String method, String toolName, String toolSource) {
        return CelVariables.of(principal, gatewayId, method, toolName, toolSource);
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private void countDenial(String gatewayId, String method) {
        if (meterRegistry != null) {
            meterRegistry.counter("governance.cel.denied",
                    "gateway", orEmpty(gatewayId), "method", orEmpty(method)).increment();
        }
    }
}
