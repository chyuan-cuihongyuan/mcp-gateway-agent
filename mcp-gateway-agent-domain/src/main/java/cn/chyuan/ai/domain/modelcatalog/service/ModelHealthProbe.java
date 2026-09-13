package cn.chyuan.ai.domain.modelcatalog.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 模型健康探测（工单 0282 AJ6）—
 * 探测端口抽象（轻量 ping / 上游探针）+ 周期触发端口驱动 + 环形探活记录 +
 * 就绪状态聚合（READY/DEGRADED/UNKNOWN：按最近探测结论与延迟阈值）。
 */
@Service
public class ModelHealthProbe {

    public static final String READY = "READY";
    public static final String DEGRADED = "DEGRADED";
    public static final String UNKNOWN = "UNKNOWN";
    /** 探测记录环形上限 */
    public static final int MAX_RECORDS = 100;
    /** 延迟超阈即 DEGRADED（毫秒） */
    public static final long DEGRADED_LATENCY_MS = 3_000;

    /** 探活记录 */
    public record ProbeRecord(long atMs, String model, boolean reachable, long latencyMs, String error) {
    }

    /** 探测动作端口（infrastructure 提供真实探针；测试注入 fake） */
    public interface ProbeAction {

        ProbeRecord probe(String model, long nowMs);
    }

    /** 周期触发端口（调度器适配；测试直接调 runProbe） */
    public interface ProbeSchedulerPort {

        void schedule(Runnable task, long intervalMs);
    }

    private final ProbeAction action;
    private final Map<String, Deque<ProbeRecord>> records = new java.util.concurrent.ConcurrentHashMap<>();

    public ModelHealthProbe(ProbeAction action) {
        this.action = action;
    }

    /** 执行一次探测并留档（手动/周期共用） */
    public ProbeRecord runProbe(String model, long nowMs) {
        ProbeRecord record;
        try {
            record = action.probe(model, nowMs);
        } catch (Exception e) {
            record = new ProbeRecord(nowMs, model, false, 0, e.getMessage());
        }
        Deque<ProbeRecord> deque = records.computeIfAbsent(model, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(record);
            while (deque.size() > MAX_RECORDS) {
                deque.removeFirst();
            }
        }
        return record;
    }

    /** 就绪状态聚合：最近一条可达且延迟未超阈=READY；可达但慢=DEGRADED；无记录=UNKNOWN */
    public String statusOf(String model) {
        Deque<ProbeRecord> deque = records.get(model);
        if (deque == null) {
            return UNKNOWN;
        }
        ProbeRecord last;
        synchronized (deque) {
            last = deque.peekLast();
        }
        if (last == null) {
            return UNKNOWN;
        }
        if (!last.reachable()) {
            return DEGRADED;
        }
        return last.latencyMs() <= DEGRADED_LATENCY_MS ? READY : DEGRADED;
    }

    /** 探活记录（最新在前，封顶返回） */
    public List<ProbeRecord> history(String model, int limit) {
        Deque<ProbeRecord> deque = records.get(model);
        if (deque == null) {
            return List.of();
        }
        List<ProbeRecord> snapshot;
        synchronized (deque) {
            snapshot = new ArrayList<>(deque);
        }
        return snapshot.reversed().stream()
                .limit(Math.max(1, Math.min(limit, MAX_RECORDS)))
                .toList();
    }

    /** 全模型状态（注册表联动由调用方传入模型清单） */
    public Map<String, String> statusAll(List<String> models) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String model : models) {
            out.put(model, statusOf(model));
        }
        return out;
    }
}
