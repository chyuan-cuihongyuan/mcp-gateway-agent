package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.DriftDetector.DriftReport;
import cn.chyuan.ai.domain.configcenter.service.DriftDetector.RuntimeConfigPort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置漂移检测单测（工单 0258 AG8）：无漂移/缺失/篡改/多余四象限 + 报告留档 + 只读不纠正。
 */
class DriftDetectorTest {

    @Test
    void 四象限分型() {
        Map<String, String> actual = new HashMap<>();
        actual.put("a", "1");
        actual.put("b", "tampered");
        actual.put("extra", "x");
        DriftDetector detector = new DriftDetector(ns -> actual);
        DriftReport report = detector.run("ns", "{\"a\":\"1\",\"b\":\"ok\",\"c\":\"3\"}", 42L);
        assertTrue(report.drifted());
        assertEquals(1, report.missing());
        assertEquals(1, report.tampered());
        assertEquals(1, report.extra());
        assertEquals("TAMPERED", report.rows().get(0).kind());
        assertEquals(42L, report.detectedAtMs());
    }

    @Test
    void 无漂移与报告留档查询() {
        Map<String, String> actual = Map.of("a", "1");
        DriftDetector detector = new DriftDetector(ns -> actual);
        DriftReport clean = detector.run("ns", "{\"a\":\"1\"}", 1L);
        assertFalse(clean.drifted());
        detector.run("ns", "{\"a\":\"1\",\"b\":\"2\"}", 2L);
        assertEquals(2, detector.listReports(null).size());
        assertEquals(2, detector.listReports("ns").size());
        assertTrue(detector.listReports("other").isEmpty());
        // 最新在前
        assertEquals(2L, detector.listReports(null).get(0).detectedAtMs());
    }

    @Test
    void 纯记录不纠正() {
        Map<String, String> actual = new java.util.concurrent.ConcurrentHashMap<>();
        actual.put("a", "drifted");
        DriftDetector detector = new DriftDetector(ns -> actual);
        DriftReport report = detector.run("ns", "{\"a\":\"1\"}", 1L);
        assertTrue(report.drifted());
        // 检测后实际值未被改动（只记录不纠正）
        assertEquals("drifted", actual.get("a"));
    }
}
