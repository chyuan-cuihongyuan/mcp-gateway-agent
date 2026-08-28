package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.factory;

import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client.impl.StdioToolMcpCreateService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Slf4j
@Service
public class DefaultMcpClientFactory {

    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    /** SSE 客户端挂接已随工单 0021 下线（0016 遗留的 deprecated 过渡清偿） */
    public ToolMcpCreateService getToolMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(),
                "挂接类型缺失或不受支持（仅 stdio/local，SSE 已下线）");
    }

}
