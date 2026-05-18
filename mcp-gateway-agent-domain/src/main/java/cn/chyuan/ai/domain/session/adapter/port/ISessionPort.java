package cn.chyuan.ai.domain.session.adapter.port;

import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;

import java.io.IOException;

/**
 * 会话端口
 *
 * @author chyuan
 *         2026/1/30 20:55
 */
public interface ISessionPort {

    Object toolCall(McpToolProtocolConfigVO.HTTPConfig httpConfig, Object params) throws IOException;

}
