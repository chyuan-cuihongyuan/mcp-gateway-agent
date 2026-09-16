package cn.chyuan.ai.domain.msgkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费组偏移（工单 0444 BB2，nats/redpanda 消费组口径）。
 * 组×主题→（已投递 delivered/已提交 committed）双游标 + fetch 从 delivered 起批量取
 * + commit 只能前进（回退拒绝）+ 新组初始位（最早/最新）+ 滞后 lag=尾部-committed。
 */
public class ConsumerOffsetRegistry {

    /** 初始位 */
    public enum Initial {
        EARLIEST, LATEST
    }

    /** fetch 批次 */
    public record Batch(String group, String topic, List<WriteAheadLog.Entry> entries, long fromOffset) {
    }

    private static final class Cursor {
        long delivered;
        long committed;
    }

    private final Initial initial;
    private final Map<String, Map<String, Cursor>> cursors = new HashMap<>();

    public ConsumerOffsetRegistry(Initial initial) {
        this.initial = initial;
    }

    /** 从 WAL 取一批（组游标推进 delivered） */
    public synchronized Batch fetch(WriteAheadLog wal, String group, String topic, int maxEntries) {
        Cursor cursor = cursorOf(group, topic, wal.tail());
        long from = cursor.delivered;
        long to = Math.min(wal.tail(), from + Math.max(1, maxEntries));
        List<WriteAheadLog.Entry> entries = to > from ? wal.scan(from, to) : List.of();
        cursor.delivered = to;
        return new Batch(group, topic, entries, from);
    }

    /** 提交偏移（只能前进，回退拒绝返回 false） */
    public synchronized boolean commit(String group, String topic, long offset) {
        Cursor cursor = cursorOf(group, topic, offset + 1);
        if (offset < cursor.committed || offset > cursor.delivered) {
            return false;
        }
        cursor.committed = offset + 1;
        return true;
    }

    /** 滞后 = 主题尾部 - committed */
    public synchronized long lag(WriteAheadLog wal, String group, String topic) {
        return wal.tail() - cursorOf(group, topic, wal.tail()).committed;
    }

    public synchronized long committed(String group, String topic) {
        Map<String, Cursor> topics = cursors.get(group);
        Cursor cursor = topics == null ? null : topics.get(topic);
        return cursor == null ? 0 : cursor.committed;
    }

    private Cursor cursorOf(String group, String topic, long tail) {
        return cursors.computeIfAbsent(group, k -> new HashMap<>())
                .computeIfAbsent(topic, k -> {
                    Cursor cursor = new Cursor();
                    cursor.delivered = initial == Initial.LATEST ? tail : 0;
                    cursor.committed = initial == Initial.LATEST ? tail : 0;
                    return cursor;
                });
    }
}
