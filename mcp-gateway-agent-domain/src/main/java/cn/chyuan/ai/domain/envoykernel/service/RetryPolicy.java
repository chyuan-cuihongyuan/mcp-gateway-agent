package cn.chyuan.ai.domain.envoykernel.service;

/**
 * 重试预算与退避（工单 0940 EA5，envoy 思想）。
 * 按率预算拒绝超额/指数退避序列/成功停重试。
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final int budgetPercent;
    private final long baseBackoffMillis;
    private int requests = 0;
    private int retries = 0;

    public RetryPolicy(int maxRetries, int budgetPercent, long baseBackoffMillis) {
        if (maxRetries <= 0 || budgetPercent <= 0 || baseBackoffMillis < 0) {
            throw new IllegalArgumentException("重试参数非法: " + maxRetries + "/" + budgetPercent + "/" + baseBackoffMillis);
        }
        this.maxRetries = maxRetries;
        this.budgetPercent = budgetPercent;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    /** 记一次主请求（重试预算基数随主请求数增长） */
    public void onRequest() {
        requests++;
    }

    /** 是否允许本次重试：未超次数且预算内（budget = requests * budgetPercent / 100） */
    public boolean allowRetry() {
        long budget = (long) requests * budgetPercent / 100;
        return retries < maxRetries && retries < budget;
    }

    /** 记一次重试 */
    public void onRetry() {
        retries++;
    }

    /** 第 attempt 次重试的退避毫秒：base * 2^(attempt-1)，attempt 从 1 起 */
    public long backoffMillis(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("退避次数非法: " + attempt);
        }
        long result = baseBackoffMillis;
        for (int i = 1; i < attempt; i++) {
            result *= 2;
        }
        return result;
    }

    /** 成功停重试：清零重试计数 */
    public void onSuccess() {
        retries = 0;
    }

    public int requests() {
        return requests;
    }

    public int retries() {
        return retries;
    }
}
