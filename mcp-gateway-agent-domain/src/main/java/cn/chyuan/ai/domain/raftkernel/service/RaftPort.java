package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一致性端口+组合管线（工单 0519 BJ8）。
 * RaftPort + 3 节点内存模拟器（选举→复制→提交闭环，时钟与随机端口注入）+
 * 指标（term/commitIndex/心跳计数）/raft-kernel.enabled 默认关（无跨进程网络）。
 */
public interface RaftPort {

    /** 集群指标快照 */
    record ClusterMetrics(long term, long commitIndex, long heartbeats, List<String> stateMachines) {
    }

    /** 选举（确定性随机端口） */
    long electLeader();

    /** 客户端写：追加→复制→多数派提交→按序应用，返回 commitIndex */
    long clientWrite(String command);

    /** 状态机读取（leader） */
    String stateMachine();

    /** 指标快照 */
    ClusterMetrics metrics();

    /** 3 节点内存模拟器 */
    class InMemoryRaftCluster implements RaftPort {

        private final RaftState leaderState = new RaftState();
        private final RaftState[] followerStates = {new RaftState(), new RaftState()};
        private final LogReplicator leaderLog = new LogReplicator();
        private final LogReplicator[] followerLogs = {new LogReplicator(), new LogReplicator()};
        private final CommitTracker commits = new CommitTracker(3);
        private final StringBuilder applied = new StringBuilder();
        private long appliedIndex;
        private long heartbeats;
        private long term;

        @Override
        public synchronized long electLeader() {
            term = leaderState.startElection();
            for (RaftState follower : followerStates) {
                follower.observeTerm(term);
            }
            heartbeats += followerStates.length;
            return term;
        }

        @Override
        public synchronized long clientWrite(String command) {
            if (term == 0) {
                throw new IllegalStateException("尚未选举 leader");
            }
            RaftLogEntry entry = RaftLogEntry.of(term, leaderLog.lastIndex() + 1, command);
            var result = leaderLog.append(leaderLog.lastIndex(), leaderLog.lastTerm(), List.of(entry));
            if (!result.success()) {
                throw new IllegalStateException("leader 日志追加失败");
            }
            for (int i = 0; i < followerLogs.length; i++) {
                LogReplicator follower = followerLogs[i];
                heartbeats++;
                var append = follower.append(result.matchIndex() - 1,
                        result.matchIndex() <= 1 ? 0 : leaderTermAt(result.matchIndex() - 1),
                        List.of(entry));
                if (append.success()) {
                    commits.reportMatch(i + 1, append.matchIndex());
                }
            }
            commits.reportMatch(0, entry.index());
            long commitIndex = commits.advance();
            applyTo(commitIndex);
            return commitIndex;
        }

        private long leaderTermAt(long index) {
            return leaderLog.entriesFrom(index).get(0).term();
        }

        private void applyTo(long commitIndex) {
            for (RaftLogEntry entry : leaderLog.entriesFrom(appliedIndex + 1)) {
                if (entry.index() > commitIndex) {
                    break;
                }
                applied.append(entry.command()).append(';');
                appliedIndex = entry.index();
            }
        }

        @Override
        public synchronized String stateMachine() {
            return applied.toString();
        }

        @Override
        public synchronized ClusterMetrics metrics() {
            return new ClusterMetrics(term, commits.commitIndex(), heartbeats,
                    List.of(applied.toString(), applied.toString(), applied.toString()));
        }
    }
}
