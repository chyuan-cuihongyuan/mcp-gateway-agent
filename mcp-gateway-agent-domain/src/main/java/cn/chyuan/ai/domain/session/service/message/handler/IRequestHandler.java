package cn.chyuan.ai.domain.session.service.message.handler;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;

/**
 * 处理请求接口
 *
 * @author chyuan
 *         2025/12/20 09:09
 */
public interface IRequestHandler {

    McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message);

    /**
     * 带认证主体的处理入口（工单 0018）：CEL 治理等需要认证上下文的处理器覆写本方法，
     * 默认实现忽略主体（等价旧行为）。
     */
    default McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message,
            GovernancePrincipal principal) {
        return handle(gatewayId, message);
    }

}
