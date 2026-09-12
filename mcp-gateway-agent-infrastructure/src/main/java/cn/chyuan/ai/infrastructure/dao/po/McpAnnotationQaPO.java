package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 标注回复表 PO（工单 0202 AA7）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpAnnotationQaPO implements Serializable {

    private Long id;

    private String questionKey;

    private String question;

    private String answer;

    private Long hitCount;

    private Integer enabled;

    private String operator;

    private Date createTime;

    private Date updateTime;
}
