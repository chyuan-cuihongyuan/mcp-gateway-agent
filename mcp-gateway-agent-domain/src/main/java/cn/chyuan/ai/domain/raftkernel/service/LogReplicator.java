package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志复制（工单 0513 BJ2）。
 * AppendEntries 一致性匹配（prevLogIndex/prevLogTerm 校验）/
 * nextIndex 单步回退/conflict term 批量回退/follower 冲突截断幂等
 * （重放同消息结果一致）。
 */
public class LogReplicator {

    /** follower AppendEntries 应答 */
    public record AppendResult(boolean success, long conflictTerm, long conflictIndex, long matchIndex) {
        public static AppendResult ok(long matchIndex) {
            return new AppendResult(true, 0L, 0L, matchIndex);
        }

        public static AppendResult fail(long conflictTerm, long conflictIndex) {
            return new AppendResult(false, conflictTerm, conflictIndex, 0L);
        }
    }

    private final List<RaftLogEntry> log = new ArrayList<>();
    private long baseIndex;

    /** follower 追加：prevLog 校验 → 冲突截断 → 追加（幂等：同 index 同 term 重复写入不重复） */
    public synchronized AppendResult append(long prevLogIndex, long prevLogTerm, List<RaftLogEntry> entries) {
        if (prevLogIndex > lastIndex()) {
            return AppendResult.fail(0L, lastIndex() + 1);
        }
        if (prevLogIndex > baseIndex && log.get((int) (prevLogIndex - baseIndex - 1)).term() != prevLogTerm) {
            return AppendResult.fail(termAt(prevLogIndex), prevLogIndex);
        }
        for (int i = 0; i < entries.size(); i++) {
            RaftLogEntry entry = entries.get(i);
            long position = entry.index();
            if (position <= lastIndex() && termAt(position) != entry.term()) {
                truncateFrom(position);
            }
            if (position > lastIndex()) {
                log.add(entry);
            }
        }
        return AppendResult.ok(Math.max(prevLogIndex, entries.isEmpty() ? prevLogIndex
                : entries.get(entries.size() - 1).index()));
    }

    /** leader 侧：nextIndex 回退计算（先单步，遇 conflict term 批量跳至该 term 最后一条） */
    public static long nextIndexAfterFailure(long nextIndex, AppendResult failure,
            java.util.function.LongUnaryOperator lastIndexOfTerm) {
        if (failure.conflictTerm() > 0) {
            long batch = lastIndexOfTerm.applyAsLong(failure.conflictTerm());
            if (batch > 0) {
                return Math.min(nextIndex - 1, batch + 1);
            }
        }
        return Math.max(1, nextIndex - 1);
    }

    /** 本地日志条目（快照基线之后） */
    public synchronized List<RaftLogEntry> entriesFrom(long index) {
        if (index <= baseIndex) {
            return List.copyOf(log);
        }
        int from = (int) (index - baseIndex - 1);
        return from >= log.size() ? List.of() : List.copyOf(log.subList(from, log.size()));
    }

    /** 冲突截断：丢弃 index 及之后全部条目 */
    public synchronized void truncateFrom(long index) {
        while (!log.isEmpty() && log.get(log.size() - 1).index() >= index) {
            log.remove(log.size() - 1);
        }
    }

    public synchronized long lastIndex() {
        return baseIndex + log.size();
    }

    public synchronized long lastTerm() {
        return log.isEmpty() ? 0L : log.get(log.size() - 1).term();
    }

    private long termAt(long index) {
        return log.get((int) (index - baseIndex - 1)).term();
    }
}
