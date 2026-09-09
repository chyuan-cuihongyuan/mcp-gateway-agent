package cn.chyuan.ai.infrastructure.dao.po;

import cn.chyuan.ai.infrastructure.dao.po.base.BasePagePO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 治理面审计日志表（工单 0017）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpAuditLogPO extends BasePagePO {

    private Long id;

    /** admin 用户名或 system */
    private String actor;

    /** 分型（工单 0111）：ADMIN/SECURITY/SYSTEM/TEST；存量回填 ADMIN */
    private String type;

    /** CREATE_KEY / UPDATE_KEY / REVOKE_KEY / GRANT / REVOKE_GRANT / MIGRATE / LOGIN 等 */
    private String action;

    /** VIRTUAL_KEY / GRANT / QUOTA / ADMIN_USER */
    private String resourceType;

    private String resourceId;

    /** 变更前快照（脱敏） */
    private String beforeJson;

    /** 变更后快照（脱敏） */
    private String afterJson;

    private Date createdAt;
}
