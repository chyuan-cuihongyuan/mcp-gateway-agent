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

    /** 金额软线越过事件（工单 0087，webhook 可订阅） */
    public static final String EVENT_COST_SOFT_CROSSED = "COST_SOFT_CROSSED";

    @Resource
    private IVirtualKeyRepository repository;

    @Resource
    private IGovernanceEventPublisher eventPublisher;

    /** 账本端口（工单 0087：已用金额窗口派生；切片测试可缺省） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository> usageRepositoryProvider;

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

        // 滚动窗口（工单 0158）：窗口类型已配置 → 按时间戳回溯的滑动窗口派生已用次数，
        // 硬线拒绝（-32014 段）与软线事件口径不变；无固定窗口惰性重置，额度不可被重置套利。
        if (QuotaWindows.isSliding(key.getBudgetWindowType())) {
            return slidingAdmit(key);
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

    /**
     * 滚动窗口准入（工单 0158）：已用次数 = 账本 countSince（窗口起点含边界 created_at >= since），
     * 不落 budget_used 列、不做惰性重置；窗口内 token 合计随软线事件上报（sumTokensSince）。
     */
    private BudgetVerdict slidingAdmit(VirtualKeyVO key) {
        cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository usageRepository =
                usageRepositoryProvider == null ? null : usageRepositoryProvider.getIfAvailable();
        if (usageRepository == null) {
            // 账本缺席（切片上下文）：退化为旧行为口径，不阻断
            log.debug("滚动窗口账本缺席，退化为固定窗口计数 keyId={}", key.getId());
        }
        Date now = new Date();
        Date windowStart = QuotaWindows.startOf(now, key.getBudgetWindowType());
        long used = 0;
        long windowTokens = 0;
        if (usageRepository != null) {
            used = usageRepository.countSince(key.getId(), windowStart);
            windowTokens = usageRepository.sumTokensSince(key.getId(), windowStart);
        }
        long hard = effectiveHard(key);
        long next = used + 1;
        if (next > hard) {
            return new BudgetVerdict(false, false, used, hard);
        }
        boolean softWarning = key.getBudgetSoft() != null && key.getBudgetSoft() > 0 && next >= key.getBudgetSoft();
        if (softWarning) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("virtualKeyId", key.getId());
            payload.put("keyName", key.getKeyName());
            payload.put("used", next);
            payload.put("soft", key.getBudgetSoft());
            payload.put("hard", key.getBudgetHard());
            payload.put("window", key.getBudgetWindowType());
            payload.put("windowStart", windowStart == null ? null : windowStart.getTime());
            payload.put("windowTokens", windowTokens);
            try {
                eventPublisher.publish(EVENT_BUDGET_SOFT_CROSSED, payload);
            } catch (Exception e) {
                log.warn("滚动窗口软线事件发布失败 keyId={}：{}", key.getId(), e.getMessage());
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

    // ---- 金额预算（工单 0087） ----

    @Override
    public CostVerdict admitCost(GovernancePrincipal principal) {
        if (principal == null || principal.getVirtualKeyId() == null) {
            return CostVerdict.passthrough();
        }
        VirtualKeyVO key = repository.findById(principal.getVirtualKeyId());
        if (key == null || key.getCostHardLimit() == null
                || key.getCostHardLimit().signum() <= 0) {
            return CostVerdict.passthrough();
        }
        java.math.BigDecimal used = usedCostOf(key);
        return new CostVerdict(used.compareTo(key.getCostHardLimit()) < 0, used, key.getCostHardLimit());
    }

    @Override
    public boolean reportCost(GovernancePrincipal principal, java.math.BigDecimal cost) {
        if (principal == null || principal.getVirtualKeyId() == null
                || cost == null || cost.signum() <= 0) {
            return false;
        }
        try {
            VirtualKeyVO key = repository.findById(principal.getVirtualKeyId());
            if (key == null || key.getCostSoftLimit() == null || key.getCostSoftLimit().signum() <= 0) {
                return false;
            }
            java.math.BigDecimal total = usedCostOf(key).add(cost);
            if (total.compareTo(key.getCostSoftLimit()) < 0) {
                return false;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("virtualKeyId", key.getId());
            payload.put("keyName", key.getKeyName());
            payload.put("usedCost", total);
            payload.put("soft", key.getCostSoftLimit());
            payload.put("hard", key.getCostHardLimit());
            eventPublisher.publish(EVENT_COST_SOFT_CROSSED, payload);
            return true;
        } catch (Exception e) {
            log.warn("金额软线判定失败（不阻断）keyId={}：{}", principal.getVirtualKeyId(), e.getMessage());
            return false;
        }
    }

    /**
     * 窗口内已用金额（账本派生）：滚动窗口类型（工单 0158）→ 起点 = QuotaWindows.startOf(now, 窗口类型)
     * 回溯（DAY=now-24h/WEEK=now-7d/MONTH=自然月回溯，月长不一自动处理）；
     * 未配置窗口类型 → 旧固定窗口口径：窗口起点 = reset_at - duration；reset 过期时先惰性重置
     * （与次数预算共用窗口列——共振语义），起点即当前时刻；未配次数预算（reset_at 为 null）
     * 时按 duration 滚动窗口（默认 30 天——月度成本口径）。
     * 含边界口径：sumCostSince 用 created_at >= 起点（起点记录计入窗口）。
     */
    private java.math.BigDecimal usedCostOf(VirtualKeyVO key) {
        cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository usageRepository =
                usageRepositoryProvider == null ? null : usageRepositoryProvider.getIfAvailable();
        if (usageRepository == null) {
            return java.math.BigDecimal.ZERO;
        }
        Date now = new Date();
        if (QuotaWindows.isSliding(key.getBudgetWindowType())) {
            return usageRepository.sumCostSince(key.getId(), QuotaWindows.startOf(now, key.getBudgetWindowType()));
        }
        long durationMs = (key.getBudgetDurationHours() == null ? 720 : key.getBudgetDurationHours()) * 3600_000L;
        Date windowStart;
        if (key.getBudgetResetAt() != null && now.before(key.getBudgetResetAt())) {
            windowStart = new Date(key.getBudgetResetAt().getTime() - durationMs);
        } else if (key.getBudgetResetAt() != null) {
            try {
                repository.resetBudgetWindow(key.getId());
            } catch (Exception e) {
                log.debug("金额窗口重置失败（沿用旧窗口合计）keyId={}：{}", key.getId(), e.getMessage());
            }
            windowStart = now;
        } else {
            windowStart = new Date(now.getTime() - durationMs);
        }
        return usageRepository.sumCostSince(key.getId(), windowStart);
    }
}
