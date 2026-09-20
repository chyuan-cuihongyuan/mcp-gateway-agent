package cn.chyuan.ai.domain.raftkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一致性内核 BJ1-BJ7 单测（工单 0512-0518）：
 * 任期投票/日志复制回退/提交多数派/选举超时/快照等价/单步成员变更/ReadIndex。
 */
class RaftKernelTest {

    @Test
    void BJ1_任期单调与投票约束() {
        RaftState state = new RaftState();
        assertEquals(0, state.currentTerm());
        state.observeTerm(5);
        assertEquals(5, state.currentTerm());
        assertThrows(IllegalArgumentException.class, () -> state.observeTerm(3), "任期不可回退");
        assertEquals(RaftState.VoteResult.GRANTED,
                state.grantVote(5, 10, 4, 8, 3), "同任期首票授予（日志新度足）");
        assertTrue(state.hasVotedThisTerm());
        assertEquals(RaftState.VoteResult.REJECTED_VOTED,
                state.grantVote(5, 10, 4, 8, 3), "单任期单投票");
        assertEquals(RaftState.VoteResult.REJECTED_STALE,
                state.grantVote(4, 10, 4, 8, 3), "旧候选拒绝");
        assertEquals(RaftState.VoteResult.REJECTED_NOT_FRESH,
                state.grantVote(6, 7, 2, 8, 3), "日志落后拒绝（lastTerm 小）");
        assertEquals(RaftState.VoteResult.REJECTED_NOT_FRESH,
                state.grantVote(6, 8, 2, 8, 3), "lastTerm 相同 lastIndex 落后拒绝");
        assertFalse(state.hasVotedThisTerm(), "拒绝后未消耗选票（term 已推进到 6）");
    }

    @Test
    void BJ2_AppendEntries匹配与回退() {
        LogReplicator follower = new LogReplicator();
        var first = follower.append(0, 0, RaftLogEntry.batch(1, 1, List.of("a", "b")));
        assertTrue(first.success());
        assertEquals(2, first.matchIndex());
        var mismatch = follower.append(5, 1, List.of());
        assertFalse(mismatch.success(), "prevLogIndex 超界失败");
        var wrongTerm = follower.append(1, 2, List.of());
        assertFalse(wrongTerm.success(), "prevLogTerm 不符失败");
        assertEquals(1, wrongTerm.conflictIndex());
        var conflict = follower.append(1, 1, RaftLogEntry.batch(9, 2, List.of("x")));
        assertTrue(conflict.success(), "冲突位置截断后写入");
        assertEquals(2, follower.lastIndex());
        assertEquals(9, follower.lastTerm(), "旧后缀被截断替换");
        var idempotent = follower.append(1, 1, RaftLogEntry.batch(9, 2, List.of("x")));
        assertTrue(idempotent.success() && follower.lastIndex() == 2, "重放同消息幂等");
        assertEquals(3, LogReplicator.nextIndexAfterFailure(4,
                LogReplicator.AppendResult.fail(0, 0), term -> 0L), "无 conflict term 单步回退");
        assertEquals(3, LogReplicator.nextIndexAfterFailure(4,
                LogReplicator.AppendResult.fail(7, 2), term -> term == 7 ? 2 : 0), "conflict term 批量回退");
    }

    @Test
    void BJ3_多数派提交推进与按序应用() {
        CommitTracker tracker = new CommitTracker(3);
        tracker.reportMatch(0, 3);
        tracker.reportMatch(1, 3);
        tracker.reportMatch(2, 1);
        assertEquals(3, tracker.advance(), "2/3 多数派推进到 3");
        tracker.reportMatch(1, 2);
        tracker.reportMatch(1, 5);
        tracker.reportMatch(2, 5);
        tracker.reportMatch(0, 5);
        assertEquals(5, tracker.advance());
        assertEquals(5, tracker.commitIndex());
        List<RaftLogEntry> entries = RaftLogEntry.batch(1, 1, List.of("a", "b", "c", "d", "e"));
        assertEquals(List.of("a", "b", "c", "d", "e"), tracker.pendingCommands(entries), "已提交区间按序应用");
        assertEquals(5, tracker.commitIndex(), "commitIndex 只前进");
        assertEquals(0, new CommitTracker(3).advance(), "空 matchIndex 不越界推进");
    }

