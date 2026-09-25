package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * owner 引用与级联（工单 0797 CP5，kubernetes GC 思想）。
 * owner 引用登记/级联删除子孙有序（深者先）/孤儿回收/环引用拒绝。
 */
public final class OwnerReferences {

    private final Map<String, String> ownerOf = new LinkedHashMap<>();

    /** 登记 owner→child；沿 owner 链上溯遇 child 即环，拒绝 */
    public void register(String owner, String child) {
        String cur = owner;
        Set<String> visited = new HashSet<>();
        while (cur != null) {
            if (cur.equals(child)) {
                throw new IllegalArgumentException("环引用拒绝: " + child);
            }
            if (!visited.add(cur)) {
                throw new IllegalArgumentException("既有环: " + cur);
            }
            cur = ownerOf.get(cur);
        }
        ownerOf.put(child, owner);
    }

    public String ownerOf(String key) {
        return ownerOf.get(key);
    }

    /** 级联删除：返回全部子孙 key，按深度降序（最深的子孙先删） */
    public List<String> cascadeDelete(String root) {
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(root);
        depth.put(root, 0);
        List<String> descendants = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Map.Entry<String, String> e : ownerOf.entrySet()) {
                if (e.getValue().equals(cur) && !depth.containsKey(e.getKey())) {
                    depth.put(e.getKey(), depth.get(cur) + 1);
                    descendants.add(e.getKey());
                    queue.add(e.getKey());
                }
            }
        }
        descendants.sort((a, b) -> Integer.compare(depth.get(b), depth.get(a)));
        descendants.forEach(ownerOf::remove);
        return descendants;
    }

    /** 孤儿回收：owner 不在存活集合中的子节点 */
    public List<String> orphans(Set<String> alive) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ownerOf.entrySet()) {
            if (!alive.contains(e.getValue())) {
                out.add(e.getKey());
            }
        }
        out.forEach(ownerOf::remove);
        return out;
    }
}
