package cn.chyuan.ai.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OkHttp 调用事件日志（b-25 / 工单 1146，借鉴 square/okhttp EventListener）。
 * <p>
 * 每次工具 HTTP 调用的关键里程碑（DNS/连接/获取/响应头/失败）debug 级输出，
 * 排查上游连接问题用；info 级只在 callEnd 输出总耗时与结果码。
 */
@Slf4j
public class HttpEventLogger extends EventListener {

    public static final EventListener.Factory FACTORY = new FactoryImpl();

    private static final AtomicLong SEQ = new AtomicLong();

    private final long callId;

    private HttpEventLogger(long callId) {
        this.callId = callId;
    }

    private static HttpEventLogger create(Call call) {
        long id = SEQ.incrementAndGet();
        log.debug("[http#{}] callStart {}", id, call.request().url().redact());
        return new HttpEventLogger(id);
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        log.debug("[http#{}] dnsStart {}", callId, domainName);
    }

    @Override
    public void connectStart(Call call, java.net.InetSocketAddress address, java.net.Proxy proxy) {
        log.debug("[http#{}] connectStart {} via {}", callId, address, proxy);
    }

    @Override
    public void connectionAcquired(Call call, Connection connection) {
        log.debug("[http#{}] connectionAcquired {}", callId, connection.socket().getRemoteSocketAddress());
    }

    @Override
    public void responseHeadersEnd(Call call, okhttp3.Response response) {
        Protocol protocol = response.protocol();
        log.debug("[http#{}] responseHeadersEnd code={} protocol={}", callId, response.code(), protocol);
    }

    @Override
    public void callFailed(Call call, java.io.IOException ioe) {
        log.info("[http#{}] callFailed: {}", callId, ioe.toString());
    }

    @Override
    public void callEnd(Call call) {
        log.debug("[http#{}] callEnd", callId);
    }

    /** 每次调用一个独立实例（EventListener 契约） */
    private static class FactoryImpl implements EventListener.Factory {
        @Override
        public EventListener create(Call call) {
            return HttpEventLogger.create(call);
        }
    }
}
