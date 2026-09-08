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
}
