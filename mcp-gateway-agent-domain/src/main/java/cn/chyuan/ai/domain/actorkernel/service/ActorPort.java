package cn.chyuan.ai.domain.actorkernel.service;

import java.util.List;

/**
 * 虚拟Actor端口（工单 1012 EI8，dapr 思想）。
 * activate·send·deactivate 入口统一编排/与 schedkernel 队列形状作排队形态只读联动（泛型形状串不 import）/
 * actor-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ActorPort {

    void registerType(String type);

    /** 按需激活：幂等；未注册类型拒绝；激活即空闲计数归零 */
    String activate(String type, String id);

    /** 失活：清状态/取消定时器/清空闲与故障计数；提醒持久保留 */
    void deactivate(String type, String id);

    boolean isActive(String type, String id);

    Object getState(String type, String id, String key);

    /** 投递消息：未激活自动按需激活；入 turn 队列 FIFO */
    void send(String type, String id, Object message);

    /** 开 turn：返回队头消息；空队列返回 false（消息经 currentTurn 取） */
    boolean beginTurn(String type, String id);

    Object currentTurn(String type, String id);

    /** 结束 turn：success 清零故障计数，failure 计数并判失活 */
    void endTurn(String type, String id, boolean success);

    void registerTimer(String type, String id, String name, long periodTicks, boolean repeat, Runnable action);

    void registerReminder(String type, String id, String name, long periodTicks, boolean repeat);

    /** 激活后重放到期提醒 */
    List<String> replayReminders(String type, String id);

    /** 时钟步进：定时器与空闲计数 +1 */
    void tick();

    /** 收割超阈空闲 actor：失活并返回名单 */
    List<String> reapIdle(long threshold);

    /** actor 间调用：环检测拒绝，自调用允许 */
    void enterCall(String from, String to);

    void exitCall();

    /** 当前调用栈深度 */
    int callDepth();

    /** schedkernel 队列形状只读联动：actor inbox 排队形状串（形状数据不 import schedkernel） */
    String queueShape(String type, String id);

    static ActorPort inMemory() {
        return new ActorHub();
    }
}
