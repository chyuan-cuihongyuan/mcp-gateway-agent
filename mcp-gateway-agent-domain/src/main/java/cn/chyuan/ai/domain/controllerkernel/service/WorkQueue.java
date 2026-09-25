package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 工作队列（工单 0795 CP3，kubernetes client-go 思想）。
 * 令牌桶限速（每步补充率与桶容量可配）/失败指数退避上限封顶/成功重置/同键再入队去重。
 * 时间以"步"推进（advance 由控制循环每步调用），保证确定性。
 */
public final class WorkQueue {

    private final double refillPerStep;
    private final int burst;
    private final long backoffBase;
    private final long backoffMax;
    private double tokens;
    private long now = 0;
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> readyAt = new HashMap<>();

    public WorkQueue(double refillPerStep, int burst, long backoffBase, long backoffMax) {
        if (refillPerStep <= 0 || burst <= 0) {
            throw new IllegalArgumentException("限速参数为正");
        }
        this.refillPerStep = refillPerStep;
        this.burst = burst;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.tokens = burst;
    }

    /** 每步推进：时钟 +1，令牌按率补充（封顶桶容量） */
    public void advance() {
        now++;
        tokens = Math.min(burst, tokens + refillPerStep);
    }

    /** 入队：去重（已排队键忽略） */
    public void add(String key) {
        if (queued.add(key)) {
            order.addLast(key);
        }
    }

    /** 弹出一个就绪键：需令牌且退避到期；无就绪返回 null */
    public String pop() {
        for (String key : order) {
            long ready = readyAt.getOrDefault(key, 0L);
            if (ready <= now && tokens >= 1) {
                order.remove(key);
                queued.remove(key);
                tokens -= 1;
                return key;
            }
            if (ready > now) {
                continue;
            }
        }
        return null;
    }

    /** 失败：指数退避 2^(n-1) 步，封顶 */
    public void fail(String key) {
        int n = failures.merge(key, 1, Integer::sum);
        long delay = Math.min(backoffBase * (1L << Math.min(n - 1, 30)), backoffMax);
        readyAt.put(key, now + delay);
        add(key);
    }

    /** 成功：退避与失败计数重置 */
    public void done(String key) {
        failures.remove(key);
        readyAt.remove(key);
    }

    public boolean contains(String key) {
        return queued.contains(key);
    }

    public int failuresOf(String key) {
        return failures.getOrDefault(key, 0);
    }

    public boolean eligible(String key) {
        return readyAt.getOrDefault(key, 0L) <= now;
    }
}
