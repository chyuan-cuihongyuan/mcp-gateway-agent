package cn.chyuan.ai.cases.mcp.session.node;

import cn.chyuan.ai.cases.mcp.session.AbstractMcpSessionSupport;
import cn.chyuan.ai.cases.mcp.session.factory.DefaultMcpSessionFactory;
import cn.chyuan.ai.domain.auth.model.entity.LicenseCommandEntity;
import cn.chyuan.ai.domain.auth.service.IAuthLicenseService;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import reactor.core.publisher.Flux;

/**
 * 鉴权核验（工单 0017：优先消费统一认证过滤器产出的主体；无主体时走遗留校验兜底）
 *
 * @author chyuan @chyuan
 * 2025/12/13 09:22
 */
@Slf4j
@Service("mcpSessionVerifyNode")
public class VerifyNode extends AbstractMcpSessionSupport {

    @Resource(name = "mcpSessionSessionNode")
    private SessionNode sessionNode;

    @Resource
    private IAuthLicenseService authLicenseService;

    @Resource
    private IGovernanceAuthService governanceAuthService;

    @Override
    protected Flux<ServerSentEvent<String>> doApply(String requestParameter, DefaultMcpSessionFactory.DynamicContext dynamicContext) throws Exception {
        log.info("创建会话-VerifyNode:{}", requestParameter);

        GovernancePrincipal principal = dynamicContext.getPrincipal();
        if (principal != null) {
            // 统一认证路径（缓存命中，幂等复核）
            governanceAuthService.validatePrincipal(requestParameter, principal);
        } else {
            // 遗留兜底：直连调用（无过滤器场景，如单测）
            boolean isCheckSuccess = authLicenseService.checkLicense(
                    new LicenseCommandEntity(requestParameter, dynamicContext.getApiKey()));
            if (!isCheckSuccess) {
                throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "fail to auth apikey");
            }
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<String, DefaultMcpSessionFactory.DynamicContext, Flux<ServerSentEvent<String>>> get(String requestParameter, DefaultMcpSessionFactory.DynamicContext dynamicContext) throws Exception {
        return sessionNode;
    }

}
