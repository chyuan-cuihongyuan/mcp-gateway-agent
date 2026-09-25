package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * finalizer 两阶段删除（工单 0798 CP6，kubernetes 思想）。
 * 删除标记与 finalizer 集合/清理器注册执行并摘除自身 finalizer/集合清空才真删/卡死检测。
 */
public final class Finalizers {

    private final Map<String, Set<String>> items = new HashMap<>();
    private final Set<String> deleting = new HashSet<>();
    private final Map<String, Consumer<String>> cleaners = new HashMap<>();

    /** 登记 finalizer（键维度的延迟删除门槛） */
    public void add(String key, String name) {
        items.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(name);
    }

    /** 注册清理器：按 finalizer 名，入参为资源键，清理器内部调用 clear 摘除自身 */
    public void registerCleaner(String name, Consumer<String> cleaner) {
        cleaners.put(name, cleaner);
    }

    /** 显式摘除 finalizer */
    public boolean clear(String key, String name) {
        Set<String> set = items.get(key);
        return set != null && set.remove(name);
    }

    /** 第一阶段：标记删除并执行清理器；finalizer 清空才真删，返回是否已真删 */
    public boolean delete(String key) {
        deleting.add(key);
        Set<String> set = items.getOrDefault(key, new LinkedHashSet<>());
        for (String name : new LinkedHashSet<>(set)) {
            Consumer<String> cleaner = cleaners.get(name);
            if (cleaner != null) {
                cleaner.accept(key);
            }
        }
        if (items.getOrDefault(key, Set.of()).isEmpty()) {
            items.remove(key);
            deleting.remove(key);
            return true;
        }
        return false;
    }

    /** 卡死检测：已标记删除但 finalizer 迟迟不清 */
    public boolean stuck(String key) {
        return deleting.contains(key) && !items.getOrDefault(key, Set.of()).isEmpty();
    }

    public Set<String> of(String key) {
        return Set.copyOf(items.getOrDefault(key, Set.of()));
    }

    public boolean isDeleting(String key) {
        return deleting.contains(key);
    }
}
