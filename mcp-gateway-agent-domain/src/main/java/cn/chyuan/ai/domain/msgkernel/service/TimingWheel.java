package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间轮延迟消息（工单 0449 BB7）。
 * 秒/分两级时间轮（槽数可配，时钟端口注入）：秒级虚拟刻度 ticks 定槽
 * （槽位=dueTick % 槽数，确定性无漂移）；延迟超秒轮进分轮（dueTick/秒槽数 定槽），
 * 超分轮进溢出表；每 secondSlots tick 分轮推进一格降槽；到期出轮投递 + 按 id 取消。
 */
public class TimingWheel {

    /** 时钟端口 */
    public interface Clock {

        long nowMs();
    }

    /** 定时消息 */
    public record Timer(String id, String payload, long deliverAtMs) {
    }

    /** 带到期刻度的内部条目 */
    private record DueTimer(Timer timer, long dueTick) {
    }

    private static final class Slot {
        final List<DueTimer> timers = new ArrayList<>();
    }

    private final int secondSlots;
    private final int minuteSlots;
    private final Clock clock;
    private final List<Slot> secondWheel;
    private final List<Slot> minuteWheel;
    private final List<DueTimer> overflow = new ArrayList<>();
    private long ticks;
    private long minuteTicks;

    public TimingWheel(int secondSlots, int minuteSlots, Clock clock) {
        if (secondSlots < 1 || minuteSlots < 1) {
            throw new IllegalArgumentException("槽数至少 1");
        }
        this.secondSlots = secondSlots;
        this.minuteSlots = minuteSlots;
        this.clock = clock;
        this.secondWheel = newWheel(secondSlots);
        this.minuteWheel = newWheel(minuteSlots);
    }

    private static List<Slot> newWheel(int slots) {
        List<Slot> wheel = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            wheel.add(new Slot());
        }
        return wheel;
    }

    private long dueTickOf(Timer timer) {
        long delayMs = Math.max(0, timer.deliverAtMs() - clock.nowMs());
        return ticks + (delayMs + 999) / 1000;
    }

    /** 入轮：秒轮容量内进秒轮；秒轮~分轮容量进分轮；更长进溢出表 */
    public synchronized void schedule(Timer timer) {
        long dueTick = dueTickOf(timer);
        DueTimer due = new DueTimer(timer, dueTick);
        if (dueTick <= ticks + secondSlots) {
            secondWheel.get((int) (dueTick % secondSlots)).timers.add(due);
        } else if (dueTick / secondSlots <= minuteTicks + minuteSlots) {
            minuteWheel.get((int) ((dueTick / secondSlots) % minuteSlots)).timers.add(due);
        } else {
            overflow.add(due);
        }
    }

    /** tick 推进 1 秒：分轮降槽先行，随后收集当前秒槽到期消息 */
    public synchronized List<Timer> tick() {
        ticks++;
        if (ticks % secondSlots == 0) {
            minuteTicks++;
            Slot slot = minuteWheel.get((int) (minuteTicks % minuteSlots));
            for (DueTimer due : slot.timers) {
                secondWheel.get((int) (due.dueTick() % secondSlots)).timers.add(due);
            }
            slot.timers.clear();
        }
        // 溢出表滚动降级：已进入分轮/秒轮覆盖范围即重新入轮
        for (DueTimer due : List.copyOf(overflow)) {
            if (due.dueTick() / secondSlots <= minuteTicks + minuteSlots) {
                overflow.remove(due);
                if (due.dueTick() <= ticks + secondSlots) {
                    secondWheel.get((int) (due.dueTick() % secondSlots)).timers.add(due);
                } else {
                    minuteWheel.get((int) ((due.dueTick() / secondSlots) % minuteSlots)).timers.add(due);
                }
            }
        }
        Slot slot = secondWheel.get((int) (ticks % secondSlots));
        List<Timer> outTimers = new ArrayList<>();
        for (DueTimer due : slot.timers) {
            outTimers.add(due.timer());
        }
        slot.timers.clear();
        return outTimers;
    }

    /** 取消：返回是否取消成功（仍在轮/溢出中） */
    public synchronized boolean cancel(String id) {
        for (Slot slot : secondWheel) {
            if (slot.timers.removeIf(due -> due.timer().id().equals(id))) {
                return true;
            }
        }
        for (Slot slot : minuteWheel) {
            if (slot.timers.removeIf(due -> due.timer().id().equals(id))) {
                return true;
            }
        }
        return overflow.removeIf(due -> due.timer().id().equals(id));
    }

    /** 挂起中（未投递未取消）消息数 */
    public synchronized int pending() {
        int count = overflow.size();
        for (Slot slot : secondWheel) {
            count += slot.timers.size();
        }
        for (Slot slot : minuteWheel) {
            count += slot.timers.size();
        }
        return count;
    }
}
