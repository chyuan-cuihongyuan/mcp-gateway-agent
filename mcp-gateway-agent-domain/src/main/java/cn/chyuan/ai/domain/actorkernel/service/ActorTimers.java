package cn.chyuan.ai.domain.actorkernel.service;

import java.util.PriorityQueue;

/**
 * actor 定时器（工单 1007 EI3，dapr timer 思想）。
 * 一次性触发后失效/周期持续触发/失活取消定时器/重复注册覆盖。
 */
public final class ActorTimers {

    private static final class Timer implements Comparable<Timer> {
        final String actorKey;
        final String name;
        final long periodTicks;
        final boolean repeat;
        final Runnable action;
        long nextTick;
        boolean dead;

        Timer(String actorKey, String name, long periodTicks, boolean repeat, Runnable action, long nextTick) {
            this.actorKey = actorKey;
            this.name = name;
            this.periodTicks = periodTicks;
            this.repeat = repeat;
            this.action = action;
            this.nextTick = nextTick;
        }

        @Override
        public int compareTo(Timer o) {
            int byTick = Long.compare(nextTick, o.nextTick);
            return byTick != 0 ? byTick : key().compareTo(o.key());
        }

        String key() {
            return actorKey + "#" + name;
        }
    }

    private final PriorityQueue<Timer> timers = new PriorityQueue<>();
    private long now;

    /** 注册定时器：同 actor 同名覆盖（旧定时器作废） */
    public synchronized void register(String actorKey, String name, long periodTicks, boolean repeat, Runnable action) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("定时器周期必须为正: " + periodTicks);
        }
        if (action == null) {
            throw new IllegalArgumentException("定时器动作为空");
        }
        cancel(actorKey, name);
        timers.add(new Timer(actorKey, name, periodTicks, repeat, action, now + periodTicks));
    }

    /** 时钟步进：到期触发，一次性失效，周期重排 */
    public synchronized void step() {
        now++;
        while (true) {
            Timer head = timers.peek();
            if (head == null || head.nextTick > now) {
                return;
            }
            Timer timer = timers.poll();
            if (timer.dead) {
                continue;
            }
            timer.action.run();
            if (timer.repeat) {
                timer.nextTick += timer.periodTicks;
                timers.add(timer);
            }
        }
    }

    /** 失活取消该 actor 全部定时器 */
    public synchronized void cancelAll(String actorKey) {
        for (Timer timer : timers) {
            if (timer.actorKey.equals(actorKey)) {
                timer.dead = true;
            }
        }
    }

    /** 取消单个：存在返回 true */
    public synchronized boolean cancel(String actorKey, String name) {
        String key = actorKey + "#" + name;
        boolean found = false;
        for (Timer timer : timers) {
            if (timer.key().equals(key) && !timer.dead) {
                timer.dead = true;
                found = true;
            }
        }
        return found;
    }

    public synchronized int pending() {
        return (int) timers.stream().filter(t -> !t.dead).count();
    }
}
