package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.WebhookEndpointResponseDTO;
import cn.chyuan.ai.api.dto.WebhookEndpointUpsertRequestDTO;

import java.util.List;

/**
 * admin 治理告警 webhook 端点服务接口（工单 0051）
 *
 * @author chyuan
 */
public interface IAdminWebhookService {

    WebhookEndpointResponseDTO createWebhook(WebhookEndpointUpsertRequestDTO requestDTO);

    WebhookEndpointResponseDTO updateWebhook(Long id, WebhookEndpointUpsertRequestDTO requestDTO);

    void deleteWebhook(Long id);

    WebhookEndpointResponseDTO getWebhook(Long id);

    List<WebhookEndpointResponseDTO> listWebhooks();
}
