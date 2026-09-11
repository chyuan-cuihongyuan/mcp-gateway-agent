package cn.chyuan.ai.domain.llmchannel.service;

/**
 * 模型上下文 Token 粗估纯函数（工单 0162）
 *
 * <p>口径（验收锚点）：
 * ①词元口径（默认）：CJK 字符每字计 1 token（中文按字）；非 CJK 连续字母/数字段每词计
 *   1 token（英文按词）；标点/空白不计——业界常用粗估，偏差 ±20% 量级，足以做"注定超限"拦截；
 * ②字符兜底口径（可配置）：总字符数 / charsPerToken（默认 4，向上取整），用于词元口径
 *   不适用的语种兜底（charsPerToken 由调用方按配置传入）；
 * ③空串恒 0；超长线性增长无上限截断。
 *
 * @author chyuan
 */
public final class TokenEstimator {

    /** 字符兜底口径默认每 token 字符数 */
    public static final int DEFAULT_CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
        // 纯函数工具类，禁止实例化
    }

    /** 词元口径粗估：CJK 按字 + 非按 CJK 词，标点/空白不计 */
    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjk = 0;
        long words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
                inWord = false;
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return cjk + words;
    }

    /** 字符兜底口径：总字符数 / charsPerToken 向上取整（charsPerToken<=0 按默认 4） */
    public static long estimateByChars(String text, int charsPerToken) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        int divisor = charsPerToken <= 0 ? DEFAULT_CHARS_PER_TOKEN : charsPerToken;
        return (text.length() + divisor - 1) / divisor;
    }

    /** CJK 判定（中日韩统一表意文字及扩展 A） */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)          // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)      // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)      // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF);     // 韩文音节
    }
}
