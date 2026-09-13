package cn.chyuan.ai.domain.policy.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略决策日志单测（工单 0265 AH5）：录制字段完整性/脱敏截断/查询过滤分页封顶。
 */
class DecisionLogRecorderTest {

    @Test
    void 录制与查询过滤() {
        DecisionLogRecorder.InMemoryDecisionLogStore store = new DecisionLogRecorder.InMemoryDecisionLogStore();
        DecisionLogRecorder recorder = new DecisionLogRecorder(store);
        recorder.record(1_000L, "k1", "gpt-5", "chat",
                new PolicyEngine.Decision(PolicyEngine.EFFECT_ALLOW, List.of("a1"), List.of(), false),
                3L);
        recorder.record(2_000L, "k2", "claude", "chat",
                new PolicyEngine.Decision(PolicyEngine.EFFECT_DENY, List.of("d1"), List.of(), true), 1L);

        List<DecisionLogRecorder.DecisionLog> all = recorder.query(null, null, null, 0, 50);
        assertEquals(2, all.size());
        // 最新在前
        assertEquals("k2", all.get(0).subject());
        assertTrue(all.get(0).cached());
        assertEquals("d1", all.get(0).hitStatementNames().get(0));
        // 按结论过滤
        assertEquals(1, recorder.query("DENY", null, null, 0, 50).size());
        // 按时间窗过滤
        assertEquals(1, recorder.query(null, 1_500L, null, 0, 50).size());
        assertEquals(1, recorder.query(null, null, 1_500L, 0, 50).size());
        // 分页
        assertEquals(1, recorder.query(null, null, null, 1, 50).size());
        assertEquals(1, recorder.query(null, null, null, 0, 1).size());
    }

    @Test
    void 脱敏截断与limit封顶() {
        assertEquals("-", DecisionLogRecorder.truncate(null));
        assertEquals("short", DecisionLogRecorder.truncate("short"));
        String longValue = "x".repeat(300);
        String truncated = DecisionLogRecorder.truncate(longValue);
        assertEquals(DecisionLogRecorder.MAX_SUMMARY_LEN + 1, truncated.length());
        assertTrue(truncated.endsWith("…"));

        DecisionLogRecorder recorder = new DecisionLogRecorder(new DecisionLogRecorder.InMemoryDecisionLogStore());
        for (int i = 0; i < 300; i++) {
            recorder.record(i, "k" + i, "m", "chat",
                    new PolicyEngine.Decision(PolicyEngine.EFFECT_ALLOW, List.of(), List.of(), false), 0);
        }
        // limit 封顶 200
        assertEquals(200, recorder.query(null, null, null, 0, 1_000).size());
    }
}
