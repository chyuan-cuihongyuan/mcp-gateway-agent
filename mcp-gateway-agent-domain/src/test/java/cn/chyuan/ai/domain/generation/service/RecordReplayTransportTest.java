package cn.chyuan.ai.domain.generation.service;

import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制回放传输单测（工单 0203 AA8）：录制/回放往返/归一哈希稳定/未命中报错。
 */
class RecordReplayTransportTest {

    @TempDir
    Path tempDir;

    @Test
    void 归一哈希稳定() {
        String body1 = "{\"model\":\"gpt-x\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}";
        String body2 = "{ \"MODEL\" : \"GPT-X\", \"messages\": [ {\"role\": \"USER\", \"content\": \"你好\"} ] }";
        assertEquals(RecordReplayStore.requestHash("http://up/v1/chat/completions", body1),
                RecordReplayStore.requestHash("http://up/v1/chat/completions", body2));
        // URL 差异导致键不同
        assertTrue(!RecordReplayStore.requestHash("http://up/v1/chat/completions", body1)
                .equals(RecordReplayStore.requestHash("http://other/v1/chat/completions", body1)));
    }

    @Test
    void 录制后回放往返一致() throws Exception {
        RecordReplayStore store = new RecordReplayStore(tempDir);
        AtomicInteger calls = new AtomicInteger();
        ILlmHttpPort fake = new FakeTransport(calls, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        RecordReplayTransport recorder = new RecordReplayTransport(fake, store, RecordReplayTransport.Mode.RECORD);
        String body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        int status = recorder.postJson("http://up/chat/completions", new HashMap<>(), body, 1000);
        assertEquals(200, status);
        assertEquals("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", recorder.lastResponseBody());
        assertEquals(1, store.indexedCount());

        // REPLAY：零真实调用
        RecordReplayTransport replayer = new RecordReplayTransport(fake, store, RecordReplayTransport.Mode.REPLAY);
        assertEquals(200, replayer.postJson("http://up/chat/completions", new HashMap<>(), body, 1000));
        assertEquals("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", replayer.lastResponseBody());
        assertEquals(1, calls.get(), "回放不应触发真实传输");
        // 流式回放：逐行回调含响应全文
        StringBuilder lines = new StringBuilder();
        int streamStatus = replayer.postJsonStreaming("http://up/chat/completions",
                new HashMap<>(), body, 1000, lines::append);
        assertEquals(200, streamStatus);
        assertTrue(lines.toString().contains("\"content\":\"ok\""));
    }

    @Test
    void 未命中报错与OFF透传() throws Exception {
        RecordReplayStore store = new RecordReplayStore(tempDir);
        ILlmHttpPort fake = new FakeTransport(new AtomicInteger(), 200, "{}");
        RecordReplayTransport replayer = new RecordReplayTransport(fake, store, RecordReplayTransport.Mode.REPLAY);
        assertThrows(IllegalStateException.class, () -> replayer.postJson("http://up/c", Map.of(), "{}", 10));
        // OFF：透传
        RecordReplayTransport off = new RecordReplayTransport(fake, store, RecordReplayTransport.Mode.OFF);
        assertEquals(200, off.postJson("http://up/c", Map.of(), "{}", 10));
        assertEquals(RecordReplayTransport.Mode.OFF, off.mode());
    }

    @Test
    void 磁带落盘与跨实例回放() throws Exception {
        RecordReplayStore store = new RecordReplayStore(tempDir);
        ILlmHttpPort fake = new FakeTransport(new AtomicInteger(), 200, "{\"d\":1}");
        new RecordReplayTransport(fake, store, RecordReplayTransport.Mode.RECORD)
                .postJson("http://up/x", Map.of(), "{\"q\":1}", 100);
        // 新实例（模拟跨进程）：内存空，从磁盘磁带回放
        RecordReplayStore fresh = new RecordReplayStore(tempDir);
        assertEquals(0, fresh.indexedCount());
        RecordReplayStore.CassetteEntry entry = fresh.replay("http://up/x", "{\"q\":1}");
        assertEquals("{\"d\":1}", entry.responseBody());
    }

    /** 可计数假传输 */
    record FakeTransport(AtomicInteger counter, int status, String responseBody) implements ILlmHttpPort {

        @Override
        public int postJson(String url, Map<String, String> headers, String body, int timeoutMs) {
            counter.incrementAndGet();
            return status;
        }

        @Override
        public String lastResponseBody() {
            return responseBody;
        }

        @Override
        public String getJson(String url, Map<String, String> headers, int timeoutMs) {
            return responseBody;
        }

        @Override
        public int postJsonStreaming(String url, Map<String, String> headers, String body, int timeoutMs,
                java.util.function.Consumer<String> onLine) {
            counter.incrementAndGet();
            onLine.accept(responseBody);
            return status;
        }
    }
}
