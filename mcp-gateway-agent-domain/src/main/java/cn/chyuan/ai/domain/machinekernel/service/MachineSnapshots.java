package cn.chyuan.ai.domain.machinekernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 状态机快照与恢复（工单 0624 BV7，XState persist 思想）。
 * 可序列化状态快照（活跃态集合+上下文+进入次数计数）/
 * 恢复后继续接受事件且迁移语义不变/状态进入次数统计（停留审计）/
 * 并行区域全量快照/文本行序列化（跨进程形态）。
 */
public final class MachineSnapshots {

    private MachineSnapshots() {
    }

    /** 快照载荷 */
    public record Snapshot(Set<String> activeStates, Map<String, Object> context,
                           Map<String, Long> entryCounts) {
    }

    /** 序列化：active|count:k=v|ctx:k=v 行协议（确定性序） */
    public static String serialize(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不得为 null");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("active:")
                .append(snapshot.activeStates().stream().sorted().collect(Collectors.joining(",")))
                .append('\n');
        snapshot.entryCounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("count:").append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        snapshot.context().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("ctx:").append(e.getKey()).append('=')
                        .append(String.valueOf(e.getValue()).replace("\n", "\\n")).append('\n'));
        return sb.toString();
    }

    /** 反序列化 */
    public static Snapshot deserialize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("快照文本不得为 null");
        }
        Set<String> active = new LinkedHashSet<>();
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("active:")) {
                for (String state : line.substring("active:".length()).split(",")) {
                    if (!state.isBlank()) {
                        active.add(state);
                    }
                }
            } else if (line.startsWith("count:")) {
                String kv = line.substring("count:".length());
                counts.put(kv.substring(0, kv.indexOf('=')), Long.parseLong(kv.substring(kv.indexOf('=') + 1)));
            } else if (line.startsWith("ctx:")) {
                String kv = line.substring("ctx:".length());
                context.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
            }
        }
        return new Snapshot(active, context, counts);
    }
}
