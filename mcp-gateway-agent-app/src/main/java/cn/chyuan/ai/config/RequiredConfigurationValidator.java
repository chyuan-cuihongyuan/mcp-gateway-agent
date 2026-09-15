package cn.chyuan.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 启动期关键配置校验（SELFLOOP2 loop-209，移植 agg 仓同名校验器模式）。
 * <p>
 * prod profile 下命中弱默认值或缺失关键配置直接阻断启动，防止应用带公开弱钥/弱口令上线；
 * dev/test 仅输出告警。弱值清单取自 application-prod.yml 现状取证。
 */
@Slf4j
@Component
public class RequiredConfigurationValidator implements InitializingBean {

    /** prod 禁用的弱默认值（键 → 弱值），来自 application-prod.yml 的 env 回落值 */
    private static final Map<String, String> FORBIDDEN_WEAK_DEFAULTS = Map.of(
            "spring.datasource.password", "123456",
            "spring.ai.openai.api-key", "your-api-key-here",
            "observability.http.auth-key", "observability-gateway-key");

    /** prod 必填关键配置（loop-680）：缺失即阻断启动（管理面 fail-closed 的启动期前置） */
    private static final List<String> PROD_REQUIRED_KEYS = List.of("admin.auth-token");

    private final Environment environment;

    public RequiredConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = collectProblems();
        if (problems.isEmpty()) {
            return;
        }
        if (isStrictProfile()) {
            throw new IllegalStateException("生产环境配置校验失败: " + String.join("; ", problems));
        }
        log.warn("当前环境配置存在风险（dev/test 仅告警）: {}", String.join("; ", problems));
    }

    private List<String> collectProblems() {
        List<String> problems = new ArrayList<>();
        FORBIDDEN_WEAK_DEFAULTS.forEach((key, weakValue) -> {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                problems.add(key + " 未配置");
            } else if (weakValue.equals(value)) {
                problems.add(key + " 使用弱默认值 \"" + weakValue + "\"");
            }
        });
        // prod 必填键（loop-680）：admin.auth-token 缺失即阻断（管理面 fail-closed 启动期前置）
        for (String key : PROD_REQUIRED_KEYS) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                problems.add(key + " 未配置（生产必填）");
            }
        }
        return problems;
    }

    private boolean isStrictProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "online".equalsIgnoreCase(p));
    }
}
