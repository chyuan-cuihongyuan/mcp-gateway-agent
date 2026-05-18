package cn.chyuan.ai.cases.mcp.session.node;

import cn.chyuan.ai.cases.mcp.session.AbstractMcpSessionSupport;
import cn.chyuan.ai.cases.mcp.session.factory.DefaultMcpSessionFactory;
import cn.chyuan.ai.domain.auth.model.entity.LicenseCommandEntity;
import cn.chyuan.ai.domain.auth.service.IAuthLicenseService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;

/**
 * 鉴权核验
 *
 * @author xiaofuge bugstack.cn @小傅�?
 * 2025/12/13 09:22
 */
@Slf4j
@Service("mcpSessionVerifyNode")
public class VerifyNode extends AbstractMcpSessionSupport {

    @Resource(name = "mcpSessionSessionNode")
    private SessionNode sessionNode;

    @Resource
    private IAuthLicenseService authLicenseService;

    @Override
    protected Flux<ServerSentEvent<String>> doApply(String requestParameter, DefaultMcpSessionFactory.DynamicContext dynamicContext) throws Exception {
        log.info("创建会话-VerifyNode:{}", requestParameter);

        boolean isCheckSuccess
                = authLicenseService.checkLicense(new LicenseCommandEntity(requestParameter, dynamicContext.getApiKey()));

        if (!isCheckSuccess) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "fail to auth apikey");
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<String, DefaultMcpSessionFactory.DynamicContext, Flux<ServerSentEvent<String>>> get(String requestParameter, DefaultMcpSessionFactory.DynamicContext dynamicContext) throws Exception {
        return sessionNode;
    }

}
