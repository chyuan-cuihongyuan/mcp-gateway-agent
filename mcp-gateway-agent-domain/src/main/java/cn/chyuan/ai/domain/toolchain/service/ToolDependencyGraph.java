package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.DependencyReportVO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 工具依赖图（工单 0336 AP6）。
 * 依赖声明（工具 → 依赖工具集）→ DAG 构建（缺失依赖自动补节点）→
 * 环检测（DFS 三色标记，报告环路径）→ Kahn 拓扑序（同层名称序稳定）。
 * domain 纯函数。
 */
public class ToolDependencyGraph {

    public DependencyReportVO analyze(Map<String, List<String>> dependencies) {
        if (dependencies == null) {
            dependencies = Map.of();
        }
        // 补全节点：缺失依赖自动建虚拟节点（无自身依赖）
        Set<String> nodes = new TreeSet<>(dependencies.keySet());
        List<String> missing = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String dep : entry.getValue()) {
                if (!declared.add(entry.getKey() + "->" + dep)) {
                    continue;
                }
                if (!dependencies.containsKey(dep)) {
                    nodes.add(dep);
                    missing.add(dep);
                }
            }
        }
        // 邻接（依赖 → 工具）与入度
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new TreeMap<>();
        for (String node : nodes) {
            inDegree.putIfAbsent(node, 0);
            edges.putIfAbsent(node, new TreeSet<>());
        }
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String dep : new TreeSet<>(entry.getValue())) {
                if (edges.get(dep).add(entry.getKey())) {
                    inDegree.merge(entry.getKey(), 1, Integer::sum);
                }
            }
        }
        // 环检测：DFS 三色标记（按名称序遍历保证确定性）
        List<String> cyclePath = findCycle(dependencies, nodes);
        // Kahn 拓扑序（同层名称序）
        Deque<String> ready = new ArrayDeque<>();
        new TreeSet<>(inDegree.keySet()).stream()
                .filter(node -> inDegree.get(node) == 0)
                .forEach(ready::addLast);
        List<String> order = new ArrayList<>();
        Map<String, Integer> working = new LinkedHashMap<>(inDegree);
        while (!ready.isEmpty()) {
            String node = ready.pollFirst();
            order.add(node);
            for (String next : edges.get(node)) {
                working.merge(next, -1, Integer::sum);
                if (working.get(next) == 0) {
                    ready.addLast(next);
                }
            }
        }
        boolean cyclic = cyclePath != null;
        return DependencyReportVO.builder()
                .topologicalOrder(order)
                .cyclic(cyclic)
                .cyclePath(cyclePath == null ? List.of() : cyclePath)
                .missingDependencies(missing.stream().distinct().sorted().toList())
                .build();
    }

    /** DFS 找环：返回环路径（a -> b -> a），无环 null */
    private List<String> findCycle(Map<String, List<String>> dependencies, Set<String> nodes) {
        Map<String, Integer> color = new LinkedHashMap<>();
        nodes.forEach(node -> color.put(node, 0));
        Map<String, String> parent = new LinkedHashMap<>();
        for (String node : new TreeSet<>(nodes)) {
            if (color.get(node) == 0) {
                List<String> cycle = dfs(node, dependencies, color, parent);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return null;
    }

    private List<String> dfs(String node, Map<String, List<String>> dependencies,
                             Map<String, Integer> color, Map<String, String> parent) {
        color.put(node, 1);
        for (String dep : new TreeSet<>(dependencies.getOrDefault(node, List.of()))) {
            if (!color.containsKey(dep)) {
                continue;
            }
            if (color.get(dep) == 1) {
                // 回边：回溯构造环路径
                List<String> path = new ArrayList<>();
                String cursor = node;
                path.add(dep);
                while (!cursor.equals(dep)) {
                    path.add(cursor);
                    cursor = parent.get(cursor);
                    if (cursor == null) {
                        break;
                    }
                }
                path.add(dep);
                java.util.Collections.reverse(path);
                return path;
            }
            if (color.get(dep) == 0) {
                parent.put(dep, node);
                List<String> cycle = dfs(dep, dependencies, color, parent);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        color.put(node, 2);
        return null;
    }
}
