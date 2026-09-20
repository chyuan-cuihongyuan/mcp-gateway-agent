package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交边界（工单 0514 BJ3）。
 * matchIndex 多数派推进 commitIndex/commitIndex 只前进不回退/
 * 已提交条目按序应用到状态机。
 */
public class CommitTracker {

    private final int clusterSize;
    private final long[] matchIndex;
    private long commitIndex;

    public CommitTracker(int clusterSize) {
        if (clusterSize < 1 || clusterSize % 2 == 0) {
            throw new IllegalArgumentException("集群规模须为奇数 ≥ 1: " + clusterSize);
        }
        this.clusterSize = clusterSize;
        this.matchIndex = new long[clusterSize];
    }

    /** leader 汇报某 follower 匹配进度（只增不减） */
    public synchronized void reportMatch(int followerIndex, long matchedIndex) {
        if (followerIndex < 0 || followerIndex >= clusterSize) {
            throw new IllegalArgumentException("follower 下标越界: " + followerIndex);
        }
        matchIndex[followerIndex] = Math.max(matchIndex[followerIndex], matchedIndex);
    }

    /**
     * 多数派推进：取 matchIndex 降序第 N/2+1 个值 N（须 > commitIndex）。
     * 返回新提交边界（未推进返回原值）。
     */
    public synchronized long advance() {
        List<Long> sorted = new ArrayList<>();
        for (long index : matchIndex) {
            sorted.add(index);
        }
        sorted.sort(java.util.Collections.reverseOrder());
        long majorityIndex = sorted.get(clusterSize / 2);
        if (majorityIndex > commitIndex) {
            commitIndex = majorityIndex;
        }
        return commitIndex;
    }

    public synchronized long commitIndex() {
        return commitIndex;
    }

    /** 按序应用到状态机：返回 [fromCommit, toCommit] 区间命令列表 */
    public synchronized List<String> pendingCommands(List<RaftLogEntry> entries) {
        List<String> commands = new ArrayList<>();
        for (RaftLogEntry entry : entries) {
            if (entry.index() > commitIndex) {
                break;
            }
            commands.add(entry.command());
        }
        return commands;
    }
}
