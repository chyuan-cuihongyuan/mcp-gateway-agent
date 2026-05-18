package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 MCP 构建服务
 *
 * @author chyuan
 *         2026/1/2 09:31
 */
public interface ToolMcpCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
