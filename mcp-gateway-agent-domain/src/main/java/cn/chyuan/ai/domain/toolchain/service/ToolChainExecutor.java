package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.adapter.port.ToolExecutionPort;
import cn.chyuan.ai.domain.toolchain.model.valobj.ChainRunResultVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ChainStepVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具链执行器（工单 0333 AP3，langchain Chain 思想）。
 * 步骤线性链（前序输出可被后续参数以 ${stepId.field} 引用）按序执行
 * （经 ToolExecutionPort），参数模板引用缺失/前序步骤不存在即失败即停，
 * 步骤留痕（输入摘要/输出字段/失败快照）。domain 纯函数编排。
 */
public class ToolChainExecutor {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_$-]+)\\.([A-Za-z0-9_-]+)}");

    private final ToolExecutionPort executionPort;

    public ToolChainExecutor(ToolExecutionPort executionPort) {
        if (executionPort == null) {
            throw new IllegalArgumentException("执行端口不能为空");
        }
        this.executionPort = executionPort;
    }

    public ChainRunResultVO execute(List<ChainStepVO> steps, Map<String, Object> initialInput, String tenantId) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("工具链步骤不能为空");
        }
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        List<String> trace = new ArrayList<>();
        if (initialInput != null && !initialInput.isEmpty()) {
            outputs.put("$input", initialInput);
        }
        int executed = 0;
        for (ChainStepVO step : steps) {
            Map<String, Object> params = new LinkedHashMap<>();
            boolean missing = false;
            String missingRef = null;
            for (Map.Entry<String, String> entry : step.getParamTemplate().entrySet()) {
                String resolved = resolve(entry.getValue(), outputs);
                if (resolved == null) {
                    missing = true;
                    missingRef = entry.getValue();
                    break;
                }
                params.put(entry.getKey(), resolved);
            }
            executed++;
            if (missing) {
                trace.add(step.getStepId() + ":引用缺失 " + missingRef);
                return ChainRunResultVO.builder()
                        .success(false)
                        .executedSteps(executed)
                        .stepOutputs(outputs)
                        .trace(trace)
                        .failedStep(step.getStepId())
                        .error("参数引用缺失: " + missingRef)
                        .build();
            }
            ToolExecutionPort.ToolExecutionResult result =
                    executionPort.execute(step.getTool(), params, tenantId);
            trace.add(step.getStepId() + ":" + result.status() + ":耗时" + result.costMs() + "ms");
            if (!"SUCCESS".equals(result.status())) {
                return ChainRunResultVO.builder()
                        .success(false)
                        .executedSteps(executed)
                        .stepOutputs(outputs)
                        .trace(trace)
                        .failedStep(step.getStepId())
                        .error("工具执行失败(" + result.status() + "): " + result.errorMessage())
                        .build();
            }
            outputs.put(step.getStepId(), result.output());
        }
        return ChainRunResultVO.builder()
                .success(true)
                .executedSteps(executed)
                .stepOutputs(outputs)
                .trace(trace)
                .build();
    }

    /** 解析模板值：整值引用返回字段对象，内嵌引用做字符串替换；引用缺失返回 null */
    static String resolve(String template, Map<String, Map<String, Object>> outputs) {
        if (template == null) {
            return null;
        }
        Matcher matcher = REFERENCE.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String stepId = matcher.group(1);
            String field = matcher.group(2);
            Map<String, Object> stepOutput = outputs.get(stepId);
            if (stepOutput == null || !stepOutput.containsKey(field)) {
                return null;
            }
            out.append(template, last, matcher.start()).append(stepOutput.get(field));
            last = matcher.end();
        }
        out.append(template.substring(last));
        return out.toString();
    }
}
