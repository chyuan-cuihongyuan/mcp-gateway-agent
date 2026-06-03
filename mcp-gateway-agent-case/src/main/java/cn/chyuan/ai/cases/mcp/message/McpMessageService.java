package cn.chyuan.ai.cases.mcp.message;

import cn.chyuan.ai.cases.mcp.IMcpMessageService;
import cn.chyuan.ai.cases.mcp.message.factory.DefaultMcpMessageFactory;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 会话消息处理
 *
 * @author chyuan @chyuan
 * 2026/2/20 07:37
 */
@Slf4j
@Service
public class McpMessageService implements IMcpMessageService {

    @Resource
    private DefaultMcpMessageFactory defaultMcpMessageFactory;

    @Override
    public ResponseEntity<Void> handleMessage(HandleMessageCommandEntity commandEntity) throws Exception {
        StrategyHandler<HandleMessageCommandEntity, DefaultMcpMessageFactory.DynamicContext, ResponseEntity<Void>> strategyHandler
                = defaultMcpMessageFactory.strategyHandler();

        return strategyHandler.apply(commandEntity, new DefaultMcpMessageFactory.DynamicContext());
    }

}
