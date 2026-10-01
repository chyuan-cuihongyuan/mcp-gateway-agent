package cn.chyuan.ai.domain.groupkernel.service;

import java.util.List;

/**
 * 消费组再均衡端口（工单 1211 FF8，kafka 思想）。
 * join·sync·heartbeat·commit 入口统一编排：成员加入·generation 代际·分配策略·心跳会话·
 * 离组·增量协作·偏移提交组合管线/msgkernel 主题分区形状只读联动
 * （Batch: group/topic/entries/fromOffset 字段名对齐，不 import msgkernel）/
 * group-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GroupPort {

    /** 声明主题分区数（FF3） */
    void topic(String name, int partitions);

    /** 成员加入：memberId 组内唯一，触发待再均衡（FF1） */
    String join(String groupId, String memberId);

    /** 同步：定策略并再均衡，generation 单调递增，返回新代际（FF2/FF3） */
    int sync(String groupId, String strategy);

    /** 组内成员（字典序）（FF1） */
    List<String> members(String groupId);

    /** 当前代际（FF2） */
    int generation(String groupId);

    /** 成员当前分配（FF3） */
    List<Integer> assignmentOf(String groupId, String memberId);

    /** 最近一次再均衡被回收分区（"memberId:分区号"）（FF5/FF6） */
    List<String> lastRevoked(String groupId);

    /** 心跳续期；代际过期拒绝；未知成员拒绝（FF2/FF4） */
    void heartbeat(String groupId, String memberId, int generation);

    /** 成员离组：触发立即再均衡；重复离组幂等（FF5） */
    void leave(String groupId, String memberId);

    /** 会话超时巡检：踢出并立即再均衡，返回被逐成员（FF4） */
    List<String> reap();

    /** 偏移提交：未分配分区/倒退/未知组与成员/越界分区拒绝（FF7） */
    void commit(String groupId, String memberId, String topic, int partition, long offset);

    /** 已提交位点（FF7） */
    long committed(String groupId, String topic, int partition);

    /** 推进虚拟时钟（FF4 会话超时语义） */
    void advance(long ticks);

    /** msgkernel 主题分区形状只读联动（Batch: group/topic/entries/fromOffset） */
    List<String> partitionShape();

    static GroupPort inMemory() {
        return new GroupCoordinator();
    }
}
