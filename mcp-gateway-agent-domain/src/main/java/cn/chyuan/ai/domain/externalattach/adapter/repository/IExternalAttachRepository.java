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
}
