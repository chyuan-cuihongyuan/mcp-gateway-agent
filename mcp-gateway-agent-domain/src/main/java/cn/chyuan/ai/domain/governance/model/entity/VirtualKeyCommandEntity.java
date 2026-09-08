package cn.chyuan.ai.domain.governance.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 虚拟密钥创建/更新指令实体（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyCommandEntity {

    /** 更新时指定；创建时为空 */
    private Long id;

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    private Date expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private java.util.List<String> ipAllowList;
}
