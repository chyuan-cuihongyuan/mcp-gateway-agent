package cn.chyuan.ai.config;

import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionMetaRepository;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.infrastructure.gateway.streamable.GatewayMcpServerRegistry;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.trigger.http.McpGatewayDelegateServlet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Streamable HTTP 传输装配（工单 0020）
 *
 * <p>/api-gateway/* 由委派路由接管（取代原 SSE 控制器），
 * 治理面过滤器（认证 order 10 / 配额 order 11）在 servlet 之前生效。
 *
 * @author chyuan
 */
@Configuration
public class McpTransportConfig {

    @Bean
    public ServletRegistrationBean<McpGatewayDelegateServlet> mcpGatewayDelegateServlet(
            GatewayMcpServerRegistry registry,
            IMcpToolCatalogService toolCatalogService,
            ObjectProvider<ISessionMetaRepository> sessionMetaRepository,
            ObservabilityHelper observabilityHelper,
            IUsageLedgerService usageLedgerService,
            IQuotaService quotaService,
            ObjectProvider<cn.chyuan.ai.domain.promptresource.service.PromptResourceService> promptResourceService,
            @Value("${governance.request.max-body-bytes:65536}") int maxBodyBytes,
            @Value("${mcp.session.timeout-minutes:30}") long sessionTimeoutMinutes) {
        ServletRegistrationBean<McpGatewayDelegateServlet> registration = new ServletRegistrationBean<>(
                new McpGatewayDelegateServlet(registry, toolCatalogService,
                        sessionMetaRepository.getIfAvailable(), observabilityHelper,
                        usageLedgerService, quotaService, promptResourceService.getIfAvailable(), maxBodyBytes, sessionTimeoutMinutes),
                "/api-gateway/*");
        // 官方 streamable 传输对 POST 请求与 GET 监听流使用 startAsync
        registration.setAsyncSupported(true);
        registration.setName("mcpGatewayDelegateServlet");
        return registration;
    }
}
