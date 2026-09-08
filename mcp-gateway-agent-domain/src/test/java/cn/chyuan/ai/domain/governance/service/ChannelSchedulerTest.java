package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 渠道调度器测试（工单 0060：分层/加权/空候选）
 */
@DisplayName("渠道调度器测试")
public class ChannelSchedulerTest {

    private final ChannelScheduler scheduler = new ChannelScheduler();

    @Test
    @DisplayName("分层 — 恒取最高 priority 层，低层永不命中")
    public void testPriorityTier() {
        var high = new ChannelScheduler.Candidate("high", 10, 1);
        var low = new ChannelScheduler.Candidate("low", 0, 1000);
        for (int i = 0; i < 100; i++) {
            assertEquals("high", scheduler.pick(List.of(low, high)).orElseThrow().id());
        }
    }

    @Test
    @DisplayName("加权 — 同层权重比近似（可注入随机数确定性验证）")
    public void testWeightedRandom() {
        var a = new ChannelScheduler.Candidate("a", 0, 3);
        var b = new ChannelScheduler.Candidate("b", 0, 1);
        // 确定性：dice=0..2 → a，dice=3 → b
        assertEquals("a", scheduler.pick(List.of(a, b), new Random(0)).orElseThrow().id());
        // 统计性：1000 次约 75/25
        int aCount = 0;
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            if (scheduler.pick(List.of(b, a), random).orElseThrow().id().equals("a")) {
                aCount++;
            }
        }
        assertTrue(aCount > 650 && aCount < 850, "a(3/4) 期望约 750，实际 " + aCount);
    }

    @Test
    @DisplayName("权重<=0 按 1 计 — 不因零权重除零或永不命中")
    public void testNonPositiveWeightTreatedAsOne() {
        var zero = new ChannelScheduler.Candidate("zero", 0, 0);
        var normal = new ChannelScheduler.Candidate("normal", 0, 5);
        for (int i = 0; i < 50; i++) {
            Optional<ChannelScheduler.Candidate> picked = scheduler.pick(List.of(zero, normal));
            assertTrue(picked.isPresent());
        }
        // 全零权重也可选出
        assertEquals("zero", scheduler.pick(List.of(zero), new Random(0)).orElseThrow().id());
    }

    @Test
    @DisplayName("空候选 — empty（回退语义归调用方）")
    public void testEmptyCandidates() {
        assertTrue(scheduler.pick(List.of()).isEmpty());
        assertTrue(scheduler.pick(null).isEmpty());
    }
}
