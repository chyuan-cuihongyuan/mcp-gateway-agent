package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 提示版本表 PO（工单 0196 AA1）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPromptVersionPO implements Serializable {

    private Long id;

    private String promptName;

    private Integer version;

    private String template;

    private String status;

    private String note;

    private String operator;

    /** 标签（工单 0197 AA2；production/staging/latest，NULL=未打标） */
    private String label;

    private Date createTime;

    private Date updateTime;
}
