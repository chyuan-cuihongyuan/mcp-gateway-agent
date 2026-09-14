package cn.chyuan.ai.domain.toolchain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工具链执行结果值对象（AP3：步骤留痕 + 失败即停快照）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChainRunResultVO {

    /** 是否全部成功 */
    private boolean success;

    /** 执行到的步骤数 */
    private int executedSteps;

    /** 步骤输出（stepId → 输出字段表，按完成序） */
    private Map<String, Map<String, Object>> stepOutputs;

    /** 步骤留痕（stepId → 摘要） */
    private List<String> trace;

    /** 失败步骤ID与原因（成功为 null） */
    private String failedStep;

    private String error;
}
