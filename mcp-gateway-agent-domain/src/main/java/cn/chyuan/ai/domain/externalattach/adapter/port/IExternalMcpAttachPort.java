package cn.chyuan.ai.domain.externalattach.adapter.port;

import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP 挂接客户端端口（工单 0021）
 *
 * <p>infrastructure 以官方 MCP SDK 2.0 客户端实现：streamable HTTP / stdio
 * 传输连接上游，工具清单并入网关目录、tools/call 透传。
 *
 * @author chyuan
 */
public interface IExternalMcpAttachPort {

    /** 网关下全部启用挂接的工具（名称已带 {@code attachName_} 前缀；连接失败的挂接自动跳过） */
    List<McpSchemaVO.Tool> listAttachedTools(String gatewayId);

    /** 工具名是否来自网关的某个启用挂接（按前缀解析且挂接健康） */
    boolean isExternalTool(String gatewayId, String prefixedToolName);

    /** 外部工具调用透传（自动去前缀路由到上游） */
    ExternalCallResult callExternalTool(String gatewayId, String prefixedToolName, Map<String, Object> arguments);

    /** 连接测试（不落库、不建常驻客户端；供 admin 保存前/后探活） */
    ConnectState probeConnect(ExternalAttachVO attach);

    /** 挂接运行期状态（admin 清单展示：连接状态/错误/工具数） */
    List<AttachRuntimeStatus> runtimeStatuses(String gatewayId);

    /** 挂接配置变更/删除后失效客户端与网关配置缓存，并请求网关服务器目录刷新 */
    void evictAttach(Long attachId, String gatewayId);

    /** 网关级失效（网关删除等场景） */
    void evictGateway(String gatewayId);

    /** 透传结果：toolError=true 表示上游以 isError 内容回包 */
    record ExternalCallResult(boolean toolError, String payload) {
    }

    /** 探活结果 */
    record ConnectState(boolean connected, int toolCount, String error) {
    }

    /** 挂接运行期状态快照 */
    record AttachRuntimeStatus(Long attachId, String attachName, String connectStatus,
            String connectError, Date connectTime, int toolCount) {
    }
}
