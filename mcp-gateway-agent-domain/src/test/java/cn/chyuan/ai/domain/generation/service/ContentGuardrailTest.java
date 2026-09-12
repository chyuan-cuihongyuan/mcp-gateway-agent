package cn.chyuan.ai.domain.generation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容护栏单测（工单 0200 AA5）：PII 脱敏/敏感词替换/messages 进站改写。
 */
class ContentGuardrailTest {

    @Test
    void PII脱敏() {
        ContentGuardrail.SanitizeResult r = ContentGuardrail.maskPii(
                "联系 13812345678 或 mail: a.b@test.com，证件 110101199003077758");
        assertEquals("联系 *** 或 mail: ***，证件 ***", r.text());
        assertEquals(1, r.hits().get("phone"));
        assertEquals(1, r.hits().get("email"));
        assertEquals(1, r.hits().get("id_card"));
        // 边界：更长的数字串不误伤（19 位数字非身份证）
        assertEquals("1234567890123456789", ContentGuardrail.maskPii("1234567890123456789").text());
    }

    @Test
    void 敏感词最长优先替换() {
        ContentGuardrail.SanitizeResult r = ContentGuardrail.maskSensitiveWords(
                "这是 BADWORD 与 BAD 混排", List.of("BAD", "BADWORD"));
        assertEquals("这是 *** 与 *** 混排", r.text());
        assertEquals(2, r.hits().get("sensitive_word"));
    }

    @Test
    void 组合脱敏() {
        ContentGuardrail.SanitizeResult r = ContentGuardrail.sanitize(
                "坏词 email:x@y.com", List.of("坏词"));
        assertEquals("*** email:***", r.text());
        Map<String, Integer> hits = r.hits();
        assertEquals(1, hits.get("sensitive_word"));
        assertEquals(1, hits.get("email"));
        assertEquals(2, r.totalHits());
    }

    @Test
    void messages进站改写() {
        com.alibaba.fastjson.JSONObject request = new com.alibaba.fastjson.JSONObject();
        com.alibaba.fastjson.JSONArray messages = new com.alibaba.fastjson.JSONArray();
        com.alibaba.fastjson.JSONObject user = new com.alibaba.fastjson.JSONObject();
        user.put("role", "user");
        user.put("content", "我的手机 13900000001");
        messages.add(user);
        request.put("messages", messages);
        int total = ContentGuardrail.sanitizeChatMessages(request, List.of());
        assertEquals(1, total);
        assertEquals("我的手机 ***", messages.getJSONObject(0).getString("content"));
        // 无 messages/无命中安全
        assertEquals(0, ContentGuardrail.sanitizeChatMessages(new com.alibaba.fastjson.JSONObject(), List.of()));
    }

    @Test
    void 空输入安全() {
        assertEquals("", ContentGuardrail.maskPii("").text());
        assertEquals("原样", ContentGuardrail.maskSensitiveWords("原样", List.of()).text());
        assertEquals(0, ContentGuardrail.sanitize(null, List.of("x")).totalHits());
        assertTrue(ContentGuardrail.hitKeys(ContentGuardrail.maskPii("无命中").hits()).isEmpty());
    }
}
