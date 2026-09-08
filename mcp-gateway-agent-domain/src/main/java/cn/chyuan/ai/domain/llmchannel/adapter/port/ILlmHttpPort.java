package cn.chyuan.ai.domain.llmchannel.adapter.port;

import java.util.Map;

/**
 * LLM 上游出站 HTTP 端口（工单 0063；infrastructure 以 JDK HttpClient 落地）
 *
 * @author chyuan
 */
public interface ILlmHttpPort {

    /**
     * POST JSON 到上游（Authorization 头由调用方给定）。
     *
     * @return HTTP 状态码；抛异常表示传输失败（调用方按故障转移处理）
     */
    int postJson(String url, Map<String, String> headers, String body, int timeoutMs) throws Exception;

    /** 响应体（postJson 成功后可读；实现保证线程内最近一次） */
    String lastResponseBody();

    /** GET JSON（/v1/models 探测等轻量用途） */
    String getJson(String url, Map<String, String> headers, int timeoutMs) throws Exception;
}
