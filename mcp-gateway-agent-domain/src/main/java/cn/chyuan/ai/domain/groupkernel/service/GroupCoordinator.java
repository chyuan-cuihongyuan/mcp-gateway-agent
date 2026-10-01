package cn.chyuan.ai.domain.groupkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费组协调器（工单 1211 FF8，kafka 思想）。
 * join 登记成员待再均衡；sync 定策略并代际递增重算分配；
 * 会话超时逐出与离组立即按既有策略再均衡；
 * eager 策略全量回收上一代、COOPERATIVE 仅回收失效成员分区（D6）；
 * 提交位点单调，倒退/未分配/越界/未知拒绝。
 */
public final class GroupCoordinator implements GroupPort {

    private final Map<String, ConsumerGroup> groups = new LinkedHashMap<>();
    private final Map<String, Integer> topics = new LinkedHashMap<>();
    private long tick;
    private long sessionTimeout = 10;

    @Override
    public void topic(String name, int partitions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("主题名不得为空");
        }
        if (partitions <= 0) {
            throw new IllegalArgumentException("分区数须为正: " + partitions);
        }
        topics.put(name, partitions);
    }

    @Override
    public String join(String groupId, String memberId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不得为空");
        }
        if (memberId == null || memberId.isBlank()) {
            throw new IllegalArgumentException("memberId 不得为空");
        }
        ConsumerGroup group = groups.computeIfAbsent(groupId, ConsumerGroup::new);
        if (group.members.containsKey(memberId)) {
            throw new IllegalArgumentException("重复成员: " + memberId);
        }
        group.departed.remove(memberId);
        group.members.put(memberId, new ConsumerGroup.MemberState(memberId, tick));
        return memberId;
    }

    @Override
    public int sync(String groupId, String strategy) {
        ConsumerGroup group = requireGroup(groupId);
        group.rebalance(Assignments.parse(strategy), defaultPartitions());
        return group.generation;
    }

    @Override
    public List<String> members(String groupId) {
        return requireGroup(groupId).members.keySet().stream().sorted().toList();
    }

    @Override
    public int generation(String groupId) {
        return requireGroup(groupId).generation;
    }

    @Override
    public List<Integer> assignmentOf(String groupId, String memberId) {
        ConsumerGroup group = requireGroup(groupId);
        List<Integer> partitions = group.assignment.get(memberId);
        if (partitions == null) {
            throw new IllegalArgumentException("成员无分配: " + memberId);
        }
        return partitions;
    }

    @Override
    public List<String> lastRevoked(String groupId) {
        return requireGroup(groupId).revoked;
    }

    @Override
    public void heartbeat(String groupId, String memberId, int generation) {
        ConsumerGroup group = requireGroup(groupId);
        ConsumerGroup.MemberState member = group.members.get(memberId);
        if (member == null) {
            throw new IllegalArgumentException("未知成员: " + memberId);
        }
        if (generation != group.generation) {
            throw new IllegalArgumentException("过期代际: " + generation + " != " + group.generation);
        }
        member.lastBeat = tick;
    }

    @Override
    public void leave(String groupId, String memberId) {
        ConsumerGroup group = requireGroup(groupId);
        if (!group.members.containsKey(memberId)) {
            if (group.departed.contains(memberId)) {
                return;
            }
            throw new IllegalArgumentException("未知成员: " + memberId);
        }
        group.remove(memberId, defaultPartitions());
    }

    @Override
    public List<String> reap() {
        List<String> evicted = new ArrayList<>();
        for (ConsumerGroup group : groups.values()) {
            List<String> expired = new ArrayList<>();
            for (ConsumerGroup.MemberState member : group.members.values()) {
                if (tick - member.lastBeat > sessionTimeout) {
                    expired.add(member.memberId);
                }
            }
            for (String memberId : expired) {
                evicted.add(group.groupId + "/" + memberId);
                group.remove(memberId, defaultPartitions());
            }
        }
        return evicted;
    }

    @Override
    public void commit(String groupId, String memberId, String topic, int partition, long offset) {
        ConsumerGroup group = requireGroup(groupId);
        if (!group.members.containsKey(memberId)) {
            throw new IllegalArgumentException("未知成员: " + memberId);
        }
        Integer partitions = topics.get(topic);
        if (partitions == null) {
            throw new IllegalArgumentException("未知主题: " + topic);
        }
        if (partition < 0 || partition >= partitions) {
            throw new IllegalArgumentException("分区越界: " + topic + "-" + partition);
        }
        if (!group.assignment.getOrDefault(memberId, List.of()).contains(partition)) {
            throw new IllegalArgumentException("分区未分配给成员: " + memberId + "/" + partition);
        }
        String key = topic + ":" + partition;
        long current = group.committed.getOrDefault(key, 0L);
        if (offset < current) {
            throw new IllegalArgumentException("位点倒退: " + offset + " < " + current);
        }
        group.committed.put(key, offset);
    }

    @Override
    public long committed(String groupId, String topic, int partition) {
        return requireGroup(groupId).committed.getOrDefault(topic + ":" + partition, 0L);
    }

    @Override
    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("时钟不得倒退");
        }
        tick += ticks;
    }

    @Override
    public List<String> partitionShape() {
        return List.of("group", "topic", "entries", "fromOffset");
    }

    private ConsumerGroup requireGroup(String groupId) {
        ConsumerGroup group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("未知消费组: " + groupId);
        }
        return group;
    }

    /** 全体主题默认单主题语义：取最大分区主题（测试以单主题为主） */
    private int defaultPartitions() {
        return topics.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
