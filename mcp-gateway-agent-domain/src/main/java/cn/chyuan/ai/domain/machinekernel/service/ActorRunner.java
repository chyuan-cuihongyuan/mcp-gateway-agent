package cn.chyuan.ai.domain.machinekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * invoke actor（工单 0623 BV6，XState invoke 思想）。
 * 进入状态时受控 Callable 端口启动（手动完成端口，时钟注入确定性，
 * 不引入 actor 运行时）/成功映射 done 事件携带结果/
 * 失败映射 error 事件携带原因/退出状态即取消未决调用。
 */
public final class ActorRunner {

    /** 受控 actor 端口：调用方手动完成/失败（确定性测试） */
    public interface ActorPort {
        void invoke(String actorId, Map<String, Object> payload);
    }

    /** 未决调用 */
    public record Pending(String actorId, String state, Map<String, Object> payload) {
    }

    private final ActorPort port;
    private final Map<String, String> actorsByState = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final List<String> completed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    public ActorRunner(ActorPort port) {
        if (port == null) {
            throw new IllegalArgumentException("actor 端口不得为 null");
        }
        this.port = port;
    }

    /** 登记状态→actor（进入即启动） */
    public synchronized ActorRunner onState(String state, String actorId) {
        if (state == null || state.isBlank() || actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("状态与 actor 不得为空");
        }
        actorsByState.put(state, actorId);
        return this;
    }

    /** 进入状态：启动 actor */
    public synchronized void onEnter(String state, Map<String, Object> payload) {
        String actorId = actorsByState.get(state);
        if (actorId != null) {
            pending.put(actorId, new Pending(actorId, state,
                    payload == null ? Map.of() : Map.copyOf(payload)));
            port.invoke(actorId, pending.get(actorId).payload());
        }
    }

    /** 退出状态：取消未决调用 */
    public synchronized void onExit(String state) {
        pending.values().removeIf(p -> p.state().equals(state));
    }

    /** 成功完成 → done 事件名 */
    public synchronized String complete(String actorId, Object result) {
        Pending call = pending.remove(actorId);
        if (call == null) {
            throw new IllegalArgumentException("actor 未在调用中：" + actorId);
        }
        completed.add(actorId);
        return "done.invoke." + actorId;
    }

    /** 失败 → error 事件名 */
    public synchronized String fail(String actorId, String reason) {
        Pending call = pending.remove(actorId);
        if (call == null) {
            throw new IllegalArgumentException("actor 未在调用中：" + actorId);
        }
        failed.add(actorId + ":" + reason);
        return "error.invoke." + actorId;
    }

    public synchronized boolean isPending(String actorId) {
        return pending.containsKey(actorId);
    }

    public synchronized List<Pending> pendingCalls() {
        return new ArrayList<>(pending.values());
    }

    public synchronized List<String> completedLog() {
        return List.copyOf(completed);
    }

    public synchronized List<String> failedLog() {
        return List.copyOf(failed);
    }
}
