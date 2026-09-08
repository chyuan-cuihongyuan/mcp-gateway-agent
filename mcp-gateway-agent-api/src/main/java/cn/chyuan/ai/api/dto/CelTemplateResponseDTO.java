package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * CEL 规则模板响应（工单 0057）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelTemplateResponseDTO implements Serializable {

    private Long id;

    private String code;

    private String name;

    private String expression;

    private String variablesDesc;

    /** 1-内置（不可删改），0-用户自建 */
    private Integer builtin;
}
