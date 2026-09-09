package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

/**
 * 预算服务端口（工单 0050）
 *
 * @author chyuan
 */
public interface IBudgetService {

    /**
     * 预算准入（次数口径）：放行即计数 +1。
     * <ul>
     *   <li>未启用预算（非 vk 主体或 hard 未配）→ 恒放行、零开销；</li>
     *   <li>窗口过期 → 惰性重置（used=0、reset_at 顺延一个窗口）；</li>
     *   <li>used+1 &gt; hard → 拒绝（-32014 语义由过滤器落 HTTP 429）；</li>
     *   <li>used+1 ≥ soft → 放行 + 软线告警标记（过滤器加 X-Budget-Warning 头 + 事件发布）。</li>
     * </ul>
     */
    BudgetVerdict admit(GovernancePrincipal principal);

    /**
     * 预算判决：allowed / softWarning / used / hard。
     */
    record BudgetVerdict(boolean allowed, boolean softWarning, long used, long hard) {

        public static BudgetVerdict passthrough() {
            return new BudgetVerdict(true, false, 0, 0);
        }
    }

    /**
     * 金额预算准入（工单 0087，LLM 面）：窗口内已用金额 ≥ 硬线 → 拒绝（-32017）。
     * 已用金额从账本派生（SUM(cost)，窗口与次数预算共振——共用 duration/reset_at 惰性重置）。
     * 未配置 costHardLimit 的密钥恒放行。账本异步落账的短暂滞后可接受（文档口径）。
     */
    CostVerdict admitCost(GovernancePrincipal principal);

    /**
     * 金额上报（工单 0087，响应后置）：累计越过软线 → COST_SOFT_LIMIT 事件（每窗口一次即可，
     * 此处按次发布由 webhook 端去重口径消化）+ 返回告警标记（响应头消费）。
     */
    boolean reportCost(GovernancePrincipal principal, java.math.BigDecimal cost);

    /** 金额预算判决 */
    record CostVerdict(boolean allowed, java.math.BigDecimal usedCost, java.math.BigDecimal hard) {

        public static CostVerdict passthrough() {
            return new CostVerdict(true, java.math.BigDecimal.ZERO, null);
        }
    }
}
