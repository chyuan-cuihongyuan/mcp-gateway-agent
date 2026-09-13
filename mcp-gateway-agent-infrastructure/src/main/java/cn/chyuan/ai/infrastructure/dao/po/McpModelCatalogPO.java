package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 模型目录表 PO（工单 0281 AJ5）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpModelCatalogPO implements Serializable {

    private Long id;

    private String model;

    private Integer contextLimit;

    /** 模态集合（逗号拼接：text/vision/audio/embedding） */
    private String modalities;

    /** 归属计价条目 id（可空） */
    private Long pricingEntryId;

    private String status;

    private String note;

    private String operator;

    private Date createTime;

    private Date updateTime;
}
