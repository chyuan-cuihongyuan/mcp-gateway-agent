package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

/**
 * 配额限流服务端口（工单 0019）
 *
 * @author chyuan
 */
public interface IQuotaService {

    /**
     * 检查并消费 1 次请求配额（RPM + 日请求，多带宽同桶原子判定）。
     *
     * <p>适用主体：仅 VIRTUAL_KEY；JWT/匿名/无主体路径不限额。
     * 限额为 null（不限）的密钥直接放行。
     *
     * @param gatewayId 网关 ID（指标标签）
     * @param principal 认证主体
     * @return 判定结果（拒绝时含剩余额度与重试提示）
     * @throws cn.chyuan.ai.types.exception.AppException QUOTA_SERVICE_UNAVAILABLE —— 后端（Redis）故障，
     *         按 0011 决议 fail-closed 拒绝，不降级放行
     */
    QuotaVerdict checkAndConsume(String gatewayId, GovernancePrincipal principal);

    /**
     * TPM 准入（工单 0065）：token 粒度窗口余量检查（不消费——真实 token 数在响应后计量）。
     * 未配置 TPM 的密钥直通；窗口耗尽返回拒绝（429 语义同 -32009，文案区分 TPM）。
     */
    QuotaVerdict admitTokens(String gatewayId, GovernancePrincipal principal);

    /**
     * TPM 计量（工单 0065）：响应 usage 已知后向窗口累加 token 数。
     * 计量失败仅告警（响应已完成，不回滚不阻断）；超额部分窗口内自然拒绝后续请求。
     */
    void consumeTokens(String gatewayId, GovernancePrincipal principal, long tokens);

    /**
     * 配额判定结果
     *
     * @param allowed           是否放行
     * @param remaining         剩余额度（多带宽最小值；拒绝时为瓶颈带宽剩余）
     * @param retryAfterSeconds 重试等待秒数（拒绝时 >0；放行为 0）
     * @param limited           是否受限额管辖（false = 无限/不适用主体）
     */
    record QuotaVerdict(boolean allowed, boolean limited, long remaining, long retryAfterSeconds) {

        public static QuotaVerdict notLimited() {
            return new QuotaVerdict(true, false, -1, 0);
        }

        public static QuotaVerdict allowed(long remaining) {
            return new QuotaVerdict(true, true, remaining, 0);
        }

        public static QuotaVerdict denied(long remaining, long retryAfterSeconds) {
            return new QuotaVerdict(false, true, remaining, retryAfterSeconds);
        }
    }
}
