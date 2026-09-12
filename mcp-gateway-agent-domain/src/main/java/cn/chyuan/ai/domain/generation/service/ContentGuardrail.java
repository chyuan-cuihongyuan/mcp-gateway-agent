package cn.chyuan.ai.domain.generation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进出站内容护栏纯函数内核（工单 0200 AA5，借鉴 Dify moderation）—
 * PII 正则脱敏（手机号/邮箱/身份证）+ 敏感词表命中替换（词表配置化）。
 * 方向语义：inbound（请求消息入站）/ outbound（响应文本出站）共用同一套规则，
 * 由调用方决定应用方向。
 *
 * @author chyuan
 */
public final class ContentGuardrail {

    private ContentGuardrail() {
    }

    /** 手机号（大陆 1 开头 11 位） */
    static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 邮箱 */
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 身份证（18 位，末位可 X） */
    static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    /** 脱敏占位 */
    public static final String MASK = "***";

    /** 脱敏结果：改写后文本 + 各类命中次数 */
    public record SanitizeResult(String text, Map<String, Integer> hits) {

        public int totalHits() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** PII 脱敏（固定规则：phone/email/id_card） */
    public static SanitizeResult maskPii(String text) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult(text == null ? "" : text, Map.of());
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        String out = replaceCounting(text, PHONE, MASK, "phone", hits);
        out = replaceCounting(out, EMAIL, MASK, "email", hits);
        out = replaceCounting(out, ID_CARD, MASK, "id_card", hits);
        return new SanitizeResult(out, Map.copyOf(hits));
    }

    /** 敏感词替换（词表配置化；最长匹配优先：词表按长度降序拼接正则） */
    public static SanitizeResult maskSensitiveWords(String text, List<String> words) {
        if (text == null || text.isEmpty() || words == null || words.isEmpty()) {
            return new SanitizeResult(text == null ? "" : text, Map.of());
        }
        List<String> sorted = words.stream()
                .filter(w -> w != null && !w.isBlank())
                .sorted((a, b) -> b.length() - a.length())
                .toList();
        if (sorted.isEmpty()) {
            return new SanitizeResult(text, Map.of());
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        String out = replaceCounting(text,
                Pattern.compile(sorted.stream()
                        .map(Pattern::quote)
                        .reduce((a, b) -> a + "|" + b)
                        .orElseThrow()),
                MASK, "sensitive_word", hits);
        return new SanitizeResult(out, Map.copyOf(hits));
    }

    /** 组合：先敏感词后 PII（避免 PII 脱敏引入的 *** 干扰词边界） */
    public static SanitizeResult sanitize(String text, List<String> sensitiveWords) {
        SanitizeResult words = maskSensitiveWords(text, sensitiveWords);
        SanitizeResult pii = maskPii(words.text());
        if (pii.hits().isEmpty()) {
            return new SanitizeResult(pii.text(), words.hits());
        }
        Map<String, Integer> merged = new LinkedHashMap<>(words.hits());
        pii.hits().forEach((k, v) -> merged.merge(k, v, Integer::sum));
        return new SanitizeResult(pii.text(), Map.copyOf(merged));
    }

    /** OpenAI chat messages 形态的进站脱敏：就地改写 messages 数组内文本内容，返回命中总次数 */
    @SuppressWarnings("unchecked")
    public static int sanitizeChatMessages(com.alibaba.fastjson.JSONObject request, List<String> sensitiveWords) {
        if (request == null || !(request.get("messages") instanceof List<?> messages)) {
            return 0;
        }
        int total = 0;
        for (Object item : messages) {
            if (item instanceof com.alibaba.fastjson.JSONObject message
                    && message.get("content") instanceof String content) {
                SanitizeResult result = sanitize(content, sensitiveWords);
                if (result.totalHits() > 0) {
                    message.put("content", result.text());
                    total += result.totalHits();
                }
            }
        }
        return total;
    }

    private static String replaceCounting(String text, Pattern pattern, String replacement,
            String hitKey, Map<String, Integer> hits) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            count++;
        }
        matcher.appendTail(out);
        if (count > 0) {
            hits.merge(hitKey, count, Integer::sum);
        }
        return out.toString();
    }

    /** 测试辅助：命中键列表（稳定顺序） */
    static List<String> hitKeys(Map<String, Integer> hits) {
        return new ArrayList<>(hits.keySet());
    }
}
