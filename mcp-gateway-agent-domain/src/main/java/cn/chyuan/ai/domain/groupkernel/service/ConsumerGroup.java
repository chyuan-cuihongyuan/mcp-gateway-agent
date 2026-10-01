package cn.chyuan.ai.domain.groupkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费组（工单 1204-1209 状态载体，kafka 思想）。
 * 成员表、generation 代际单调、当前/上一代分配、回收留痕、提交位点。
 */
public final class ConsumerGroup {

    final String groupId;
    int generation;
    Assignments.Strategy strategy;
    /** 当前代分配：memberId → 分区号 */
    Map<String, List<Integer>> assignment = Map.of();
    /** 上一代分配（再均衡前） */
    Map<String, List<Integer>> previous = Map.of();
    /** 最近一次再均衡被回收的分区（"memberId:分区号"） */
    List<String> revoked = List.of();
    final Map<String, Long> committed = new LinkedHashMap<>();
    final Map<String, MemberState> members = new LinkedHashMap<>();
    /** 已离组成员留痕（重复 leave 幂等判定） */
    final java.util.Set<String> departed = new java.util.LinkedHashSet<>();

    static final class MemberState {
        final String memberId;
        long lastBeat;

        MemberState(String memberId, long lastBeat) {
            this.memberId = memberId;
            this.lastBeat = lastBeat;
        }
    }

    ConsumerGroup(String groupId) {
        this.groupId = groupId;
    }

    /** 再均衡：记上一代、按策略重算、按 eager/cooperative 语义留痕回收 */
    void rebalance(Assignments.Strategy strategy, int partitions) {
        this.strategy = strategy;
        previous = assignment;
        List<String> membersSorted = members.keySet().stream().sorted().toList();
        assignment = Assignments.assign(strategy, partitions, membersSorted);
        generation++;
        List<String> revokes = new ArrayList<>();
        boolean cooperative = strategy == Assignments.Strategy.COOPERATIVE;
        for (Map.Entry<String, List<Integer>> entry : previous.entrySet()) {
            boolean memberGone = !members.containsKey(entry.getKey());
            if (cooperative && !memberGone) {
                continue;
            }
            for (Integer partition : entry.getValue()) {
                revokes.add(entry.getKey() + ":" + partition);
            }
        }
        revoked = List.copyOf(revokes);
    }

    /** 成员离开/被逐：移除后立即按既有策略再均衡（无策略仅移除） */
    void remove(String memberId, int partitions) {
        members.remove(memberId);
        departed.add(memberId);
        if (strategy != null) {
            rebalance(strategy, partitions);
        } else {
            generation++;
        }
    }
}
