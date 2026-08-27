package cn.chyuan.ai.domain.session.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;

/**
 * 会话消息服务接口
 *
 * @author chyuan
 *         2025/12/20 08:49
 */
public interface ISessionMessageService {

    McpSchemaVO.JSONRPCResponse processHandlerMessage(String gatewayId, McpSchemaVO.JSONRPCMessage message);

    McpSchemaVO.JSONRPCResponse processHandlerMessage(HandleMessageCommandEntity commandEntity);

    /** 带认证主体的分发（工单 0018：CEL 治理消费认证上下文） */
    McpSchemaVO.JSONRPCResponse processHandlerMessage(String gatewayId, McpSchemaVO.JSONRPCMessage message,
            GovernancePrincipal principal);

}
