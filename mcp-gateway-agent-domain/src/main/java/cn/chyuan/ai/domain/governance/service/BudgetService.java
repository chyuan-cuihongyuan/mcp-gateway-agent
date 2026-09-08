package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 预算服务（工单 0050，LiteLLM BudgetTable 次数口径裁剪）
 *
 * <p>per-key 周期窗口计数（DB 行态），窗口惰性重置；软线告警（事件+响应头标记）、
 * 硬线阻断。未配置预算的密钥零额外开销。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class BudgetService implements IBudgetService {

    /** 软线越过事件类型（0051 webhook 订阅口径） */
    public static final String EVENT_BUDGET_SOFT_CROSSED = "BUDGET_SOFT_CROSSED";

    @Resource
    private IVirtualKeyRepository repository;

    @Resource
    private IGovernanceEventPublisher eventPublisher;

    @Override
    public BudgetVerdict admit(GovernancePrincipal principal) {
        if (principal == null || principal.getVirtualKeyId() == null
                || principal.getBudgetHard() == null || principal.getBudgetHard() <= 0) {
            return BudgetVerdict.passthrough();
        }

        VirtualKeyVO key = repository.findById(principal.getVirtualKeyId());
        if (key == null || key.getBudgetHard() == null) {
            return BudgetVerdict.passthrough();
        }

        long used = key.getBudgetUsed() == null ? 0 : key.getBudgetUsed();
        // 生效硬线 = max(原硬线, 未过期临时提额)（工单 0052 惰性回落）
        long hard = effectiveHard(key);
        // 窗口惰性重置（请求时点发现过期即清零顺延）
        if (key.getBudgetResetAt() != null && new Date().after(key.getBudgetResetAt())) {
            try {
                repository.resetBudgetWindow(key.getId());
                used = 0;
                log.info("预算窗口已惰性重置 keyId={} durationHours={}", key.getId(), key.getBudgetDurationHours());
            } catch (Exception e) {
                log.warn("预算窗口重置失败（沿用旧计数）keyId={}：{}", key.getId(), e.getMessage());
            }
        }

        long next = used + 1;
        if (next > hard) {
            return new BudgetVerdict(false, false, used, hard);
        }

        try {
            repository.incrementBudgetUsed(key.getId());
        } catch (Exception e) {
            log.warn("预算计数失败（放行不计数）keyId={}：{}", key.getId(), e.getMessage());
        }

        boolean softWarning = key.getBudgetSoft() != null && key.getBudgetSoft() > 0 && next >= key.getBudgetSoft();
        if (softWarning) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("virtualKeyId", key.getId());
            payload.put("keyName", key.getKeyName());
            payload.put("used", next);
            payload.put("soft", key.getBudgetSoft());
            payload.put("hard", key.getBudgetHard());
            payload.put("resetAt", key.getBudgetResetAt() == null ? null : key.getBudgetResetAt().getTime());
            try {
                eventPublisher.publish(EVENT_BUDGET_SOFT_CROSSED, payload);
            } catch (Exception e) {
                log.warn("预算软线事件发布失败 keyId={}：{}", key.getId(), e.getMessage());
            }
        }
        return new BudgetVerdict(true, softWarning, next, hard);
    }

    /** 生效硬线：临时提额未过期时取较大值（工单 0052） */
    static long effectiveHard(VirtualKeyVO key) {
        long base = key.getBudgetHard() == null ? 0 : key.getBudgetHard();
        if (key.getTempBudgetHard() != null && key.getTempBudgetHard() > base
                && key.getTempBudgetExpires() != null && new Date().before(key.getTempBudgetExpires())) {
            return key.getTempBudgetHard();
        }
        return base;
    }
}
