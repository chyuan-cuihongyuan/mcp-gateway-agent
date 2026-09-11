package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 特性开关表 PO（工单 0178 Y2）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpFeatureFlagPO implements Serializable {
    private Long id;
    private String flagKey;
    private Integer enabled;
    private String note;
    private String operator;
    private String updateTime;
}
