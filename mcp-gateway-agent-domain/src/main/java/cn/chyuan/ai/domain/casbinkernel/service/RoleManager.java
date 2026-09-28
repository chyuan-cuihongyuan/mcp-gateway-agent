package cn.chyuan.ai.domain.casbinkernel.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色继承（工单 0954 EC3，casbin 思想）。
 * g 角色图与传递闭包/环检测拒绝；g2 域租户图按域隔离。
 */
public final class RoleManager {

    private final Map<String, Set<String>> edges = new HashMap<>();
    private final Map<String, Set<String>> domainEdges = new HashMap<>();

    /** 加边（无域）：自环拒绝、成环拒绝、重复幂等 */
    public void link(String child, String parent) {
        link(child, parent, null);
    }

    /** 加边（可带域）：域边与无域边分图存放 */
    public void link(String child, String parent, String domain) {
        if (child.equals(parent)) {
            throw new IllegalArgumentException("自环: " + child);
        }
        Map<String, Set<String>> graph = graph(domain);
        if (hasLink(child, parent, domain)) {
            return;
        }
        if (reaches(parent, child, domain)) {
            throw new IllegalArgumentException("成环拒绝: " + child + " -> " + parent);
        }
        graph.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
    }

    /** 传递闭包查询：name 自身视为可达 */
    public boolean hasLink(String name, String ancestor) {
        return hasLink(name, ancestor, null);
    }

    public boolean hasLink(String name, String ancestor, String domain) {
        if (name.equals(ancestor)) {
            return true;
        }
        return reaches(name, ancestor, domain);
    }

    private boolean reaches(String from, String target, String domain) {
        Set<String> seen = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(from);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String node : frontier) {
                if (node.equals(target)) {
                    return true;
                }
                if (seen.add(node)) {
                    Set<String> parents = graph(domain).get(node);
                    if (parents != null) {
                        next.addAll(parents);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    private Map<String, Set<String>> graph(String domain) {
        return domain == null ? edges : domainEdges;
    }

    public int edgeCount() {
        return edges.values().stream().mapToInt(Set::size).sum();
    }
}
