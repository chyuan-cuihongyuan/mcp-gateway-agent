package cn.chyuan.ai.domain.governance.service;

import io.github.bucket4j.ConsumptionProbe;

/**
 * 配额桶后端端口（工单 0019）
 *
 * <p>多带宽同桶（RPM + 日请求，0011 决议）；Redis/Lettuce 实现保证跨实例共享计数。
 * 桶键语义：同一 (keyId, rpmLimit, dailyLimit) 共享同一桶——限额变更视作新桶
 * （计数窗口重置），避免热更新旧桶配置的分布式替换复杂度。
 *
 * @author chyuan
 */
public interface IQuotaBucketBackend {

    /**
     * 取（或首次创建）指定密钥的配额桶。
     *
     * @param keyId             虚拟密钥 ID
     * @param rpmLimit          RPM 限额（null 表示该带宽不启用）
     * @param dailyRequestLimit 日请求配额（null 表示该带宽不启用）
     */
    QuotaBucket getBucket(long keyId, Integer rpmLimit, Integer dailyRequestLimit);

    /** 组合维度桶（工单 0107：scope 空=与两参版本同键） */
    default QuotaBucket getBucket(long keyId, String scope, Integer rpmLimit, Integer dailyRequestLimit) {
        return getBucket(keyId, rpmLimit, dailyRequestLimit);
    }

    /**
     * TPM 桶（工单 0065：token 粒度单带宽；null 表示不启用——调用方不取桶）。
     */
    QuotaBucket getTpmBucket(long keyId, Integer tpmLimit);

    /** 配额桶（本地或 Redis 代理桶的抽象，实现须线程安全） */
    interface QuotaBucket {

        /** 尝试消费 n 个令牌（所有带宽一次性原子判定） */
        ConsumptionProbe tryConsume(int n);

        /** 当前剩余令牌（多带宽取最小值，用于诊断展示） */
        long availableTokens();
    }
}
