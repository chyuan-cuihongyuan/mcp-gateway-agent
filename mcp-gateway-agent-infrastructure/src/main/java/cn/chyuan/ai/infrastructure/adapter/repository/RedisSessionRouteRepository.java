package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 会话亲和路由表 Redis 实现（工单 0055；键前缀 mcp:route:，TTL 与会话超时一致）
 *
 * @author chyuan
 */
@Slf4j
@Repository
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisSessionRouteRepository implements ISessionRouteRepository {

    private static final String KEY_PREFIX = "mcp:route:";

    private final StringRedisTemplate redisTemplate;

    public RedisSessionRouteRepository(org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public void save(String sessionId, String instanceId, Duration ttl) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, instanceId, ttl);
    }

    @Override
    public String findInstance(String sessionId) {
        if (redisTemplate == null) {
            return null;
        }
        return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
    }

    @Override
    public void delete(String sessionId) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}
