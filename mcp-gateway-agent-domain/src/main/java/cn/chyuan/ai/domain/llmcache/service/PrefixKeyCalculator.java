package cn.chyuan.ai.domain.llmcache.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 前缀缓存键计算器（工单 0277 AJ1，借鉴 vLLM prefix caching）—
 * 消息列表按消息为块做链式哈希：keys[i] = SHA-256(keys[i-1] + role + content)，
 * 任一中间块不同则后续键全变（前缀敏感性）；键与 model|tenant 命名空间隔离。
 * 确定性精确匹配（非语义缓存——语义缓存维持 0117 挂雾）。
 *
 * @author chyuan
 */
public final class PrefixKeyCalculator {

    /** 缓存消息块（与 OpenAI messages 数组对齐的最小形态） */
    public record ChatMessage(String role, String content) {

        public ChatMessage {
            role = role == null ? "user" : role;
            content = content == null ? "" : content;
        }
    }

    /** 命名空间（模型 + 租户隔离） */
    public static String namespace(String model, String tenant) {
        return model + "|" + (tenant == null ? "-" : tenant);
    }

    /** 链式前缀键序列：keys[0] 为首消息块，keys[n-1] 为全前缀 */
    public static List<String> prefixKeys(String namespace, List<ChatMessage> messages) {
        List<String> keys = new ArrayList<>();
        String previous = namespace;
        for (ChatMessage message : messages) {
            previous = sha256Hex(previous + "\u0001" + message.role() + "\u0002" + message.content());
            keys.add(previous);
        }
        return List.copyOf(keys);
    }

    /** 全前缀键（缓存条目主键 = 命名空间 + 最后一个链键） */
    public static String fullKey(String namespace, List<String> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }
        return namespace + ":" + keys.get(keys.size() - 1);
    }

    static String sha256Hex(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PrefixKeyCalculator() {
    }
}
