package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * CEL 规则模板值对象（工单 0057）
 *
 * <p>表达式骨架以 {@code {{paramName}}} 占位；实例化时全部占位符必须被参数替换
 * （未替换完整即拒绝，避免半渲染表达式入库为 fail-closed 隐患）。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelRuleTemplateVO {

    private Long id;

    /** 模板编码（唯一；内置模板 bot- 前缀保留） */
    private String code;

    private String name;

    /** 表达式骨架（含 {{param}} 占位符） */
    private String expression;

    /** 参数说明（每个参数的名/说明/示例，文档化用） */
    private String variablesDesc;

    /** 1-内置（不可删改），0-用户自建 */
    private Integer builtin;

    private Date createTime;

    private Date updateTime;
}
