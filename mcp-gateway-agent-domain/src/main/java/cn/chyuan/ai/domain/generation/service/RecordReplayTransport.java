package cn.chyuan.ai.domain.generation.service;

import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * LLM 录制回放传输装饰器（工单 0203 AA8）—
 * 包装既有 {@link ILlmHttpPort}：RECORD 模式放行真实调用并录制 2xx 响应；
 * REPLAY 模式零网络直接回放磁带（未命中报错暴露测试缺口）；OFF 模式纯透传零开销。
 * lastResponseBody 线程内最近一次语义经 ThreadLocal 承接（与端口契约一致）。
 * 组装：infrastructure/trigger 按 generation.record-replay.mode 注入包装后的端口。
 *
 * @author chyuan
 */
@Slf4j
public class RecordReplayTransport implements ILlmHttpPort {

    /** 模式：OFF/RECORD/REPLAY */
    public enum Mode {
        OFF, RECORD, REPLAY
    }

    /** 最近一次回放响应体（REPLAY 模式填充；键=线程，满足端口"线程内最近一次"契约） */
    private static final ThreadLocal<String> LAST_REPLAYED_BODY = new ThreadLocal<>();

    private final ILlmHttpPort delegate;
    private final RecordReplayStore store;
    private final Mode mode;

    public RecordReplayTransport(ILlmHttpPort delegate, RecordReplayStore store, Mode mode) {
        this.delegate = delegate;
        this.store = store;
        this.mode = mode;
    }

    @Override
    public int postJson(String url, Map<String, String> headers, String body, int timeoutMs) throws Exception {
        if (mode == Mode.REPLAY) {
            RecordReplayStore.CassetteEntry entry = store.replay(url, body);
            LAST_REPLAYED_BODY.set(entry.responseBody());
            log.info("回放磁带: url={} hash={}", url, entry.requestHash().substring(0, Math.min(12,
                    entry.requestHash().length())));
            return entry.status();
        }
        int status = delegate.postJson(url, headers, body, timeoutMs);
        if (mode == Mode.RECORD && status >= 200 && status < 300) {
            store.record(url, body, status, delegate.lastResponseBody());
        }
        return status;
    }

    @Override
    public String lastResponseBody() {
        if (mode == Mode.REPLAY) {
            String replayed = LAST_REPLAYED_BODY.get();
            return replayed != null ? replayed : delegate.lastResponseBody();
        }
        return delegate.lastResponseBody();
    }

    @Override
    public int postJsonStreaming(String url, Map<String, String> headers, String body, int timeoutMs,
            java.util.function.Consumer<String> onLine) throws Exception {
        if (mode == Mode.REPLAY) {
            RecordReplayStore.CassetteEntry entry = store.replay(url, body);
            String replayed = entry.responseBody() == null ? "" : entry.responseBody();
            for (String line : replayed.split("\n", -1)) {
                onLine.accept(line + "\n");
            }
            LAST_REPLAYED_BODY.set(replayed);
            return entry.status();
        }
        return delegate.postJsonStreaming(url, headers, body, timeoutMs, onLine);
    }

    @Override
    public String getJson(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        // GET（探测类轻量用途）不录制，始终走真实委托（REPLAY 模式下测试不触发探测）
        return delegate.getJson(url, headers, timeoutMs);
    }

    public Mode mode() {
        return mode;
    }
}
