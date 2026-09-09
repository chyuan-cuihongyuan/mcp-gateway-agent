package cn.chyuan.ai.infrastructure.adapter.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * LLM 响应精确缓存（工单 0097，LiteLLM redis 精确缓存口径裁剪）
 *
 * <p>键 = sha256(vkId|model|参与字段 JSON)——vk 隔离（不同密钥同请求不串）；
 * 非流式 JSON 响应整串缓存；语义缓存不做（官方警告 agentic 流量重放风险，0081 雾项）。
 * Redis 未装配/读写异常一律静默降级直通（不 fail 请求）。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class LlmResponseCache implements cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort {

    static final String KEY_PREFIX = "mcp.gateway.llm.cache:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private final long ttlSeconds;

    /** 总开关（工单 0098：默认 false——显式开启才缓存） */
    private final boolean enabled;

    public LlmResponseCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${governance.cache.llm.ttl-seconds:600}") long ttlSeconds,
            @Value("${governance.cache.llm.enabled:false}") boolean enabled) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.ttlSeconds = ttlSeconds;
        this.enabled = enabled;
    }

    /** 缓存键：vk 隔离 + 模型 + 参与字段规范化串 */
    public String cacheKey(Long virtualKeyId, String model, String normalizedRequest) {
        String material = (virtualKeyId == null ? "anon" : virtualKeyId) + "|" + model + "|" + normalizedRequest;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return KEY_PREFIX + hex;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String get(String cacheKey) {
        if (cacheKey == null) {
            return null;
        }
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return null;
            }
            return template.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.debug("LLM 缓存读失败（降级直连）：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String cacheKey, String response) {
        if (cacheKey == null || response == null || response.isEmpty()) {
            return;
        }
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return;
            }
            template.opsForValue().set(cacheKey, response, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("LLM 缓存写失败（忽略）：{}", e.getMessage());
        }
    }

    @Override
    public boolean available() {
        return enabled && redisTemplateProvider != null && redisTemplateProvider.getIfAvailable() != null;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /** 按键删除（工单 0100；key 可从响应头 X-Gateway-Cache-Key 获取） */
    public boolean delete(String cacheKey) {
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null || cacheKey == null || cacheKey.isBlank()) {
                return false;
            }
            return Boolean.TRUE.equals(template.delete(cacheKey));
        } catch (Exception e) {
            log.debug("缓存键删除失败：{}", e.getMessage());
            return false;
        }
    }

    /** 前缀清空（工单 0100；SCAN 游标避免 keys 阻塞） */
    public long purge() {
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return -1;
            }
            long removed = 0;
            var options = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(KEY_PREFIX + "*").count(500).build();
            try (var cursor = template.scan(options)) {
                while (cursor.hasNext()) {
                    if (Boolean.TRUE.equals(template.delete(cursor.next()))) {
                        removed++;
                    }
                }
            }
            return removed;
        } catch (Exception e) {
            log.debug("缓存清空失败：{}", e.getMessage());
            return -1;
        }
    }

    /** 统计（工单 0100：键数 + 降级标识；命中率由账本口径出） */
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("available", available());
        stats.put("ttlSeconds", ttlSeconds);
        long keys = -1;
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template != null && available()) {
                keys = 0;
                var options = org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(KEY_PREFIX + "*").count(500).build();
                try (var cursor = template.scan(options)) {
                    while (cursor.hasNext()) {
                        cursor.next();
                        keys++;
                    }
                }
            }
        } catch (Exception e) {
            keys = -1;
        }
        stats.put("keys", keys);
        return stats;
    }
}
