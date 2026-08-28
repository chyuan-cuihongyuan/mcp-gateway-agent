package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpExternalAttachPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 外部 MCP 挂接配置 DAO（工单 0021）
 */
@Mapper
public interface IExternalAttachDao {

    /** 新增（gateway_id + attach_name 唯一键冲突抛 DuplicateKeyException） */
    int insert(McpExternalAttachPO attach);

    /** 按 id 更新配置字段（不含连接状态） */
    int update(McpExternalAttachPO attach);

    int deleteById(@Param("id") Long id);

    McpExternalAttachPO queryById(@Param("id") Long id);

    List<McpExternalAttachPO> queryByGatewayId(@Param("gatewayId") String gatewayId);

    /** 回写运行期连接状态 */
    int updateConnectStatus(@Param("id") Long id, @Param("connectStatus") String connectStatus,
            @Param("connectError") String connectError);
}
