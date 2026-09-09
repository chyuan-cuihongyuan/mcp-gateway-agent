package cn.chyuan.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic 兼容翻译层测试（工单 0102）：请求/响应/stop_reason/usage 映射（表驱动）+ 流式事件序
 */
@DisplayName("Anthropic 兼容翻译层测试")
class AnthropicCompatTranslatorTest {

    @Test
    @DisplayName("请求翻译：system 顶层→system message；blocks→text；max_tokens/temperature/top_p 映射")
    void requestTranslation() {
        JSONObject anthropic = JSON.parseObject("""
                {"model":"claude-x","system":"你是助手",
                 "messages":[
                   {"role":"user","content":"你好"},
                   {"role":"assistant","content":[{"type":"text","text":"答复"},{"type":"tool_use","id":"t1"}]}],
                 "max_tokens":256,"temperature":0.5,"top_p":0.9,"stream":true}
                """);
        JSONObject openai = AnthropicCompatController.toOpenAiRequest(anthropic, true);
        assertEquals("claude-x", openai.getString("model"));
        assertEquals(true, openai.getBoolean("stream"));
        assertEquals(256, openai.getIntValue("max_tokens"));
        assertEquals(0.5, openai.getDoubleValue("temperature"));
        assertEquals(0.9, openai.getDoubleValue("top_p"));
        JSONArray messages = openai.getJSONArray("messages");
        assertEquals(3, messages.size());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("你是助手", messages.getJSONObject(0).getString("content"));
        assertEquals("你好", messages.getJSONObject(1).getString("content"));
        // blocks 数组：text 拼接、tool_use 跳过
        assertEquals("答复", messages.getJSONObject(2).getString("content"));
    }

    @Test
    @DisplayName("响应翻译：choices→content blocks、finish_reason→stop_reason、usage 字段映射")
    void responseTranslation() {
        String openai = """
                {"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"答案"},
                  "finish_reason":"length"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7}}
                """;
        String anthropic = AnthropicCompatController.toAnthropicResponse(
                openai, JSON.parseObject("{}"), "claude-x");
        JSONObject body = JSON.parseObject(anthropic);
        assertEquals("message", body.getString("type"));
        assertEquals("assistant", body.getString("role"));
        assertEquals("msg_chatcmpl-1", body.getString("id"));
        JSONArray content = body.getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("答案", content.getJSONObject(0).getString("text"));
        assertEquals("max_tokens", body.getString("stop_reason"));
        assertEquals(11, body.getJSONObject("usage").getIntValue("input_tokens"));
        assertEquals(7, body.getJSONObject("usage").getIntValue("output_tokens"));
    }

    @Test
    @DisplayName("stop_reason 映射表：stop→end_turn、length→max_tokens、stop_sequence 原样、null→end_turn")
    void stopReasonMapping() {
        assertEquals("end_turn", AnthropicCompatController.stopReasonOf("stop"));
        assertEquals("max_tokens", AnthropicCompatController.stopReasonOf("length"));
        assertEquals("stop_sequence", AnthropicCompatController.stopReasonOf("stop_sequence"));
        assertEquals("end_turn", AnthropicCompatController.stopReasonOf(null));
    }

    @Test
    @DisplayName("流式事件序：message_start→content_block_start→delta→stop→message_delta→message_stop")
    void streamEventSequence() throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        AnthropicCompatController.AnthropicSseTranslator translator =
                new AnthropicCompatController.AnthropicSseTranslator(response, "claude-x");

        translator.onLine("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
        translator.onLine("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
        translator.onLine("data: [DONE]");
        translator.finish();

        String out = response.getContentAsString();
        int messageStart = out.indexOf("event: message_start");
        int blockStart = out.indexOf("event: content_block_start");
        int delta = out.indexOf("event: content_block_delta");
        int blockStop = out.indexOf("event: content_block_stop");
        int messageDelta = out.indexOf("event: message_delta");
        int messageStop = out.indexOf("event: message_stop");
        assertTrue(messageStart >= 0 && blockStart > messageStart && delta > blockStart
                && blockStop > delta && messageDelta > blockStop && messageStop > messageDelta,
                "事件序非法：" + out);
        assertTrue(out.contains("text_delta"));
        assertTrue(out.contains("\"stop_reason\":\"end_turn\""));
        assertTrue(out.contains("\"output_tokens\":2"));
    }
}
