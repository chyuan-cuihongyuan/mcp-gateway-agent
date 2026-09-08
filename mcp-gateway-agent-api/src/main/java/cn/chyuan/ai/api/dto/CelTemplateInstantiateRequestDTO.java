package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * CEL 规则模板实例化请求（工单 0057）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelTemplateInstantiateRequestDTO implements Serializable {

    /** 模板编码 */
    private String code;

    /** 规则名（空=模板名） */
    private String ruleName;

    /** GLOBAL / GATEWAY / VIRTUAL_KEY（默认 GLOBAL） */
    private String scopeType;

    private String gatewayId;

    private Long virtualKeyId;

    /** 占位符参数（名→替换值） */
    private Map<String, String> params;
}
