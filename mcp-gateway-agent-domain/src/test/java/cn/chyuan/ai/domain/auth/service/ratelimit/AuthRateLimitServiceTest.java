package cn.chyuan.ai.domain.auth.service.ratelimit;

import cn.chyuan.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.chyuan.ai.domain.auth.model.entity.RateLimitCommandEntity;
import cn.chyuan.ai.domain.auth.model.valobj.McpGatewayAuthVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthRateLimitService 限流契约测试（工单 1139）：
 * 约定 rateLimit 返回 true=拒绝（配额耗尽/限流值非法），false=放行（无配置/校验异常）。
 */
@DisplayName("AuthRateLimitService 限流契约")
class AuthRateLimitServiceTest {

    private final IAuthRepository repository = mock(IAuthRepository.class);

    private AuthRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new AuthRateLimitService();
        ReflectionTestUtils.setField(service, "repository", repository);
    }

    private RateLimitCommandEntity command(String gatewayId, String apiKey) {
        return new RateLimitCommandEntity(gatewayId, apiKey);
    }

    @Test
    @DisplayName("apiKey 为空直接放行，不查仓储")
    void blankApiKeyPassesWithoutRepository() {
        assertThat(service.rateLimit(command("gw-1", null))).isFalse();
        assertThat(service.rateLimit(command("gw-1", "  "))).isFalse();
        verify(repository, never()).queryEffectiveGatewayAuthInfo(any());
    }

    @Test
    @DisplayName("首调用放行，令牌耗尽后立即拒绝（真限流语义）")
    void firstCallPassesThenBurstRejected() {
        // 1 次/小时 → 首令牌立即可用，第二个请求必须等 1 小时 → 拒绝
        when(repository.queryEffectiveGatewayAuthInfo(any()))
                .thenReturn(McpGatewayAuthVO.builder().gatewayId("gw-1").apiKey("k1").rateLimit(1).build());

        assertThat(service.rateLimit(command("gw-1", "k1"))).isFalse();
        assertThat(service.rateLimit(command("gw-1", "k1"))).isTrue();
    }

    @Test
    @DisplayName("不同网关/密钥的限流器相互隔离")
    void keysAreIsolated() {
        when(repository.queryEffectiveGatewayAuthInfo(any()))
                .thenReturn(McpGatewayAuthVO.builder().rateLimit(1).build());

        assertThat(service.rateLimit(command("gw-1", "k1"))).isFalse();
        assertThat(service.rateLimit(command("gw-2", "k1"))).isFalse();
        assertThat(service.rateLimit(command("gw-1", "k2"))).isFalse();
    }

    @Test
    @DisplayName("未配置限流信息（VO 为 null 或 rateLimit 空）→ 放行")
    void missingRateLimitConfigPasses() {
        when(repository.queryEffectiveGatewayAuthInfo(any())).thenReturn(null);
        assertThat(service.rateLimit(command("gw-1", "k1"))).isFalse();

        when(repository.queryEffectiveGatewayAuthInfo(any()))
                .thenReturn(McpGatewayAuthVO.builder().gatewayId("gw-1").apiKey("k2").build());
        assertThat(service.rateLimit(command("gw-1", "k2"))).isFalse();
    }

    @Test
    @DisplayName("限流值非法（≤0）→ 一律拒绝")
    void illegalRateLimitValueRejects() {
        when(repository.queryEffectiveGatewayAuthInfo(any()))
                .thenReturn(McpGatewayAuthVO.builder().gatewayId("gw-1").apiKey("k1").rateLimit(0).build());

        assertThat(service.rateLimit(command("gw-1", "k1"))).isTrue();
        assertThat(service.rateLimit(command("gw-1", "k1"))).isTrue();
    }

    @Test
    @DisplayName("仓储异常 → 放行（fail-open）")
    void repositoryErrorFailsOpen() {
        when(repository.queryEffectiveGatewayAuthInfo(any())).thenThrow(new RuntimeException("db down"));

        assertThat(service.rateLimit(command("gw-1", "k1"))).isFalse();
    }
}
