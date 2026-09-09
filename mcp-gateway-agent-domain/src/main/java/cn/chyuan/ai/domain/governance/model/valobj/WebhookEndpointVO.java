package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 治理告警 webhook 端点值对象（工单 0051）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEndpointVO {

    private Long id;

    /** 端点名称 */
    private String name;

    /** 接收端 URL（http(s)） */
    private String url;

    /** 订阅事件类型列表（空=全部） */
    private List<String> events;

    /** 载荷格式（工单 0116）：GENERIC/DINGTALK/FEISHU/SLACK；默认 GENERIC */
    private String format;

    /** HMAC-SHA256 签名密钥（可选；配置后请求带 X-Gw-Signature 头） */
    private String secret;

    /** 0-禁用，1-启用 */
    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}
