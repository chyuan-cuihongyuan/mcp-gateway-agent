package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;

import java.util.List;

/**
 * MCP 工具目录服务（工单 0020）
 *
 * <p>streamable HTTP 端点的工具清单来源：按网关查询工具配置并做 CEL 可见性过滤，
 * 供委派路由的 tools/list 应答与官方服务器工具规格构建复用。
 *
 * @author chyuan
 */
public interface IMcpToolCatalogService {

    /** CEL 放行的可见工具清单（tools/list 生效点；principal 为空时不过滤，与遗留口径一致） */
    List<McpSchemaVO.Tool> visibleTools(String gatewayId, GovernancePrincipal principal, String method);

    /** 网关下是否配置了指定工具（未知工具在委派层直答 -32003 的依据） */
    boolean toolExists(String gatewayId, String toolName);

}
