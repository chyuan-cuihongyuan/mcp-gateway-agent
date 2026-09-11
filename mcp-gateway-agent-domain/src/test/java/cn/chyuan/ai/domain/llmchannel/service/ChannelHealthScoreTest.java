package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 渠道健康分加权纯函数测试（工单 0159：权重和=1/缺探测项降级/0-100 钳制/调度降权排序）
 */
@DisplayName("渠道健康分纯函数测试")
public class ChannelHealthScoreTest {

    @Test
    @DisplayName("权重校验 — 和为 1 通过；负值/和≠1 抛 IllegalArgumentException")
    public void testValidateWeights() {
        assertDoesNotThrow(() -> ChannelHealthScore.validateWeights(0.5, 0.2, 0.3));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelHealthScore.validateWeights(0.6, 0.2, 0.3), "和 1.1 非法");
        assertThrows(IllegalArgumentException.class,
                () -> ChannelHealthScore.validateWeights(-0.1, 0.6, 0.5), "负权重非法");
        assertDoesNotThrow(() -> ChannelHealthScore.validateWeights(0.5000000001, 0.2, 0.3),
                "浮点误差 ±1e-9 内放行");
    }

    @Test
    @DisplayName("缺探测项降级 — probe=null 按 50 中性计入，总分低于满分探测")
    public void testMissingProbeDegrades() {
        var weights = ChannelHealthScore.validateWeights(0.5, 0.2, 0.3);
        // 错误分 100 + 探测分 100 + 延迟分 100*(1-100/5000)=98 → 0.5*100+0.2*100+0.3*98=99.4
        double withProbe = ChannelHealthScore.score(20, 0, 100.0, 100L, 5000, weights);
        // 缺探测 → 50 中性 → 0.5*100+0.2*50+0.3*98=89.4
        double noProbe = ChannelHealthScore.score(20, 0, null, 100L, 5000, weights);
        assertEquals(99.4, withProbe, 0.01);
        assertEquals(89.4, noProbe, 0.01);
        assertTrue(noProbe < withProbe, "缺探测项降级");
    }

    @Test
    @DisplayName("0-100 钳制 — 全败渠道与超参考延迟渠道钳制 0；无数据错误分 60 中性")
    public void testClampAndNoData() {
        var weights = ChannelHealthScore.validateWeights(0.5, 0.2, 0.3);
        double allFail = ChannelHealthScore.score(10, 10, 0.0, 9000L, 5000, weights);
        assertEquals(0.0, allFail, 0.001, "全败+探测 0+超延迟 → 钳制 0");
        // 无账本数据 60 + 缺探测 50 + 无延迟 50 → 0.5*60+0.2*50+0.3*50=55
        double noData = ChannelHealthScore.score(0, 0, null, null, 5000, weights);
        assertEquals(55.0, noData, 0.001);
        double badProbe = ChannelHealthScore.score(10, 0, 500.0, 10L, 5000, weights);
        assertTrue(badProbe <= 100.0, "探测分超界入参被钳制");
    }

    @Test
    @DisplayName("调度降权排序 — 低分渠道稳定排尾；阈值 0 关闭")
    public void testDemoteBelow() {
        LlmChannelVO a = LlmChannelVO.builder().id(1L).name("a").build();
        LlmChannelVO b = LlmChannelVO.builder().id(2L).name("b").build();
        LlmChannelVO c = LlmChannelVO.builder().id(3L).name("c").build();

        // b 低分 → 排尾，其余相对顺序不变
        List<LlmChannelVO> reordered = ChannelHealthScore.demoteBelow(
                List.of(a, b, c), LlmChannelVO::getId, id -> id == 2L ? 40.0 : 90.0, 60);
        assertEquals(List.of(a, c, b), reordered, "低分渠道稳定排尾");

        // 全部低分 → 顺序不变（整体后置无意义）
        List<LlmChannelVO> allLow = ChannelHealthScore.demoteBelow(
                List.of(a, b, c), LlmChannelVO::getId, id -> 10.0, 60);
        assertEquals(List.of(a, b, c), allLow);

        // 阈值 0 = 关闭降权
        List<LlmChannelVO> off = ChannelHealthScore.demoteBelow(
                List.of(a, b, c), LlmChannelVO::getId, id -> 10.0, 0);
        assertEquals(List.of(a, b, c), off);
    }
}
