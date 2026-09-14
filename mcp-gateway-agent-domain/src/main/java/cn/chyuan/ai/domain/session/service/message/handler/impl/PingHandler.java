package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.service.message.handler.IRequestHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 保活探活请求（b-49 / 工单 1266，MCP lifecycle spec 的 ping utility）。
 * <p>
 * spec 语义：接收方必须尽快响应 ping，结果为空对象——用于连接健康检查。
 * 此前未注册 ping，客户端保活探活会收到 METHOD_NOT_FOUND，长连接被误判死链。
 */
@Slf4j
@Service("pingHandler")
public class PingHandler implements IRequestHandler {

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {
        return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION, message.id(),
                Map.of(), null);
    }

}
