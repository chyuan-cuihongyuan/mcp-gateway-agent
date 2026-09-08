package cn.chyuan.ai.domain.promptresource.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 网关本地 Resource 值对象（工单 0053）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceVO {

    private Long id;

    private String gatewayId;

    /** 资源 URI（resources/read 引用） */
    private String uri;

    private String name;

    private String description;

    private String mimeType;

    /** 资源内容（文本态；二进制留后续 Base64 扩展） */
    private String content;

    /** 1-启用，0-禁用 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
