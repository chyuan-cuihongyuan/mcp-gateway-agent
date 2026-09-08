package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;

import java.util.List;

/**
 * 治理告警 webhook 端点仓储端口（工单 0051）
 *
 * @author chyuan
 */
public interface IWebhookEndpointRepository {

    Long insert(WebhookEndpointVO endpoint);

    boolean update(WebhookEndpointVO endpoint);

    boolean deleteById(Long id);

    WebhookEndpointVO findById(Long id);

    List<WebhookEndpointVO> findAll();

    /** 启用态端点（投递路径用） */
    List<WebhookEndpointVO> findEnabled();
}
