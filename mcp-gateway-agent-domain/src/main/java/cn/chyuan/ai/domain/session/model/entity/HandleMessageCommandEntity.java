package cn.chyuan.ai.domain.session.model.entity;

import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 处理消息命令实体对象
 *
 * @author chyuan
 *         2026/2/20 07:53
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HandleMessageCommandEntity {

    private String gatewayId;

    /** 调用凭证明文，禁止随 toString 进入日志（工单 0017 脱敏要求） */
    @ToString.Exclude
    private String apiKey;

    private String sessionId;

    private McpSchemaVO.JSONRPCMessage jsonrpcMessage;

    public HandleMessageCommandEntity(String gatewayId, String sessionId, String messageBody) throws Exception {
        this.gatewayId = gatewayId;
        this.sessionId = sessionId;
        this.jsonrpcMessage = McpSchemaVO.deserializeJsonRpcMessage(messageBody);
    }

    public HandleMessageCommandEntity(String gatewayId, String apiKey, String sessionId, String messageBody)
            throws Exception {
        this.gatewayId = gatewayId;
        this.apiKey = apiKey;
        this.sessionId = sessionId;
        this.jsonrpcMessage = McpSchemaVO.deserializeJsonRpcMessage(messageBody);
    }

}
