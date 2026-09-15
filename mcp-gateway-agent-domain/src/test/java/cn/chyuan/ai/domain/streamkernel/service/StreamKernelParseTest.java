package cn.chyuan.ai.domain.streamkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT1/AT2 单测（工单 0371/0372）：部分 JSON 增量解析 + SSE 帧切分。
 */
class StreamKernelParseTest {

    @Test
    void 部分JSON增量产出完整字段与悬挂键() {
        PartialJsonParser parser = new PartialJsonParser();
        PartialJsonParser.Delta d1 = parser.feed("{\"message\":\"你");
        // message 值未闭合 → 悬挂
        assertTrue(d1.getCompleted().isEmpty());
        assertTrue(d1.getPendingKeys().contains("message"));
        PartialJsonParser.Delta d2 = parser.feed("好\",\"index\":3,\"role");
        // message 与 index 完成，role 悬挂
        assertEquals(2, d2.getCompleted().size());
        assertEquals("message", d2.getCompleted().get(0).key());
        assertEquals("\"你好\"", d2.getCompleted().get(0).jsonValue());
        assertEquals("3", d2.getCompleted().get(1).jsonValue());
        assertTrue(d2.getPendingKeys().contains("role"));
        PartialJsonParser.Delta d3 = parser.feed("\":\"assistant\"}");
        assertEquals("role", d3.getCompleted().get(0).key());
        assertTrue(parser.isComplete());
        assertTrue(parser.currentPending().isEmpty());
    }

    @Test
    void 部分JSON容器值与转义引号边界() {
        PartialJsonParser parser = new PartialJsonParser();
        List<PartialJsonParser.FieldUpdate> all = new java.util.ArrayList<>(parser
                .feed("{\"list\":[1,2,{\"x\":\"a\\\"b\"}],\"note\":\"z\"").getCompleted());
        all.addAll(parser.feed("}").getCompleted());
        // 容器值完整产出（含转义引号不破结构）
        assertEquals("list", all.get(0).key());
        assertEquals("[1,2,{\"x\":\"a\\\"b\"}]", all.get(0).jsonValue());
        assertEquals("z", all.get(1).jsonValue().replace("\"", ""));
        assertTrue(parser.isComplete());
    }

    @Test
    void 部分JSON重放一致与整段等价() {
        String full = "{\"a\":123,\"b\":{\"c\":true},\"d\":null}";
        String[] chunks = {"{\"a\":" , "123,", "\"b\":{\"c\":" , "true},\"d\":nul", "l}"};
        PartialJsonParser incremental = new PartialJsonParser();
        List<String> seen = new java.util.ArrayList<>();
        for (String chunk : chunks) {
            for (PartialJsonParser.FieldUpdate update : incremental.feed(chunk).getCompleted()) {
                seen.add(update.key() + "=" + update.jsonValue());
            }
        }
        List<String> wholeSeen = wholeParse(full);
        // 增量产出与整段一次性解析等价
        assertEquals(wholeSeen, seen);
        // 同输入重放一致
        assertEquals(wholeSeen, wholeParse(full));
    }

    private List<String> wholeParse(String full) {
        PartialJsonParser p = new PartialJsonParser();
        List<String> out = new java.util.ArrayList<>();
        for (PartialJsonParser.FieldUpdate update : p.feed(full).getCompleted()) {
            out.add(update.key() + "=" + update.jsonValue());
        }
        return out;
    }

    @Test
    void SSE帧派发与跨帧任意切分() {
        String stream = ": ping\n\n"
                + "event: delta\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n"
                + "id: 42\nretry: 3000\ndata: tail\n\n";
        // 逐字符喂入（任意切分点正确性）
        SseFrameParser parser = new SseFrameParser();
        List<SseFrameParser.SseEvent> events = new java.util.ArrayList<>();
        for (char ch : stream.toCharArray()) {
            events.addAll(parser.feed(String.valueOf(ch)));
        }
        // 注释行不派发
        assertEquals(2, events.size());
        // 多 data 行 \n 拼接 + CRLF 兼容 + event 语义
        assertEquals("delta", events.get(0).getEvent());
        assertEquals("第一行\n第二行", events.get(0).getData());
        // id/retry 语义
        assertEquals("42", events.get(1).getLastEventId());
        assertEquals(3000L, events.get(1).getRetryMs());
        assertEquals("message", events.get(1).getEvent());
        // 无 event 重置回 message；id 保留
        assertEquals("42", parser.lastEventId());
    }

    @Test
    void SSE流首BOM与无data不派发() {
        SseFrameParser parser = new SseFrameParser();
        List<SseFrameParser.SseEvent> events = parser.feed("\uFEFFevent: x\n\n");
        // event 后无 data：不派发，且 event 缓冲随空行重置（WHATWG 语义）
        assertTrue(events.isEmpty());
        events = parser.feed("data: ok\n\n");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).getEvent());
        assertFalse(events.get(0).getData().isEmpty());
    }
}
