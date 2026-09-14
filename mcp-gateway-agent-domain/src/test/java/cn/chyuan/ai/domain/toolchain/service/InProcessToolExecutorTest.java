package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.adapter.port.ToolExecutionPort;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内沙箱执行器单测（工单 0334 AP4）：四路径/结果结构/边界。
 */
class InProcessToolExecutorTest {

    @Test
    void 正常执行与结果结构() {
        InProcessToolExecutor executor = new InProcessToolExecutor(5, 100)
                .register("add", (params, tenant) -> Map.of("sum", 3, "tenant", tenant));
        ToolExecutionPort.ToolExecutionResult result =
                executor.execute("add", Map.of("a", 1, "b", 2), "t1");
        assertEquals("SUCCESS", result.status());
        assertEquals(3, result.output().get("sum"));
        assertEquals("t1", result.output().get("tenant"));
        assertTrue(result.costMs() >= 0, "应记录耗时");
    }

    @Test
    void 未注册与异常与截断路径() {
        InProcessToolExecutor executor = new InProcessToolExecutor(5, 100)
                .register("boom", (params, tenant) -> {
                    throw new IllegalStateException("工具内部错误");
                })
                .register("timeout", (params, tenant) -> {
                    throw new InProcessToolExecutor.ToolTimeoutException("超时中断");
                });
        assertEquals("ERROR", executor.execute("ghost", Map.of(), "t").status());
        ToolExecutionPort.ToolExecutionResult boom = executor.execute("boom", Map.of(), "t");
        assertEquals("ERROR", boom.status());
        assertTrue(boom.errorMessage().contains("工具内部错误"));
        assertEquals("TIMEOUT", executor.execute("timeout", Map.of(), "t").status());
        // null 输出按空处理成功
        InProcessToolExecutor nullOut = new InProcessToolExecutor(5, 100)
                .register("nullish", (params, tenant) -> null);
        assertEquals("SUCCESS", nullOut.execute("nullish", Map.of(), "t").status());
    }

    @Test
    void 截断路径与非法配置() {
        InProcessToolExecutor executor = new InProcessToolExecutor(2, 100)
                .register("wide", (params, tenant) -> Map.of("a", 1, "b", 2, "c", 3));
        ToolExecutionPort.ToolExecutionResult truncated = executor.execute("wide", Map.of(), "t");
        assertEquals("TRUNCATED", truncated.status());
        assertTrue(truncated.errorMessage().contains("超上限"));
        assertThrows(IllegalArgumentException.class, () -> new InProcessToolExecutor(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new InProcessToolExecutor(5, 0));
    }
}
