package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * conditions 状态聚合（工单 0799 CP7，kubernetes 思想）。
 * 条件项登记（type/status/reason/lastTransition）/ready 聚合（必需条件全 True）/覆盖更新/时间单调。
 */
public final class Conditions {

    public record Condition(String type, String status, String reason, long lastTransition) {
    }

    public static final String TRUE = "True";
    public static final String FALSE = "False";

    private final Map<String, Condition> byType = new LinkedHashMap<>();
    private long clock = 0;

    /** 覆盖更新：status 变化才推进 lastTransition（单调时钟） */
    public void set(String type, String status, String reason) {
        clock++;
        Condition prev = byType.get(type);
        long transition = prev != null && prev.status().equals(status) ? prev.lastTransition() : clock;
        byType.put(type, new Condition(type, status, reason, transition));
    }

    public Condition get(String type) {
        return byType.get(type);
    }

    /** ready 聚合：必需条件全部存在且为 True */
    public boolean ready(List<String> required) {
        return required.stream().allMatch(t -> {
            Condition c = byType.get(t);
            return c != null && TRUE.equals(c.status());
        });
    }

    /** 时间单调：任一条件的 lastTransition 不小于先前记录 */
    public boolean monotonic(String type, long previous) {
        Condition c = byType.get(type);
        return c != null && c.lastTransition() >= previous;
    }

    public int size() {
        return byType.size();
    }
}
