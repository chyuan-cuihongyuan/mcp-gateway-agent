package cn.chyuan.ai.domain.streamkernel.service;

import java.util.Map;

/**
 * 流事件状态机归一（工单 0373 AT3，vercel/ai 模型流归一思想）。
 * OpenAI chat.completion.chunk 与 Anthropic message/content_block 两族增量事件
 * → 统一内部事件（MESSAGE_START/TEXT_DELTA/TOOL_CALL_DELTA/MESSAGE_STOP/ERROR/DONE）；
 * 状态机校验序列合法性（起始前事件/终止后事件/未知族拒绝）。
 * 入参为已解析的 Map（解析面在协议层，domain 只做归一与校验）。
 */
public class StreamEventNormalizer {

    /** 统一事件类型 */
    public static final String MESSAGE_START = "MESSAGE_START";
    public static final String TEXT_DELTA = "TEXT_DELTA";
    public static final String TOOL_CALL_DELTA = "TOOL_CALL_DELTA";
    public static final String MESSAGE_STOP = "MESSAGE_STOP";
    public static final String ERROR = "ERROR";
    public static final String DONE = "DONE";

    /** 族标识 */
    public static final String FAMILY_OPENAI = "openai";
    public static final String FAMILY_ANTHROPIC = "anthropic";

    /** 归一事件 */
    public record NormalizedEvent(String type, String textDelta, String toolIndex,
                                  String toolName, String argsFragment, String finishReason, String error) {
    }

    private boolean started;
    private boolean stopped;

    /**
     * 归一单事件；非法返回 IllegalArgumentException（序列校验）。
     *
     * @param family openai/anthropic
     * @param event  事件 Map（如 OpenAI chunk 或 Anthropic 事件体）
     */
    @SuppressWarnings("unchecked")
    public NormalizedEvent normalize(String family, Map<String, Object> event) {
        if (stopped) {
            throw new IllegalStateException("终止事件后不允许再有事件");
        }
        return switch (family) {
            case FAMILY_OPENAI -> normalizeOpenAi(event);
            case FAMILY_ANTHROPIC -> normalizeAnthropic(event);
            default -> throw new IllegalArgumentException("未知事件族: " + family);
        };
    }

    /** OpenAI [DONE] 哨兵（协议层识别后以空 Map+family 标记调用） */
    public NormalizedEvent done() {
        stopped = true;
        return new NormalizedEvent(DONE, null, null, null, null, null, null);
    }

    private NormalizedEvent normalizeOpenAi(Map<String, Object> event) {
        requireStart();
        Object choicesObj = event.get("choices");
        if (!(choicesObj instanceof java.util.List<?> choices) || choices.isEmpty()) {
            // 心跳/空 chunk：不改变状态
            return new NormalizedEvent(TEXT_DELTA, "", null, null, null, null, null);
        }
        Map<String, Object> choice = (Map<String, Object>) choices.get(0);
        Map<String, Object> delta = choice.get("delta") instanceof Map
                ? (Map<String, Object>) choice.get("delta") : Map.of();
        Object finish = choice.get("finish_reason");
        if (finish != null) {
            stopped = true;
            return new NormalizedEvent(MESSAGE_STOP, null, null, null, null, String.valueOf(finish), null);
        }
        Object toolCalls = delta.get("tool_calls");
        if (toolCalls instanceof java.util.List<?> list && !list.isEmpty()) {
            Map<String, Object> tool = (Map<String, Object>) list.get(0);
            return new NormalizedEvent(TOOL_CALL_DELTA, null,
                    str(tool.get("index")), str(tool.get("id") != null ? tool.get("id") : tool.get("name")),
                    str(tool.get("arguments")), null, null);
        }
        String text = str(delta.get("content"));
        return new NormalizedEvent(TEXT_DELTA, text == null ? "" : text, null, null, null, null, null);
    }

    private NormalizedEvent normalizeAnthropic(Map<String, Object> event) {
        String type = str(event.get("type"));
        return switch (type == null ? "" : type) {
            case "message_start" -> {
                requireStart();
                started = true;
                yield new NormalizedEvent(MESSAGE_START, null, null, null, null, null, null);
            }
            case "content_block_delta" -> {
                requireStart();
                Object deltaObj = event.get("delta");
                Map<String, Object> delta = deltaObj instanceof Map ? (Map<String, Object>) deltaObj : Map.of();
                String deltaType = str(delta.get("type"));
                if ("input_json_delta".equals(deltaType)) {
                    yield new NormalizedEvent(TOOL_CALL_DELTA, null,
                            str(event.get("index")), null, str(delta.get("partial_json")), null, null);
                }
                yield new NormalizedEvent(TEXT_DELTA, str(delta.get("text")), null, null, null, null, null);
            }
            case "message_stop" -> {
                requireStart();
                stopped = true;
                yield new NormalizedEvent(MESSAGE_STOP, null, null, null, null, "stop", null);
            }
            case "error" -> {
                stopped = true;
                yield new NormalizedEvent(ERROR, null, null, null, null, null, str(event.get("message")));
            }
            case "ping", "message_delta" -> new NormalizedEvent(TEXT_DELTA, "", null, null, null, null, null);
            default -> throw new IllegalArgumentException("未知 Anthropic 事件类型: " + type);
        };
    }

    private void requireStart() {
        if (!started) {
            started = true;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
