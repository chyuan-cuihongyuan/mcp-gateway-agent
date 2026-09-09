package cn.chyuan.ai.types.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 请求标签解析与清洗（工单 0088）
 *
 * <p>来源：请求头 X-Gateway-Tags（MCP/LLM/A2A 三面统一）与 LLM 请求体 metadata.tags。
 * 清洗规则：逗号分隔 → trim → 丢弃空项与超长项（&gt;32 字符）→ 去重（保序）→ 截断至多 5 个。
 * 超限截断不拒绝（观测维度不设硬闸）。
 *
 * @author chyuan
 */
public final class TagParser {

    /** 单标签最大长度 */
    public static final int MAX_TAG_LENGTH = 32;
    /** 单请求最大标签数 */
    public static final int MAX_TAGS = 5;

    private TagParser() {
    }

    /** 解析标签头（形如 "proj-a, team-1"）；null/空返回空列表 */
    public static List<String> parseHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return List.of();
        }
        return clean(List.of(headerValue.split("[,，]")));
    }

    /** 合并头来源与请求体来源（body 优先靠前；重新走清洗规则） */
    public static List<String> merge(List<String> headerTags, List<String> bodyTags) {
        List<String> merged = new ArrayList<>();
        if (bodyTags != null) {
            merged.addAll(bodyTags);
        }
        if (headerTags != null) {
            merged.addAll(headerTags);
        }
        return clean(merged);
    }

    /** 清洗：trim / 丢空 / 丢超长 / 去重保序 / 截断 */
    public static List<String> clean(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_TAG_LENGTH) {
                seen.add(trimmed);
            }
        }
        return seen.stream().limit(MAX_TAGS).toList();
    }

    /** 落库规范形：",a,b,"（首尾包裹逗号，支持 LIKE '%,tag,%' 精确匹配） */
    public static String toStorage(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return "," + String.join(",", tags) + ",";
    }

    /** 展示形："a,b"（剥离包裹逗号） */
    public static String toDisplay(String storage) {
        if (storage == null || storage.length() < 2) {
            return null;
        }
        return storage.substring(1, storage.length() - 1);
    }
}
