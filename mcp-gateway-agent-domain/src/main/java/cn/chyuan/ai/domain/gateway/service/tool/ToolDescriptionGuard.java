package cn.chyuan.ai.domain.gateway.service.tool;

import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具描述投毒防护闸（SELFLOOP7 loop-812，OWASP MCP03 / 工单 3023/3024）
 *
 * 借鉴 Invariant Labs tool poisoning 研究：恶意指令可藏于工具描述（用户不可见、
 * LLM 可见）。描述最终进入 LLM 上下文，因此在保存链上做输入侧内容闸——
 * 三类规则任一命中即阻断（fail-closed，与 loop-669 管理面拦截器同哲学）：
 * ① 指令劫持短语（中英双语）
 * ② 不可见 / 双向排版控制字符（zero-width、bidi override——「不可见文本」攻击向量）
 * ③ 超长描述（默认 2000 字符上限）
 */
public final class ToolDescriptionGuard {

    static final int MAX_DESCRIPTION_LENGTH = 2000;

    /** 指令劫持短语：诱导模型无视系统/用户既有指令的常见形态（大小写不敏感） */
    private static final List<Pattern> HIJACK_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+((all|any|the)\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
            Pattern.compile("(?i)disregard\\s+((all|any|the)\\s+)?(previous|prior|above|your)"),
            Pattern.compile("(?i)forget\\s+(everything|all|your\\s+instructions)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are\\s+now"),
            Pattern.compile("忽略(之前|以上|先前|上述)(的)?(指令|指示|规则|要求)"),
            Pattern.compile("无视(之前|以上|先前|上述)(的)?(指令|指示|规则|要求)"),
            Pattern.compile("(?i)override\\s+(your\\s+)?(system\\s+)?(instructions?|rules?)")
    );

    /** 不可见与双向排版控制字符区间（zero-width / bidi）——正常中文英文描述零命中 */
    private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]");

    private ToolDescriptionGuard() {
        // 工具类，禁止实例化
    }

    /**
     * 校验工具描述，命中投毒特征时抛 AppException（ILLEGAL_PARAMETER）阻断保存。
     *
     * @param description 工具描述（可为 null，null/空放行）
     * @throws AppException 命中任一投毒规则
     */
    public static void check(String description) {
        if (description == null || description.isEmpty()) {
            return;
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "工具描述超过 " + MAX_DESCRIPTION_LENGTH + " 字符上限，疑似投毒或误粘贴，已阻断");
        }
        for (Pattern pattern : HIJACK_PATTERNS) {
            if (pattern.matcher(description).find()) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "工具描述含指令劫持短语（" + pattern.pattern() + "），疑似投毒，已阻断");
            }
        }
        if (INVISIBLE_CHARACTERS.matcher(description).find()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "工具描述含不可见/双向排版控制字符，疑似隐藏指令投毒，已阻断");
        }
    }
}
