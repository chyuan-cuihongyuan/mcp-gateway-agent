package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具链步骤值对象（AP3：工具引用 + 参数模板，值支持 ${step.field} 引用前序输出）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChainStepVO {

    /** 步骤ID */
    private String stepId;

    /** 工具名 */
    private String tool;

    /** 参数模板（参数名 → 模板值，可含 ${stepId.field} 引用） */
    private Map<String, String> paramTemplate;
}
