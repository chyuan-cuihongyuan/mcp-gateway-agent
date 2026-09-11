package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.session.model.valobj.SessionMetaVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSessionMetaRepositoryTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedisSessionMetaRepository repository = new RedisSessionMetaRepository();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void saveWritesJsonWithTtlUnderSessionKey() {
        SessionMetaVO meta = SessionMetaVO.builder()
                .sessionId("sess-001").gatewayId("gateway_001")
                .apiKeyHash("abc123").status("ACTIVE")
                .createTime(1L).lastAccessedTime(2L)
                .build();

        repository.save(meta, Duration.ofMinutes(30));

        verify(valueOperations).set(eq("mcp:session:meta:sess-001"), any(String.class), eq(Duration.ofMinutes(30)));
    }

    @Test
    void findRoundTripsStoredMeta() {
        SessionMetaVO meta = SessionMetaVO.builder()
                .sessionId("sess-001").gatewayId("gateway_001")
                .apiKeyHash("abc123").status("ACTIVE")
                .createTime(1L).lastAccessedTime(2L)
                .build();
        when(valueOperations.get("mcp:session:meta:sess-001")).thenReturn(
                com.alibaba.fastjson.JSON.toJSONString(meta));

        SessionMetaVO found = repository.find("sess-001");

        assertThat(found).usingRecursiveComparison().isEqualTo(meta);
    }

    @Test
    void findReturnsNullWhenKeyMissing() {
        when(valueOperations.get("mcp:session:meta:sess-404")).thenReturn(null);

        assertThat(repository.find("sess-404")).isNull();
    }

    @Test
    void touchRewritesMetaWithRenewedTtlAndFreshTimestamp() {
        SessionMetaVO meta = SessionMetaVO.builder()
                .sessionId("sess-001").gatewayId("gateway_001")
                .status("ACTIVE").createTime(1L).lastAccessedTime(1L)
                .build();
        when(valueOperations.get("mcp:session:meta:sess-001")).thenReturn(
                com.alibaba.fastjson.JSON.toJSONString(meta));

        long before = System.currentTimeMillis();
        repository.touch("sess-001", Duration.ofMinutes(30));

        ArgumentCaptor<String> savedCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("mcp:session:meta:sess-001"), savedCaptor.capture(), eq(Duration.ofMinutes(30)));
        SessionMetaVO saved = com.alibaba.fastjson.JSON.parseObject(savedCaptor.getValue(), SessionMetaVO.class);
        assertThat(saved.getLastAccessedTime()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void touchIsNoOpWhenMetaMissing() {
        when(valueOperations.get("mcp:session:meta:sess-404")).thenReturn(null);

        repository.touch("sess-404", Duration.ofMinutes(30));

        verify(valueOperations, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void deleteRemovesSessionKey() {
        repository.delete("sess-001");

        verify(redisTemplate).delete("mcp:session:meta:sess-001");
    }

    @Test
    void redisFailureDegradesSilentlyInsteadOfBreakingSessionFlow() {
        when(valueOperations.get(any(String.class))).thenThrow(new RuntimeException("redis down"));

        assertThat(repository.find("sess-001")).isNull();
        // save 异常被吞掉，不向会话链路传播
        repository.save(SessionMetaVO.builder().sessionId("sess-001").build(), Duration.ofMinutes(30));
        repository.touch("sess-001", Duration.ofMinutes(30));
        repository.delete("sess-001");
    }
}