    @Test
    void BJ4_随机化超时与心跳重置() {
        ElectionTimer timer = new ElectionTimer(150, 300, () -> 0.5);
        long timeout = timer.randomizedTimeout(1000);
        assertTrue(timeout >= 150 && timeout < 300, "区间 [min, max)");
        assertFalse(timer.expired(1000 + 200));
        assertTrue(timer.expired(1000 + 225), "到时触发");
        timer.onHeartbeat(1200);
        assertFalse(timer.expired(1300), "心跳重置后未到期");
        ElectionTimer fixed = new ElectionTimer(100, 200, () -> 0.0);
        fixed.randomizedTimeout(0);
        assertEquals(100, fixed.remaining(0));
        assertEquals(100, fixed.remaining(0), "同随机端口同超时（确定性）");
        assertThrows(IllegalArgumentException.class, () -> new ElectionTimer(0, 1, () -> 0));
    }

    @Test
    void BJ5_快照截断与重建等价() {
        SnapshotHandler handler = new SnapshotHandler();
        List<RaftLogEntry> all = RaftLogEntry.batch(1, 1, List.of("a", "b", "c", "d"));
        all.forEach(handler::append);
        handler.install(new SnapshotHandler.Snapshot(2, 1, "a;b;"));
        assertEquals(2, handler.baseIndex());
        assertEquals(1, handler.baseTerm());
        assertEquals(2, handler.remainingLog().size(), "前缀截断保留后续");
        assertEquals("a;b;c;d;",
                SnapshotHandler.restore(new SnapshotHandler.Snapshot(2, 1, "a;b;"),
                        handler.remainingLog()),
                "快照重建与全量重放等价");
        assertEquals("a;b;c;d;", SnapshotHandler.replayAll(all, ""), "全量重放基准");
        assertThrows(IllegalArgumentException.class,
                () -> handler.install(new SnapshotHandler.Snapshot(1, 1, "")), "快照前缀不可回退");
    }

    @Test
    void BJ6_单步成员变更() {
        MembershipChange change = new MembershipChange();
        assertEquals(2, change.config().majority());
        change.propose(MembershipChange.Op.ADD, "n4");
        assertTrue(change.hasPendingChange());
        assertThrows(IllegalStateException.class,
                () -> change.propose(MembershipChange.Op.REMOVE, "n1"), "未决变更拒绝并发");
        assertEquals(3, change.config().majority(), "新配置 n1-n4 多数派 3");
        change.committed();
        assertFalse(change.hasPendingChange());
        change.propose(MembershipChange.Op.ADD, "n4");
        assertEquals(4, change.config().members().size(), "重复添加幂等不变更");
        change.committed();
        change.propose(MembershipChange.Op.REMOVE, "n1");
        assertEquals(3, change.config().members().size());
        change.committed();
        change.propose(MembershipChange.Op.REMOVE, "n2");
        change.committed();
        change.propose(MembershipChange.Op.REMOVE, "n3");
        change.committed();
        assertThrows(IllegalStateException.class, () -> change.propose(MembershipChange.Op.REMOVE, "n4"),
                "不可删除最后节点");
    }

    @Test
    void BJ7_ReadIndex线性一致读() {
        ReadIndexProtocol protocol = new ReadIndexProtocol(() -> true, () -> 7L);
        ReadIndexProtocol.ReadTicket first = protocol.request();
        assertEquals(7, first.readIndex(), "read index = 当前 commitIndex");
        ReadIndexProtocol.ReadTicket second = protocol.request();
        assertEquals(List.of(first, second), protocol.drained(), "按 read index/序列有序出队");
        assertTrue(ReadIndexProtocol.visibleAt(7, 7), "已应用到位读可见");
        assertFalse(ReadIndexProtocol.visibleAt(6, 7), "应用落后于读点不可见");
        ReadIndexProtocol unconfirmed = new ReadIndexProtocol(() -> false, () -> 0L);
        assertThrows(IllegalStateException.class, unconfirmed::request, "leadership 未确认拒绝");
    }
}
