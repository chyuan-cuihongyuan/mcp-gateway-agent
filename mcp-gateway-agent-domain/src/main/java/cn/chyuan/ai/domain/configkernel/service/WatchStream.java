package cn.chyuan.ai.domain.configkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * watch 事件流（工单 0465 BD2，etcd watch 思想）。
 * 从指定 revision 订阅 PUT/DELETE 事件 + 键前缀过滤 + 历史补发
 * （从指定 revision 重放，含该 revision 本身的事件）。事件日志由写入方追加。
 */
public class WatchStream {

    /** 事件类型 */
    public enum EventType {
        PUT, DELETE
    }

    /** watch 事件：revision + 类型 + 键 + 值（DELETE 时为 null） */
    public record Event(long revision, EventType type, String key, String value) {
    }

    private final List<Event> eventLog = new ArrayList<>();

    /** 写入方追加事件（revision 须与 MvccStore 分配一致，由调用方保证） */
    public synchronized void append(Event event) {
        eventLog.add(event);
    }

    /** 历史补发：重放 revision ≥ fromRevision 且键前缀匹配的事件（有序） */
    public synchronized List<Event> replayFrom(long fromRevision, String keyPrefix) {
        if (fromRevision <= 0) {
            throw new IllegalArgumentException("起点 revision 须 > 0: " + fromRevision);
        }
        List<Event> matched = new ArrayList<>();
        for (Event event : eventLog) {
            if (event.revision() >= fromRevision && matchesPrefix(event.key(), keyPrefix)) {
                matched.add(event);
            }
        }
        return List.copyOf(matched);
    }

    /** 增量订阅：返回 revision > afterRevision 的前缀匹配事件（严格大于） */
    public synchronized List<Event> after(long afterRevision, String keyPrefix) {
        List<Event> matched = new ArrayList<>();
        for (Event event : eventLog) {
            if (event.revision() > afterRevision && matchesPrefix(event.key(), keyPrefix)) {
                matched.add(event);
            }
        }
        return List.copyOf(matched);
    }

    public synchronized long lastRevision() {
        return eventLog.isEmpty() ? 0L : eventLog.get(eventLog.size() - 1).revision();
    }

    public synchronized int eventCount() {
        return eventLog.size();
    }

    private boolean matchesPrefix(String key, String keyPrefix) {
        return keyPrefix == null || keyPrefix.isEmpty() || key.startsWith(keyPrefix);
    }
}
