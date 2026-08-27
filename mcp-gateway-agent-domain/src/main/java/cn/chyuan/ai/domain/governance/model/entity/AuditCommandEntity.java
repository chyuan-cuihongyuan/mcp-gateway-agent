package cn.chyuan.ai.domain.governance.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理面审计指令实体（工单 0017 / 0011 决策：变更留痕）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCommandEntity {

    /** 操作者（admin 用户名或 system） */
    private String actor;

    /** 动作：CREATE_KEY / UPDATE_KEY / REVOKE_KEY / GRANT / REVOKE_GRANT / MIGRATE / LOGIN 等 */
    private String action;

    /** 资源类型：VIRTUAL_KEY / GRANT / QUOTA 等 */
    private String resourceType;

    private String resourceId;

    /** 变更前快照（脱敏 JSON，可空） */
    private String beforeJson;

    /** 变更后快照（脱敏 JSON，可空） */
    private String afterJson;
}
