package cn.chyuan.ai.domain.groupkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分区分配策略（工单 1206 FF3，kafka 思想）。
 * RANGE 每成员连续区间（前者多担余数）/ROUND_ROBIN 逐区轮转；
 * 分区数少于成员时空分配合法；未知策略拒绝。COOPERATIVE 分配同 RANGE，回收语义不同。
 */
public final class Assignments {

    public enum Strategy {
        RANGE, ROUND_ROBIN, COOPERATIVE
    }

    private Assignments() {
    }

    public static Strategy parse(String name) {
        try {
            return Strategy.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("未知分配策略: " + name);
        }
    }

    /** 按策略分配：members 为成员字典序 */
    public static Map<String, List<Integer>> assign(Strategy strategy, int partitions, List<String> members) {
        return switch (strategy) {
            case RANGE -> range(partitions, members);
            case ROUND_ROBIN -> roundRobin(partitions, members);
            case COOPERATIVE -> range(partitions, members);
        };
    }

    /** 连续区间：前 (partitions % members) 个成员各多担一区 */
    static Map<String, List<Integer>> range(int partitions, List<String> members) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String member : members) {
            out.put(member, new ArrayList<>());
        }
        int cursor = 0;
        for (int i = 0; i < members.size(); i++) {
            int share = partitions / members.size() + (i < partitions % members.size() ? 1 : 0);
            for (int p = 0; p < share; p++) {
                out.get(members.get(i)).add(cursor++);
            }
        }
        return out;
    }

    /** 逐区轮转：分区 i 归 members[i % n] */
    static Map<String, List<Integer>> roundRobin(int partitions, List<String> members) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String member : members) {
            out.put(member, new ArrayList<>());
        }
        for (int p = 0; p < partitions; p++) {
            out.get(members.get(p % members.size())).add(p);
        }
        return out;
    }
}
