package cn.chyuan.ai.config;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.trigger.filter.AdminJwtAuthFilter;
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

    /** 配额限流（认证之后：per-key RPM/日配额，超配额 429、Redis 故障 fail-closed 503） */
    @Bean
    public FilterRegistrationBean<QuotaEnforcementFilter> quotaEnforcementFilter() {
        FilterRegistrationBean<QuotaEnforcementFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new QuotaEnforcementFilter(quotaService));
        registration.addUrlPatterns("/api-gateway/*");
        registration.setOrder(11);
        registration.setName("quotaEnforcementFilter");
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
