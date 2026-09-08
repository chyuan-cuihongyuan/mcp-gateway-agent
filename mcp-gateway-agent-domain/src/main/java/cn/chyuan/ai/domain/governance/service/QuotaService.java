package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static cn.chyuan.ai.domain.governance.service.IQuotaService.QuotaVerdict;

/**
 * 配额限流服务（工单 0019 / 0011 决议①）
 *
 * <p>per-key RPM + 日请求配额，多带宽同桶原子判定；Redis 后端跨实例共享计数。
 * Redis 故障 fail-closed（503 结构化拒绝，不降级放行——配额丢失窗口为零）。
 * 拒绝计数：governance.quota.denied{gateway, key}（key 用密钥 ID 脱敏，非凭证）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class QuotaService implements IQuotaService {

    @Resource
    private IQuotaBucketBackend quotaBucketBackend;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Override
    public QuotaVerdict checkAndConsume(String gatewayId, GovernancePrincipal principal) {
        if (!isPerKeyQuotaApplicable(principal)) {
            return QuotaVerdict.notLimited();
        }
        Integer rpmLimit = principal.getRpmLimit();
        Integer dailyLimit = principal.getDailyRequestLimit();
        if (!isLimited(rpmLimit) && !isLimited(dailyLimit)) {
            // 双带宽均不限（NULL 语义），免桶直通
            return QuotaVerdict.notLimited();
        }

        ConsumptionProbe probe;
        try {
            IQuotaBucketBackend.QuotaBucket bucket = quotaBucketBackend.getBucket(
                    principal.getVirtualKeyId(), rpmLimit, dailyLimit);
            probe = bucket.tryConsume(1);
        } catch (Exception e) {
            // 0011 决策①：Redis 不可用 → fail-closed 拒绝，不降级放行
            log.warn("配额后端不可用（fail-closed 拒绝）gateway:{} keyId:{}: {}",
                    gatewayId, principal.getVirtualKeyId(), e.getMessage());
            throw new AppException(McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE,
                    "配额服务暂不可用，请稍后重试（fail-closed）");
        }

        if (probe.isConsumed()) {
            return QuotaVerdict.allowed(probe.getRemainingTokens());
        }
        long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000);
        countDenial(gatewayId, principal.getVirtualKeyId());
        log.info("配额限流拒绝 gateway:{} keyId:{} remaining:{} retryAfterSeconds:{}",
                gatewayId, principal.getVirtualKeyId(), probe.getRemainingTokens(), retryAfterSeconds);
        return QuotaVerdict.denied(probe.getRemainingTokens(), retryAfterSeconds);
    }

    @Override
    public QuotaVerdict admitTokens(String gatewayId, GovernancePrincipal principal) {
        Integer tpmLimit = principal == null ? null : principal.getTpmLimit();
        if (!isPerKeyQuotaApplicable(principal) || !isLimited(tpmLimit)) {
            return QuotaVerdict.notLimited();
        }
        try {
            IQuotaBucketBackend.QuotaBucket bucket = quotaBucketBackend.getTpmBucket(
                    principal.getVirtualKeyId(), tpmLimit);
            long available = bucket.availableTokens();
            if (available > 0) {
                return QuotaVerdict.allowed(available);
            }
            countDenial(gatewayId, principal.getVirtualKeyId());
            log.info("TPM 限流拒绝 gateway:{} keyId:{} tpmLimit:{}", gatewayId, principal.getVirtualKeyId(), tpmLimit);
            return QuotaVerdict.denied(0, 60);
        } catch (Exception e) {
            // 与请求配额同口径 fail-closed（0011 决议①）
            log.warn("TPM 桶后端不可用（fail-closed 拒绝）gateway:{} keyId:{}: {}",
                    gatewayId, principal.getVirtualKeyId(), e.getMessage());
            throw new AppException(McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE,
                    "配额服务暂不可用，请稍后重试（fail-closed）");
        }
    }

    @Override
    public void consumeTokens(String gatewayId, GovernancePrincipal principal, long tokens) {
        Integer tpmLimit = principal == null ? null : principal.getTpmLimit();
        if (tokens <= 0 || !isPerKeyQuotaApplicable(principal) || !isLimited(tpmLimit)) {
            return;
        }
        try {
            IQuotaBucketBackend.QuotaBucket bucket = quotaBucketBackend.getTpmBucket(
                    principal.getVirtualKeyId(), tpmLimit);
            int chunk = (int) Math.min(tokens, Integer.MAX_VALUE);
            if (!bucket.tryConsume(chunk).isConsumed()) {
                // 超出窗口余量：尽量扣满（余量清零），超出部分随补币自然吸收
                long available = bucket.availableTokens();
                if (available > 0) {
                    bucket.tryConsume((int) Math.min(available, Integer.MAX_VALUE));
                }
            }
        } catch (Exception e) {
            // 响应已完成：计量失败仅告警
            log.warn("TPM 计量失败（不影响已完成响应）gateway:{} keyId:{} tokens:{}: {}",
                    gatewayId, principal.getVirtualKeyId(), tokens, e.getMessage());
        }
    }

    /** 仅虚拟密钥主体受限额管辖（JWT 管理面/匿名开放网关不限） */
    private boolean isPerKeyQuotaApplicable(GovernancePrincipal principal) {
        return principal != null
                && GovernancePrincipal.AuthType.VIRTUAL_KEY.equals(principal.getAuthType())
                && principal.getVirtualKeyId() != null;
    }

    /** NULL 或 <=0 视为不限 */
    private boolean isLimited(Integer limit) {
        return limit != null && limit > 0;
    }

    private void countDenial(String gatewayId, Long keyId) {
        if (meterRegistry != null) {
            meterRegistry.counter("governance.quota.denied",
                    "gateway", gatewayId == null ? "" : gatewayId,
                    "key", "vk-" + keyId).increment();
        }
    }
}
