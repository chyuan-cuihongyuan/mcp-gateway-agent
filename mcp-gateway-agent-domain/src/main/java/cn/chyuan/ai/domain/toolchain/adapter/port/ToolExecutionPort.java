package cn.chyuan.ai.domain.toolchain.adapter.port;

import java.util.Map;

/**
 * 工具沙箱执行端口（工单 0334 AP4，openai-python function calling 执行语义）。
 * 六边形端口：infrastructure 提供真实执行器，进程内默认实现用于本机/测试。
 */
public interface ToolExecutionPort {

    /**
     * 执行工具。
     *
     * @param toolName 工具名
     * @param params   参数（调用方已过 Schema 校验）
     * @param tenantId 租户（配额/审计维度）
     * @return 统一结果（status: SUCCESS/TIMEOUT/ERROR/TRUNCATED）
     */
    ToolExecutionResult execute(String toolName, Map<String, Object> params, String tenantId);

    /** 执行结果：状态/输出字段表/耗时毫秒/错误消息 */
    record ToolExecutionResult(String status, Map<String, Object> output,
                               long costMs, String errorMessage) {

        public static ToolExecutionResult success(Map<String, Object> output, long costMs) {
            return new ToolExecutionResult("SUCCESS", output, costMs, null);
        }

        public static ToolExecutionResult failure(String status, String error, long costMs) {
            return new ToolExecutionResult(status, Map.of(), costMs, error);
        }
    }
}
