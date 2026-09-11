package cn.chyuan.ai.domain.llmchannel.service;

import java.util.List;
import java.util.Locale;

/**
 * API Key 模型白名单纯函数（工单 0157）
 *
 * <p>判定口径：白名单空（null/空列表）=不限制（兼容存量密钥）；命中=大小写归一后的
 * 精确匹配（trim + lowercase，模型名业界惯例不区分大小写）；未命中即拒绝。
 *
 * @author chyuan
 */
public final class ModelWhitelist {

    private ModelWhitelist() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 白名单判定。
     *
     * @param model     请求模型名
     * @param whitelist 密钥白名单（空=不限制放行）
     * @return true=放行；false=白名单外（调度前拒绝）
     */
    public static boolean allowed(String model, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        for (String entry : whitelist) {
            if (entry != null && normalized.equals(entry.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
