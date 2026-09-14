package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.adapter.port.ToolExecutionPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 进程内默认工具执行器（工单 0334 AP4）。
 * 超时控制（可配置，超时中断返回 TIMEOUT）/输出截断（上限+TRUNCATED 标记）/
 * 异常隔离（异常→ERROR 结构化结果不外抛）。处理函数经注册表注入
 * （infrastructure 适配真实工具；测试注入假函数）。domain 编排内核。
 */
public class InProcessToolExecutor implements ToolExecutionPort {

    /** 输出字段上限 */
    private final int outputLimit;

    /** 超时毫秒（纯函数环境以处理函数自检耗时近似：函数超时自行抛出由本类拦截；此处记录上限供适配器使用） */
    private final long timeoutMs;

    private final Map<String, BiFunction<Map<String, Object>, String, Map<String, Object>>> handlers = new LinkedHashMap<>();

    public InProcessToolExecutor(int outputLimit, long timeoutMs) {
        if (outputLimit <= 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("输出上限与超时必须为正数");
        }
        this.outputLimit = outputLimit;
        this.timeoutMs = timeoutMs;
    }

    /** 注册工具处理函数（工具名 → (参数,租户) → 输出字段表） */
    public InProcessToolExecutor register(String toolName,
                                          BiFunction<Map<String, Object>, String, Map<String, Object>> handler) {
        handlers.put(toolName, handler);
        return this;
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> params, String tenantId) {
        long start = System.nanoTime();
        BiFunction<Map<String, Object>, String, Map<String, Object>> handler = handlers.get(toolName);
        if (handler == null) {
            return ToolExecutionPort.ToolExecutionResult.failure(
                    "ERROR", "工具未注册: " + toolName, elapsedMs(start));
        }
        Map<String, Object> output;
        try {
            output = handler.apply(params, tenantId);
        } catch (ToolTimeoutException e) {
            return ToolExecutionPort.ToolExecutionResult.failure("TIMEOUT", e.getMessage(), elapsedMs(start));
        } catch (RuntimeException e) {
            return ToolExecutionPort.ToolExecutionResult.failure("ERROR", e.getMessage(), elapsedMs(start));
        }
        if (output == null) {
            output = new LinkedHashMap<>();
        }
        if (output.size() > outputLimit) {
            return ToolExecutionPort.ToolExecutionResult.failure("TRUNCATED", "输出超上限 " + outputLimit,
                    elapsedMs(start));
        }
        return ToolExecutionPort.ToolExecutionResult.success(output, elapsedMs(start));
    }

    /** 超时异常：真实适配器在执行线程超时抛出，本类统一转 TIMEOUT 结果 */
    public static class ToolTimeoutException extends RuntimeException {
        public ToolTimeoutException(String message) {
            super(message);
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
