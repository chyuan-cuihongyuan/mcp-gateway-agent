package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyEngine.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 策略执行挂点（工单 0267 AH8）：vk 校验通过后、模型调用前挂策略引擎——
 * sub=key 身份 / obj=请求模型或路径 / act=动作；DENY → -32027 拒绝。
 * {@code policy.engine.enabled} 默认 false 零行为变化；挂点接决策缓存（引擎内）与决策日志。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class PolicyEnforceService {

    /** 策略拒绝错误码段（沿用 -32xxx 网关口径） */
    public static final int ERR_POLICY_DENIED = -32027;

    /** 执行结果 */
    public record EnforceResult(boolean allowed, int errorCode, Decision decision) {

        public static EnforceResult allow(Decision decision) {
            return new EnforceResult(true, 0, decision);
        }

        public static EnforceResult deny(Decision decision) {
            return new EnforceResult(false, ERR_POLICY_DENIED, decision);
        }
    }

    private final PolicyEngine engine;
    private final DecisionLogRecorder decisionLog;

    @Value("${policy.engine.enabled:false}")
    private boolean enabled;

    public PolicyEnforceService(PolicyEngine engine, DecisionLogRecorder decisionLog) {
        this.engine = engine;
        this.decisionLog = decisionLog;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 执行鉴权：开关关闭时直接放行（不评估不落日志，零行为变化）。
     * open 决策路径同时写决策日志。
     */
    public EnforceResult enforce(String keyIdentity, String object, String action,
            Map<String, Object> envContext) {
        if (!enabled) {
            return EnforceResult.allow(new Decision(PolicyEngine.EFFECT_ALLOW, List.of(), List.of(), false));
        }
        long start = System.currentTimeMillis();
        Decision decision = engine.evaluate(keyIdentity, object, action, envContext);
        long costMs = System.currentTimeMillis() - start;
        decisionLog.record(System.currentTimeMillis(), keyIdentity, object, action, decision, costMs);
        if (decision.allowed()) {
            return EnforceResult.allow(decision);
        }
        log.info("策略拒绝: key={} object={} action={} hits={}", keyIdentity, object, action,
                decision.hitStatementNames());
        return EnforceResult.deny(decision);
    }
}
