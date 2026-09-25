package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * informer 事件流（工单 0794 CP2，kubernetes 控制器思想）。
 * 增删改事件入队/同资源键去重合并为最近事件（保首现位置序）/处理器注册与派发。
 */
public final class Informer {

    public enum Type { ADD, UPDATE, DELETE }

    public record Event(Type type, Resource resource) {
        public String key() {
            return resource.key();
        }
    }

    public interface Handler {
        void on(Event event);
    }

    private final List<Event> pending = new ArrayList<>();
    private final List<Handler> handlers = new ArrayList<>();

    public void addHandler(Handler handler) {
        handlers.add(handler);
    }

    /** 事件入队：同键已存在则原位合并为最近事件 */
    public void emit(Event event) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).key().equals(event.key())) {
                pending.set(i, event);
                return;
            }
        }
        pending.add(event);
    }

    /** 派发：清空缓冲并逐事件通知处理器（事件序=首现序） */
    public List<Event> drain() {
        List<Event> drained = new ArrayList<>(pending);
        pending.clear();
        for (Event e : drained) {
            for (Handler h : handlers) {
                h.on(e);
            }
        }
        return drained;
    }

    public int pendingCount() {
        return pending.size();
    }
}
