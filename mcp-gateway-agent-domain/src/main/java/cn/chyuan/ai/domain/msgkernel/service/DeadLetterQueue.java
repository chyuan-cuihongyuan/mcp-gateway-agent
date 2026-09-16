package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信队列与重试退避（工单 0446 BB4）。
 * 投递失败计数 + 指数退避下次可投时间（base×2^n 封顶，时钟端口注入）
 * + 达最大次数入 DLQ（原主题/偏移/原因/计数）+ DLQ 查询 + 重投复位。纯函数内核。
 */
public class DeadLetterQueue {

    /** 时钟端口 */
    public interface Clock {

        long nowMs();
    }

    /** 死信条目 */
    public record DeadLetter(String id, String topic, long offset, String reason, int attempts, long deadAtMs) {
    }

    /** 投递调度判定 */
    public record RetryDecision(boolean retryScheduled, long nextRetryAtMs, boolean dead, int attempts) {
    }

    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final Clock clock;
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Long> nextRetryAt = new HashMap<>();
    private final List<DeadLetter> deadLetters = new ArrayList<>();

    public DeadLetterQueue(int maxAttempts, long baseBackoffMs, long maxBackoffMs, Clock clock) {
        if (maxAttempts < 1 || baseBackoffMs < 1 || maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException("次数至少 1 且封顶退避不小于基准退避");
        }
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.clock = clock;
    }

    /** 投递失败登记：未达上限排退避重试，达上限入 DLQ */
    public synchronized RetryDecision fail(String messageId, String topic, long offset, String reason) {
        int count = attempts.merge(messageId, 1, Integer::sum);
        if (count >= maxAttempts) {
            deadLetters.add(new DeadLetter(messageId, topic, offset, reason, count, clock.nowMs()));
            nextRetryAt.remove(messageId);
            return new RetryDecision(false, 0, true, count);
        }
        long backoff = Math.min(maxBackoffMs, baseBackoffMs * (1L << Math.min(31, count - 1)));
        long nextAt = clock.nowMs() + backoff;
        nextRetryAt.put(messageId, nextAt);
        return new RetryDecision(true, nextAt, false, count);
    }

    /** 是否可投（重试时间已到或无排期） */
    public synchronized boolean readyToDeliver(String messageId) {
        Long nextAt = nextRetryAt.get(messageId);
        return nextAt == null || clock.nowMs() >= nextAt;
    }

    /** 投递成功清除计数与排期 */
    public synchronized void succeed(String messageId) {
        attempts.remove(messageId);
        nextRetryAt.remove(messageId);
    }

    /** 从 DLQ 重投：计数清零回原流 */
    public synchronized boolean redeliver(String messageId) {
        boolean removed = deadLetters.removeIf(letter -> letter.id().equals(messageId));
        if (removed) {
            succeed(messageId);
        }
        return removed;
    }

    /** DLQ 查询（按主题过滤） */
    public synchronized List<DeadLetter> list(String topic) {
        List<DeadLetter> out = new ArrayList<>();
        for (DeadLetter letter : deadLetters) {
            if (topic == null || topic.isEmpty() || letter.topic().equals(topic)) {
                out.add(letter);
            }
        }
        return out;
    }
}
