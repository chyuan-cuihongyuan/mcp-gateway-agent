package cn.chyuan.ai.domain.streamkernel.service;

import cn.chyuan.ai.domain.streamkernel.adapter.port.ISchemaValidatorPort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用参数增量聚合（工单 0374 AT4，openai-python 流式工具语义）。
 * 分片按 index 聚合 name/arguments 片段 → 完成事件产出完整参数 JSON → 接 Schema 校验端口。
 * 缺 index 容错（自动补位）、乱序/交错到达安全；完成事件幂等（重复 finish 只产出一次）。
 */
public class ToolCallAggregator {

    /** 聚合中的工具调用 */
    private static final class Pending {
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
        boolean finished;
    }

    /** 完成的工具调用 */
    public record CompletedToolCall(String callId, String name, String argumentsJson,
                                    boolean schemaValid, String schemaError) {
    }

    private final Map<String, Pending> pendingByIndex = new LinkedHashMap<>();
    private final ISchemaValidatorPort schemaValidator;

    public ToolCallAggregator(ISchemaValidatorPort schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    /**
     * 喂入分片（index 唯一标识并发工具调用；name/id 可在任意分片补齐）。
     */
    public void onDelta(String index, String callId, String name, String argumentsFragment) {
        String key = index == null ? "0" : index;
        Pending pending = pendingByIndex.computeIfAbsent(key, k -> new Pending());
        if (pending.callId == null && callId != null) {
            pending.callId = callId;
        }
        if (pending.name == null && name != null && !name.isBlank()) {
            pending.name = name;
        }
        if (argumentsFragment != null) {
            pending.arguments.append(argumentsFragment);
        }
    }

    /**
     * 完成指定工具调用：产出完整参数并接 Schema 校验（端口失败视为校验不可用=通过+错误说明）。
     */
    public CompletedToolCall onFinish(String index) {
        String key = index == null ? "0" : index;
        Pending pending = pendingByIndex.get(key);
        if (pending == null) {
            throw new IllegalStateException("未知的工具调用 index: " + key);
        }
        if (pending.finished) {
            throw new IllegalStateException("工具调用已完成，不可重复 finish: " + key);
        }
        pending.finished = true;
        String argsJson = pending.arguments.toString().isBlank() ? "{}" : pending.arguments.toString();
        String name = pending.name;
        boolean valid;
        String error = null;
        try {
            if (name == null || name.isBlank()) {
                valid = false;
                error = "工具名缺失";
            } else {
                ISchemaValidatorPort.Result result = schemaValidator.validate(name, argsJson);
                valid = result.valid();
                error = result.error();
            }
        } catch (Exception e) {
            valid = false;
            error = "校验端口异常: " + e.getMessage();
        }
        return new CompletedToolCall(pending.callId, name, argsJson, valid, error);
    }

    /** 完成数量（观测） */
    public int finishedCount() {
        return (int) pendingByIndex.values().stream().filter(p -> p.finished).count();
    }
}
