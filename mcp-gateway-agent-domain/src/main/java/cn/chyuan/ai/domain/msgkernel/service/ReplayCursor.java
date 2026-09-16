package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 重放与游标缺口检测（工单 0445 BB3）。
 * 按偏移区间/按时间戳范围两种重放计划（WAL 扫描产出重放序列）
 * + 连续偏移断号缺口报告（跨段）+ 同计划重放幂等。纯函数内核。
 */
public class ReplayCursor {

    /** 缺口 */
    public record Gap(long from, long toExclusive) {
    }

    /** 重放计划 */
    public record Plan(List<WriteAheadLog.Entry> entries, List<Gap> gaps) {
    }

    /** 按偏移区间重放 */
    public Plan replayByOffsets(WriteAheadLog wal, long from, long to) {
        List<WriteAheadLog.Entry> entries = wal.scan(from, to);
        return new Plan(entries, detectGaps(entries, from, to));
    }

    /** 按时间戳范围重放 [fromMs, toMs] */
    public Plan replayByTime(WriteAheadLog wal, long fromMs, long toMs) {
        List<WriteAheadLog.Entry> all = wal.scan(0, wal.tail());
        List<WriteAheadLog.Entry> matched = new ArrayList<>();
        for (WriteAheadLog.Entry entry : all) {
            if (entry.appendedAtMs() >= fromMs && entry.appendedAtMs() <= toMs) {
                matched.add(entry);
            }
        }
        return new Plan(matched, detectGaps(matched, 0, wal.tail()));
    }

    /** 缺口检测：序列内相邻偏移差 >1 即断号（区间外尾部不补报告） */
    public List<Gap> detectGaps(List<WriteAheadLog.Entry> entries, long from, long to) {
        List<Gap> gaps = new ArrayList<>();
        if (entries.isEmpty()) {
            if (to > from) {
                gaps.add(new Gap(from, to));
            }
            return gaps;
        }
        long expected = entries.get(0).offset();
        for (WriteAheadLog.Entry entry : entries) {
            if (entry.offset() > expected) {
                gaps.add(new Gap(expected, entry.offset()));
            }
            expected = entry.offset() + 1;
        }
        return gaps;
    }
}
