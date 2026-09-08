package cn.chyuan.ai.domain.promptresource.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 网关本地 Prompt 值对象（工单 0053）
 *
 * <p>模板以 {@code {{argName}}} 占位，prompts/get 时以实参渲染。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptVO {

    private Long id;

    private String gatewayId;

    /** Prompt 名（prompts/get 引用） */
    private String name;

    private String description;

    /** 参数声明 JSON 数组（[{"name","description","required"}]） */
    private String argumentsJson;

    /** 模板（{{arg}} 占位） */
    private String template;

    /** 1-启用，0-禁用 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
