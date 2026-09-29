package cn.chyuan.ai.domain.actorkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * turn 串行化（工单 1006 EI2，dapr turn-based 并发思想）。
 * 消息排队 FIFO/turn 内独占/并发请求排队序/空队列不空转。
 */
public final class TurnQueue {

    private final Deque<Object> inbox = new ArrayDeque<>();
    private boolean inTurn;

    /** 投递入 inbox：turn 中亦入队（排队序） */
    public synchronized void send(Object message) {
        if (message == null) {
            throw new IllegalArgumentException("消息为空");
        }
        inbox.addLast(message);
    }

    /** 开 turn：取出队头消息；turn 内独占（重入拒绝）；空队列返回 null（不空转） */
    public synchronized Object beginTurn() {
        if (inTurn) {
            throw new IllegalStateException("turn 内独占，禁止重入");
        }
        Object message = inbox.pollFirst();
        if (message != null) {
            inTurn = true;
        }
        return message;
    }

    /** 结束 turn：允许下一条消息开 turn */
    public synchronized void endTurn() {
        if (!inTurn) {
            throw new IllegalStateException("无进行中 turn");
        }
        inTurn = false;
    }

    public synchronized boolean inTurn() {
        return inTurn;
    }

    public synchronized int pending() {
        return inbox.size();
    }
}
