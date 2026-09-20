package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照与截断（工单 0516 BJ5）。
 * 日志压缩 installSnapshot/已快照前缀截断/快照元数据（lastIncludedIndex/Term）/
 * 恢复重建状态机与逐条重放等价。
 */
public class SnapshotHandler {

    /** 快照：最后包含 index/term + 状态机数据 */
    public record Snapshot(long lastIncludedIndex, long lastIncludedTerm, String stateMachineData) {
    }

    private final List<RaftLogEntry> log = new ArrayList<>();
    private long baseIndex;
    private long baseTerm;

    /** 写入日志（测试/重建用） */
    public synchronized void append(RaftLogEntry entry) {
        log.add(entry);
    }

    /** installSnapshot：截断 ≤ lastIncluded 前缀，保留之后日志（等价重建） */
    public synchronized void install(Snapshot snapshot) {
        if (snapshot.lastIncludedIndex() < baseIndex) {
            throw new IllegalArgumentException("快照前缀不可回退: " + snapshot.lastIncludedIndex());
        }
        log.removeIf(entry -> entry.index() <= snapshot.lastIncludedIndex());
        baseIndex = snapshot.lastIncludedIndex();
        baseTerm = snapshot.lastIncludedTerm();
    }

    /** 快照后剩余日志 */
    public synchronized List<RaftLogEntry> remainingLog() {
        return List.copyOf(log);
    }

    /** 重建状态机：快照数据 + 剩余日志逐条重放（与全量重放等价由测试断言） */
    public static String restore(Snapshot snapshot, List<RaftLogEntry> remaining) {
        StringBuilder state = new StringBuilder(snapshot.stateMachineData());
        for (RaftLogEntry entry : remaining) {
            state.append(entry.command()).append(';');
        }
        return state.toString();
    }

    /** 全量重放基准（等价对照） */
    public static String replayAll(List<RaftLogEntry> entries, String initialState) {
        StringBuilder state = new StringBuilder(initialState);
        for (RaftLogEntry entry : entries) {
            state.append(entry.command()).append(';');
        }
        return state.toString();
    }

    public synchronized long baseIndex() {
        return baseIndex;
    }

    public synchronized long baseTerm() {
        return baseTerm;
    }
}
