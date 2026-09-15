package cn.chyuan.ai.domain.streamkernel.service;

import cn.chyuan.ai.domain.streamkernel.adapter.port.ISchemaValidatorPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT3/AT4 单测（工单 0373/0374）：事件归一状态机 + 工具参数聚合。
 */
class StreamKernelEventTest {

    @Test
    void OpenAI事件归一与终止后拒绝() {
        StreamEventNormalizer normalizer = new StreamEventNormalizer();
        var start = normalizer.normalize(StreamEventNormalizer.FAMILY_OPENAI, Map.of(
                "choices", List.of(Map.of("delta", Map.of("role", "assistant")))));
        assertEquals(StreamEventNormalizer.TEXT_DELTA, start.type());
        var text = normalizer.normalize(StreamEventNormalizer.FAMILY_OPENAI, Map.of(
                "choices", List.of(Map.of("delta", Map.of("content", "你好")))));
        assertEquals("你好", text.textDelta());
        var tool = normalizer.normalize(StreamEventNormalizer.FAMILY_OPENAI, Map.of(
                "choices", List.of(Map.of("delta", Map.of("tool_calls",
                        List.of(Map.of("index", 0, "id", "call_1", "arguments", "{\"x\":")))))));
        assertEquals(StreamEventNormalizer.TOOL_CALL_DELTA, tool.type());
        assertEquals("0", tool.toolIndex());
        assertEquals("call_1", tool.toolName());
        var stop = normalizer.normalize(StreamEventNormalizer.FAMILY_OPENAI, Map.of(
                "choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))));
        assertEquals(StreamEventNormalizer.MESSAGE_STOP, stop.type());
        assertEquals("stop", stop.finishReason());
        // 终止后再有事件拒绝
        assertThrows(IllegalStateException.class, () -> normalizer.normalize(
                StreamEventNormalizer.FAMILY_OPENAI, Map.of("choices", List.of())));
    }

    @Test
    void Anthropic事件归一两族同语义同内部事件() {
        StreamEventNormalizer normalizer = new StreamEventNormalizer();
        var start = normalizer.normalize(StreamEventNormalizer.FAMILY_ANTHROPIC, Map.of("type", "message_start"));
        assertEquals(StreamEventNormalizer.MESSAGE_START, start.type());
        var text = normalizer.normalize(StreamEventNormalizer.FAMILY_ANTHROPIC, Map.of(
                "type", "content_block_delta",
                "delta", Map.of("type", "text_delta", "text", "你好")));
        assertEquals(StreamEventNormalizer.TEXT_DELTA, text.type());
        assertEquals("你好", text.textDelta());
        var tool = normalizer.normalize(StreamEventNormalizer.FAMILY_ANTHROPIC, Map.of(
                "type", "content_block_delta", "index", "0",
                "delta", Map.of("type", "input_json_delta", "partial_json", "{\"x\":")));
        assertEquals(StreamEventNormalizer.TOOL_CALL_DELTA, tool.type());
        assertEquals("{\"x\":", tool.argsFragment());
        var stop = normalizer.normalize(StreamEventNormalizer.FAMILY_ANTHROPIC, Map.of("type", "message_stop"));
        assertEquals(StreamEventNormalizer.MESSAGE_STOP, stop.type());
        // 未知类型/未知族拒绝（独立实例，避免与终止态校验交叠）
        assertThrows(IllegalArgumentException.class, () -> new StreamEventNormalizer().normalize(
                StreamEventNormalizer.FAMILY_ANTHROPIC, Map.of("type", "mystery")));
        assertThrows(IllegalArgumentException.class, () -> new StreamEventNormalizer().normalize(
                "gemini", Map.of()));
        // 两族同语义产出同内部事件类型
        assertEquals(StreamEventNormalizer.TEXT_DELTA, new StreamEventNormalizer().normalize(
                StreamEventNormalizer.FAMILY_OPENAI, Map.of(
                        "choices", List.of(Map.of("delta", Map.of("content", "hi"))))).type());
    }

    @Test
    void 工具参数分片聚合与Schema校验联动() {
        // 记录校验入参的假校验器：名字含 weather 视合法
        ToolCallAggregator aggregator = new ToolCallAggregator((toolName, argsJson) -> {
            boolean valid = toolName.contains("weather") && argsJson.contains("city");
            return new ISchemaValidatorPort.Result(valid, valid ? null : "$.city 缺失");
        });
        // 两个并发工具调用交错分片（index 聚合）
        aggregator.onDelta("0", "call_a", "get_weather", "{\"city\":\"深");
        aggregator.onDelta("1", "call_b", "search_web", "{\"q\":\"x\"}");
        aggregator.onDelta("0", null, null, "圳\"}");
        var a = aggregator.onFinish("0");
        var b = aggregator.onFinish("1");
        assertEquals("get_weather", a.name());
        assertEquals("{\"city\":\"深圳\"}", a.argumentsJson());
        assertTrue(a.schemaValid());
        assertEquals("search_web", b.name());
        assertTrue(!b.schemaValid());
        assertEquals("$.city 缺失", b.schemaError());
        // 重复 finish 拒绝
        assertThrows(IllegalStateException.class, () -> aggregator.onFinish("0"));
        assertEquals(2, aggregator.finishedCount());
    }

    @Test
    void 工具名缺失与未知索引边界() {
        ToolCallAggregator aggregator = new ToolCallAggregator((n, a) -> new ISchemaValidatorPort.Result(true, null));
        aggregator.onDelta(null, "call_x", null, "{\"a\":1}");
        var done = aggregator.onFinish(null);
        assertTrue(!done.schemaValid());
        assertEquals("工具名缺失", done.schemaError());
        assertThrows(IllegalStateException.class, () -> aggregator.onFinish("9"));
    }
}
