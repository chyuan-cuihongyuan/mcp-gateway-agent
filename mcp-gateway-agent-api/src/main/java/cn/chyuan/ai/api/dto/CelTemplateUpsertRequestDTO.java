package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * CEL 规则模板创建请求（工单 0057；仅用户自建，builtin- 前缀保留）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelTemplateUpsertRequestDTO implements Serializable {

    private String code;

    private String name;

    private String expression;

    private String variablesDesc;
}
