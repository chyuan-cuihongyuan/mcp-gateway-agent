package cn.chyuan.ai.config;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.service.IBudgetService;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.governance.service.ConcurrencyGuardService;
import cn.chyuan.ai.trigger.filter.AdminJwtAuthFilter;
import cn.chyuan.ai.trigger.filter.ConcurrencyLimitFilter;
import cn.chyuan.ai.trigger.filter.GlobalTrafficAuthFilter;
import cn.chyuan.ai.trigger.filter.GovernanceAuthFilter;
import cn.chyuan.ai.trigger.filter.QuotaEnforcementFilter;
import jakarta.annotation.Resource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 治理面过滤器注册（工单 0017 认证 / 0019 配额限流）
 *
 * @author chyuan
 */
@Configuration
public class GovernanceFilterConfig {

    @Resource
    private IGovernanceAuthService governanceAuthService;

    @Resource
    private IJwtCodec jwtCodec;

    @Resource
    private IQuotaService quotaService;

    @Resource
    private IBudgetService budgetService;

    @Resource
    private ConcurrencyGuardService concurrencyGuardService;

    @org.springframework.beans.factory.annotation.Value("${governance.request.max-concurrent-per-key:0}")
    private int maxConcurrentPerKey;

    /** MCP 协议面统一认证（无凭证 401 / 错凭证 403） */
    @Bean
    public FilterRegistrationBean<GovernanceAuthFilter> governanceAuthFilter() {
        FilterRegistrationBean<GovernanceAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GovernanceAuthFilter(governanceAuthService));
        registration.addUrlPatterns("/api-gateway/*");
        registration.setOrder(10);
        registration.setName("governanceAuthFilter");
        return registration;
    }

    /** 配额限流（认证之后：per-key RPM/日配额 + 周期预算，超配额 429、Redis 故障 fail-closed 503） */
    @Bean
    public FilterRegistrationBean<QuotaEnforcementFilter> quotaEnforcementFilter() {
        FilterRegistrationBean<QuotaEnforcementFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new QuotaEnforcementFilter(quotaService, budgetService));
        registration.addUrlPatterns("/api-gateway/*");
        registration.setOrder(11);
        registration.setName("quotaEnforcementFilter");
        return registration;
    }

    /** 全局流量面认证（/v1 OpenAI 兼容面 + /a2a 任务面，order 10；card 发现面 /.well-known 免认证） */
    @Bean
    public FilterRegistrationBean<GlobalTrafficAuthFilter> globalTrafficAuthFilter() {
        FilterRegistrationBean<GlobalTrafficAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GlobalTrafficAuthFilter(governanceAuthService));
        registration.addUrlPatterns("/v1/*", "/a2a/*");
        registration.setOrder(10);
        registration.setName("globalTrafficAuthFilter");
        return registration;
    }

    /** /actuator/prometheus 认证开关（工单 0070：governance.metrics.auth-required=true 时要求 admin JWT） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "governance.metrics.auth-required", havingValue = "true")
    public FilterRegistrationBean<cn.chyuan.ai.trigger.filter.MetricsAuthFilter> metricsAuthFilter() {
        FilterRegistrationBean<cn.chyuan.ai.trigger.filter.MetricsAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new cn.chyuan.ai.trigger.filter.MetricsAuthFilter(jwtCodec));
        registration.addUrlPatterns("/actuator/prometheus");
        registration.setOrder(5);
        registration.setName("metricsAuthFilter");
        return registration;
    }

    /** 每密钥并发闸（配额/预算之后，order 12；限值 0 不注册） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "governance.request.max-concurrent-per-key", matchIfMissing = false)
    public FilterRegistrationBean<ConcurrencyLimitFilter> concurrencyLimitFilter() {
        FilterRegistrationBean<ConcurrencyLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ConcurrencyLimitFilter(concurrencyGuardService, maxConcurrentPerKey));
        registration.addUrlPatterns("/api-gateway/*");
        registration.setOrder(12);
        registration.setName("concurrencyLimitFilter");
        return registration;
    }

    /** admin 控制台 JWT 认证 + 角色约束（登录接口白名单内放行） */
    @Bean
    public FilterRegistrationBean<AdminJwtAuthFilter> adminJwtAuthFilter() {
        FilterRegistrationBean<AdminJwtAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminJwtAuthFilter(jwtCodec));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(20);
        registration.setName("adminJwtAuthFilter");
        return registration;
    }
}
