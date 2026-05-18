package cn.chyuan.ai.domain.session.service.message.handler;

import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;

/**
 * 处理请求接口
 *
 * @author chyuan
 *         2025/12/20 09:09
 */
public interface IRequestHandler {

    McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message);

}
