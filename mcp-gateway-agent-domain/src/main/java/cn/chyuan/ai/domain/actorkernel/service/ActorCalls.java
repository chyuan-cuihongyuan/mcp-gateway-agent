package cn.chyuan.ai.domain.actorkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * actor 间调用（工单 1010 EI6，dapr 调用链思想）。
 * 直接调用传递/调用环检测拒绝/自调用允许。
 */
public final class ActorCalls {

    private final Deque<String> callStack = new ArrayDeque<>();

    /** 进入调用：外部发起 from 为 null；自调用允许；跨 actor 栈内已存在目标即环拒绝 */
    public synchronized void enter(String from, String to) {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("调用目标为空");
        }
        boolean selfCall = to.equals(from);
        if (!selfCall && callStack.contains(to)) {
            throw new IllegalStateException("调用环检测: " + trace() + " -> " + to);
        }
        if (from != null && !callStack.isEmpty() && !callStack.peekLast().equals(from)) {
            throw new IllegalStateException("调用链断裂: 栈顶 " + callStack.peekLast() + " != 发起方 " + from);
        }
        callStack.addLast(to);
    }

    /** 退出调用 */
    public synchronized void exit() {
        if (callStack.isEmpty()) {
            throw new IllegalStateException("无进行中调用");
        }
        callStack.pollLast();
    }

    public synchronized int depth() {
        return callStack.size();
    }

    /** 调用链形状（A->B->C） */
    public synchronized String trace() {
        return String.join("->", callStack);
    }
}
