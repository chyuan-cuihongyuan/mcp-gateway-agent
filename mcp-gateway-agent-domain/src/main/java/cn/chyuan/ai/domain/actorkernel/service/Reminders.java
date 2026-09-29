package cn.chyuan.ai.domain.actorkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * actor 提醒（工单 1008 EI4，dapr reminder 思想）。
 * 持久注册（失活不丢）/激活后到期重放/一次性触发后失效/周期持续。
 */
public final class Reminders {

    private static final class Reminder {
        final String actorKey;
        final String name;
        final long periodTicks;
        final boolean repeat;
        long nextDue;
        boolean expired;

        Reminder(String actorKey, String name, long periodTicks, boolean repeat, long nextDue) {
            this.actorKey = actorKey;
            this.name = name;
            this.periodTicks = periodTicks;
            this.repeat = repeat;
            this.nextDue = nextDue;
        }
    }

    private final Map<String, Reminder> table = new LinkedHashMap<>();
    private long now;

    /** 注册提醒：同 actor 同名覆盖；失活不影响（持久语义） */
    public synchronized void register(String actorKey, String name, long periodTicks, boolean repeat) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("提醒周期必须为正: " + periodTicks);
        }
        table.put(actorKey + "#" + name, new Reminder(actorKey, name, periodTicks, repeat, now + periodTicks));
    }

    /** 时钟步进 */
    public synchronized void step() {
        now++;
    }

    /** 激活重放：到期提醒按名返回；一次性触发后失效，周期持续重排 */
    public synchronized List<String> replay(String actorKey) {
        List<String> due = new ArrayList<>();
        for (Reminder reminder : table.values()) {
            if (reminder.expired || !reminder.actorKey.equals(actorKey) || reminder.nextDue > now) {
                continue;
            }
            due.add(reminder.name);
            if (reminder.repeat) {
                reminder.nextDue = now + reminder.periodTicks;
            } else {
                reminder.expired = true;
            }
        }
        return due;
    }

    /** 失活不清除（持久），仅显式注销 */
    public synchronized boolean unregister(String actorKey, String name) {
        return table.remove(actorKey + "#" + name) != null;
    }

    public synchronized int pending() {
        return (int) table.values().stream().filter(r -> !r.expired).count();
    }
}
