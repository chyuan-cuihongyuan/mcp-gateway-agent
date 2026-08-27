package cn.chyuan.ai.cases.mcp;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 *
 * @author chyuan
 *         2025/12/13 09:07
 */
public interface IMcpSessionService {

    /**
     * 创建 MCP 会话服务
     *
     * @param gatewayId 网关ID
     * @param apiKey    凭证字符串（会话元数据关联；统一认证已由过滤器完成）
     * @param principal 治理面认证主体（过滤器产出；null 时节点链走遗留校验兜底）
     * @return 流式响应
     */
    Flux<ServerSentEvent<String>> createMcpSession(String gatewayId, String apiKey, GovernancePrincipal principal) throws Exception;

}
