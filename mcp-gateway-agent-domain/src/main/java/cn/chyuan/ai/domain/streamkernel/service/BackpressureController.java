package cn.chyuan.ai.domain.streamkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 流背压水位控制器（工单 0377 AT7，Reactive Streams backpressure 思想）。
 * 有界缓冲高低水位滞回：>=高水位 PAUSE、<=低水位 RESUME（只在翻转沿发信号）；
 * 溢出策略可配：DROP_OLDEST/DROP_NEWEST/ERROR。
 */
public class BackpressureController<T> {

    /** 溢出策略 */
    public enum OverflowPolicy {
        DROP_OLDEST, DROP_NEWEST, ERROR
    }

    /** 背压信号 */
    public enum Signal {
        NONE, PAUSE, RESUME
    }

    private final int highWatermark;
    private final int lowWatermark;
    private final OverflowPolicy policy;
    private final ArrayDeque<T> buffer = new ArrayDeque<>();
    private boolean paused;

    public BackpressureController(int highWatermark, int lowWatermark, OverflowPolicy policy) {
        if (highWatermark < 1 || lowWatermark < 0 || lowWatermark >= highWatermark) {
            throw new IllegalArgumentException("要求 0 <= lowWatermark < highWatermark");
        }
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.policy = policy;
    }

    /**
     * 推入元素：返回本步信号（PAUSE 仅在进入暂停态的翻转沿；溢出按策略处置）。
     */
    public Signal push(T item) {
        if (buffer.size() >= highWatermark && policy == OverflowPolicy.ERROR) {
            throw new IllegalStateException("缓冲溢出（ERROR 策略）");
        }
        if (buffer.size() >= highWatermark) {
            if (policy == OverflowPolicy.DROP_NEWEST) {
                return currentOrPause();
            }
            buffer.pollFirst(); // DROP_OLDEST
            buffer.addLast(item);
            return currentOrPause();
        }
        buffer.addLast(item);
        return currentOrPause();
    }

    /**
     * 消费元素：返回本步信号（RESUME 仅在退出暂停态的翻转沿）。
     */
    public Signal pop() {
        buffer.pollFirst();
        if (paused && buffer.size() <= lowWatermark) {
            paused = false;
            return Signal.RESUME;
        }
        return Signal.NONE;
    }

    private Signal currentOrPause() {
        if (!paused && buffer.size() >= highWatermark) {
            paused = true;
            return Signal.PAUSE;
        }
        return Signal.NONE;
    }

    /** 当前缓冲规模 */
    public int size() {
        return buffer.size();
    }

    /** 是否暂停态 */
    public boolean isPaused() {
        return paused;
    }

    /** 快照（按序） */
    public List<T> snapshot() {
        return new ArrayList<>(buffer);
    }
}
