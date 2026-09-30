package cn.chyuan.ai.domain.containkernel.service;

/**
 * 容器状态机（工单 1169 FB3，moby 思想）。
 * created→running→stopped/running→paused→running/paused→stopped/非法跃迁拒绝。
 */
public final class ContainerStates {

    public enum State { CREATED, RUNNING, PAUSED, STOPPED }

    private State state = State.CREATED;

    public State state() {
        return state;
    }

    /** 迁移：非法跃迁拒绝（created 不可直接 stop/pause；stopped 不可 pause/再 stop） */
    public void to(String target) {
        State next;
        try {
            next = State.valueOf(target.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知状态: " + target);
        }
        boolean legal = switch (next) {
            case RUNNING -> state == State.CREATED || state == State.PAUSED || state == State.STOPPED;
            case PAUSED -> state == State.RUNNING;
            case STOPPED -> state == State.RUNNING || state == State.PAUSED;
            case CREATED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("非法状态跃迁: " + state + " -> " + next);
        }
        state = next;
    }
}
