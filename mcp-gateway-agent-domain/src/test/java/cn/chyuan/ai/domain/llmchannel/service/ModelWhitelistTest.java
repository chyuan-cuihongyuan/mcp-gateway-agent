package cn.chyuan.ai.domain.llmchannel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型白名单纯函数测试（工单 0157：空=放行/命中/未命中/大小写归一）
 */
@DisplayName("模型白名单纯函数测试")
public class ModelWhitelistTest {

    @Test
    @DisplayName("空白名单（null/空列表）= 不限制放行（兼容存量密钥）")
    public void testEmptyWhitelistAllowsAll() {
        assertTrue(ModelWhitelist.allowed("gpt-4o", null));
        assertTrue(ModelWhitelist.allowed("gpt-4o", List.of()));
        assertTrue(ModelWhitelist.allowed("deepseek-v4-pro", List.of()));
    }

    @Test
    @DisplayName("命中 — 精确匹配放行")
    public void testHit() {
        assertTrue(ModelWhitelist.allowed("gpt-4o", List.of("gpt-4o", "deepseek-v4-pro")));
    }

    @Test
    @DisplayName("未命中 — 白名单外拒绝")
    public void testMiss() {
        assertFalse(ModelWhitelist.allowed("claude-x", List.of("gpt-4o", "deepseek-v4-pro")));
        assertFalse(ModelWhitelist.allowed(null, List.of("gpt-4o")));
        assertFalse(ModelWhitelist.allowed("  ", List.of("gpt-4o")));
    }

    @Test
    @DisplayName("大小写归一 — 两侧 trim + 忽略大小写")
    public void testCaseInsensitiveNormalization() {
        assertTrue(ModelWhitelist.allowed("GPT-4O", List.of("gpt-4o")));
        assertTrue(ModelWhitelist.allowed("  gpt-4o  ", List.of(" GPT-4O ")));
    }
}
