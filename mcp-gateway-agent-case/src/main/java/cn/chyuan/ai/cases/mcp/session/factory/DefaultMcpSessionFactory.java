package cn.chyuan.ai.cases.mcp.session.factory;

import cn.chyuan.ai.cases.mcp.session.node.RootNode;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;

/**
 * MCP 会话服务工厂
 *
 * @author chyuan @chyuan
 * 2025/12/13 09:09
 */
@Service
public class DefaultMcpSessionFactory {

    @Resource(name = "mcpSessionRootNode")
    private RootNode rootNode;

    public StrategyHandler<String, DefaultMcpSessionFactory.DynamicContext, Flux<ServerSentEvent<String>>> strategyHandler() {
        return rootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        private String apiKey;

        /** 治理面认证主体（统一认证过滤器产出，工单 0017） */
        private cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal principal;

        private SessionConfigVO sessionConfigVO;
    }

}
