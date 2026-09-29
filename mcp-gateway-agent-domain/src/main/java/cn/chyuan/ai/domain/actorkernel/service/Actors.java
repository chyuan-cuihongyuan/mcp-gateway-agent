package cn.chyuan.ai.domain.actorkernel.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 类型与 ID 注册激活（工单 1005 EI1，dapr 虚拟Actor 思想）。
 * actor 类型注册/ID 按需激活幂等/未注册类型拒绝/跨类型同 ID 互不可见。
 */
public final class Actors {

    /** actor 实例：身份键 + 内存状态 */
    public static final class Actor {
        final String type;
        final String id;
        final Map<String, Object> state = new HashMap<>();

        Actor(String type, String id) {
            this.type = type;
            this.id = id;
        }

        public String key() {
            return type + "/" + id;
        }

        public void putState(String k, Object v) {
            state.put(k, v);
        }

        public Object getState(String k) {
            return state.get(k);
        }

        public Map<String, Object> snapshotState() {
            return new HashMap<>(state);
        }
    }

    private final Map<String, Actor> active = new LinkedHashMap<>();

    /** 类型注册：重复注册幂等 */
    public synchronized void registerType(String type) {
        // 类型表只做存在性校验，无需存储结构——登记即留痕
        registeredTypes.add(type);
    }

    private final java.util.Set<String> registeredTypes = new java.util.HashSet<>();

    /** 按需激活：已激活幂等返回同实例；未注册类型拒绝 */
    public synchronized Actor activate(String type, String id) {
        requireType(type);
        String key = type + "/" + id;
        Actor existing = active.get(key);
        if (existing != null) {
            return existing;
        }
        Actor actor = new Actor(type, id);
        active.put(key, actor);
        return actor;
    }

    /** 失活：移除实例与状态；未激活拒绝 */
    public synchronized Actor deactivate(String type, String id) {
        requireType(type);
        String key = type + "/" + id;
        Actor actor = active.remove(key);
        if (actor == null) {
            throw new IllegalStateException("actor 未激活: " + key);
        }
        return actor;
    }

    public synchronized boolean isActive(String type, String id) {
        return active.containsKey(type + "/" + id);
    }

    public synchronized Actor get(String type, String id) {
        Actor actor = active.get(type + "/" + id);
        if (actor == null) {
            throw new IllegalStateException("actor 未激活: " + type + "/" + id);
        }
        return actor;
    }

    public synchronized int activeCount() {
        return active.size();
    }

    /** 未注册类型拒绝 */
    public synchronized void requireType(String type) {
        if (!registeredTypes.contains(type)) {
            throw new IllegalStateException("未注册 actor 类型: " + type);
        }
    }
}
