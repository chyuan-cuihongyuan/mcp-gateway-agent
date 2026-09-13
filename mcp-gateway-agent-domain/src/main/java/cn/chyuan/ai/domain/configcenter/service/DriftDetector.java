package cn.chyuan.ai.domain.configcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 配置漂移检测（工单 0258 AG8，借鉴 Argo CD drift）—
 * 期望快照内容 vs 运行时实际值（{@link RuntimeConfigPort} 供给）行级 diff，
 * 报告留档仅记录不自动纠正；漂移键分型（缺失/篡改/多余）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class DriftDetector {

    /** 单行漂移：MISSING=期望有实际无；TAMPERED=值不一致；EXTRA=实际有期望无 */
    public record DriftRow(String path, String expected, String actual, String kind) {
    }

    /** 漂移报告 */
    public record DriftReport(long id, String namespace, long detectedAtMs,
            List<DriftRow> rows, boolean drifted) {

        public int missing() {
            return (int) rows.stream().filter(r -> "MISSING".equals(r.kind())).count();
        }

        public int tampered() {
            return (int) rows.stream().filter(r -> "TAMPERED".equals(r.kind())).count();
        }

        public int extra() {
            return (int) rows.stream().filter(r -> "EXTRA".equals(r.kind())).count();
        }
    }

    /** 运行时实际值端口（infrastructure 提供环境属性实现；测试注入 fake） */
    public interface RuntimeConfigPort {

        /** 运行时实际扁平键值（无数据返回空 Map） */
        Map<String, String> actualFlat(String namespace);
    }

    private final RuntimeConfigPort runtimeConfig;
    private final Deque<DriftReport> reports = new ArrayDeque<>();
    private static final int MAX_REPORTS = 100;
    private long nextId = 1;

    public DriftDetector(RuntimeConfigPort runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /** 检测并留档（期望内容为快照 JSON；纯记录不纠正） */
    public DriftReport run(String namespace, String expectedJson, long nowMs) {
        Map<String, String> expected = ConfigPlanDiffer.flatten(expectedJson);
        Map<String, String> actual = runtimeConfig.actualFlat(namespace);
        List<DriftRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actualValue = actual.get(entry.getKey());
            if (actualValue == null) {
                rows.add(new DriftRow(entry.getKey(), entry.getValue(), null, "MISSING"));
            } else if (!entry.getValue().equals(actualValue)) {
                rows.add(new DriftRow(entry.getKey(), entry.getValue(), actualValue, "TAMPERED"));
            }
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                rows.add(new DriftRow(entry.getKey(), null, entry.getValue(), "EXTRA"));
            }
        }
        rows.sort((a, b) -> {
            int byKind = rank(a.kind()) - rank(b.kind());
            return byKind != 0 ? byKind : a.path().compareTo(b.path());
        });
        DriftReport report = new DriftReport(nextId++, namespace, nowMs, List.copyOf(rows), !rows.isEmpty());
        synchronized (reports) {
            reports.addLast(report);
            while (reports.size() > MAX_REPORTS) {
                reports.removeFirst();
            }
        }
        if (report.drifted()) {
            log.warn("配置漂移: ns={} rows={} missing={} tampered={} extra={}",
                    namespace, rows.size(), report.missing(), report.tampered(), report.extra());
        }
        return report;
    }

    /** 报告查询（最新在前，可选按命名空间过滤） */
    public List<DriftReport> listReports(String namespace) {
        synchronized (reports) {
            return reports.stream()
                    .filter(r -> namespace == null || namespace.isBlank() || r.namespace().equals(namespace))
                    .toList()
                    .reversed();
        }
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "TAMPERED" -> 0;
            case "MISSING" -> 1;
            default -> 2;
        };
    }
}
