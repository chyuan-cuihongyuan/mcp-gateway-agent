package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 治理护栏 VO（工单 0091）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailVO {

    public static final String TYPE_PII_MASK = "PII_MASK";
    public static final String TYPE_KEYWORD_BLOCK = "KEYWORD_BLOCK";
    public static final String TYPE_REGEX_BLOCK = "REGEX_BLOCK";
    public static final String TYPE_RESPONSE_FILTER = "RESPONSE_FILTER";
    public static final String TYPE_RESPONSE_MASK = "RESPONSE_MASK";

    public static final String MODE_PRE_CALL = "PRE_CALL";
    public static final String MODE_POST_CALL = "POST_CALL";
    public static final String MODE_LOGGING_ONLY = "LOGGING_ONLY";

    public static final String TRAFFIC_MCP = "MCP";
    public static final String TRAFFIC_LLM = "LLM";
    public static final String TRAFFIC_ALL = "ALL";

    private Long id;

    private String name;

    private String type;

    private String mode;

    private String config;

    private String trafficMask;

    private Integer priority;

    private Integer enabled;

    private Date createTime;

    private Date updateTime;

    /** 该护栏是否作用于指定流量面 */
    public boolean appliesToTraffic(String traffic) {
        return trafficMask == null || TRAFFIC_ALL.equals(trafficMask) || trafficMask.equals(traffic);
    }
}
