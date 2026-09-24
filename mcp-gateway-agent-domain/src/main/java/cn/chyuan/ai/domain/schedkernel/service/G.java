package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * G 任务与状态机（工单 0732 CI1，golang GMP 思想）。
 * G 任务创建与 id 单调/runnable·running·waiting·dead 迁移合法性/
 * 非法迁移拒绝/终态不可复活。
 */
public final class G {

    public enum State {RUNNABLE, RUNNING, WAITING, DEAD}

    /** 协作式单步动作：调度一轮执行一个 */
    public interface Action {
        void run(Scheduler scheduler, G self);
    }

    private static long sequence = 0;

    private final long id;
    private final String name;
    private final Deque<Action> actions;
    private State state = State.RUNNABLE;
    private long runTicks;
    private long waitTicks;
    private long parkedSince = -1;

    public G(String name, java.util.List<Action> actions) {
        this.id = ++sequence;
        this.name = name;
        this.actions = new ArrayDeque<>(actions);
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public long runTicks() {
        return runTicks;
    }

    public long waitTicks() {
        return waitTicks;
    }

    public boolean hasSteps() {
        return !actions.isEmpty();
    }

    /** 取一步执行（RUNNING 态由调度器管理）：阻塞动作留队首，唤醒后由动作自查完成标志重试 */
    void runStep(Scheduler scheduler) {
        Action action = actions.peek();
        if (action == null) {
            transit(State.DEAD, scheduler.tick());
            return;
        }
        action.run(scheduler, this);
        if (state != State.WAITING) {
            actions.poll();
        }
    }

    void transit(State target, long nowTick) {
        boolean legal = switch (state) {
            case RUNNABLE -> target == State.RUNNING || target == State.DEAD;
            case RUNNING -> target == State.RUNNABLE || target == State.WAITING || target == State.DEAD;
            case WAITING -> target == State.RUNNABLE;
            case DEAD -> false;
        };
        if (!legal) {
            throw new IllegalStateException("非法状态迁移: " + state + " -> " + target);
        }
        if (state == State.RUNNING && target == State.RUNNABLE) {
            runTicks++;
        }
        if (target == State.WAITING) {
            parkedSince = nowTick;
        }
        if (state == State.WAITING && target == State.RUNNABLE && parkedSince >= 0) {
            waitTicks += nowTick - parkedSince;
            parkedSince = -1;
        }
        state = target;
    }

    static long sequence() {
        return sequence;
    }

    static void resetSequenceForTest() {
        sequence = 0;
    }
}
