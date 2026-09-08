package cn.chyuan.ai.domain.governance.adapter;

import java.util.Map;

/**
 * 治理事件发布端口（工单 0050 引入）
 *
 * <p>预算软线、密钥自动禁用、渠道故障等治理事件的出站投递抽象；
 * 默认无操作实现（本票），0051 webhook 投递接管。发布失败不得影响主链。
 *
 * @author chyuan
 */
public interface IGovernanceEventPublisher {

    /**
     * 发布治理事件（尽力而为语义）。
     *
     * @param type    事件类型（如 BUDGET_SOFT_CROSSED / KEY_AUTO_DISABLED / CHANNEL_AUTO_DISABLED / CIRCUIT_OPEN）
     * @param payload 事件载荷（键值对，须避免明文凭证）
     */
    void publish(String type, Map<String, Object> payload);
}
