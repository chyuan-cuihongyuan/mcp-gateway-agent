package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.ChainRunResultVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ChainStepVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具链执行器单测（工单 0333 AP3）：拓扑顺序/引用解析/失败即停/边界。
 */
class ToolChainExecutorTest {

    @Test
    void 线性链执行与引用解析() {
        InProcessToolExecutor executor = new InProcessToolExecutor(10, 1000)
                .register("fetch", (params, tenant) -> Map.of("userId", params.get("id")))
                .register("profile", (params, tenant) -> Map.of("greeting", "你好 " + params.get("userId")));
        ToolChainExecutor chain = new ToolChainExecutor(executor);
        ChainRunResultVO result = chain.execute(List.of(
                ChainStepVO.builder().stepId("s1").tool("fetch")
                        .paramTemplate(Map.of("id", "42")).build(),
                ChainStepVO.builder().stepId("s2").tool("profile")
                        .paramTemplate(Map.of("userId", "${s1.userId}")).build()),
                Map.of(), "tenant-1");
        assertTrue(result.isSuccess());
        assertEquals(2, result.getExecutedSteps());
        assertEquals("你好 42", result.getStepOutputs().get("s2").get("greeting"));
        assertEquals(2, result.getTrace().size());
        assertTrue(result.getTrace().get(0).startsWith("s1:SUCCESS"));
        // 初始输入可被 ${$input.x} 引用
        ChainRunResultVO withInput = chain.execute(List.of(
                ChainStepVO.builder().stepId("s1").tool("fetch")
                        .paramTemplate(Map.of("id", "${$input.uid}")).build()),
                Map.of("uid", "7"), "t");
        assertTrue(withInput.isSuccess());
        assertEquals("7", withInput.getStepOutputs().get("s1").get("userId"));
    }

    @Test
    void 引用缺失失败即停带快照() {
        InProcessToolExecutor executor = new InProcessToolExecutor(10, 1000)
                .register("echo", (params, tenant) -> Map.of("value", params.getOrDefault("v", "")));
        ToolChainExecutor chain = new ToolChainExecutor(executor);
        ChainRunResultVO result = chain.execute(List.of(
                ChainStepVO.builder().stepId("s1").tool("echo")
                        .paramTemplate(Map.of("v", "${ghost.field}")).build()), Map.of(), "t");
        assertFalse(result.isSuccess());
        assertEquals("s1", result.getFailedStep());
        assertTrue(result.getError().contains("引用缺失"));
        assertEquals(1, result.getExecutedSteps());
        // 工具失败 → 快照保留已完成步骤
        InProcessToolExecutor broken = new InProcessToolExecutor(10, 1000)
                .register("ok", (params, tenant) -> Map.of("v", "1"))
                .register("boom", (params, tenant) -> {
                    throw new IllegalStateException("炸");
                });
        ChainRunResultVO failed = new ToolChainExecutor(broken).execute(List.of(
                ChainStepVO.builder().stepId("s1").tool("ok").paramTemplate(Map.of()).build(),
                ChainStepVO.builder().stepId("s2").tool("boom").paramTemplate(Map.of()).build(),
                ChainStepVO.builder().stepId("s3").tool("ok").paramTemplate(Map.of()).build()),
                Map.of(), "t");
        assertFalse(failed.isSuccess());
        assertEquals("s2", failed.getFailedStep());
        assertTrue(failed.getStepOutputs().containsKey("s1"), "已完成步骤留快照");
        assertFalse(failed.getStepOutputs().containsKey("s3"), "失败后不再执行");
        assertTrue(failed.getError().contains("ERROR"));
    }

    @Test
    void 未注册工具与非法输入() {
        InProcessToolExecutor executor = new InProcessToolExecutor(10, 1000);
        ToolChainExecutor chain = new ToolChainExecutor(executor);
        ChainRunResultVO result = chain.execute(List.of(
                ChainStepVO.builder().stepId("s1").tool("未注册").paramTemplate(Map.of()).build()),
                Map.of(), "t");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("未注册"));
        assertThrows(IllegalArgumentException.class, () -> chain.execute(List.of(), Map.of(), "t"));
        assertThrows(IllegalArgumentException.class, () -> new ToolChainExecutor(null));
    }
}
