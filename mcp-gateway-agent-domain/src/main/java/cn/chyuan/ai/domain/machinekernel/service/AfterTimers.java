package cn.chyuan.ai.domain.machinekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 延迟迁移（工单 0622 BV5，XState delayed transitions 思想）。
 * after 超时迁移注册/时钟注入端口（虚拟时钟 advance 触发到期迁移）/
 * 同态多超时取最早/事件到达即取消未决超时（重算）/
 * 迁移后超时清单重置（进入态重新登记）。
 */
public final class AfterTimers {

    /** 延迟规则：状态+事件 → 延迟毫秒+触发事件 */
    public record Delayed(String state, long delayMs, String fireEvent) {

        public Delayed {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("状态不得为空");
            }
            if (delayMs <= 0) {
                throw new IllegalArgumentException("延迟须为正");
            }
            if (fireEvent == null || fireEvent.isBlank()) {
                throw new IllegalArgumentException("触发事件不得为空");
            }
        }
    }

    /** 触发回调（由状态机解释器消费：fireEvent 定向派发） */
    public interface FirePort {
        void fire(String state, String event);
    }

    private final List<Delayed> delayed = new ArrayList<>();
    private final Map<String, Long> deadlines = new LinkedHashMap<>();
    private long now;
    private final FirePort port;

    public AfterTimers(FirePort port, List<Delayed> delayed) {
        if (port == null) {
            throw new IllegalArgumentException("触发端口不得为 null");
        }
        this.port = port;
        this.delayed.addAll(delayed == null ? List.of() : delayed);
    }

    /** 进入状态：登记该状态的未决超时（同状态取最早者胜出） */
    public synchronized void onEnter(String state, long at) {
        for (Delayed d : delayed) {
            if (d.state().equals(state)) {
                long deadline = at + d.delayMs();
                Long existing = deadlines.get(state + "#" + d.fireEvent());
                if (existing == null || deadline < existing) {
                    deadlines.put(state + "#" + d.fireEvent(), deadline);
                }
            }
        }
    }

    /** 退出/迁移：取消该状态全部未决超时 */
    public synchronized void onExit(String state) {
        deadlines.keySet().removeIf(key -> key.startsWith(state + "#"));
    }

    /** 事件到达：取消该状态全部未决超时（事件优先于超时） */
    public synchronized void onEvent(String state) {
        onExit(state);
    }

    /** 虚拟时钟推进：按到期序（同刻按登记序）触发 */
    public synchronized List<String> advance(long to) {
        if (to < now) {
            throw new IllegalArgumentException("时钟不得回拨");
        }
        List<String> fired = new ArrayList<>();
        List<Map.Entry<String, Long>> due = new ArrayList<>();
        for (Map.Entry<String, Long> e : deadlines.entrySet()) {
            if (e.getValue() <= to) {
                due.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        due.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, Long> e : due) {
            String key = e.getKey();
            deadlines.remove(key);
            String state = key.substring(0, key.indexOf('#'));
            String event = key.substring(key.indexOf('#') + 1);
            port.fire(state, event);
            fired.add(state + "#" + event);
        }
        now = to;
        return fired;
    }

    /** 当前时刻 */
    public synchronized long now() {
        return now;
    }

    /** 未决超时数 */
    public synchronized int pending() {
        return deadlines.size();
    }
}
