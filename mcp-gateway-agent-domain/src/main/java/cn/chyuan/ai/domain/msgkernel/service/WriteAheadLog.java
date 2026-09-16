package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * WAL 追加日志（工单 0443 BB1，NSQ/redpanda 段式日志思想）。
 * 追加写单调偏移分配 + 段大小上限滚动（旧段只读列表）+ 按偏移区间跨段扫描 + 越界拒绝。
 * 内存段进程内实现，纯函数内核。
 */
public class WriteAheadLog {

    /** 日志条目 */
    public record Entry(long offset, String topic, String payload, long appendedAtMs) {
    }

    /** 段 */
    static final class Segment {
        final long baseOffset;
        final List<Entry> entries = new ArrayList<>();

        Segment(long baseOffset) {
            this.baseOffset = baseOffset;
        }
    }

    private final int maxSegmentSize;
    private final List<Segment> sealed = new ArrayList<>();
    private Segment active;
    private long nextOffset;

    public WriteAheadLog(int maxSegmentSize, long clockMs) {
        if (maxSegmentSize < 1) {
            throw new IllegalArgumentException("段大小上限至少 1");
        }
        this.maxSegmentSize = maxSegmentSize;
        this.active = new Segment(0);
        this.nextOffset = 0;
    }

    /** 追加：返回分配的单调偏移 */
    public synchronized long append(String topic, String payload, long nowMs) {
        if (active.entries.size() >= maxSegmentSize) {
            sealed.add(active);
            active = new Segment(nextOffset);
        }
        long offset = nextOffset++;
        active.entries.add(new Entry(offset, topic, payload, nowMs));
        return offset;
    }

    /** 按偏移区间扫描 [from, to)（跨段拼接）；越界（from>当前尾部）拒绝 */
    public synchronized List<Entry> scan(long from, long to) {
        if (from < 0 || to > nextOffset || from > to) {
            throw new IllegalArgumentException("扫描区间越界: [" + from + "," + to + ") 尾部=" + nextOffset);
        }
        List<Entry> out = new ArrayList<>();
        for (Segment segment : sealed) {
            collect(segment, from, to, out);
        }
        collect(active, from, to, out);
        return out;
    }

    private void collect(Segment segment, long from, long to, List<Entry> out) {
        for (Entry entry : segment.entries) {
            if (entry.offset() >= from && entry.offset() < to) {
                out.add(entry);
            }
        }
    }

    /** 当前尾部（下一条偏移） */
    public synchronized long tail() {
        return nextOffset;
    }

    /** 段数（含活跃段） */
    public synchronized int segmentCount() {
        return sealed.size() + 1;
    }
}
