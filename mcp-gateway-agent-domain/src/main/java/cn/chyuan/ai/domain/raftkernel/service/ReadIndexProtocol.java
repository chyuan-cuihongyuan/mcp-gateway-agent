package cn.chyuan.ai.domain.raftkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * ReadIndex 线性一致读（工单 0518 BJ7）。
 * leadership 确认（心跳多数派应答）/read index 记录（当前 commitIndex）/
 * 读请求按 index 排序于提交点之后返回。
 */
public class ReadIndexProtocol {

    /** 读请求票据 */
    public record ReadTicket(long readIndex, long sequence) {
    }

    private final java.util.function.Supplier<Boolean> leadershipConfirmed;
    private final java.util.function.LongSupplier commitIndexSupplier;
    private final List<ReadTicket> outstanding = new ArrayList<>();
    private long sequence;

    public ReadIndexProtocol(java.util.function.Supplier<Boolean> leadershipConfirmed,
            java.util.function.LongSupplier commitIndexSupplier) {
        this.leadershipConfirmed = leadershipConfirmed;
        this.commitIndexSupplier = commitIndexSupplier;
    }

    /** 发起读：leadership 未确认拒绝；确认后以当前 commitIndex 作为 read index */
    public synchronized ReadTicket request() {
        if (!leadershipConfirmed.get()) {
            throw new IllegalStateException("leadership 未确认（心跳多数派未应答），拒绝线性一致读");
        }
        ReadTicket ticket = new ReadTicket(commitIndexSupplier.getAsLong(), ++sequence);
        outstanding.add(ticket);
        return ticket;
    }

    /** 读完成：按 read index 排序出队（提交点之后的读按序返回） */
    public synchronized List<ReadTicket> drained() {
        List<ReadTicket> ordered = new ArrayList<>(outstanding);
        ordered.sort(java.util.Comparator.comparingLong(ReadTicket::readIndex)
                .thenComparingLong(ReadTicket::sequence));
        outstanding.clear();
        return List.copyOf(ordered);
    }

    /** 读结果是否可见于提交点（appliedIndex ≥ readIndex） */
    public static boolean visibleAt(long appliedIndex, long readIndex) {
        return appliedIndex >= readIndex;
    }
}
