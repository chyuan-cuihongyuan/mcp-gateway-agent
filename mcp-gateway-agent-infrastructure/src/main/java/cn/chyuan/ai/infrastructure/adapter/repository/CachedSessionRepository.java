package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Primary
@Repository
@ConditionalOnBean(StringRedisTemplate.class)
public class CachedSessionRepository implements ISessionRepository {

    private static final String GATEWAY_KEY_PREFIX = "mcp:gateway:config:";
    private static final String TOOL_LIST_KEY_PREFIX = "mcp:gateway:tools:";
    private static final String TOOL_PROTOCOL_KEY_PREFIX = "mcp:tool:protocol:";

    @Resource
    private SessionRepository delegate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${mcp.cache.tool-config.enabled:true}")
    private boolean enabled;

    @Value("${mcp.cache.tool-config.ttl-minutes:30}")
    private long ttlMinutes;

    @Override
    public McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId) {
        return getOrLoad(GATEWAY_KEY_PREFIX + gatewayId,
                McpGatewayConfigVO.class,
                () -> delegate.queryMcpGatewayConfigByGatewayId(gatewayId));
    }

    @Override
    public List<McpToolConfigVO> queryMcpGatewayToolConfigListByGatewayId(String gatewayId) {
        return getListOrLoad(TOOL_LIST_KEY_PREFIX + gatewayId,
                new TypeReference<List<McpToolConfigVO>>() {},
                () -> delegate.queryMcpGatewayToolConfigListByGatewayId(gatewayId));
    }

    @Override
    public McpToolProtocolConfigVO queryMcpGatewayProtocolConfig(String gatewayId, String toolName) {
        return getOrLoad(TOOL_PROTOCOL_KEY_PREFIX + gatewayId + ":" + toolName,
                McpToolProtocolConfigVO.class,
                () -> delegate.queryMcpGatewayProtocolConfig(gatewayId, toolName));
    }

    public void evictGateway(String gatewayId) {
        stringRedisTemplate.delete(GATEWAY_KEY_PREFIX + gatewayId);
        stringRedisTemplate.delete(TOOL_LIST_KEY_PREFIX + gatewayId);
    }

    public void evictToolProtocol(String gatewayId, String toolName) {
        stringRedisTemplate.delete(TOOL_PROTOCOL_KEY_PREFIX + gatewayId + ":" + toolName);
    }

    private <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return JSON.parseObject(cached, type);
            }
        } catch (Exception e) {
            log.debug("read mcp cache failed: key={}", key, e);
        }
        T value = loader.get();
        cache(key, value);
        return value;
    }

    private <T> T getListOrLoad(String key, TypeReference<T> typeReference, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return JSON.parseObject(cached, typeReference);
            }
        } catch (Exception e) {
            log.debug("read mcp list cache failed: key={}", key, e);
        }
        T value = loader.get();
        cache(key, value);
        return value;
    }

    private void cache(String key, Object value) {
        if (value == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value), Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.debug("write mcp cache failed: key={}", key, e);
        }
    }
}
