package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 治理告警 webhook 端点响应（工单 0051；secret 恒脱敏）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEndpointResponseDTO implements Serializable {

    private Long id;

    private String name;

    private String url;

    private List<String> events;

    /** 已配置返回 "****"，未配置返回 null */
    private String secretMasked;

    private Integer enabled;

    private String createTime;

    private String updateTime;
}
