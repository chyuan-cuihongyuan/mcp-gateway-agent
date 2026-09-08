package cn.chyuan.ai.domain.governance.adapter;

import java.util.Map;

/**
 * webhook 出站 HTTP 端口（工单 0051；infrastructure 以 JDK HttpClient 落地）
 *
 * @author chyuan
 */
public interface IWebhookHttpClient {

    /**
     * POST JSON 出站。
     *
     * @return HTTP 状态码；抛异常表示传输失败（投递方按重试策略处理）
     */
    int postJson(String url, Map<String, String> headers, String body) throws Exception;
}
