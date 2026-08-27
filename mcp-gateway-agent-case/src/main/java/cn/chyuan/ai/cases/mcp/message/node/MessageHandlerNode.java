package cn.chyuan.ai.cases.mcp.message.node;

import cn.chyuan.ai.cases.mcp.message.AbstractMcpMessageServiceSupport;
import cn.chyuan.ai.cases.mcp.message.factory.DefaultMcpMessageFactory;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

/**
 * 消息节点
 *
 * @author chyuan
 *         2026/2/20 08:07
 */
@Slf4j
@Service("mcpMessageMessageHandlerNode")
public class MessageHandlerNode extends AbstractMcpMessageServiceSupport {

    @Resource
    private ObjectMapper objectMapper;

    @Override
    protected ResponseEntity<Void> doApply(HandleMessageCommandEntity requestParameter,
            DefaultMcpMessageFactory.DynamicContext dynamicContext) throws Exception {
        log.info("消息处理 mcp message MessageHandlerNode:{}", requestParameter);

        McpSchemaVO.JSONRPCResponse jsonrpcResponse = serviceMessageService
                .processHandlerMessage(requestParameter.getGatewayId(), requestParameter.getJsonrpcMessage(),
                        requestParameter.getPrincipal());

        if (null != jsonrpcResponse) {
            String responseJson = objectMapper.writeValueAsString(jsonrpcResponse);

            SessionConfigVO sessionConfigVO = dynamicContext.getSessionConfigVO();
            sessionConfigVO.getSink().tryEmitNext(ServerSentEvent.<String>builder()
                    .event("message")
                    .data(responseJson)
                    .build());
        }

        return ResponseEntity.accepted().build();
    }

    @Override
    public StrategyHandler<HandleMessageCommandEntity, DefaultMcpMessageFactory.DynamicContext, ResponseEntity<Void>> get(
            HandleMessageCommandEntity requestParameter, DefaultMcpMessageFactory.DynamicContext dynamicContext)
            throws Exception {
        return defaultStrategyHandler;
    }

}
