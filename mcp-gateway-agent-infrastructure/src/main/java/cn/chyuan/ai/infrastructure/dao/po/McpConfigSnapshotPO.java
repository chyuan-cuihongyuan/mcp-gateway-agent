package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 配置快照表 PO（工单 0251 AG1）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConfigSnapshotPO implements Serializable {

    private Long id;

    private String namespace;

    private String configKey;

    private Integer version;

    /** 内容（敏感项为 enc-v1:* 密文） */
    private String content;

    private String contentMd5;

    private Boolean sensitive;

    private String publisher;

    private String note;

    private String status;

    private Date createTime;

    private Date updateTime;
}
