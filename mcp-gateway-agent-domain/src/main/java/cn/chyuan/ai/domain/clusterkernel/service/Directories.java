package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务目录（工单 1082 ER1，dubbo 思想）。
 * 提供者注册/下线即时摘除/订阅变更推送（JOIN·LEAVE 事件）/重复注册幂等、未注册下线拒绝。
 */
public final class Directories {

    /** 目录事件 */
    public enum EventType { JOIN, LEAVE }

    /** 目录事件值对象 */
    public record DirectoryEvent(String service, String provider, EventType type) {
    }

    /** 订阅者：目录变更推送 */
    public interface Listener {
        void onChange(DirectoryEvent event);
    }

    private final Map<String, List<String>> providers = new LinkedHashMap<>();
    private final Map<String, List<Listener>> listeners = new LinkedHashMap<>();
    private final List<DirectoryEvent> events = new CopyOnWriteArrayList<>();

    /** 注册：重复幂等；推送 JOIN */
    public void register(String service, String provider) {
        requireService(service);
        requireProvider(provider);
        List<String> list = providers.computeIfAbsent(service, key -> new ArrayList<>());
        if (list.contains(provider)) {
            return;
        }
        list.add(provider);
        DirectoryEvent event = new DirectoryEvent(service, provider, EventType.JOIN);
        events.add(event);
        notify(service, event);
    }

    /** 下线：即时摘除并推送 LEAVE；未注册拒绝 */
    public void unregister(String service, String provider) {
        List<String> list = providers.get(service);
        if (list == null || !list.remove(provider)) {
            throw new IllegalArgumentException("未注册提供者拒绝下线: " + service + "/" + provider);
        }
        DirectoryEvent event = new DirectoryEvent(service, provider, EventType.LEAVE);
        events.add(event);
        notify(service, event);
    }

    /** 订阅变更 */
    public void subscribe(String service, Listener listener) {
        requireService(service);
        if (listener == null) {
            throw new IllegalArgumentException("订阅者不能为空");
        }
        listeners.computeIfAbsent(service, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public List<String> providers(String service) {
        List<String> list = providers.get(service);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    public List<DirectoryEvent> events() {
        return new ArrayList<>(events);
    }

    private void notify(String service, DirectoryEvent event) {
        List<Listener> subs = listeners.get(service);
        if (subs == null) {
            return;
        }
        for (Listener listener : subs) {
            listener.onChange(event);
        }
    }

    private void requireService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("服务名不能为空");
        }
    }

    private void requireProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("提供者不能为空");
        }
    }
}
