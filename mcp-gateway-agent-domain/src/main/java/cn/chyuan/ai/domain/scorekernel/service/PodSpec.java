package cn.chyuan.ai.domain.scorekernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 调度单元规格（工单 1189-1191 规格载体，kubernetes Pod 思想）。
 * 资源请求/优先级/创建序、节点选择器、污点容忍、亲和与反亲和声明。
 */
public final class PodSpec {

    public final String name;
    public final int priority;
    public final int seq;
    public final int cpu;
    public final int mem;
    public final Map<String, String> selector = new LinkedHashMap<>();
    public final Set<String> tolerations = new LinkedHashSet<>();
    public final Map<String, String> affinity = new LinkedHashMap<>();
    public final Set<String> antiLabels = new LinkedHashSet<>();

    public PodSpec(String name, int priority, int seq, int cpu, int mem) {
        this.name = name;
        this.priority = priority;
        this.seq = seq;
        this.cpu = cpu;
        this.mem = mem;
    }
}
