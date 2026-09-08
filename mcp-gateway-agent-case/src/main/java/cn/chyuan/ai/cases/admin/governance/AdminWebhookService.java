package cn.chyuan.ai.cases.admin.governance;

import cn.chyuan.ai.api.IAdminWebhookService;
import cn.chyuan.ai.api.dto.WebhookEndpointResponseDTO;
import cn.chyuan.ai.api.dto.WebhookEndpointUpsertRequestDTO;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import cn.chyuan.ai.domain.governance.service.WebhookAdminService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * admin 治理告警 webhook 编排（工单 0051）
 *
 * @author chyuan
 */
@Service
public class AdminWebhookService implements IAdminWebhookService {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private WebhookAdminService webhookAdminService;

    @Override
    public WebhookEndpointResponseDTO createWebhook(WebhookEndpointUpsertRequestDTO requestDTO) {
        return toDto(webhookAdminService.create(toVo(null, requestDTO)));
    }

    @Override
    public WebhookEndpointResponseDTO updateWebhook(Long id, WebhookEndpointUpsertRequestDTO requestDTO) {
        WebhookEndpointVO existing = webhookAdminService.get(id);
        WebhookEndpointVO vo = toVo(id, requestDTO);
        if (vo.getSecret() == null) {
            vo.setSecret(existing.getSecret());
        }
        return toDto(webhookAdminService.update(id, vo));
    }

    @Override
    public void deleteWebhook(Long id) {
        webhookAdminService.delete(id);
    }

    @Override
    public WebhookEndpointResponseDTO getWebhook(Long id) {
        return toDto(webhookAdminService.get(id));
    }

    @Override
    public List<WebhookEndpointResponseDTO> listWebhooks() {
        return webhookAdminService.list().stream().map(this::toDto).toList();
    }

    private WebhookEndpointVO toVo(Long id, WebhookEndpointUpsertRequestDTO dto) {
        return WebhookEndpointVO.builder()
                .id(id)
                .name(dto.getName())
                .url(dto.getUrl())
                .events(dto.getEvents())
                .secret(dto.getSecret())
                .enabled(dto.getEnabled())
                .build();
    }

    private WebhookEndpointResponseDTO toDto(WebhookEndpointVO vo) {
        return WebhookEndpointResponseDTO.builder()
                .id(vo.getId())
                .name(vo.getName())
                .url(vo.getUrl())
                .events(vo.getEvents())
                .secretMasked(vo.getSecret() == null ? null : "****")
                .enabled(vo.getEnabled())
                .createTime(format(vo.getCreateTime()))
                .updateTime(format(vo.getUpdateTime()))
                .build();
    }

    private String format(Date value) {
        return value == null ? null : new SimpleDateFormat(DATE_PATTERN).format(value);
    }
}
