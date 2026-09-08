package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 治理告警 webhook 端点创建/更新请求（工单 0051）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEndpointUpsertRequestDTO implements Serializable {

    private String name;

    private String url;

    /** 订阅事件类型列表（空=全部；可选值见 WebhookAdminService 事件口径注释） */
    private List<String> events;

    /** HMAC-SHA256 签名密钥（可选；更新时空值=保留原值） */
    private String secret;

    /** 0-禁用，1-启用（默认 1） */
    private Integer enabled;
}
