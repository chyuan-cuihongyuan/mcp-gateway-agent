package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * 背压水位（工单 0448 BB6，nats 流控思想，沿 AT7 滞回口径）。
 * 缓冲区高水位暂停/低水位恢复滞回 + 溢出三策略可配
 * （BLOCK 拒绝写入/DROP_NEWEST 丢最新留最旧/TO_DLQ 转死信回调）+ 策略切换审计计数。
 */
public class BackpressureValve {

    /** 溢出策略 */
    public enum OverflowPolicy {
        BLOCK, DROP_NEWEST, TO_DLQ
    }

    /** 入队结果 */
    public record EnqueueResult(boolean accepted, String reason) {
    }

    private final int highWatermark;
    private final int lowWatermark;
    private final OverflowPolicy policy;
    private final Consumer<String> deadLetterSink;
    private final Deque<String> buffer = new ArrayDeque<>();
    private boolean paused;
    private long blockedCount;
    private long droppedCount;
    private long deadLetteredCount;

    public BackpressureValve(int highWatermark, int lowWatermark, OverflowPolicy policy,
                             Consumer<String> deadLetterSink) {
        if (lowWatermark >= highWatermark || highWatermark < 1) {
            throw new IllegalArgumentException("低水位须小于高水位且高水位至少 1");
        }
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.policy = policy;
        this.deadLetterSink = deadLetterSink;
    }

    /** 入队：暂停期按策略处理 */
    public synchronized EnqueueResult enqueue(String payload) {
        if (buffer.size() >= highWatermark) {
            paused = true;
        }
        if (paused) {
            return switch (policy) {
                case BLOCK -> {
                    blockedCount++;
                    yield new EnqueueResult(false, "背压暂停（BLOCK）");
                }
                case DROP_NEWEST -> {
                    droppedCount++;
                    yield new EnqueueResult(false, "丢弃最新（DROP_NEWEST）");
                }
                case TO_DLQ -> {
                    deadLetteredCount++;
                    if (deadLetterSink != null) {
                        deadLetterSink.accept(payload);
                    }
                    yield new EnqueueResult(true, "转死信（TO_DLQ）");
                }
            };
        }
        buffer.addLast(payload);
        if (buffer.size() >= highWatermark) {
            paused = true;
        }
        return new EnqueueResult(true, "入队");
    }

    /** 出队：低于低水位自动恢复 */
    public synchronized String dequeue() {
        String payload = buffer.pollFirst();
        if (payload != null && paused && buffer.size() <= lowWatermark) {
            paused = false;
        }
        return payload;
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized int buffered() {
        return buffer.size();
    }

    public synchronized long blockedTotal() {
        return blockedCount;
    }

    public synchronized long droppedTotal() {
        return droppedCount;
    }

    public synchronized long deadLetteredTotal() {
        return deadLetteredCount;
    }
}
