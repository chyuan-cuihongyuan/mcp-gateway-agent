package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 治理面审计日志响应（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponseDTO implements Serializable {

    private Long id;

    private String actor;

    private String action;

    private String resourceType;

    private String resourceId;

    private String beforeJson;

    private String afterJson;

    private String createdAt;
}
