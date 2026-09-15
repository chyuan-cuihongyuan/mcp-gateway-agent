package cn.chyuan.ai.domain.streamkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 帧切分解析器（工单 0372 AT2，WHATWG Server-Sent Events 规范子集）。
 * 任意切分的文本流 → 事件帧（event/data 多行拼接/id/retry），注释行忽略，
 * 空行派发，跨帧缓冲；CRLF/CR/LF 换行兼容，流首 BOM 剥离；重放一致。
 */
public class SseFrameParser {

    /** SSE 事件帧 */
    public static final class SseEvent {
        private final String event;
        private final String data;
        private final String lastEventId;
        private final Long retryMs;

        SseEvent(String event, String data, String lastEventId, Long retryMs) {
            this.event = event;
            this.data = data;
            this.lastEventId = lastEventId;
            this.retryMs = retryMs;
        }

        public String getEvent() {
            return event;
        }

        public String getData() {
            return data;
        }

        public String getLastEventId() {
            return lastEventId;
        }

        public Long getRetryMs() {
            return retryMs;
        }
    }

    private final StringBuilder buffer = new StringBuilder();
    private boolean firstFeed = true;
    private String lastEventId;

    /**
     * 喂入任意切分的文本，返回本轮派发完成的事件帧。
     */
    public List<SseEvent> feed(String chunk) {
        if (chunk == null) {
            return List.of();
        }
        if (firstFeed) {
            // 流首 BOM 剥离
            if (chunk.startsWith("\uFEFF")) {
                chunk = chunk.substring(1);
            }
            firstFeed = false;
        }
        buffer.append(chunk);
        List<SseEvent> out = new ArrayList<>();
        // 行读取（兼容 LF/CRLF/CR）
        int lineStart = 0;
        while (true) {
            int idx = buffer.indexOf("\n", lineStart);
            int crIdx = buffer.indexOf("\r", lineStart);
            int breakAt;
            int nextStart;
            if (idx >= 0 && (crIdx < 0 || idx < crIdx)) {
                breakAt = idx;
                nextStart = idx + 1;
            } else if (crIdx >= 0) {
                breakAt = crIdx;
                // CRLF：\r 后若已有 \n 一并吞掉
                nextStart = crIdx + 1;
                if (nextStart < buffer.length() && buffer.charAt(nextStart) == '\n') {
                    nextStart++;
                } else if (nextStart == buffer.length()) {
                    // 可能是 CRLF 被切断：等待下一个 chunk
                    break;
                }
            } else {
                break;
            }
            String line = buffer.substring(lineStart, breakAt);
            lineStart = nextStart;
            SseEvent event = handleLine(line);
            if (event != null) {
                out.add(event);
            }
        }
        buffer.delete(0, lineStart);
        return out;
    }

    /** 语义缓冲：field 状态 */
    private final StringBuilder dataBuffer = new StringBuilder();
    private String eventType;
    private Long retryMs;
    private boolean hasData;

    private SseEvent handleLine(String line) {
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.startsWith(":")) {
            return null; // 注释行
        }
        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }
        switch (field) {
            case "data" -> {
                if (dataBuffer.length() > 0) {
                    dataBuffer.append('\n');
                }
                dataBuffer.append(value);
                hasData = true;
            }
            case "event" -> eventType = value;
            case "id" -> {
                if (!value.contains("\u0000")) {
                    lastEventId = value;
                }
            }
            case "retry" -> {
                try {
                    retryMs = Long.parseLong(value.trim());
                } catch (NumberFormatException ignored) {
                    // 非 retry 数值忽略
                }
            }
            default -> {
                // 未知字段忽略（规范行为）
            }
        }
        return null;
    }

    /** 空行派发：无 data 不派发；派发后重置 event/retry，id 保留 */
    private SseEvent dispatch() {
        if (!hasData) {
            eventType = null;
            retryMs = null;
            return null;
        }
        SseEvent event = new SseEvent(eventType == null ? "message" : eventType,
                dataBuffer.toString(), lastEventId, retryMs);
        dataBuffer.setLength(0);
        hasData = false;
        eventType = null;
        retryMs = null;
        return event;
    }

    /** 最近一次 id 字段值（断线重连 Last-Event-ID 语义） */
    public String lastEventId() {
        return lastEventId;
    }
}
