package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedSessionRepositoryTest {

    @Test
    void loadsFromDelegateThenWritesCacheOnMiss() {
        McpGatewayConfigVO config = McpGatewayConfigVO.builder().gatewayId("gateway_001").build();
        SessionRepository delegate = new SessionRepository() {
            @Override
            public McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId) {
                return config;
            }
        };
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mcp:gateway:config:gateway_001")).thenReturn(null);

        CachedSessionRepository repository = new CachedSessionRepository();
        ReflectionTestUtils.setField(repository, "delegate", delegate);
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(repository, "meterRegistry", meterRegistry);
        ReflectionTestUtils.setField(repository, "enabled", true);
        ReflectionTestUtils.setField(repository, "ttlMinutes", 30L);

        assertThat(repository.queryMcpGatewayConfigByGatewayId("gateway_001")).isSameAs(config);
        verify(valueOperations).set(eq("mcp:gateway:config:gateway_001"), any(String.class), eq(java.time.Duration.ofMinutes(30)));
        assertThat(meterRegistry.counter("mcp_gateway_config_cache_total",
                "cache", "gateway_config", "result", "miss").count()).isEqualTo(1.0);
    }

    @Test
    void evictsGatewayAndToolKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CachedSessionRepository repository = new CachedSessionRepository();
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redisTemplate);

        repository.evictGateway("gateway_001");
        repository.evictToolProtocol("gateway_001", "query_logs");

        verify(redisTemplate).delete("mcp:gateway:config:gateway_001");
        verify(redisTemplate).delete("mcp:gateway:tools:gateway_001");
        verify(redisTemplate).delete("mcp:tool:protocol:gateway_001:query_logs");
    }
}
