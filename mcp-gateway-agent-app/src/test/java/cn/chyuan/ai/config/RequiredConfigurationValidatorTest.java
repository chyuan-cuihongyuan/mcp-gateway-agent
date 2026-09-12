package cn.chyuan.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动配置校验器契约测试（SELFLOOP2 loop-209）：
 * prod 弱值阻断、正常放行、dev/test 告警不阻断。
 */
@DisplayName("RequiredConfigurationValidator 配置契约")
class RequiredConfigurationValidatorTest {

    private RequiredConfigurationValidator validator(MockEnvironment env) {
        return new RequiredConfigurationValidator(env);
    }

    private MockEnvironment prod() {
        return new MockEnvironment().withProperty("spring.profiles.active", "prod");
    }

    @Test
    @DisplayName("prod + 三处弱默认值 → 阻断启动并逐项指出")
    void prodWithWeakDefaults_blocks() {
        MockEnvironment env = prod()
                .withProperty("spring.datasource.password", "123456")
                .withProperty("spring.ai.openai.api-key", "your-api-key-here")
                .withProperty("observability.http.auth-key", "observability-gateway-key");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator(env).afterPropertiesSet());
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertTrue(ex.getMessage().contains("spring.ai.openai.api-key"));
        assertTrue(ex.getMessage().contains("observability.http.auth-key"));
    }

    @Test
    @DisplayName("prod + 缺配置 → 阻断")
    void prodWithMissing_blocks() {
        assertThrows(IllegalStateException.class,
                () -> validator(prod()).afterPropertiesSet());
    }

    @Test
    @DisplayName("prod + 真实配置 → 放行")
    void prodWithRealValues_passes() {
        MockEnvironment env = prod()
                .withProperty("spring.datasource.password", "real-strong-pw")
                .withProperty("spring.ai.openai.api-key", "sk-real")
                .withProperty("observability.http.auth-key", "real-random-key");
        assertDoesNotThrow(() -> validator(env).afterPropertiesSet());
    }

    @Test
    @DisplayName("dev + 弱值 → 仅告警不阻断")
    void devWithWeakDefaults_warnsOnly() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "dev")
                .withProperty("spring.datasource.password", "123456");
        assertDoesNotThrow(() -> validator(env).afterPropertiesSet());
    }

    @Test
    @DisplayName("online profile 同样按严格档处理")
    void onlineProfileIsStrict() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "online")
                .withProperty("spring.datasource.password", "123456");
        assertThrows(IllegalStateException.class, () -> validator(env).afterPropertiesSet());
    }
}
