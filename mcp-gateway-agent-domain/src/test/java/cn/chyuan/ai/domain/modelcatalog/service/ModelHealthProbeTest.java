package cn.chyuan.ai.domain.modelcatalog.service;

import cn.chyuan.ai.domain.modelcatalog.service.ModelHealthProbe.ProbeRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型健康探测单测（工单 0282 AJ6）：状态聚合三分支/环形封顶/历史倒序/探测异常兜底。
 */
class ModelHealthProbeTest {

    @Test
    void 状态聚合三分支() {
        ModelHealthProbe probe = new ModelHealthProbe((model, now) ->
                new ProbeRecord(now, model, !model.equals("down"), model.equals("slow") ? 5_000 : 100, null));
        probe.runProbe("ok", 1_000);
        probe.runProbe("down", 1_000);
        probe.runProbe("slow", 1_000);
        assertEquals(ModelHealthProbe.READY, probe.statusOf("ok"));
        assertEquals(ModelHealthProbe.DEGRADED, probe.statusOf("down"));
        assertEquals(ModelHealthProbe.DEGRADED, probe.statusOf("slow"));
        assertEquals(ModelHealthProbe.UNKNOWN, probe.statusOf("never-probed"));
    }

    @Test
    void 环形封顶与历史倒序() {
        ModelHealthProbe probe = new ModelHealthProbe((model, now) ->
                new ProbeRecord(now, model, true, 50, null));
        for (long i = 1; i <= 150; i++) {
            probe.runProbe("m", i);
        }
        List<ProbeRecord> history = probe.history("m", 1_000);
        assertEquals(ModelHealthProbe.MAX_RECORDS, history.size());
        // 最新在前
        assertEquals(150, history.get(0).atMs());
        assertEquals(51, history.get(history.size() - 1).atMs());
        // limit 收敛
        assertEquals(5, probe.history("m", 5).size());
        // 全模型状态
        assertEquals(2, probe.statusAll(List.of("m", "other")).size());
        assertTrue(probe.statusAll(List.of("m")).containsKey("m"));
    }

    @Test
    void 探测异常兜底() {
        ModelHealthProbe probe = new ModelHealthProbe((model, now) -> {
            throw new IllegalStateException("probe exploded");
        });
        ProbeRecord record = probe.runProbe("m", 42);
        assertTrue(!record.reachable());
        assertEquals("probe exploded", record.error());
        assertEquals(ModelHealthProbe.DEGRADED, probe.statusOf("m"));
    }
}
