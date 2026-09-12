package cn.chyuan.ai.domain.generation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提示注入检测纯函数内核（工单 0201 AA6，OWASP LLM01 思想）—
 * 四类规则评分（0-100）：指令覆盖/角色越权/分隔符走私/编码走私，各带权重与命中明细，
 * 超阈（默认 70）由调用方拒绝（-32024）。
 *
 * @author chyuan
 */
public final class InjectionDetector {

    private InjectionDetector() {
    }

    /** 默认拒绝阈值 */
    public static final int DEFAULT_THRESHOLD = 70;

    /** 规则类别 */
    public static final String RULE_OVERRIDE = "instruction_override";
    public static final String RULE_ROLE_ESCALATION = "role_escalation";
    public static final String RULE_DELIMITER_SMUGGLE = "delimiter_smuggle";
    public static final String RULE_ENCODING_SMUGGLE = "encoding_smuggle";

    private static final Map<String, Integer> WEIGHTS = Map.of(
            RULE_OVERRIDE, 75,
            RULE_ROLE_ESCALATION, 75,
            RULE_DELIMITER_SMUGGLE, 40,
            RULE_ENCODING_SMUGGLE, 40);

    private static final Map<String, List<Pattern>> RULES = Map.of(
            RULE_OVERRIDE, List.of(
                    Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
                    Pattern.compile("(?i)(忽略|无视)(之前|以上|上面|先前)?的?(全部|所有)?(指令|提示|规则|设定)"),
                    Pattern.compile("(?i)disregard\\s+(your|the|all)\\s+(instructions?|rules?)")),
            RULE_ROLE_ESCALATION, List.of(
                    Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|the)\\s+"),
                    Pattern.compile("(?i)(pretend|act)\\s+to\\s+be\\s+"),
                    Pattern.compile("(?i)(你现在是|假装你是|扮演)(一个)?")),
            RULE_DELIMITER_SMUGGLE, List.of(
                    Pattern.compile("(?i)(system|assistant|developer)\\s*[:：]\\s*"),
                    Pattern.compile("<\\|?(im_start|im_end|system)\\|?>"),
                    Pattern.compile("(###|===)\\s*(system|instructions?|rules?)")),
            RULE_ENCODING_SMUGGLE, List.of(
                    Pattern.compile("(?i)(base64|hex|rot13)\\s*(decode|decode and execute|decoded)"),
                    Pattern.compile("(?i)(解码|解密)(后)?(执行|运行)")));

    /** 检测结果：0-100 分 + 各类别命中明细 */
    public record DetectionResult(int score, Map<String, Integer> categoryHits, int threshold) {

        public boolean blocked() {
            return score >= threshold;
        }
    }

    /** 检测：多规则可叠加（同类别多次命中取类别内最高计数×权重，跨类别累加，封顶 100） */
    public static DetectionResult detect(String text, int threshold) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return new DetectionResult(0, Map.of(), threshold);
        }
        int score = 0;
        for (Map.Entry<String, List<Pattern>> rule : RULES.entrySet()) {
            int best = 0;
            for (Pattern pattern : rule.getValue()) {
                int count = 0;
                var matcher = pattern.matcher(text);
                while (matcher.find()) {
                    count++;
                }
                best = Math.max(best, count);
            }
            if (best > 0) {
                hits.put(rule.getKey(), best);
                score += WEIGHTS.get(rule.getKey());
            }
        }
        return new DetectionResult(Math.min(100, score), Map.copyOf(hits), threshold);
    }

    /** 默认阈值检测 */
    public static DetectionResult detect(String text) {
        return detect(text, DEFAULT_THRESHOLD);
    }

    /** 从 OpenAI chat messages 取待检文本拼接（仅 user 角色，避免系统提示自伤） */
    public static String textOfUserMessages(com.alibaba.fastjson.JSONObject request) {
        if (request == null || !(request.get("messages") instanceof List<?> messages)) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (Object item : messages) {
            if (item instanceof com.alibaba.fastjson.JSONObject message
                    && "user".equalsIgnoreCase(message.getString("role"))
                    && message.get("content") instanceof String content) {
                joined.append(content).append('\n');
            }
        }
        return joined.toString();
    }
}
