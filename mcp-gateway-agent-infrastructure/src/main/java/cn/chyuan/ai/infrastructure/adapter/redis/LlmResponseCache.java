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

    public LlmResponseCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${governance.cache.llm.ttl-seconds:600}") long ttlSeconds) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.ttlSeconds = ttlSeconds;
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
        return redisTemplateProvider != null && redisTemplateProvider.getIfAvailable() != null;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
