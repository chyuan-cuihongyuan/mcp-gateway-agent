package cn.chyuan.ai.domain.streamkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 断流恢复游标与重放（工单 0376 AT6）。
 * 事件序号游标 + 有界环形缓冲；重连携带 Last-Event-ID → 从游标重放；
 * 游标早于最旧缓冲 → 缺口标记（不可完整重放）；重放不重不漏（at-least-once）、序列与原序一致。
 */
public class ResumeReplayBuffer<T> {

    private record Entry<T>(long seq, T event) {
    }

    private final int capacity;
    private final ArrayDeque<Entry<T>> ring = new ArrayDeque<>();
    private long oldestSeq = -1;
    private long newestSeq = -1;

    public ResumeReplayBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("缓冲容量至少 1");
        }
        this.capacity = capacity;
    }

    /** 追加事件（seq 必须严格递增） */
    public void offer(long seq, T event) {
        if (seq <= newestSeq) {
            throw new IllegalArgumentException("事件序号必须严格递增: " + seq);
        }
        ring.addLast(new Entry<>(seq, event));
        newestSeq = seq;
        if (oldestSeq < 0) {
            oldestSeq = seq;
        }
        while (ring.size() > capacity) {
            ring.pollFirst();
            oldestSeq = ring.isEmpty() ? -1 : ring.peekFirst().seq();
        }
    }

    /** 重放结果：events=重放序列（seq>from，原序），gap=游标早于最旧缓冲存在缺口 */
    public record ReplayResult<T>(List<T> events, boolean gap, long oldestAvailable, long newestAvailable) {
    }

    /**
     * 从游标重放：from 为客户端已收到的最后序号（-1 表示从头）。
     */
    public ReplayResult<T> replay(long from) {
        List<T> events = new ArrayList<>();
        boolean gap = from >= 0 && oldestSeq >= 0 && from < oldestSeq - 1;
        for (Entry<T> entry : ring) {
            if (entry.seq() > from) {
                events.add(entry.event());
            }
        }
        return new ReplayResult<>(events, gap, oldestSeq, newestSeq);
    }

    /** 重放幂等：同游标重复重放同序列（观测辅助） */
    public boolean replayIdempotent(long from) {
        ReplayResult<T> first = replay(from);
        ReplayResult<T> second = replay(from);
        return first.events().equals(second.events()) && first.gap() == second.gap();
    }

    /** 缓冲当前规模 */
    public int size() {
        return ring.size();
    }
}
