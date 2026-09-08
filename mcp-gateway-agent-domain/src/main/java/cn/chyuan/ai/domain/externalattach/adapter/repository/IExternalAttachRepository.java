package cn.chyuan.ai.domain.externalattach.adapter.repository;

import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;

import java.util.List;

/**
 * 外部 MCP 挂接配置仓储接口（工单 0021）
 *
 * @author chyuan
 */
public interface IExternalAttachRepository {

    /** 新增（gateway_id + attach_name 唯一，冲突抛 IllegalStateException） */
    Long insert(ExternalAttachVO attach);

    /** 按 id 更新配置字段（不含运行期连接状态） */
    boolean update(ExternalAttachVO attach);

    boolean deleteById(Long id);

    ExternalAttachVO findById(Long id);

    /** 网关下全部挂接（含禁用；启用过滤由调用方做） */
    List<ExternalAttachVO> findByGatewayId(String gatewayId);

    /** 回写运行期连接状态（连接失败可观测） */
    boolean updateConnectStatus(Long id, String connectStatus, String connectError);

    /** 全部挂接（巡检遍历用，工单 0058） */
    List<ExternalAttachVO> findAllAttaches();

    /** 回写巡检健康（test_time/response_time_ms，工单 0058） */
    void updateChannelHealth(Long id, long responseTimeMs);

    /**
     * 渠道状态迁移（工单 0058/0059）：置状态（2=自动禁用附带冷却；恢复=1）并清空被动熔断三计数。
     */
    void updateChannelStatus(Long id, int status, java.util.Date cooldownUntil);

    /**
     * 被动熔断计数递增（工单 0059）。
     *
     * @param column fail_connect / fail_timeout / fail_http（实现侧白名单校验）
     */
    void incrementChannelFailure(Long id, String column);
}
