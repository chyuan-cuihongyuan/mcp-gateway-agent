package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpWebhookEndpointPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 治理告警 webhook 端点 DAO（工单 0051）
 */
@Mapper
public interface IWebhookEndpointDao {

    int insert(McpWebhookEndpointPO po);

    int update(McpWebhookEndpointPO po);

    int deleteById(@Param("id") Long id);

    McpWebhookEndpointPO queryById(@Param("id") Long id);

    List<McpWebhookEndpointPO> queryAll();

    List<McpWebhookEndpointPO> queryEnabled();
}
