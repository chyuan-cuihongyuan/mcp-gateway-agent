package cn.chyuan.ai.domain.asynckernel.service;

import java.util.PriorityQueue;

/**
 * 虚拟时钟（工单 1002 EH6，tokio time 思想）。
 * 时间轮步进/timeout 到期触发/interval 周期触发/未到期不触发。
 */
public final class VirtualClock {

    private static final class Timer implements Comparable<Timer> {
        final String id;
        final long periodMs;
        final boolean repeat;
        final Runnable action;
        long nextFireMs;
        boolean cancelled;

        Timer(String id, long nextFireMs, long periodMs, boolean repeat, Runnable action) {
            this.id = id;
            this.nextFireMs = nextFireMs;
            this.periodMs = periodMs;
            this.repeat = repeat;
            this.action = action;
        }

        @Override
        public int compareTo(Timer o) {
            int byTime = Long.compare(nextFireMs, o.nextFireMs);
            return byTime != 0 ? byTime : id.compareTo(o.id);
        }
    }

    private final PriorityQueue<Timer> wheel = new PriorityQueue<>();
    private long nowMs;
    private long seq;

    public synchronized long now() {
        return nowMs;
    }

    /** 一次性超时：到期触发一次 */
    public synchronized String timeout(long delayMs, Runnable action) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("timeout 延迟为负: " + delayMs);
        }
        if (action == null) {
            throw new IllegalArgumentException("timeout 动作为空");
        }
        String id = "k" + (++seq);
        wheel.add(new Timer(id, nowMs + delayMs, 0, false, action));
        return id;
    }

    /** 周期定时：到期触发并按周期重排 */
    public synchronized String interval(long periodMs, Runnable action) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("interval 周期必须为正: " + periodMs);
        }
        if (action == null) {
            throw new IllegalArgumentException("interval 动作为空");
        }
        String id = "k" + (++seq);
        wheel.add(new Timer(id, nowMs + periodMs, periodMs, true, action));
        return id;
    }

    /** 时间轮步进：到期任务按（到期时间，id）序触发，interval 重排，未到期不触发 */
    public synchronized void advance(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("advance 步长为负: " + deltaMs);
        }
        nowMs += deltaMs;
        while (true) {
            Timer head = wheel.peek();
            if (head == null || head.nextFireMs > nowMs) {
                return;
            }
            Timer timer = wheel.poll();
            if (timer.cancelled) {
                continue;
            }
            timer.action.run();
            if (timer.repeat) {
                timer.nextFireMs += timer.periodMs;
                wheel.add(timer);
            }
        }
    }

    /** 取消定时：返回是否存在；已过期未触发的不可再取消 */
    public synchronized boolean cancel(String timerId) {
        for (Timer timer : wheel) {
            if (timer.id.equals(timerId) && !timer.cancelled) {
                timer.cancelled = true;
                return true;
            }
        }
        return false;
    }

    public synchronized int pending() {
        return (int) wheel.stream().filter(t -> !t.cancelled).count();
    }
}
