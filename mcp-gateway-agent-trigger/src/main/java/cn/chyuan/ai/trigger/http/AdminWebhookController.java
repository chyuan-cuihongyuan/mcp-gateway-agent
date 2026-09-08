package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminWebhookService;
import cn.chyuan.ai.api.dto.WebhookEndpointResponseDTO;
import cn.chyuan.ai.api.dto.WebhookEndpointUpsertRequestDTO;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * admin 治理告警 webhook 端点控制台接口（工单 0051：/admin/v1/webhooks）
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/webhooks")
public class AdminWebhookController {

    @Resource
    private IAdminWebhookService adminWebhookService;

    @PostMapping
    public Response<WebhookEndpointResponseDTO> createWebhook(@RequestBody WebhookEndpointUpsertRequestDTO requestDTO) {
        return Response.success(adminWebhookService.createWebhook(requestDTO));
    }

    @GetMapping
    public Response<List<WebhookEndpointResponseDTO>> listWebhooks() {
        return Response.success(adminWebhookService.listWebhooks());
    }

    @GetMapping("/{id}")
    public Response<WebhookEndpointResponseDTO> getWebhook(@PathVariable Long id) {
        return Response.success(adminWebhookService.getWebhook(id));
    }

    @PutMapping("/{id}")
    public Response<WebhookEndpointResponseDTO> updateWebhook(@PathVariable Long id,
            @RequestBody WebhookEndpointUpsertRequestDTO requestDTO) {
        return Response.success(adminWebhookService.updateWebhook(id, requestDTO));
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteWebhook(@PathVariable Long id) {
        adminWebhookService.deleteWebhook(id);
        return Response.success(null);
    }
}
