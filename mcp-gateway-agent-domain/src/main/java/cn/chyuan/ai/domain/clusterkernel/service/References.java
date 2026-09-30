package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 引用计数（工单 1088 ER7，dubbo 思想）。
 * acquire 递增/release 递减/归零触发销毁回调/销毁后 acquire 拒绝。
 */
public final class References {

    private static final class Ref {
        int count;
        boolean destroyed;
        Runnable onDestroy;
    }

    private final Map<String, Ref> refs = new HashMap<>();

    /** 建立引用（可带销毁回调）：销毁后拒绝重建 */
    public void acquire(String key, Runnable onDestroy) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("引用键不能为空");
        }
        Ref ref = refs.get(key);
        if (ref == null) {
            ref = new Ref();
            ref.onDestroy = onDestroy;
            refs.put(key, ref);
        } else if (ref.destroyed) {
            throw new IllegalStateException("已销毁引用拒绝 acquire: " + key);
        }
        if (onDestroy != null && ref.onDestroy == null) {
            ref.onDestroy = onDestroy;
        }
        ref.count++;
    }

    public void acquire(String key) {
        acquire(key, null);
    }

    /** 释放引用：归零触发销毁回调；未建立拒绝 */
    public void release(String key) {
        Ref ref = refs.get(key);
        if (ref == null) {
            throw new IllegalArgumentException("未建立引用拒绝 release: " + key);
        }
        if (ref.destroyed) {
            throw new IllegalStateException("已销毁引用拒绝 release: " + key);
        }
        ref.count--;
        if (ref.count == 0) {
            ref.destroyed = true;
            if (ref.onDestroy != null) {
                ref.onDestroy.run();
            }
        }
    }

    public int count(String key) {
        Ref ref = refs.get(key);
        return ref == null ? 0 : ref.count;
    }

    public boolean destroyed(String key) {
        Ref ref = refs.get(key);
        return ref != null && ref.destroyed;
    }
}
