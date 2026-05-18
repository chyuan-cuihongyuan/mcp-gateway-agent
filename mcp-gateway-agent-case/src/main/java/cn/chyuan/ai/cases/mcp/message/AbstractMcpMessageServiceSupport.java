package cn.chyuan.ai.cases.mcp.message;

import cn.chyuan.ai.cases.mcp.message.factory.DefaultMcpMessageFactory;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.service.ISessionManagementService;
import cn.chyuan.ai.domain.session.service.ISessionMessageService;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import org.springframework.http.ResponseEntity;

import jakarta.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractMcpMessageServiceSupport extends AbstractMultiThreadStrategyRouter<HandleMessageCommandEntity, DefaultMcpMessageFactory.DynamicContext, ResponseEntity<Void>> {

    @Resource
    protected ISessionMessageService serviceMessageService;

    @Resource
    protected ISessionManagementService sessionManagementService;

    @Override
    protected void multiThread(HandleMessageCommandEntity requestParameter, DefaultMcpMessageFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

}
