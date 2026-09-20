package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Raft 任期与投票（工单 0512 BJ1，tikv/etcd raft 子集思想）。
 * term 单调约束/单任期单投票/日志新度比较（lastTerm 优先 lastIndex）拒绝旧候选。
 */
public class RaftState {

    /** 投票结果 */
    public enum VoteResult {
        GRANTED, REJECTED_VOTED, REJECTED_STALE, REJECTED_NOT_FRESH
    }

    private long currentTerm;
    private Long votedFor;

    public synchronized long currentTerm() {
        return currentTerm;
    }

    /** 收到更高任期：任期推进并清空投票 */
    public synchronized void observeTerm(long term) {
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
        } else if (term < currentTerm) {
            throw new IllegalArgumentException("任期不可回退: 当前 " + currentTerm + " 收到 " + term);
        }
    }

    /** 发起选举：任期 +1，投票给自己（votedFor=-1 表示自投） */
    public synchronized long startElection() {
        currentTerm++;
        votedFor = -1L;
        return currentTerm;
    }

    /** 投票裁定：单任期单投票 + 日志新度比较 */
    public synchronized VoteResult grantVote(long candidateTerm, long candidateLastTerm,
            long candidateLastIndex, long myLastTerm, long myLastIndex) {
        if (candidateTerm < currentTerm) {
            return VoteResult.REJECTED_STALE;
        }
        if (candidateTerm > currentTerm) {
            observeTerm(candidateTerm);
        }
        if (votedFor != null) {
            return VoteResult.REJECTED_VOTED;
        }
        if (!LogFreshness.isAtLeastAsFresh(candidateLastTerm, candidateLastIndex, myLastTerm, myLastIndex)) {
            return VoteResult.REJECTED_NOT_FRESH;
        }
        votedFor = candidateLastTerm;
        return VoteResult.GRANTED;
    }

    public synchronized boolean hasVotedThisTerm() {
        return votedFor != null;
    }
}

/** 日志新度比较（工具类） */
final class LogFreshness {

    private LogFreshness() {
    }

    /** lastTerm 优先 lastIndex；候选新度 ≥ 本地才可投票 */
    static boolean isAtLeastAsFresh(long candidateLastTerm, long candidateLastIndex,
            long myLastTerm, long myLastIndex) {
        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        return candidateLastIndex >= myLastIndex;
    }

    /** 判断日志列表的 last term/index */
    static long[] bounds(List<RaftLogEntry> entries) {
        if (entries.isEmpty()) {
            return new long[]{0L, 0L};
        }
        RaftLogEntry last = entries.get(entries.size() - 1);
        return new long[]{last.term(), last.index()};
    }
}

/** 日志条目：term + index + 命令 */
record RaftLogEntry(long term, long index, String command) {

    static RaftLogEntry of(long term, long index, String command) {
        return new RaftLogEntry(term, index, command);
    }

    static List<RaftLogEntry> batch(long term, long fromIndex, List<String> commands) {
        List<RaftLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            entries.add(new RaftLogEntry(term, fromIndex + i, commands.get(i)));
        }
        return entries;
    }
}
