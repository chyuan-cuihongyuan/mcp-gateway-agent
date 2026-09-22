package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拓扑与算子链（工单 0563 BO7，flink 拓扑思想）。
 * source→window(agg)→sink 拓扑描述/算子链合并（同并行度直连）/
 * 拓扑校验（无环/有源有汇/孤立节点拒绝）/拓扑文本化。
 */
public final class Topology {

    public enum OpType {
        SOURCE, WINDOW_AGG, SINK
    }

    /** 算子节点 */
    public record Operator(String id, OpType type, int parallelism) {
        public Operator {
            if (parallelism < 1) {
                throw new IllegalArgumentException("并行度须≥1");
            }
        }
    }

    private final Map<String, Operator> operators = new LinkedHashMap<>();
    private final Map<String, java.util.Set<String>> edges = new LinkedHashMap<>();

    public Topology add(Operator operator) {
        if (operators.containsKey(operator.id())) {
            throw new IllegalArgumentException("算子 id 重复: " + operator.id());
        }
        operators.put(operator.id(), operator);
        edges.put(operator.id(), new java.util.LinkedHashSet<>());
        return this;
    }

    /** 连线（源 id → 目标 id） */
    public Topology connect(String from, String to) {
        if (!operators.containsKey(from) || !operators.containsKey(to)) {
            throw new IllegalArgumentException("连线端点不存在: " + from + "->" + to);
        }
        edges.get(from).add(to);
        return this;
    }

    /** 拓扑校验：恰好一个 SOURCE、至少一个 SINK、无环、无孤立节点 */
    public void validate() {
        long sources = operators.values().stream().filter(op -> op.type() == OpType.SOURCE).count();
        long sinks = operators.values().stream().filter(op -> op.type() == OpType.SINK).count();
        if (sources != 1) {
            throw new IllegalArgumentException("须恰好一个 SOURCE，实际 " + sources);
        }
        if (sinks < 1) {
            throw new IllegalArgumentException("须至少一个 SINK");
        }
        for (Map.Entry<String, java.util.Set<String>> entry : edges.entrySet()) {
            if (entry.getValue().isEmpty() && operators.get(entry.getKey()).type() != OpType.SINK) {
                throw new IllegalArgumentException("孤立算子拒绝: " + entry.getKey());
            }
        }
        if (hasCycle()) {
            throw new IllegalArgumentException("拓扑存在环");
        }
    }

    /** 算子链合并：同类型相邻且并行度相同则合并为链组（返回链数） */
    public List<List<Operator>> chained() {
        List<List<Operator>> chains = new ArrayList<>();
        List<Operator> current = new ArrayList<>();
        for (Operator op : operators.values()) {
            if (!current.isEmpty()
                    && current.get(current.size() - 1).type() == op.type()
                    && current.get(current.size() - 1).parallelism() == op.parallelism()) {
                current.add(op);
            } else {
                if (!current.isEmpty()) {
                    chains.add(List.copyOf(current));
                }
                current = new ArrayList<>(List.of(op));
            }
        }
        if (!current.isEmpty()) {
            chains.add(List.copyOf(current));
        }
        return List.copyOf(chains);
    }

    /** 拓扑文本化：op(type/parallelism) → 邻接 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String id : operators.keySet()) {
            Operator op = operators.get(id);
            sb.append(id).append('(').append(op.type()).append('/').append(op.parallelism()).append(')');
            if (!edges.get(id).isEmpty()) {
                sb.append(" -> ").append(String.join(",", edges.get(id)));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private boolean hasCycle() {
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String id : operators.keySet()) {
            if (visit(id, state)) {
                return true;
            }
        }
        return false;
    }

    private boolean visit(String id, Map<String, Integer> state) {
        Integer mark = state.get(id);
        if (mark != null) {
            return mark == 1;
        }
        state.put(id, 1);
        for (String next : edges.get(id)) {
            if (visit(next, state)) {
                return true;
            }
        }
        state.put(id, 2);
        return false;
    }
}
