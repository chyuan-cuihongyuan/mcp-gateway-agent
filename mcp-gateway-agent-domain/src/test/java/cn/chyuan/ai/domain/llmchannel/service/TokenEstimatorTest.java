package cn.chyuan.ai.domain.llmchannel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 粗估纯函数测试（工单 0162：中英混合/空串/超长/字符兜底）
 */
@DisplayName("Token 粗估纯函数测试")
public class TokenEstimatorTest {

    @Test
    @DisplayName("中英混合 — CJK 按字 + 英文按词，标点空白不计")
    public void testMixed() {
        // 3 CJK 字（你/好/你）+ 1 英文词（world）= 4
        assertEquals(4L, TokenEstimator.estimate("你好, world! 你"));
        // 纯中文按字
        assertEquals(5L, TokenEstimator.estimate("你好世界呀"));
        // 纯英文按词（字母数字连续段各计一词）
        assertEquals(3L, TokenEstimator.estimate("hello world gpt4o"));
    }

    @Test
    @DisplayName("空串/null — 恒 0")
    public void testEmpty() {
        assertEquals(0L, TokenEstimator.estimate(null));
        assertEquals(0L, TokenEstimator.estimate(""));
        assertEquals(0L, TokenEstimator.estimate("   "));
        assertEquals(0L, TokenEstimator.estimateByChars(null, 4));
    }

    @Test
    @DisplayName("超长 — 线性增长（1 万字中文=1 万 token）")
    public void testVeryLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            sb.append('长');
        }
        assertEquals(10_000L, TokenEstimator.estimate(sb.toString()), "粗估线性无截断");
    }

    @Test
    @DisplayName("字符兜底口径 — 总字符 / charsPerToken 向上取整（可配置）")
    public void testCharsFallback() {
        assertEquals(2L, TokenEstimator.estimateByChars("abcdefgh", 4), "8/4=2 整除");
        assertEquals(3L, TokenEstimator.estimateByChars("abcdefghij", 4), "10/4 向上取整 3");
        assertEquals(2L, TokenEstimator.estimateByChars("abcdefgh", 5), "可配置 charsPerToken");
        assertEquals(2L, TokenEstimator.estimateByChars("abcdefgh", 0), "非法兜底值取默认 4");
    }
}
